# Packet capture helper for lab runs: joins a container's network namespace.
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends tcpdump && rm -rf /var/lib/apt/lists/*
ENTRYPOINT ["tcpdump"]
