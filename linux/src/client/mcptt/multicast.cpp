//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// IP multicast reception of MCPTT group call speech, an OpenMCX profile and not a 3GPP procedure
// (include/mcx/multicast.hpp): while a configured group's call is active the client joins the group's IPv4 host
// group and reports that it is listening (TS 24.379 clause 14.3.3.2), and the speech received there takes the same
// path as the speech received by unicast. When it stops listening during the call ("multicast off") it reports
// "not-listening", and the server sends it the speech by unicast again. Floor control stays on the unicast media
// plane.
//
// Specifications: RFC 1112, RFC 3550, TS 24.379 V19.7.0 (Rel-19), TS 24.380 V19.2.0 (Rel-19).

#include "client.hpp"
#include "client_rules.hpp"

#include "mcx/mbms_usage.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// RFC 3550 section 5.1: the RTP fixed header, with the sequence number in octets 2 and 3.
inline constexpr std::size_t rtp_fixed_header_bytes = 12;

//------------------------------------------------------------------------------
// Helper: how long the client stays in the host group after the server answered its "not-listening" report, for the
// speech sent there before the report was taken (a local choice; ten 20 ms packets).
inline constexpr std::uint64_t multicast_leave_grace_ms = 200;

//------------------------------------------------------------------------------
// RFC 3550 section 5.1: the sequence number of an RTP packet of at least the fixed header.
static std::uint16_t rtp_sequence(std::span<const std::uint8_t> packet) {
  return static_cast<std::uint16_t>((packet[2] << 8) | packet[3]);
}

//------------------------------------------------------------------------------
// RFC 1112 section 7.2: when the call of a configured group becomes active, the client joins the group's host
// group and reports "listening" for the session (TS 24.379 clause 14.3.3.2). Only a prearranged, chat or broadcast
// group call (or a regroup) with floor control: the server sends nothing else by multicast. A refused join leaves
// the call on unicast, and so does "multicast off" until "multicast on".
void Client::multicast_join() {
  multicast_leave(false);
  if (!multicast_ || multicast_off_ || private_media() || call_type_ == "adhoc" || no_floor()) return;
  const auto group = active_group();
  const auto *destination = multicast_->group(group);
  if (!destination || session_uri_.empty()) return;
  try {
    // Bound to every address: a socket bound to a unicast address receives no datagram sent to a host group.
    auto socket = std::make_unique<UdpSocket>("0.0.0.0", destination->port);
    socket->join(destination->address, multicast_->interface);
    multicast_socket_ = std::move(socket);
  } catch (const std::exception &error) {
    event("multicast_join_failed", {{"group", group}, {"reason", error.what()}});
    return;
  }
  multicast_session_ = session_uri_;
  multicast_tmgi_ = destination->tmgi;
  status_["multicast_joined"] = destination->address + ":" + std::to_string(destination->port);
  event("multicast_joined", {{"group", group}, {"host_group", status_["multicast_joined"]}});
  multicast_report(true);
}

//------------------------------------------------------------------------------
// RFC 1112 section 7.2: the client leaves the host group (the membership ends with the socket). During the call it
// reports "not-listening" first (TS 24.379 clause 14.3.3.2 step 1 g) and leaves shortly after the server has
// answered: until then the server may still send the speech by multicast, and a packet that arrives both ways is kept
// once. At the end of the call there is no session left to report on.
void Client::multicast_leave(bool report) {
  if (!multicast_socket_) return;
  status_["multicast_listening"] = false;
  if (report) return multicast_report(false);
  last_rtp_.reset();
  multicast_socket_.reset();
  status_["multicast_joined"] = "";
  event("multicast_left");
}

//------------------------------------------------------------------------------
// TS 24.379 clause 14.3.3.2: the listening status report, a SIP MESSAGE to the participating function with the
// MBMS usage information (<mbms-listening-status>, the session, the TMGI; version 1) and an mcptt-info naming the
// user (step 1 h). The Request-URI is the service's PSI: without an announcement there is no MBMS PSI of step 1 a.
// A refused "listening" report, and any answer to a "not-listening" one, leave the host group: the call goes on by
// unicast.
void Client::multicast_report(bool listening) {
  MbmsListeningStatus report{listening, multicast_session_, std::nullopt, {multicast_tmgi_}, std::nullopt};
  McpttInfo info;
  info.request_uri = user();
  const std::vector<BodyPart> parts{{mcptt_mime, mcptt_info_xml(info)},
                                    {mbms_usage_mime, mbms_listening_status_xml(report)}};
  const auto boundary = "mcx-" + random_id();
  const auto session = multicast_session_;
  sip_.request("MESSAGE", service(), "multipart/mixed; boundary=" + boundary, multipart_body(parts, boundary),
               {{"Accept-Contact", "*;+g.3gpp.icsi-ref=\"" + url_encode(mcptt_icsi) + "\";require;explicit"},
                {"P-Preferred-Service", mcptt_icsi}},
               [this, listening, session](const SipResponse &response) {
                 if (response.status_code < 200) return;
                 const bool accepted = response.status_code < 300;
                 event("multicast_report_answered", {{"listening", listening}, {"status", response.status_code}});
                 if (session != multicast_session_ || !multicast_socket_) return;
                 if (listening && accepted && !multicast_off_) {
                   status_["multicast_listening"] = true;
                   return;
                 }
                 if (listening && !accepted) status_["multicast_refused"] = response.status_code;
                 // Refused: the call goes on by unicast.
                 if (listening) return multicast_leave(false);
                 // Left for "multicast off" a grace after the answer: speech the server sent to the host group just
                 // before it took the report may still be on its way. A "multicast on" given meanwhile joins again.
                 schedule_after(multicast_leave_grace_ms, [this, session] {
                   if (session != multicast_session_ || !multicast_socket_) return;
                   multicast_leave(false);
                   if (!multicast_off_ && status_.value("call_state", "") == "active") multicast_join();
                 });
               });
}

//------------------------------------------------------------------------------
// Helper: "multicast off" stops listening during the call (as when the client loses the multicast) and "multicast on"
// starts again; the choice lasts until the call ends.
void Client::multicast_command(const std::string &cmd) {
  if (!multicast_) throw std::runtime_error("multicast not configured");
  if (cmd == "multicast off") {
    multicast_off_ = true;
    multicast_leave(true);
  } else if (cmd == "multicast on") {
    multicast_off_ = false;
    if (!multicast_socket_ && status_.value("call_state", "") == "active") multicast_join();
  } else {
    throw std::runtime_error("usage: multicast on|off");
  }
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
bool Client::duplicate_rtp(std::span<const std::uint8_t> packet, bool multicast) {
  if (!multicast_socket_ || packet.size() < rtp_fixed_header_bytes) return false;
  const auto ssrc = wire_ssrc(packet, false);
  if (!last_rtp_ || last_rtp_->first != ssrc) return false;
  if (client_rules::rtp_sequence_newer(rtp_sequence(packet), last_rtp_->second)) return false;
  // Counted by the way the dropped copy came, so that the copies the server sent each way can be accounted for.
  const char *counter = multicast ? "duplicates_dropped_multicast" : "duplicates_dropped_unicast";
  status_[counter] = status_.value(counter, 0ULL) + 1;
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
