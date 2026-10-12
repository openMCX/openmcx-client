#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

# Network controls for the Android lab, run in a client's network namespace:
#   docker run --rm --net container:<client> --cap-add NET_ADMIN mcx-nettools:1 …
# nftables filters (coverage loss), tc netem (impairment), and iptables-nft for NAT, whose xt_nat
# module Docker already loads (nft's own nat expression may not be available on the host).
FROM alpine:latest
RUN apk add --no-cache nftables iproute2-tc iptables
