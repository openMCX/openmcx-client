//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// The client's MBMS usage procedures for MCPTT (TS 24.379 clause 14.3) and MCVideo (TS 24.281 clause 16.2): the
// MBMS bearer announcements of the participating function, the general purpose MBMS subchannel of each announced
// bearer, the Bearer Announcement message received over it (TS 24.380 clause 10.3.6, TS 24.581 clause 10.3.6) and
// the listening status reports (TS 24.379 clause 14.3.3). The UE's MBMS interface (its modem and the radio) is
// emulated: the "mbms" block of the configuration gives the MBMS service areas the UE is in, "mbms area" moves it and
// "mbms quality" sets the radio conditions of a bearer; a bearer the UE can receive is an IPv4 host group on the lab
// network.
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 24.281 V19.6.0 (Rel-19), TS 24.380 V19.2.0 (Rel-19),
// TS 24.581 V19.2.0 (Rel-19), RFC 1112, RFC 4566.

#include "mcx/key_client.hpp"
#include "mcx/mbms.hpp"
#include "mcx/warning.hpp"

#include "client.hpp"

#include <algorithm>
#include <sstream>

namespace mcx {

namespace {

//------------------------------------------------------------------------------
// Helper: the service profile of an MBMS service.
const ServiceProfile &profile_of(MbmsService service) {
  return service == MbmsService::Mcvideo ? mcvideo_service : mcptt_service;
}

//------------------------------------------------------------------------------
// RFC 4566 sections 5.7 and 5.14 and RFC 4567 section 3.1: the port of the n-th media line (counted from 1), its
// connection address (media-level, else session-level; an IPv4 multicast address may carry "/<ttl>") and its
// "a=key-mgmt:mikey" data, if any. Throws when there is no such line.
struct MediaLine {
  std::string address;
  std::uint16_t port = 0;
  std::string key_mgmt;
};
MediaLine media_line(const std::string &sdp, unsigned number) {
  std::istringstream lines(sdp);
  std::string line, session_address;
  MediaLine out;
  unsigned current = 0;
  long port = -1;
  while (std::getline(lines, line) && current <= number) {
    if (!line.empty() && line.back() == '\r') line.pop_back();
    if (line.starts_with("m=")) {
      if (++current != number) continue;
      std::istringstream fields(line.substr(2));
      std::string media;
      fields >> media >> port;
    } else if (line.starts_with("c=IN IP4 ") && (current == 0 || current == number)) {
      auto value = line.substr(9);
      if (const auto slash = value.find('/'); slash != std::string::npos) value.resize(slash);
      (current == 0 ? session_address : out.address) = value;
    } else if (line.starts_with("a=key-mgmt:mikey ") && current == number) {
      out.key_mgmt = line.substr(17);
    }
  }
  if (current < number || port <= 0 || port > 65535) throw std::runtime_error("no such media line in the SDP");
  if (out.address.empty()) out.address = session_address;
  out.port = static_cast<std::uint16_t>(port);
  return out;
}

//------------------------------------------------------------------------------
// TS 24.379 clauses 14.3.2 step 1 f and 14.3.4: the answer to an announcement or MuSiK download whose MIKEY-SAKKE
// I_MESSAGE fails: 488 with warning 136.
SipResponse mikey_refusal(const ServiceProfile &profile) {
  SipResponse response;
  response.status_code = 488;
  response.headers = {{"Warning", "399 mcx \"" + warning_text(profile, Warning::mikey_authentication_failed) + "\""}};
  return response;
}

} // namespace

//------------------------------------------------------------------------------
// Helper: reads the "mbms" block (the emulated MBMS interface): the MBMS service area IDs the UE is in (hexBinary,
// lower case) and the interface to join on. Without it the client takes no announcement.
void Client::mbms_configure() {
  if (!cfg_.contains("mbms")) return;
  const auto &block = cfg_.at("mbms");
  mbms_enabled_ = true;
  mbms_interface_ = block.value("interface", std::string("0.0.0.0"));
  for (const auto &area : block.value("service_areas", Json::array())) {
    auto value = area.get<std::string>();
    std::ranges::transform(value, value.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    mbms_areas_.insert(value);
  }
  publish_mbms();
}

//------------------------------------------------------------------------------
// Helper: an MBMS usage information body of a service in a MESSAGE, or none.
const std::string *Client::mbms_usage_body(const SipMessage &message, MbmsService service) const {
  const std::string mime = mbms_usage_type(service).mime;
  if (message.content_type == mime) return &message.body;
  for (const auto &part : message.parts)
    if (part.content_type == mime) return &part.body;
  return nullptr;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 14.3.2 (TS 24.281 clause 16.2.2) and clause 14.3.4: a SIP MESSAGE with the service's
// P-Asserted-Service and MBMS usage information. Each <announcement> with service areas is stored by its TMGI
// (replacing one stored before; steps 1 a and 1 b) with the SDP, its <GPMS> line and the MBMS public service
// identity asserted by the sender (steps 1 c to 1 e); one without service areas discards the stored one (step 2).
// The conditions of clause 14.3.3.1 are then checked. A message without an announcement (a MuSiK download) is
// accepted and left to later procedures.
SipResponse Client::mbms_message(const SipMessage &message, MbmsService service, const std::string &body, bool mbs) {
  SipResponse response;
  response.status_code = 200;
  const auto &profile = profile_of(service);
  if (std::string(get_header(message.headers, "P-Asserted-Service")) != profile.icsi) {
    response.status_code = 403;
    return response;
  }
  // TS 24.379 clause 14B.3.2 and annex L.3.4: an MBS announcement is taken as the MBMS one, the MBS session ID for the
  // TMGI and the de-announcement acknowledgement for the suspension report; its own fields are kept with the bearer.
  MbmsUsageInfo usage;
  std::map<std::string, MbsAnnouncement> sessions;
  try {
    if (mbs) {
      const auto parsed = parse_mbs_usage_info(body, service);
      usage.musik_download = parsed.musik_download;
      for (const auto &session : parsed.announcements) {
        MbmsAnnouncement announcement;
        announcement.tmgi = session.session_id;
        announcement.frequency = session.frequency;
        announcement.service_areas = session.service_areas;
        announcement.gpms = session.gpms;
        announcement.report_suspension = session.de_announcement_ack;
        usage.announcements.push_back(std::move(announcement));
        sessions[session.session_id] = session;
      }
    } else {
      usage = parse_mbms_usage_info(body, service);
    }
  } catch (const std::exception &error) {
    event("mbms_announcement_refused", {{"reason", error.what()}});
    response.status_code = 400;
    return response;
  }
  if (!mbms_enabled_) return response;
  auto psi = asserted_identity(message);
  if (psi.empty()) psi = message.from_uri;
  auto *keys = service == MbmsService::Mcvideo ? mcvideo_keys_.get() : keys_.get();
  // TS 24.379 clause 14.3.4 (TS 24.281 clause 16.2.4): a MuSiK download: the I_MESSAGE of the application/mikey body
  // from the participating function's PSI (step 1 a), opened with this user's key (TS 33.180 clause 5.8); the MuSiK
  // protects the floor control over the MBMS subchannels. Groups listed or not, the client holds one MuSiK.
  if (usage.musik_download) {
    const std::string *mikey = nullptr;
    for (const auto &part : message.parts)
      if (part.content_type == "application/mikey") mikey = &part.body;
    try {
      if (!mikey || !keys) throw std::runtime_error("no MuSiK I_MESSAGE or no key management");
      if (KeyClient::initiator(*mikey) != psi) throw std::runtime_error("MuSiK from another initiator");
      const auto key = keys->accept_signalling_key(psi, *mikey, 6);
      auto &musik = service == MbmsService::Mcvideo ? mcvideo_mbms_musik_ : mbms_musik_;
      musik = std::make_unique<ProtectedCall>(service == MbmsService::Mcvideo);
      musik->set_floor(key);
      status_[service == MbmsService::Mcvideo ? "mcvideo_mbms_musik_id" : "mbms_musik_id"] = key.csb_id;
      event("mbms_musik_received", {{"service", profile.name}, {"musik_id", key.csb_id}});
    } catch (const std::exception &error) {
      event("mbms_musik_refused", {{"service", profile.name}, {"reason", error.what()}});
      return mikey_refusal(profile);
    }
  }
  if (usage.announcements.empty()) return response;
  std::string sdp_body;
  if (message.content_type == "application/sdp") sdp_body = message.body;
  for (const auto &part : message.parts)
    if (part.content_type == "application/sdp") sdp_body = part.body;
  for (const auto &announcement : usage.announcements) {
    if (announcement.cancellation()) {
      if (const auto found = mbms_bearers_.find(announcement.tmgi); found != mbms_bearers_.end()) {
        if (found->second.reported_listening)
          mbms_cancelled_[announcement.tmgi] = {found->second.service, found->second.psi, found->second.mbs};
        mbms_bearers_.erase(found);
      }
      event("mbms_announcement_cancelled", {{"tmgi", announcement.tmgi}});
      continue;
    }
    MbmsBearer bearer;
    bearer.service = service;
    bearer.announcement = announcement;
    bearer.psi = psi;
    bearer.sdp = sdp_body;
    if (mbs) {
      bearer.mbs = true;
      bearer.mode = sessions.at(announcement.tmgi).mode;
      bearer.report_join = sessions.at(announcement.tmgi).report_join;
    }
    MediaLine line;
    try {
      if (!announcement.gpms) throw std::runtime_error("announcement without GPMS");
      line = media_line(sdp_body, *announcement.gpms);
      bearer.general_purpose_address = line.address;
      bearer.general_purpose_port = line.port;
      if (!is_ipv4_multicast(bearer.general_purpose_address))
        throw std::runtime_error("general purpose subchannel is not an IPv4 host group");
    } catch (const std::exception &error) {
      event("mbms_announcement_refused", {{"tmgi", announcement.tmgi}, {"reason", error.what()}});
      response.status_code = 488;
      return response;
    }
    // Step 1 f: the MSCCK of the general purpose subchannel, from the participating function's PSI (i, iv: else 488
    // with warning 136), decrypted with this user's key (v) with its MSCCK-ID (vi).
    if (!line.key_mgmt.empty()) {
      try {
        if (!keys) throw std::runtime_error("no key management");
        if (KeyClient::initiator(line.key_mgmt) != psi) throw std::runtime_error("MSCCK from another initiator");
        const auto key = keys->accept_signalling_key(psi, line.key_mgmt, 5);
        bearer.mscck = std::make_unique<ProtectedCall>(service == MbmsService::Mcvideo);
        bearer.mscck->set_floor(key);
      } catch (const std::exception &error) {
        event("mbms_announcement_refused", {{"tmgi", announcement.tmgi}, {"reason", error.what()}});
        return mikey_refusal(profile);
      }
    }
    // A replaced announcement keeps the reception of its general purpose subchannel when it is unchanged.
    if (const auto old = mbms_bearers_.find(announcement.tmgi); old != mbms_bearers_.end()) {
      bearer.reported_listening = old->second.reported_listening;
      if (old->second.general_purpose_address == bearer.general_purpose_address &&
          old->second.general_purpose_port == bearer.general_purpose_port)
        bearer.socket = std::move(old->second.socket);
    }
    event("mbms_announcement_stored",
          {{"tmgi", announcement.tmgi},
           {"service", profile.name},
           {"general_purpose", bearer.general_purpose_address + ":" + std::to_string(bearer.general_purpose_port)}});
    mbms_bearers_[announcement.tmgi] = std::move(bearer);
  }
  schedule([this] { mbms_update(); });
  return response;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 14.3.2 step 1 g and clause 14.3.3.1: the client listens to the general purpose subchannel of a
// bearer while it is in one of the bearer's MBMS service areas with good radio conditions (the emulated MBMS
// interface), and reports "listening" (with <general-purpose> "true") for the bearers it starts receiving. It
// reports "not-listening" (with <general-purpose> "false") for those it stops receiving: the announcement was
// cancelled, the radio conditions turned bad, or the UE left the service areas. A cancelled bearer it never reported
// listening to needs no report.
void Client::mbms_update() {
  std::map<std::tuple<MbmsService, std::string, bool>, std::vector<std::string>> start, stop;
  for (auto &[tmgi, bearer] : mbms_bearers_) {
    const bool in_area = std::ranges::any_of(bearer.announcement.service_areas,
                                             [&](const auto &area) { return mbms_areas_.contains(area); });
    const bool receivable = in_area && !mbms_bad_.contains(tmgi) && !mbms_bad_.contains("all");
    if (receivable && !bearer.socket) {
      try {
        auto socket = std::make_unique<UdpSocket>("0.0.0.0", bearer.general_purpose_port);
        socket->join(bearer.general_purpose_address, mbms_interface_);
        bearer.socket = std::move(socket);
        event("mbms_general_purpose_joined", {{"tmgi", tmgi}});
      } catch (const std::exception &error) {
        event("mbms_join_failed", {{"tmgi", tmgi}, {"reason", error.what()}});
        continue;
      }
    } else if (!receivable && bearer.socket) {
      bearer.socket.reset();
      event("mbms_general_purpose_left", {{"tmgi", tmgi}});
    }
    if (receivable && !bearer.reported_listening) start[{bearer.service, bearer.psi, bearer.mbs}].push_back(tmgi);
    if (!receivable && bearer.reported_listening) stop[{bearer.service, bearer.psi, bearer.mbs}].push_back(tmgi);
  }
  // Bearers dropped by a cancellation while reported: their last state is kept to report "not-listening".
  for (auto it = mbms_cancelled_.begin(); it != mbms_cancelled_.end(); it = mbms_cancelled_.erase(it))
    stop[it->second].push_back(it->first);
  // TS 24.379 clause 14B.3.3.1 condition 2 b and clause 14B.3.5: a multicast MBS session whose announcement asks for
  // it gets the UE session join notification before the listening report, and "ue-session-leave" when the client
  // stops listening.
  for (const auto &[key, tmgis] : start) {
    for (const auto &tmgi : tmgis)
      if (const auto &bearer = mbms_bearers_.at(tmgi);
          bearer.mbs && bearer.mode == "multicast" && bearer.report_join.value_or(false))
        mbs_join_notification(tmgi, true);
    mbms_report(std::get<0>(key), std::get<1>(key), true, tmgis, std::get<2>(key));
  }
  for (const auto &[key, tmgis] : stop) {
    mbms_report(std::get<0>(key), std::get<1>(key), false, tmgis, std::get<2>(key));
    for (const auto &tmgi : tmgis)
      if (const auto found = mbms_bearers_.find(tmgi); found != mbms_bearers_.end() && found->second.mbs &&
                                                        found->second.mode == "multicast" &&
                                                        found->second.report_join.value_or(false))
        mbs_join_notification(tmgi, false);
  }
  publish_mbms();
}

//------------------------------------------------------------------------------
// TS 24.379 clause 14.3.3.2 (TS 24.281 clause 16.2.3.2): the listening status report on the general purpose
// subchannel: a SIP MESSAGE to the MBMS public service identity of the announcement (step 1 a), with the
// service's Accept-Contact and P-Preferred-Service (steps 1 b and 1 d), the MBMS usage information (<general-purpose>
// and the TMGIs; steps 1 e to 1 g) and the service information naming the user (step 1 h).
BodyPart Client::mbms_status_part(MbmsService service, bool mbs, const MbmsListeningStatus &status) {
  if (!mbs) return {mbms_usage_type(service).mime, mbms_listening_status_xml(status, service)};
  // TS 24.379 annex F.2A.3 item 1: <unicast-listening-status> is mandatory without semantics of its own
  // (docs/3GPP_CORRECTION_CANDIDATES.md M23): "listening" except when the client reports listening to a session's
  // MBS subchannel, which replaces the unicast delivery.
  MbsUsageInfo usage;
  usage.listening_status = MbsListeningStatus{status.listening, !(status.listening && !status.session_id.empty()),
                                              status.session_id, status.general_purpose, status.tmgis};
  return {mbs_usage_type(service).mime, mbs_usage_xml(usage, service)};
}

//------------------------------------------------------------------------------
// TS 24.379 clause 14B.3.5.2 (TS 24.281 clause 16A.2.4): the UE session join notification, a SIP MESSAGE to the MBS
// public service identity of the announcement with <ue-session-join-notification> ("ue-session-join" or
// "ue-session-leave", the MBS session ID and, for an ongoing conversation, the MC service session) and the service
// information naming the user.
void Client::mbs_join_notification(const std::string &session, bool join, const std::string &mcptt_session) {
  const auto found = mbms_bearers_.find(session);
  if (found == mbms_bearers_.end()) return;
  const auto &bearer = found->second;
  const auto &profile = profile_of(bearer.service);
  MbsUsageInfo usage;
  usage.join_notification = UeSessionJoin{join, session, mcptt_session};
  McpttInfo info;
  info.request_uri = bearer.service == MbmsService::Mcvideo ? mcvideo_user() : user();
  const std::vector<BodyPart> parts{{profile.info_mime, service_info_xml(info, profile)},
                                    {mbs_usage_type(bearer.service).mime, mbs_usage_xml(usage, bearer.service)}};
  const auto boundary = "mcx-" + random_id();
  sip_.request("MESSAGE", bearer.psi, "multipart/mixed; boundary=" + boundary, multipart_body(parts, boundary),
               {{"Accept-Contact", std::string("*;+g.3gpp.icsi-ref=\"") + profile.icsi_encoded + "\";require;explicit"},
                {"P-Preferred-Service", profile.icsi}},
               [this, session, join](const SipResponse &response) {
                 if (response.status_code < 200) return;
                 event("mbs_join_answered", {{"mbs_session_id", session}, {"join", join}, {"status", response.status_code}});
               });
  status_["mbs_join_notifications"] = status_.value("mbs_join_notifications", 0ULL) + 1;
  event("mbs_join_sent", {{"mbs_session_id", session}, {"join", join}, {"session", mcptt_session}});
}

//------------------------------------------------------------------------------
// The listening status report on the general purpose subchannel (TS 24.379 clause 14.3.3.2; an MBS session's with the
// MBS usage information, clause 14B.3.3.2).
void Client::mbms_report(MbmsService service, const std::string &psi, bool listening,
                         const std::vector<std::string> &tmgis, bool mbs) {
  const auto &profile = profile_of(service);
  for (const auto &tmgi : tmgis)
    if (auto found = mbms_bearers_.find(tmgi); found != mbms_bearers_.end())
      found->second.reported_listening = listening;
  MbmsListeningStatus status;
  status.listening = listening;
  status.general_purpose = listening;
  status.tmgis = tmgis;
  McpttInfo info;
  info.request_uri = service == MbmsService::Mcvideo ? mcvideo_user() : user();
  const std::vector<BodyPart> parts{{profile.info_mime, service_info_xml(info, profile)},
                                    mbms_status_part(service, mbs, status)};
  const auto boundary = "mcx-" + random_id();
  sip_.request("MESSAGE", psi, "multipart/mixed; boundary=" + boundary, multipart_body(parts, boundary),
               {{"Accept-Contact", std::string("*;+g.3gpp.icsi-ref=\"") + profile.icsi_encoded + "\";require;explicit"},
                {"P-Preferred-Service", profile.icsi}},
               [this, listening, tmgis](const SipResponse &response) {
                 if (response.status_code < 200) return;
                 event("mbms_report_answered", {{"listening", listening},
                                                {"general_purpose", true},
                                                {"tmgis", tmgis},
                                                {"status", response.status_code}});
                 for (const auto &tmgi : tmgis)
                   if (auto found = mbms_bearers_.find(tmgi); found != mbms_bearers_.end())
                     found->second.accepted = listening && response.status_code < 300;
                 // TS 24.379 clause 14.2.3 NOTE 2: "not-listening" on the general purpose subchannel ends the
                 // listening to the bearer's MBMS subchannels; the joined one is left after the grace.
                 if (!listening && mcvideo_multicast_socket_ &&
                     std::ranges::find(tmgis, mcvideo_multicast_tmgi_) != tmgis.end()) {
                   status_["mcvideo_multicast_listening"] = false;
                   schedule_after(200, [this, session = mcvideo_multicast_session_] {
                     if (session == mcvideo_multicast_session_) mcvideo_multicast_leave(false);
                   });
                 }
                 if (!listening && multicast_socket_ && std::ranges::find(tmgis, multicast_tmgi_) != tmgis.end()) {
                   status_["multicast_listening"] = false;
                   schedule_after(200, [this, session = multicast_session_] {
                     if (session == multicast_session_) multicast_leave(false);
                   });
                 }
                 publish_mbms();
               });
  event("mbms_report_sent", {{"listening", listening}, {"general_purpose", true}, {"tmgis", tmgis}});
}

//------------------------------------------------------------------------------
// Helper: the datagrams received on the general purpose subchannels: the subchannel control messages of the
// bearer's service (TS 24.380 clause 10.3, TS 24.581 clause 10.3); anything else is counted and dropped.
void Client::mbms_poll() {
  constexpr int max_datagrams = 32;
  for (auto &[tmgi, bearer] : mbms_bearers_) {
    for (int index = 0; bearer.socket && index < max_datagrams; index++) {
      auto datagram = bearer.socket->receive();
      if (!datagram) break;
      status_["mbms_general_purpose_received"] = status_.value("mbms_general_purpose_received", 0ULL) + 1;
      try {
        // TS 24.380 clause 8.5 (TS 24.581 clause 9.4): an MBMS notification, clear or SRTCP under the MuSiK.
        const auto &bytes = datagram->bytes;
        const bool clear_notification = bytes.size() >= 12 && std::string_view(
                                                                   reinterpret_cast<const char *>(bytes.data()) + 8, 4) == "MCNC";
        const auto &musik = bearer.service == MbmsService::Mcvideo ? mcvideo_mbms_musik_ : mbms_musik_;
        if (clear_notification || (musik && mbms_mki_purpose(bytes) == 6 && !is_subchannel_message(bytes))) {
          mbms_group_dynamic_data(bearer.service, bytes);
          continue;
        }
        const auto message = decode_subchannel(mbms_open_control(datagram->bytes, bearer));
        if (message.service != bearer.service) throw std::runtime_error("subchannel control of another service");
        mbms_subchannel_message(tmgi, message);
      } catch (const std::exception &error) {
        status_["mbms_general_purpose_rejected"] = status_.value("mbms_general_purpose_rejected", 0ULL) + 1;
        event("mbms_general_purpose_rejected", {{"tmgi", tmgi}, {"reason", error.what()}});
      }
    }
  }
}

//------------------------------------------------------------------------------
// TS 33.180 annex G: the purpose tag of the MKI that ends an SRTCP packet (RFC 3711 section 3.4, the 32-bit KFC-ID;
// TS 33.180 clause 9.4.6), or 0 for a packet too short to carry one.
std::uint8_t Client::mbms_mki_purpose(std::span<const std::uint8_t> bytes) {
  return bytes.size() < 12 ? 0 : static_cast<std::uint8_t>(bytes[bytes.size() - 4] >> 4);
}

//------------------------------------------------------------------------------
// TS 24.380 clause 13.3.3: a subchannel control message of a bearer: with the bearer's MSCCK it must be SRTCP under it
// (MKI purpose tag 5), except the Bearer Announcement message, which has no SSRC (M17); without one it must be clear.
std::vector<std::uint8_t> Client::mbms_open_control(std::span<const std::uint8_t> bytes, const MbmsBearer &bearer) {
  if (is_subchannel_message(bytes)) {
    if (bearer.mscck && (bytes[0] & 0x1f) != static_cast<std::uint8_t>(SubchannelMessageType::Announcement))
      throw std::runtime_error("clear subchannel control message under an MSCCK");
    return {bytes.begin(), bytes.end()};
  }
  if (!bearer.mscck || mbms_mki_purpose(bytes) != 5) throw std::runtime_error("not a subchannel control message");
  return bearer.mscck->unprotect_floor(bytes);
}

//------------------------------------------------------------------------------
// TS 24.380 clause 10.3.6 (TS 24.581 clause 10.3.6): a Bearer Announcement message received over a bearer is
// acknowledged with a listening status report for the TMGIs it names that the client has an announcement of; the
// monitoring state "inactive" is taken as an instruction to stop monitoring them (the specifications do not say,
// docs/3GPP_CORRECTION_CANDIDATES.md M10), reported "not-listening". Map and Unmap Group To Bearer are taken by
// mcptt/multicast.cpp.
void Client::mbms_subchannel_message(const std::string &tmgi, const SubchannelMessage &message) {
  if (message.type == SubchannelMessageType::MapGroup) return mbms_mapped(tmgi, message);
  if (message.type == SubchannelMessageType::UnmapGroup) return mbms_unmapped(message);
  if (message.type == SubchannelMessageType::ApplicationPaging) return mbms_paged(message);
  if (message.type != SubchannelMessageType::Announcement) return;
  status_["mbms_bearer_announcements"] = status_.value("mbms_bearer_announcements", 0ULL) + 1;
  const auto &bearer = mbms_bearers_.at(tmgi);
  std::vector<std::string> known;
  for (const auto &named : message.tmgis)
    if (mbms_bearers_.contains(named)) known.push_back(named);
  if (known.empty()) return;
  const bool active = message.monitoring.value_or(true);
  if (!active)
    for (const auto &named : known) mbms_bad_.insert(named);
  mbms_report(bearer.service, bearer.psi, active, known, bearer.mbs);
  if (!active) schedule([this] { mbms_update(); });
}

//------------------------------------------------------------------------------
// TS 24.379 clauses 14.3.3.1 and 14.3.3.2 (TS 24.281 clause 16.2.3): the RAN (here "mbms suspend" and "mbms resume")
// says a bearer the client reported listening to is about to be suspended, or no longer: unless the announcement said
// <report-suspension> false, the client reports it: <mbms-suspension-status> "suspending" or "not-suspending" with
// the TMGI in <suspended-TMGI>. The actual suspension is a loss of the bearer ("mbms quality bad").
void Client::mbms_suspension_report(const std::string &tmgi, bool suspending) {
  const auto found = mbms_bearers_.find(tmgi);
  if (found == mbms_bearers_.end()) throw std::runtime_error("no announced bearer " + tmgi);
  const auto &bearer = found->second;
  if (suspending ? !mbms_suspending_.insert(tmgi).second : !mbms_suspending_.erase(tmgi)) return;
  if (!bearer.reported_listening || bearer.announcement.report_suspension == false) {
    event("mbms_suspension_not_reported", {{"tmgi", tmgi}, {"suspending", suspending}});
    return;
  }
  const auto &profile = profile_of(bearer.service);
  McpttInfo info;
  info.request_uri = bearer.service == MbmsService::Mcvideo ? mcvideo_user() : user();
  std::vector<BodyPart> parts{{profile.info_mime, service_info_xml(info, profile)}};
  if (bearer.mbs) {
    // TS 24.379 clause 14B.3.3.2 and annex L.3.4: for an MBS session, the de-announcement status ("deleting").
    MbsUsageInfo usage;
    usage.de_announcement_status = MbsDeAnnouncementStatus{suspending, tmgi, {}};
    parts.push_back({mbs_usage_type(bearer.service).mime, mbs_usage_xml(usage, bearer.service)});
  } else {
    MbmsUsageInfo usage;
    usage.suspension_status = MbmsSuspensionStatus{suspending, tmgi, {}};
    parts.push_back({mbms_usage_type(bearer.service).mime, mbms_usage_xml(usage, bearer.service)});
  }
  const auto boundary = "mcx-" + random_id();
  sip_.request("MESSAGE", bearer.psi, "multipart/mixed; boundary=" + boundary, multipart_body(parts, boundary),
               {{"Accept-Contact", std::string("*;+g.3gpp.icsi-ref=\"") + profile.icsi_encoded + "\";require;explicit"},
                {"P-Preferred-Service", profile.icsi}},
               [this, tmgi, suspending](const SipResponse &response) {
                 if (response.status_code < 200) return;
                 event("mbms_suspension_answered",
                       {{"tmgi", tmgi}, {"suspending", suspending}, {"status", response.status_code}});
               });
  status_["mbms_suspension_reports"] = status_.value("mbms_suspension_reports", 0ULL) + 1;
  event("mbms_suspension_reported", {{"tmgi", tmgi}, {"suspending", suspending}});
}

//------------------------------------------------------------------------------
// TS 24.380 clause 10.3.5 (TS 24.581 clause 10.3.5): an Application Paging message: a client in idle mode would make a
// service request to enter RRC connected mode; the emulated MBMS interface is always connected, so it is counted.
void Client::mbms_paged(const SubchannelMessage &message) {
  status_["mbms_paging_received"] = status_.value("mbms_paging_received", 0ULL) + 1;
  event("mbms_paged", {{"group", message.group}});
}

//------------------------------------------------------------------------------
// TS 24.380 clause 10A.1 and clause 8.5.4 (TS 24.581 clauses 10.5.1 and 9.4.4): a Group Dynamic Data Notify over a
// general purpose subchannel, SRTCP under the service's MuSiK when there is one: the group's status, who set it,
// whether a call is ongoing and its aliases, kept by group in the status.
void Client::mbms_group_dynamic_data(MbmsService service, std::span<const std::uint8_t> bytes) {
  const auto &musik = service == MbmsService::Mcvideo ? mcvideo_mbms_musik_ : mbms_musik_;
  const auto clear = bytes.size() >= 12 && std::string_view(reinterpret_cast<const char *>(bytes.data()) + 8, 4) == "MCNC";
  if (!clear && !musik) throw std::runtime_error("protected notification without a MuSiK");
  if (clear && musik) throw std::runtime_error("clear notification under a MuSiK");
  const auto data = decode_group_dynamic_data(clear ? std::vector<std::uint8_t>(bytes.begin(), bytes.end())
                                                    : musik->unprotect_floor(bytes));
  Json entry = {{"service", profile_of(service).name}};
  if (data.call_ongoing) entry["call_ongoing"] = *data.call_ongoing;
  if (data.status) entry["status"] = *data.status == 0 ? "emergency" : "in-peril";
  if (!data.status_changer.empty()) entry["status_changer"] = data.status_changer;
  if (!data.broadcast_alias.empty()) entry["broadcast_alias"] = data.broadcast_alias;
  if (!data.regroup_alias.empty()) entry["regroup_alias"] = data.regroup_alias;
  status_["mbms_group_dynamic_data"][data.group] = entry;
  status_["mbms_group_dynamic_data_received"] = status_.value("mbms_group_dynamic_data_received", 0ULL) + 1;
  event("mbms_group_dynamic_data", {{"group", data.group}, {"data", entry}});
}

//------------------------------------------------------------------------------
// Helper: the emulated MBMS interface: "mbms area <id>[,<id>...]|none" sets the MBMS service areas the UE is in,
// "mbms quality good|bad [<TMGI>]" the radio conditions of one bearer or of all of them (TS 24.379 clause 14.3.3.1),
// "mbms suspend|resume <TMGI>" the RAN's notice that a bearer is about to be suspended, or no longer.
void Client::mbms_command(const std::string &cmd) {
  if (!mbms_enabled_) throw std::runtime_error("mbms not configured");
  std::istringstream words(cmd);
  std::string verb, what, value, tmgi;
  words >> verb >> what >> value >> tmgi;
  if (what == "area" && !value.empty()) {
    mbms_areas_.clear();
    if (value != "none") {
      std::istringstream list(value);
      for (std::string area; std::getline(list, area, ',');) {
        std::ranges::transform(area, area.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        mbms_areas_.insert(area);
      }
    }
  } else if (what == "quality" && (value == "good" || value == "bad")) {
    const auto key = tmgi.empty() ? std::string("all") : tmgi_hex(tmgi);
    if (value == "bad") {
      mbms_bad_.insert(key);
    } else if (key == "all") {
      mbms_bad_.clear();
    } else {
      mbms_bad_.erase(key);
    }
  } else if ((what == "suspend" || what == "resume") && !value.empty()) {
    mbms_suspension_report(tmgi_hex(value), what == "suspend");
    return;
  } else {
    throw std::runtime_error(
        "usage: mbms area <id>[,<id>...]|none, mbms quality good|bad [<TMGI>], mbms suspend|resume <TMGI>");
  }
  event("mbms_interface", {{"command", cmd}});
  mbms_update();
}

//------------------------------------------------------------------------------
// Helper: the status shows the service areas, the stored announcements and what each bearer's reception is.
void Client::publish_mbms() {
  if (!mbms_enabled_) return;
  auto bearers = Json::object();
  for (const auto &[tmgi, bearer] : mbms_bearers_)
    bearers[tmgi] = {
        {"service", profile_of(bearer.service).name},
        {"general_purpose", bearer.general_purpose_address + ":" + std::to_string(bearer.general_purpose_port)},
        {"joined", bearer.socket != nullptr},
        {"mscck", bearer.mscck != nullptr},
        {"system", bearer.mbs ? "mbs" : "mbms"},
        {"reported_listening", bearer.reported_listening},
        {"accepted", bearer.accepted}};
  status_["mbms_areas"] = std::vector<std::string>(mbms_areas_.begin(), mbms_areas_.end());
  status_["mbms_bearers"] = std::move(bearers);
}

} // namespace mcx
