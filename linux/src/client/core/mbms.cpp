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

#include "mcx/mbms.hpp"

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
// RFC 4566 sections 5.7 and 5.14: the port of the n-th media line (counted from 1) and its connection address
// (media-level, else session-level; an IPv4 multicast address may carry "/<ttl>"). Throws when there is no such line.
std::pair<std::string, std::uint16_t> media_line_address(const std::string &sdp, unsigned number) {
  std::istringstream lines(sdp);
  std::string line, session_address, address;
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
      (current == 0 ? session_address : address) = value;
    }
  }
  if (current < number || port <= 0 || port > 65535) throw std::runtime_error("no such media line in the SDP");
  return {address.empty() ? session_address : address, static_cast<std::uint16_t>(port)};
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
SipResponse Client::mbms_message(const SipMessage &message, MbmsService service, const std::string &body) {
  SipResponse response;
  response.status_code = 200;
  const auto &profile = profile_of(service);
  if (std::string(get_header(message.headers, "P-Asserted-Service")) != profile.icsi) {
    response.status_code = 403;
    return response;
  }
  MbmsUsageInfo usage;
  try {
    usage = parse_mbms_usage_info(body, service);
  } catch (const std::exception &error) {
    event("mbms_announcement_refused", {{"reason", error.what()}});
    response.status_code = 400;
    return response;
  }
  if (!mbms_enabled_ || usage.announcements.empty()) return response;
  std::string sdp_body;
  if (message.content_type == "application/sdp") sdp_body = message.body;
  for (const auto &part : message.parts)
    if (part.content_type == "application/sdp") sdp_body = part.body;
  auto psi = asserted_identity(message);
  if (psi.empty()) psi = message.from_uri;
  for (const auto &announcement : usage.announcements) {
    if (announcement.cancellation()) {
      if (const auto found = mbms_bearers_.find(announcement.tmgi); found != mbms_bearers_.end()) {
        if (found->second.reported_listening)
          mbms_cancelled_[announcement.tmgi] = {found->second.service, found->second.psi};
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
    try {
      if (!announcement.gpms) throw std::runtime_error("announcement without GPMS");
      std::tie(bearer.general_purpose_address, bearer.general_purpose_port) =
          media_line_address(sdp_body, *announcement.gpms);
      if (!is_ipv4_multicast(bearer.general_purpose_address))
        throw std::runtime_error("general purpose subchannel is not an IPv4 host group");
    } catch (const std::exception &error) {
      event("mbms_announcement_refused", {{"tmgi", announcement.tmgi}, {"reason", error.what()}});
      response.status_code = 488;
      return response;
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
  std::map<std::pair<MbmsService, std::string>, std::vector<std::string>> start, stop;
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
    if (receivable && !bearer.reported_listening) start[{bearer.service, bearer.psi}].push_back(tmgi);
    if (!receivable && bearer.reported_listening) stop[{bearer.service, bearer.psi}].push_back(tmgi);
  }
  // Bearers dropped by a cancellation while reported: their last state is kept to report "not-listening".
  for (auto it = mbms_cancelled_.begin(); it != mbms_cancelled_.end(); it = mbms_cancelled_.erase(it))
    stop[it->second].push_back(it->first);
  for (const auto &[key, tmgis] : start) mbms_report(key.first, key.second, true, tmgis);
  for (const auto &[key, tmgis] : stop) mbms_report(key.first, key.second, false, tmgis);
  publish_mbms();
}

//------------------------------------------------------------------------------
// TS 24.379 clause 14.3.3.2 (TS 24.281 clause 16.2.3.2): the listening status report on the general purpose
// subchannel: a SIP MESSAGE to the MBMS public service identity of the announcement (step 1 a), with the
// service's Accept-Contact and P-Preferred-Service (steps 1 b and 1 d), the MBMS usage information (<general-purpose>
// and the TMGIs; steps 1 e to 1 g) and the service information naming the user (step 1 h).
void Client::mbms_report(MbmsService service, const std::string &psi, bool listening,
                         const std::vector<std::string> &tmgis) {
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
                                    {mbms_usage_type(service).mime, mbms_listening_status_xml(status, service)}};
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
        if (!is_subchannel_message(datagram->bytes)) throw std::runtime_error("not a subchannel control message");
        const auto message = decode_subchannel(datagram->bytes);
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
// TS 24.380 clause 10.3.6 (TS 24.581 clause 10.3.6): a Bearer Announcement message received over a bearer is
// acknowledged with a listening status report for the TMGIs it names that the client has an announcement of; the
// monitoring state "inactive" is taken as an instruction to stop monitoring them (the specifications do not say,
// docs/3GPP_CORRECTION_CANDIDATES.md M10), reported "not-listening". Map and Unmap Group To Bearer are taken by
// mcptt/multicast.cpp.
void Client::mbms_subchannel_message(const std::string &tmgi, const SubchannelMessage &message) {
  if (message.type == SubchannelMessageType::MapGroup) return mbms_mapped(tmgi, message);
  if (message.type == SubchannelMessageType::UnmapGroup) return mbms_unmapped(message);
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
  mbms_report(bearer.service, bearer.psi, active, known);
  if (!active) schedule([this] { mbms_update(); });
}

//------------------------------------------------------------------------------
// Helper: the emulated MBMS interface: "mbms area <id>[,<id>...]|none" sets the MBMS service areas the UE is in,
// "mbms quality good|bad [<TMGI>]" the radio conditions of one bearer or of all of them (TS 24.379 clause 14.3.3.1).
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
  } else {
    throw std::runtime_error("usage: mbms area <id>[,<id>...]|none, mbms quality good|bad [<TMGI>]");
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
        {"reported_listening", bearer.reported_listening},
        {"accepted", bearer.accepted}};
  status_["mbms_areas"] = std::vector<std::string>(mbms_areas_.begin(), mbms_areas_.end());
  status_["mbms_bearers"] = std::move(bearers);
}

} // namespace mcx
