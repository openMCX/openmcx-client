#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

# mcx-agent: the MCX client engine (the openmcx-client image built from ../linux with tools/build-image) plus the
# agent daemon, its CLI and its MCP server.
#   docker build -t openmcx-agent:<tag> --build-arg CLIENT_IMAGE=openmcx-client:<tag> agent
ARG CLIENT_IMAGE=openmcx-client:0.1
FROM openmcx-dev:0.3 AS build
WORKDIR /src
COPY service/go.mod ./
COPY service/ .
RUN go vet ./... && go test ./... && CGO_ENABLED=0 go build -trimpath -o /install/mcx-agent .

FROM ${CLIENT_IMAGE}
COPY --from=build /install/mcx-agent /usr/local/bin/mcx-agent
COPY examples/ /usr/local/share/mcx-agent/examples/
LABEL org.opencontainers.image.title="openmcx-agent" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later" \
      org.opencontainers.image.source="https://github.com/openMCX/openmcx-client"
ENTRYPOINT ["mcx-agent"]
CMD ["daemon", "--config", "/etc/mcx-agent/agent.json"]
