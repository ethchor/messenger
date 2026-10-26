#!/usr/bin/env bash
# A demo on this Mac: a fresh local server, the Mac app signed in as vimu,
# the iPhone app in the simulator signed in as maya, and three more people
# (alex, sam, kai) who have already sent photos, a voice message, a file,
# a thread and reactions.
#
#   scripts/demo.sh          # build, set up and open everything
#   scripts/demo.sh stop     # stop the demo server
#
# The apps' current data on this Mac is moved to build/demo/backup-<time>
# first, so nothing is lost. Set DEMO_DEVICE to use another simulator.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEMO="$ROOT/build/demo"
DEVICE="${DEMO_DEVICE:-iPhone 17 Pro}"
BUNDLE=com.qwantarc.messenger
SERVER=http://localhost:8080
MAC_DATA="$HOME/Library/Containers/$BUNDLE/Data/Library/Application Support/Messenger"

step() { printf '\n== %s\n' "$*"; }

stop_server() {
  if [ -f "$DEMO/server.pid" ]; then
    kill "$(cat "$DEMO/server.pid")" 2>/dev/null || true
    rm -f "$DEMO/server.pid"
  fi
}

if [ "${1:-}" = stop ]; then
  stop_server
  echo "Demo server stopped."
  exit 0
fi

command -v cargo >/dev/null || { echo "Rust is needed: https://rustup.rs" >&2; exit 1; }
command -v xcodebuild >/dev/null || { echo "Xcode is needed." >&2; exit 1; }

stop_server
if lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Something is already listening on port 8080 (another server?). Stop it, then run this again." >&2
  exit 1
fi

step "Building the server and the CLI"
cargo build -q -p messenger-server -p messenger-cli
CLI="$ROOT/target/debug/messenger"

step "Building the Mac app and the iPhone app (the first time takes a few minutes)"
UDID="$(xcrun simctl list devices available | grep -F "    $DEVICE (" | head -1 | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/')"
[ -n "$UDID" ] || { echo "No simulator called \"$DEVICE\". Set DEMO_DEVICE to one from: xcrun simctl list devices" >&2; exit 1; }
xcodebuild -project "$ROOT/apple/Messenger.xcodeproj" -scheme Messenger -configuration Debug \
  -destination "platform=macOS,arch=$(uname -m)" -derivedDataPath "$DEMO/DerivedData" build -quiet
xcodebuild -project "$ROOT/apple/Messenger.xcodeproj" -scheme Messenger -configuration Debug \
  -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath "$DEMO/DerivedData" build -quiet
MAC_APP="$DEMO/DerivedData/Build/Products/Debug/Messenger.app"
IOS_APP="$DEMO/DerivedData/Build/Products/Debug-iphonesimulator/Messenger.app"

step "Setting aside the apps' current data"
osascript -e "quit app id \"$BUNDLE\"" >/dev/null 2>&1 || true
xcrun simctl boot "$UDID" 2>/dev/null || true
xcrun simctl terminate "$UDID" "$BUNDLE" 2>/dev/null || true
xcrun simctl install "$UDID" "$IOS_APP"
IOS_DATA="$(xcrun simctl get_app_container "$UDID" "$BUNDLE" data)/Library/Application Support/Messenger"
BACKUP="$DEMO/backup-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP"
[ -d "$MAC_DATA" ] && mv "$MAC_DATA" "$BACKUP/mac"
[ -d "$IOS_DATA" ] && mv "$IOS_DATA" "$BACKUP/iphone"
rm -rf "$DEMO/server" "$DEMO/people" "$DEMO/media"
mkdir -p "$DEMO/server" "$DEMO/people" "$DEMO/media" "$MAC_DATA" "$IOS_DATA"
echo "Kept in $BACKUP"

step "Starting the server on $SERVER"
"$ROOT/target/debug/messenger-server" --listen 0.0.0.0:8080 --db "$DEMO/server/messenger-server.db" \
  > "$DEMO/server.log" 2>&1 &
echo $! > "$DEMO/server.pid"
for _ in $(seq 50); do
  curl -sf -o /dev/null "$SERVER/v1/push" && break
  sleep 0.2
done
curl -sf -o /dev/null "$SERVER/v1/push" || { echo "The server didn't start; see $DEMO/server.log" >&2; exit 1; }

step "Making photos, a voice message and a file to send"
python3 -I - "$DEMO/media" <<'EOF'
import struct, sys, zlib, pathlib
out = pathlib.Path(sys.argv[1])
# Gradients as PNGs (sips makes JPEGs of them below).
def png(name, w, h, pixel):
    rows = b"".join(b"\0" + b"".join(bytes(pixel(x, y, w, h)) for x in range(w)) for y in range(h))
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    (out / name).write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                             + chunk(b"IDAT", zlib.compress(rows, 6)) + chunk(b"IEND", b""))
def sky(x, y, w, h):
    t = y / h
    sun = max(0, 1 - ((x - w * .7) ** 2 + (y - h * .55) ** 2) ** .5 / (h * .18))
    sea = y > h * .62
    return (int(255 * (1 - t * .4) if not sea else 40 + 60 * t), int(140 + 80 * sun - 60 * t) if not sea else 90, int(90 + 120 * t) if not sea else 140)
