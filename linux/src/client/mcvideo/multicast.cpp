//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// MCVideo media over an MBMS subchannel (TS 24.581 clause 10.3): a Map Group To Bearer message ("MCV3") received over
// the general purpose subchannel of an announced MCVideo bearer associates its TMGI and MBMS subchannel with a group
// (clause 10.3.2). While that group's MCVideo call is active the client joins the subchannel's video port and reports
// "listening" for the session and the TMGI (TS 24.281 clause 16.2.3); the video received there is reassembled and
// decoded as the video received by unicast (TS 24.581 clause 10.3.3). The subchannel's audio port is not joined: the
// audio component of a call is not played. Unmap Group To Bearer ends the association (clause 10.3.4). Transmission
// control stays on the unicast media plane.
//
// Specifications: TS 24.281 V19.6.0 (Rel-19), TS 24.581 V19.2.0 (Rel-19), RFC 1112, RFC 3550, RFC 5761.

#include "client.hpp"
#include "client_rules.hpp"

#include "mcx/mbms.hpp"
#include "mcx/mbms_usage.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// Helper: how long the client stays on the subchannel after the server answered its "not-listening" report (a local
// choice, as for MCPTT).
inline constexpr std::uint64_t mcvideo_leave_grace_ms = 200;

//------------------------------------------------------------------------------
// Helper: whether the MCVideo call is an active group call that the MCVideo server may map to a subchannel.
bool Client::mcvideo_mappable() const {
  return mcvideo_call_ && mcvideo_call_->state == "active" && mcvideo_call_->type != "private" &&
         mcvideo_call_->ambient_type.empty() && mcvideo_call_->video_mode.empty() && !mcvideo_call_->session_uri.empty();
}

//------------------------------------------------------------------------------
// TS 24.581 clause 10.3.2: the association of a group with a subchannel, repeated while the transmission lasts; for the
// active call's group the subchannel is joined (a changed subchannel anew).
void Client::mcvideo_mbms_mapped(const std::string &tmgi, const SubchannelMessage &message) {
  if (!message.subchannel) return;
  MbmsMapping mapping{message.service, message.tmgis.empty() ? tmgi : message.tmgis.front(), *message.subchannel,
                      message.ssrc};
  auto &stored = mbms_video_mappings_[message.group];
  const bool changed = stored.tmgi != mapping.tmgi || stored.subchannel != mapping.subchannel;
  stored = mapping;
  if (changed) event("mcvideo_mbms_mapped", {{"group", message.group}, {"tmgi", mapping.tmgi}});
  if (!mcvideo_mappable() || message.group != mcvideo_call_->group) return;
  if (!mcvideo_multicast_socket_ || (changed && mcvideo_multicast_group_ == message.group)) mcvideo_multicast_join();
}

//------------------------------------------------------------------------------
// TS 24.581 clause 10.3.4: Unmap Group To Bearer removes the association; a client on that subchannel reports
// "not-listening" for the session (TS 24.281 clause 16.2.3.2) and leaves it.
void Client::mcvideo_mbms_unmapped(const SubchannelMessage &message) {
  if (!mbms_video_mappings_.erase(message.group)) return;
  event("mcvideo_mbms_unmapped", {{"group", message.group}});
  if (mcvideo_multicast_socket_ && mcvideo_multicast_group_ == message.group) mcvideo_multicast_leave(true);
}

