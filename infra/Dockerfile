# Mailbox server image. CI publishes it as ghcr.io/ethchor/messenger-server;
# to build it yourself, from the repository root:
#   docker build -f infra/Dockerfile -t messenger-server .
FROM rust:1-bookworm AS build
WORKDIR /src
COPY . .
RUN cargo build --release --locked -p messenger-server

FROM debian:bookworm-slim
# ca-certificates: pushes to Apple and Web Push services check certificates
# against the system's roots. sqlite3: for consistent backups (backup.sh).
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates sqlite3 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --no-create-home messenger \
    && mkdir -p /data /secrets && chown messenger /data
COPY --from=build /src/target/release/messenger-server /usr/local/bin/messenger-server
USER messenger
VOLUME /data
EXPOSE 8080
# Uploaded files go to /data/blobs, next to the database.
ENTRYPOINT ["messenger-server", "--listen", "0.0.0.0:8080", "--db", "/data/server.db"]
