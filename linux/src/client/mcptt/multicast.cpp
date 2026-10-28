//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// MCPTT media over an MBMS subchannel (TS 24.380 clause 10.3): a Map Group To Bearer message received over the
// general purpose subchannel of an announced bearer associates its TMGI and MBMS subchannel with a group (clause
// 10.3.2). While that group's call is active the client joins the subchannel and reports "listening" for the
// session and the TMGI (TS 24.379 clause 14.3.3.1 condition 1, clause 14.3.3.2); the speech received there takes the
// same path as the speech received by unicast (TS 24.380 clause 10.3.3), and the server stops sending it by unicast
// once it has taken the report (TS 24.380 annex A.6.2 step 26). An Unmap Group To Bearer message ends the
// association (clause 10.3.4): the client reports "not-listening" for the session and gets unicast again. Floor Taken
// and Floor Idle may come over the subchannel too (clause 10.3.3); the other floor control messages stay unicast.
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 24.380 V19.2.0 (Rel-19), RFC 1112, RFC 3550, RFC 5761.

#include "client.hpp"
#include "client_rules.hpp"

#include "mcx/mbms.hpp"
#include "mcx/mbms_usage.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// RFC 3550 section 5.1: the RTP fixed header, with the sequence number in octets 2 and 3.
inline constexpr std::size_t rtp_fixed_header_bytes = 12;

//------------------------------------------------------------------------------
// Helper: how long the client stays on the subchannel after the server answered its "not-listening" report, for the
// speech sent there before the report was taken (a local choice; ten 20 ms packets).
inline constexpr std::uint64_t multicast_leave_grace_ms = 200;

//------------------------------------------------------------------------------
// RFC 3550 section 5.1: the sequence number of an RTP packet of at least the fixed header.
static std::uint16_t rtp_sequence(std::span<const std::uint8_t> packet) {
  return static_cast<std::uint16_t>((packet[2] << 8) | packet[3]);
}

//------------------------------------------------------------------------------
// TS 24.380 clause 10.3.2: a Map Group To Bearer message over the general purpose subchannel of a bearer associates
// the TMGI and the MBMS subchannel with the group; it is repeated while the conversation lasts (clause 4.1.3.2), so a
// late joiner hears it too. A mapping for the group of the active call joins its subchannel (TS 24.379 clause
// 14.3.3.1 condition 1); a changed subchannel is joined anew.
void Client::mbms_mapped(const std::string &tmgi, const SubchannelMessage &message) {
  if (message.service == MbmsService::Mcvideo) return mcvideo_mbms_mapped(tmgi, message);
  if (!message.subchannel) return;
  // TS 24.380 clause 10B: a MapGroupToSessionStream names the MBS session instead of a TMGI.
  const auto bearer_id = !message.mbs_session_id.empty() ? message.mbs_session_id
                         : message.tmgis.empty()         ? tmgi
                                                         : message.tmgis.front();
  MbmsMapping mapping{message.service, bearer_id, *message.subchannel, message.ssrc};
  auto &stored = mbms_mappings_[message.group];
  const bool changed = stored.tmgi != mapping.tmgi || stored.subchannel != mapping.subchannel;
  if (stored.ssrc != mapping.ssrc) mbms_floor_sequence_.reset();
  stored = mapping;
  if (changed) event("mbms_mapped", {{"group", message.group}, {"tmgi", mapping.tmgi}});
  if (status_.value("call_state", "") != "active" || message.group != active_group()) return;
  if (!multicast_socket_ || (changed && multicast_group_ == message.group)) multicast_join();
}

//------------------------------------------------------------------------------
// TS 24.380 clause 10.3.4: Unmap Group To Bearer removes the association; a client on that subchannel reports
// "not-listening" for the session (TS 24.379 clause 14.3.3.2 step 1 g iii) and leaves it.
void Client::mbms_unmapped(const SubchannelMessage &message) {
  if (message.service == MbmsService::Mcvideo) return mcvideo_mbms_unmapped(message);
  if (!mbms_mappings_.erase(message.group)) return;
  event("mbms_unmapped", {{"group", message.group}});
  if (multicast_socket_ && multicast_group_ == message.group) multicast_leave(true);
}

