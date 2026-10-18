#!/usr/bin/env bash
# Turns compiler errors in a build log into GitHub annotations, so they show
# on the pull request (and through the API) without opening the raw log.
#
#   scripts/ci-annotate.sh build.log
set -euo pipefail

log="$1"
[ -f "$log" ] || exit 0
root="${GITHUB_WORKSPACE:-$PWD}/"

{
  # Kotlin: e: file:///…/File.kt:12:34 message
  sed -nE "s#^e: file://${root}([^:]+):([0-9]+):([0-9]+) (.*)#::error file=\1,line=\2,col=\3::\4#p" "$log"
  # Swift, clang and the linker: /…/File.swift:12:34: error: message
  sed -nE "s#^${root}([^:]+):([0-9]+):([0-9]+): error: (.*)#::error file=\1,line=\2,col=\3::\4#p" "$log"
  # Whatever else Gradle reports, such as a dependency that won't resolve.
  awk '/^\* What went wrong:/ { on = 1; next } /^\* Try:/ { on = 0 } on && NF { print "::error::" $0 }' "$log" | head -n 10
} | awk '!seen[$0]++' | head -n 50