def lake(x, y, w, h):
    t = y / h
    hill = h * (.45 + .1 * (((x / w) * 6) % 2 - 1) ** 2)
    return (70, 130, 200) if y > h * .7 else ((40, 110 + int(40 * t), 60) if y > hill else (150 + int(60 * t), 200, 245))
def ridge(x, y, w, h):
    peak = h * (.3 + .4 * abs((x / w) - .5))
    return (230, 230, 240) if y < peak and y > peak - h * .05 else ((90, 90, 120) if y > peak else (255, 190 - int(100 * y / h), 120))
for name, f in [("sunset.png", sky), ("lake.png", lake), ("ridge.png", ridge)]:
    png(name, 640, 480, f)
# A small PDF, by hand.
lines = ["Weekend plan", "Saturday: ridge walk, lunch by the lake", "Sunday: kayaks, back by six"]
text = "BT /F1 18 Tf 72 720 Td " + " ".join(f"({l}) Tj 0 -28 Td" for l in lines) + " ET"
objs = ["<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        f"<< /Length {len(text)} >>\nstream\n{text}\nendstream", "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
pdf, offsets = "%PDF-1.4\n", []
for i, o in enumerate(objs, 1):
    offsets.append(len(pdf))
    pdf += f"{i} 0 obj\n{o}\nendobj\n"
xref = len(pdf)
pdf += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n" + "".join(f"{o:010d} 00000 n \n" for o in offsets)
pdf += f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n"
(out / "weekend-plan.pdf").write_text(pdf)
EOF
for p in sunset lake ridge; do
  sips -s format jpeg "$DEMO/media/$p.png" --out "$DEMO/media/$p.jpg" >/dev/null
done
say -o "$DEMO/media/voice.m4a" --data-format=aac "Hey! I'm at the harbour. Call me when you're free." 2>/dev/null \
  || say -o "$DEMO/media/voice.m4a" "Hey! I'm at the harbour."

step "Creating vimu (Mac), maya (iPhone), alex, sam and kai"
cli() { local dir="$1"; shift; "$CLI" --data "$dir" --server "$SERVER" "$@"; }
person() { case "$1" in vimu) echo "$MAC_DATA" ;; maya) echo "$IOS_DATA" ;; *) echo "$DEMO/people/$1" ;; esac; }
as() { local who="$1"; shift; cli "$(person "$who")" "$@"; }
for who in vimu maya alex sam kai; do
  as "$who" register "$who" > "$DEMO/people/$who.recovery-key"
done
as vimu profile --avatar lion --ink forest >/dev/null
as maya profile --avatar dog --ink blue-black >/dev/null
as alex profile --avatar fox --ink teal >/dev/null
as sam profile --avatar owl --ink saffron >/dev/null
as kai profile --avatar penguin --ink oxblood >/dev/null

step "Filling the chats"
M="$DEMO/media"
as alex chat vimu >/dev/null
as alex send vimu "Hey Vimu! Back from the coast, it was gorgeous." >/dev/null
as alex send-file vimu "$M/sunset.jpg" --caption "Golden hour at the harbour" >/dev/null
as alex send-file vimu "$M/weekend-plan.pdf" --caption "Here's the plan for the weekend" >/dev/null
as alex send-file vimu "$M/voice.m4a" >/dev/null

as sam group "Weekend trip" vimu maya alex >/dev/null
as alex sync >/dev/null
as sam send "weekend trip" "Made a group for Saturday!" >/dev/null
as sam send-file "weekend trip" "$M/lake.jpg" "$M/ridge.jpg" "$M/sunset.jpg" --caption "Some spots we could go to" >/dev/null
root="$(as sam send "weekend trip" "Who's bringing what?")"
as alex sync >/dev/null   # alex has to have the message to reply under it
as alex send "weekend trip" "I'll bring the kayak paddles" --thread "$root" >/dev/null
as sam send "weekend trip" "Perfect, I've got snacks" --thread "$root" >/dev/null
as alex react "weekend trip" "$root" "👍" >/dev/null
as alex send-file "weekend trip" "$M/ridge.jpg" "$M/lake.jpg" --caption "Ridge or lake first?" >/dev/null

as kai chat maya >/dev/null
as kai send maya "Maya! Look at this sky" >/dev/null
as kai send-file maya "$M/sunset.jpg" >/dev/null
as kai send maya "Are you coming on Saturday?" >/dev/null

as maya chat vimu >/dev/null
as maya send vimu "Hi Vimu, see you on Saturday?" >/dev/null

# Both apps' people take in their invitations and messages now, so the
# apps open with everything in place.
for who in vimu maya; do as "$who" sync >/dev/null; done

step "Opening the apps"
open "$MAC_APP"
xcrun simctl launch "$UDID" "$BUNDLE" >/dev/null
open -a "Device Hub" 2>/dev/null || open -a Simulator 2>/dev/null || true

cat <<EOF

Ready.
  Mac app:     vimu
  iPhone app:  maya, in the "$DEVICE" simulator (Xcode's Device Hub)
  Others:      alex, sam, kai; send as them with
               target/debug/messenger --data build/demo/people/alex send vimu "hi"
  Server log:  build/demo/server.log
  Stop it:     scripts/demo.sh stop
  Your earlier app data: $BACKUP
EOF
