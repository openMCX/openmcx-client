//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// IP multicast reception of MCPTT group call speech, an OpenMCX profile and not a 3GPP procedure
// (include/mcx/multicast.hpp): while a configured group's call is active the client joins the group's IPv4 host
// group, and the speech received there takes the same path as the speech received by unicast. Floor control stays on
// the unicast media plane.
//
// Specifications: RFC 1112, RFC 3550, TS 24.380 V19.2.0 (Rel-19).

#include "client.hpp"
#include "client_rules.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// RFC 3550 section 5.1: the RTP fixed header, with the sequence number in octets 2 and 3.
inline constexpr std::size_t rtp_fixed_header_bytes = 12;

//------------------------------------------------------------------------------
// RFC 3550 section 5.1: the sequence number of an RTP packet of at least the fixed header.
static std::uint16_t rtp_sequence(std::span<const std::uint8_t> packet) {
  return static_cast<std::uint16_t>((packet[2] << 8) | packet[3]);
}

//------------------------------------------------------------------------------
// RFC 1112 section 7.2: when the call of a configured group becomes active, the client joins the group's host
// group. Only a prearranged, chat or broadcast group call (or a regroup) with floor control: the server sends
// nothing else by multicast. A refused join leaves the call on unicast.
void Client::multicast_join() {
  multicast_leave();
  if (!multicast_ || private_media() || call_type_ == "adhoc" || no_floor()) return;
  const auto group = active_group();
  const auto *destination = multicast_->group(group);
  if (!destination) return;
  try {
    // Bound to every address: a socket bound to a unicast address receives no datagram sent to a host group.
    auto socket = std::make_unique<UdpSocket>("0.0.0.0", destination->port);
    socket->join(destination->address, multicast_->interface);
    multicast_socket_ = std::move(socket);
  } catch (const std::exception &error) {
    event("multicast_join_failed", {{"group", group}, {"reason", error.what()}});
    return;
  }
  status_["multicast_joined"] = destination->address + ":" + std::to_string(destination->port);
  event("multicast_joined", {{"group", group}, {"host_group", status_["multicast_joined"]}});
}

//------------------------------------------------------------------------------
// RFC 1112 section 7.2: when the call ends the client leaves the host group (the membership ends with the socket).
void Client::multicast_leave() {
  last_rtp_.reset();
  if (!multicast_socket_) return;
  multicast_socket_.reset();
  status_["multicast_joined"] = "";
  event("multicast_left");
}

//------------------------------------------------------------------------------
// Helper: the speech received on the joined host group goes to the call's received media (TS 24.380 clause
// 6.2.4.3.4), as the speech received by unicast does.
void Client::multicast_poll() {
  constexpr int max_datagrams = 32;
  for (int index = 0; multicast_socket_ && index < max_datagrams; index++) {
    auto datagram = multicast_socket_->receive();
    if (!datagram) break;
    try {
      audio_packet(*datagram, true);
    } catch (const std::exception &error) {
      status_["media_rejected_packets"] = status_.value("media_rejected_packets", 0ULL) + 1;
      event("media_rejected", {{"reason", error.what()}, {"multicast", true}});
    }
  }
}

//------------------------------------------------------------------------------
// RFC 3550 appendix A.1: while the client has joined a host group, a packet of the SSRC it follows that is not newer
// than the last one kept is a copy that arrived the other way (unicast or multicast) during a change, or an old one:
// it is dropped before it reaches the SRTP replay check. Without a host group nothing is dropped.
bool Client::duplicate_rtp(std::span<const std::uint8_t> packet) {
  if (!multicast_socket_ || packet.size() < rtp_fixed_header_bytes) return false;
  const auto ssrc = wire_ssrc(packet, false);
  if (!last_rtp_ || last_rtp_->first != ssrc) return false;
  if (client_rules::rtp_sequence_newer(rtp_sequence(packet), last_rtp_->second)) return false;
  status_["multicast_duplicates_dropped"] = status_.value("multicast_duplicates_dropped", 0ULL) + 1;
  return true;
}

//------------------------------------------------------------------------------
// Helper: a packet that passed every check (SSRC, SRTP authentication, payload type) becomes the last one kept;
// only such a packet moves the sequence number on, so that a forged one cannot hold back the speech.
void Client::kept_rtp(std::span<const std::uint8_t> packet) {
  if (multicast_socket_ && packet.size() >= rtp_fixed_header_bytes)
    last_rtp_ = {wire_ssrc(packet, false), rtp_sequence(packet)};
}

} // namespace mcx
