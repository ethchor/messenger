# Run `just` to list recipes. Every recipe is a thin wrapper, so the commands
# work without `just` too.

default:
    @just --list

# Unit and end-to-end tests
test:
    cargo test --workspace

# Formatting and lints, as CI runs them
lint:
    cargo fmt --all --check
    cargo clippy --workspace --all-targets -- -D warnings
    scripts/bindings.sh --check

# Kill a client in the middle of a commit and check it recovers
kill-test:
    scripts/kill-test.sh

# Run the server, reachable from simulators, emulators and phones on the LAN
server:
    cargo run -p messenger-server -- --listen 0.0.0.0:8080

# Run the CLI, e.g. `just cli --data /tmp/alice register alice`
cli *ARGS:
    cargo run -q -p messenger-cli -- {{ARGS}}

# Regenerate the Swift and Kotlin bindings after changing crates/ffi
bindings:
    scripts/bindings.sh

# Open the iPhone/Mac project in Xcode
apple:
    open apple/Messenger.xcodeproj

# Open the Android project in Android Studio
android:
    open -a "Android Studio" android
