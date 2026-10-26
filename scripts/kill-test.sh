#!/usr/bin/env bash
# Phase 0 gate: a client process that dies in the middle of a commit recovers.
#
# For each crash point, Alice's CLI process exits abruptly (exit code 86, no
# cleanup) while inviting Bob. A fresh process then has to finish the job from
# what is on disk, and the two must be able to talk.
#
#   scripts/kill-test.sh            # uses port 18080
#   PORT=19000 scripts/kill-test.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${PORT:-18080}"
URL="http://127.0.0.1:${PORT}"

cargo build --quiet --manifest-path "$ROOT/Cargo.toml" -p messenger-server -p messenger-cli
BIN="$ROOT/target/debug"

WORK="$(mktemp -d)"
SERVER_PID=""
cleanup() {
  if [ -n "$SERVER_PID" ]; then kill "$SERVER_PID" 2>/dev/null || true; fi
  rm -rf "$WORK"
}
trap cleanup EXIT

m() { "$BIN/messenger" --server "$URL" "$@"; }

for point in commit-created commit-accepted commit-merge; do
  rm -rf "${WORK:?}"/*
  "$BIN/messenger-server" --listen "127.0.0.1:${PORT}" --db "$WORK/server.db" >"$WORK/server.log" 2>&1 &
  SERVER_PID=$!
  for _ in $(seq 50); do
    curl --noproxy '*' -fs "$URL/v1/health" >/dev/null 2>&1 && break
    sleep 0.1
  done

  m --data "$WORK/alice" register alice >/dev/null
  m --data "$WORK/bob" register bob >/dev/null

  set +e
  MESSENGER_CRASH_AT="$point" m --data "$WORK/alice" chat bob >/dev/null 2>&1
  status=$?
  set -e
  if [ "$status" -ne 86 ]; then
    echo "FAIL: expected alice to die at $point (exit 86), got exit $status" >&2
    exit 1
  fi

  # New processes pick up from disk.
  m --data "$WORK/alice" sync >/dev/null
  m --data "$WORK/bob" sync >/dev/null
  m --data "$WORK/alice" send bob "survived $point" >/dev/null
  m --data "$WORK/bob" send alice "welcome back" >/dev/null
  m --data "$WORK/alice" sync >/dev/null
  m --data "$WORK/bob" sync >/dev/null

  if ! m --data "$WORK/bob" show alice | grep -q "survived $point"; then
    echo "FAIL ($point): bob did not get alice's message" >&2
    m --data "$WORK/bob" show alice >&2 || true
    exit 1
  fi
  if ! m --data "$WORK/alice" show bob | grep -q "welcome back"; then
    echo "FAIL ($point): alice did not get bob's reply" >&2
    m --data "$WORK/alice" show bob >&2 || true
    exit 1
  fi
  echo "ok: killed at $point, recovered"

  kill "$SERVER_PID"
  wait "$SERVER_PID" 2>/dev/null || true
  SERVER_PID=""
done
echo "kill test passed"
