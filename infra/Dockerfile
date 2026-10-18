# Mailbox server image. Build from the repository root:
#   docker build -f infra/Dockerfile -t messenger-server .
FROM rust:1-bookworm AS build
WORKDIR /src
COPY . .
RUN cargo build --release -p messenger-server

FROM debian:bookworm-slim
RUN useradd --system --uid 10001 --no-create-home messenger \
    && mkdir /data && chown messenger /data
COPY --from=build /src/target/release/messenger-server /usr/local/bin/messenger-server
USER messenger
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["messenger-server", "--listen", "0.0.0.0:8080", "--db", "/data/server.db"]
