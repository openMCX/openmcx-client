#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

# The MC client image (openmcx-client): one build stage on the development image, then a slim Ubuntu image with
# only the client's run-time packages and the client stripped of its debug information (the same -O2 machine code),
# plus curl and Python for the lab's health checks. Build with tools/build-image, which stages this repository under
# /client and the needed openmcx-server files under /openmcx-server.

ARG DEV_IMAGE=openmcx-dev:0.4
ARG RUNTIME_BASE=ubuntu:24.04@sha256:008173c23f95b170204355c12626cb5a965d779a7e1283b09e9cffbb1bf33ca3

#------------------------------------------------------------------------------
# Build: the client and its test; a stripped copy for the runtime image.
FROM ${DEV_IMAGE} AS build
COPY openmcx-server /openmcx-server
COPY client /client
RUN cmake -S /client -B /build -DCMAKE_BUILD_TYPE=RelWithDebInfo -DOPENMCX_SERVER_DIR=/openmcx-server \
    && cmake --build /build --target mcx-client -j4 \
    && python3 /client/tests/test_binary.py /build/mcx-client \
    && strip --strip-debug -o /build/mcx-client.stripped /build/mcx-client

#------------------------------------------------------------------------------
# Runtime: SIP and media (SRTP, AMR-WB, OpenH264 for MCVideo, TS 26.281), HTTP to the common services.
FROM ${RUNTIME_BASE} AS openmcx-client
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl python3 \
       libcurl4t64 libxml2 libssl3t64 libuuid1 libopencore-amrwb0 libvo-amrwbenc0 libopenh264-7 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/mcx-client.stripped /usr/local/bin/mcx-client
COPY openmcx-server/tools/healthcheck.py /healthcheck.py
COPY LICENSE NOTICE /usr/share/doc/openmcx-client/
# hostap's licence (BSD) for the MILENAGE of the IMS AKA ISIM (openmcx-server dependencies/hostap).
COPY openmcx-server/dependencies/hostap/README /usr/share/doc/openmcx-client/hostap-README
# libsrtp's licence (BSD-3-Clause): the client links the development image's libsrtp, built with OpenMCX's patch
# (openmcx-server dependencies/libsrtp), statically.
COPY --from=build /usr/local/share/doc/libsrtp/LICENSE /usr/share/doc/openmcx-client/libsrtp-LICENSE
LABEL org.opencontainers.image.licenses="AGPL-3.0-or-later" \
      org.opencontainers.image.source="https://github.com/openMCX/openmcx-client"
WORKDIR /workspace
ENTRYPOINT ["/usr/local/bin/mcx-client"]