//------------------------------------------------------------------------------
// TS 24.380 clause 10.3.3 and TS 24.379 clause 14.3.3.2: the client joins the MBMS subchannel mapped to the active
// call's group, while it receives the bearer's general purpose subchannel, and reports "listening" for the session.
// Only a prearranged, chat or broadcast group call (or a regroup) with floor control: the server maps nothing else.
void Client::multicast_join() {
  multicast_leave(false);
  if (!mbms_enabled_ || private_media() || call_type_ == "adhoc" || no_floor()) return;
  const auto group = active_group();
  const auto mapping = mbms_mappings_.find(group);
  if (mapping == mbms_mappings_.end() || session_uri_.empty()) return;
  const auto bearer = mbms_bearers_.find(mapping->second.tmgi);
  if (bearer == mbms_bearers_.end() || !bearer->second.socket) return;
  const auto &channel = mapping->second.subchannel;
  try {
    // Bound to every address: a socket bound to a unicast address receives no datagram sent to a host group.
    auto socket = std::make_unique<UdpSocket>("0.0.0.0", channel.media_port);
    socket->join(channel.address, mbms_interface_);
    multicast_socket_ = std::move(socket);
  } catch (const std::exception &error) {
    event("multicast_join_failed", {{"group", group}, {"reason", error.what()}});
    return;
  }
  multicast_session_ = session_uri_;
  multicast_group_ = group;
  multicast_tmgi_ = mapping->second.tmgi;
  status_["multicast_joined"] = channel.address + ":" + std::to_string(channel.media_port);
  event("multicast_joined", {{"group", group}, {"subchannel", status_["multicast_joined"]}});
  // TS 24.379 clause 14B.3.5.1 condition 1: a multicast MBS session asking for it gets the join for the session.
  if (bearer->second.mbs && bearer->second.mode == "multicast" && bearer->second.report_join.value_or(false))
    mbs_join_notification(multicast_tmgi_, true, multicast_session_);
  multicast_report(true);
}

//------------------------------------------------------------------------------
// RFC 1112 section 7.2: the client leaves the subchannel (the membership ends with the socket). During the call it
// reports "not-listening" first (TS 24.379 clause 14.3.3.2 step 1 g) and leaves shortly after the server has
// answered: until then the server may still send the speech over the subchannel, and a packet that arrives both ways
// is kept once. At the end of the call there is no session left to report on.
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
// TS 24.379 clause 14.3.3.2: the listening status report for the session, a SIP MESSAGE to the MBMS public service
// identity of the bearer's announcement (step 1 a) with the MBMS usage information (<mbms-listening-status>, the
// session, the TMGI; version 1) and an mcptt-info naming the user (step 1 h). A refused "listening" report, and any
// answer to a "not-listening" one, leave the subchannel: the call goes on by unicast.
void Client::multicast_report(bool listening) {
  MbmsListeningStatus report{listening, multicast_session_, std::nullopt, {multicast_tmgi_}, std::nullopt};
  McpttInfo info;
  info.request_uri = user();
  const auto bearer = mbms_bearers_.find(multicast_tmgi_);
  const bool mbs = bearer != mbms_bearers_.end() && bearer->second.mbs;
  const std::vector<BodyPart> parts{{mcptt_mime, mcptt_info_xml(info)},
                                    mbms_status_part(MbmsService::Mcptt, mbs, report)};
  const auto boundary = "mcx-" + random_id();
  const auto session = multicast_session_;
  const auto psi = bearer == mbms_bearers_.end() ? service() : bearer->second.psi;
  sip_.request("MESSAGE", psi, "multipart/mixed; boundary=" + boundary, multipart_body(parts, boundary),
               {{"Accept-Contact", "*;+g.3gpp.icsi-ref=\"" + url_encode(mcptt_icsi) + "\";require;explicit"},
                {"P-Preferred-Service", mcptt_icsi}},
               [this, listening, session](const SipResponse &response) {
                 if (response.status_code < 200) return;
                 const bool accepted = response.status_code < 300;
                 event("multicast_report_answered", {{"listening", listening}, {"status", response.status_code}});
                 if (session != multicast_session_ || !multicast_socket_) return;
                 if (listening && accepted) {
                   status_["multicast_listening"] = true;
                   return;
                 }
                 if (listening && !accepted) status_["multicast_refused"] = response.status_code;
                 // Refused: the call goes on by unicast.
                 if (listening) return multicast_leave(false);
                 // Left a grace after the answer: speech the server sent over the subchannel just before it took the
                 // report may still be on its way. A mapping still in place (and received) is joined again.
                 schedule_after(multicast_leave_grace_ms, [this, session] {
                   if (session != multicast_session_ || !multicast_socket_) return;
                   multicast_leave(false);
                   if (status_.value("call_state", "") == "active") multicast_join();
                 });
               });
}

