# The openMCX dispatcher position: the MCX client engine (the openmcx-client image built from ../linux with
# tools/build-image) plus the dispatcher web service.
#   docker build -t openmcx-dispatcher:<tag> --build-arg CLIENT_IMAGE=openmcx-client:<tag> dispatcher
ARG CLIENT_IMAGE=openmcx-client:0.1
FROM openmcx-dev:0.1 AS build
WORKDIR /src
COPY service/go.mod service/go.sum ./
RUN go mod download
COPY service/ .
RUN go vet ./... && go test ./... && CGO_ENABLED=0 go build -trimpath -o /install/dispatcher .

FROM ${CLIENT_IMAGE}
COPY --from=build /install/dispatcher /usr/local/bin/dispatcher
LABEL org.opencontainers.image.title="openmcx-dispatcher" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later" \
      org.opencontainers.image.source="https://github.com/openMCX/openmcx-client"
ENTRYPOINT ["dispatcher"]