//------------------------------------------------------------------------------
// TS 24.581 clause 10.3.3 and TS 24.281 clause 16.2.3.2: the client joins the video port of the subchannel mapped to
// the active call's group, while it receives the bearer's general purpose subchannel, and reports "listening".
void Client::mcvideo_multicast_join() {
  mcvideo_multicast_leave(false);
  if (!mbms_enabled_ || !mcvideo_mappable()) return;
  const auto group = mcvideo_call_->group;
  const auto mapping = mbms_video_mappings_.find(group);
  if (mapping == mbms_video_mappings_.end()) return;
  const auto bearer = mbms_bearers_.find(mapping->second.tmgi);
  if (bearer == mbms_bearers_.end() || !bearer->second.socket) return;
  const auto &channel = mapping->second.subchannel;
  try {
    auto socket = std::make_unique<UdpSocket>("0.0.0.0", channel.media_port);
    socket->join(channel.address, mbms_interface_);
    mcvideo_multicast_socket_ = std::move(socket);
  } catch (const std::exception &error) {
    event("mcvideo_multicast_join_failed", {{"group", group}, {"reason", error.what()}});
    return;
  }
  mcvideo_multicast_session_ = mcvideo_call_->session_uri;
  mcvideo_multicast_group_ = group;
  mcvideo_multicast_tmgi_ = mapping->second.tmgi;
  status_["mcvideo_multicast_joined"] = channel.address + ":" + std::to_string(channel.media_port);
  event("mcvideo_multicast_joined", {{"group", group}, {"subchannel", status_["mcvideo_multicast_joined"]}});
  mcvideo_multicast_report(true);
}

//------------------------------------------------------------------------------
// RFC 1112 section 7.2: the client leaves the subchannel; during the call after reporting "not-listening" and a grace
// after the server's answer (mcvideo_multicast_report).
void Client::mcvideo_multicast_leave(bool report) {
  if (!mcvideo_multicast_socket_) return;
  status_["mcvideo_multicast_listening"] = false;
  if (report) return mcvideo_multicast_report(false);
  mcvideo_last_rtp_.reset();
  mcvideo_multicast_socket_.reset();
  status_["mcvideo_multicast_joined"] = "";
  event("mcvideo_multicast_left");
}

//------------------------------------------------------------------------------
// TS 24.281 clause 16.2.3.2: the listening status report for the session, a SIP MESSAGE to the MBMS public service
// identity of the bearer's announcement with the MCVideo MBMS usage information and an mcvideo-info naming the user.
void Client::mcvideo_multicast_report(bool listening) {
  MbmsListeningStatus report{listening, mcvideo_multicast_session_, std::nullopt, {mcvideo_multicast_tmgi_},
                             std::nullopt};
  McpttInfo info;
  info.request_uri = mcvideo_user();
  const std::vector<BodyPart> parts{
      {mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)},
      {mbms_usage_type(MbmsService::Mcvideo).mime, mbms_listening_status_xml(report, MbmsService::Mcvideo)}};
  const auto boundary = "mcx-" + random_id();
  const auto session = mcvideo_multicast_session_;
  const auto bearer = mbms_bearers_.find(mcvideo_multicast_tmgi_);
  const auto psi = bearer == mbms_bearers_.end() ? mcvideo_service_uri() : bearer->second.psi;
  sip_.request("MESSAGE", psi, "multipart/mixed; boundary=" + boundary, multipart_body(parts, boundary),
               mcvideo_call_headers(), [this, listening, session](const SipResponse &response) {
                 if (response.status_code < 200) return;
                 event("mcvideo_multicast_report_answered",
                       {{"listening", listening}, {"status", response.status_code}});
                 if (session != mcvideo_multicast_session_ || !mcvideo_multicast_socket_) return;
                 if (listening && response.status_code < 300) {
                   status_["mcvideo_multicast_listening"] = true;
                   return;
                 }
                 if (listening) return mcvideo_multicast_leave(false);
                 schedule_after(mcvideo_leave_grace_ms, [this, session] {
                   if (session != mcvideo_multicast_session_ || !mcvideo_multicast_socket_) return;
                   mcvideo_multicast_leave(false);
                   mcvideo_multicast_join();
                 });
               });
}