//------------------------------------------------------------------------------
// TS 24.380 clause 10.3.3: the speech received on the joined subchannel goes to the call's received media (TS 24.380
// clause 6.2.4.3.4), as the speech received by unicast does; an Unmap Group To Bearer sent there ends it (clause
// 10.3.4), and the other media control messages are left to the unicast media plane.
void Client::multicast_poll() {
  constexpr int max_datagrams = 32;
  for (int index = 0; multicast_socket_ && index < max_datagrams; index++) {
    auto datagram = multicast_socket_->receive();
    if (!datagram) break;
    try {
      // RFC 5761 section 4: media control shares the subchannel's port; RTCP packet types are 200 to 204.
      if (datagram->bytes.size() >= 2 && datagram->bytes[1] >= 200 && datagram->bytes[1] <= 204) {
        // TS 24.380 clause 13.3.3: an Unmap Group To Bearer (clear, or SRTCP under the bearer's MSCCK) or a floor
        // control message (clear, or SRTCP under the MuSiK).
        const auto bearer = mbms_bearers_.find(multicast_tmgi_);
        // The MKI's purpose tag tells SRTCP under the MSCCK only when the bearer has one: a clear message's last
        // octets are not an MKI.
        const bool control = is_subchannel_message(datagram->bytes) ||
                             (bearer != mbms_bearers_.end() && bearer->second.mscck &&
                              mbms_mki_purpose(datagram->bytes) == 5);
        if (control && bearer != mbms_bearers_.end()) {
          const auto message = decode_subchannel(mbms_open_control(datagram->bytes, bearer->second));
          if (message.type == SubchannelMessageType::UnmapGroup) mbms_unmapped(message);
          if (message.type == SubchannelMessageType::ApplicationPaging) mbms_paged(message);
        } else if (!control) {
          mbms_floor_packet(*datagram);
        }
        continue;
      }
      audio_packet(*datagram, true);
    } catch (const std::exception &error) {
      status_["media_rejected_packets"] = status_.value("media_rejected_packets", 0ULL) + 1;
      event("media_rejected", {{"reason", error.what()}, {"multicast", true}});
    }
  }
}

//------------------------------------------------------------------------------
// TS 24.380 clause 10.3.3 step 1 a: Floor Taken and Floor Idle over the joined subchannel (clauses 10.2.3.2 and
// 10.2.3.3) go to the floor participant as those received by unicast do, from the server's media address with the
// participating function's SSRC of the Map Group To Bearer; the talker's own Floor Taken is not its own state. Any
// other floor control message there is ignored.
void Client::mbms_floor_packet(const Datagram &datagram) {
  if (!remote_ || !from_server_address(datagram.source, remote_->address)) return;
  const auto mapping = mbms_mappings_.find(multicast_group_);
  if (mapping == mbms_mappings_.end()) return;
  // TS 24.380 clause 13.3.3: under protected floor control the messages are SRTCP under the MuSiK (its MKI purpose tag
  // 6); a clear one is then refused, as is a protected one without the MuSiK.
  if (protected_floor() && (!mbms_musik_ || mbms_mki_purpose(datagram.bytes) != 6)) return;
  auto packet = decode_floor(protected_floor() ? mbms_musik_->unprotect_floor(datagram.bytes) : datagram.bytes);
  if (packet.ssrc != mapping->second.ssrc || (packet.type != FloorType::Taken && packet.type != FloorType::Idle)) return;
  if (packet.type == FloorType::Taken)
    for (const auto &field : packet.fields)
      if (field.id == static_cast<std::uint8_t>(FloorFieldId::GrantedPartyIdentity) &&
          read_granted_party_identity(field) == user())
        return;
  status_["mbms_floor_received"] = status_.value("mbms_floor_received", 0ULL) + 1;
  floor_message(std::move(packet), true);
}

//------------------------------------------------------------------------------
// RFC 3550 appendix A.1: while the client has joined a subchannel, a packet of the SSRC it follows that is not newer
// than the last one kept is a copy that arrived the other way (unicast or multicast) during a change, or an old one:
// it is dropped before it reaches the SRTP replay check (TS 24.380 annex A.6.2 step 22). Without a subchannel nothing
// is dropped.
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