//------------------------------------------------------------------------------
// TS 24.581 clause 10.3.3: the video received on the joined subchannel goes to the call's received video, as the video
// received by unicast does; an Unmap Group To Bearer sent there ends it (clause 10.3.4). A subchannel left over from a
// call that has ended is left.
void Client::mcvideo_multicast_poll() {
  if (mcvideo_multicast_socket_ && (!mcvideo_mappable() || mcvideo_call_->session_uri != mcvideo_multicast_session_))
    mcvideo_multicast_leave(false);
  for (int index = 0; mcvideo_multicast_socket_ && index < 256; index++) {
    auto datagram = mcvideo_multicast_socket_->receive();
    if (!datagram) break;
    try {
      if (is_subchannel_message(datagram->bytes)) {
        const auto message = decode_subchannel(datagram->bytes);
        if (message.service == MbmsService::Mcvideo && message.type == SubchannelMessageType::UnmapGroup)
          mcvideo_mbms_unmapped(message);
        continue;
      }
      // RFC 5761 section 4: media control shares the subchannel's port; RTCP packet types are 200 to 204.
      if (datagram->bytes.size() >= 2 && datagram->bytes[1] >= 200 && datagram->bytes[1] <= 204) {
        mcvideo_mbms_control_packet(*datagram);
        continue;
      }
      // TS 24.581 clause 10.3.3 step 1 b: the transmitting client drops its own video.
      if (mcvideo_call_->granted) continue;
      mcvideo_video_packet(*datagram, true);
    } catch (const std::exception &error) {
      event("mcvideo_video_rejected", {{"reason", error.what()}, {"multicast", true}});
    }
  }
}

//------------------------------------------------------------------------------
// TS 24.581 clause 10.3.3: Transmission Idle and Media Transmission Notification over the joined subchannel
// (clauses 10.2.3.2 and 10.2.3.3) go to the transmission participant as those received by unicast do, from the
// server's media address with the participating function's SSRC of the Map Group To Bearer; the transmitter's own
// notification is ignored, and so is anything else. Clear only: protected transmission control stays unicast.
void Client::mcvideo_mbms_control_packet(const Datagram &datagram) {
  auto &call = *mcvideo_call_;
  if (!call.remote || !from_server_address(datagram.source, call.remote->address) || protected_floor()) return;
  const auto mapping = mbms_video_mappings_.find(mcvideo_multicast_group_);
  if (mapping == mbms_video_mappings_.end()) return;
  const auto packet = transmission::decode(datagram.bytes);
  using transmission::Type;
  if (packet.ssrc != mapping->second.ssrc ||
      (packet.type != Type::Idle && packet.type != Type::MediaTransmissionNotification))
    return;
  if (const auto *field = transmission::find(packet, transmission::Field::TransmittingUserId);
      field && transmission::read_transmitting_user(*field) == mcvideo_user())
    return;
  status_["mcvideo_mbms_control_received"] = status_.value("mcvideo_mbms_control_received", 0ULL) + 1;
  mcvideo_control_message(packet);
}

//------------------------------------------------------------------------------
// RFC 3550 appendix A.1: while the client has joined a subchannel, a video packet of the stream it follows that is not
// newer than the last one kept is a copy that came the other way during a change: dropped before reassembly.
bool Client::mcvideo_duplicate(std::span<const std::uint8_t> bytes, bool multicast) {
  // RFC 3550 section 5.1: the sequence number in octets 2 and 3, the SSRC in octets 8 to 11 (clear under SRTP too).
  if (!mcvideo_multicast_socket_ || bytes.size() < 12) return false;
  const auto sequence = static_cast<std::uint16_t>((bytes[2] << 8) | bytes[3]);
  const auto ssrc = (static_cast<std::uint32_t>(bytes[8]) << 24) | (static_cast<std::uint32_t>(bytes[9]) << 16) |
                    (static_cast<std::uint32_t>(bytes[10]) << 8) | bytes[11];
  if (mcvideo_last_rtp_ && mcvideo_last_rtp_->first == ssrc &&
      !client_rules::rtp_sequence_newer(sequence, mcvideo_last_rtp_->second)) {
    const char *counter = multicast ? "mcvideo_duplicates_dropped_multicast" : "mcvideo_duplicates_dropped_unicast";
    status_[counter] = status_.value(counter, 0ULL) + 1;
    return true;
  }
  mcvideo_last_rtp_ = {ssrc, sequence};
  if (multicast)
    status_["mcvideo_multicast_received_packets"] = status_.value("mcvideo_multicast_received_packets", 0ULL) + 1;
  return false;
}

} // namespace mcx
