// MCVideo calls: priority conditions, emergency alerts, incoming MESSAGEs, private calls and calls to the server
// (TS 24.281 clauses 6.2.8, 10.2, 11.2.1, 12.2.2, 13.2.2). Member functions of Client (declared in client.hpp).
//
// Specifications: TS 24.281 V19.6.0 (Rel-19), TS 24.581 V19.2.0 (Rel-19), RFC 4412, RFC 8101.

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.281 clause 6.2.8.1.15 (used by clauses 6.2.8.1.2, 6.2.8.1.12 and 6.2.8.3.3): the Resource-Priority header
// field of an emergency or imminent peril call, from the MCVideo service configuration document (RFC 4412 with
// RFC 8101).
std::vector<SipHeader> Client::mcvideo_priority_headers(const McpttInfo &info) const {
  const auto values = service_priorities("mcvideo");
  const char *name = info.emergency == true ? "emergency" : info.imminent_peril == true ? "imminent" : "normal";
  const auto value = values.value(name, std::string{});
  if (value.empty()) {
    if (info.emergency == true || info.imminent_peril == true)
      throw std::runtime_error("MCVideo Resource-Priority values are not provisioned");
    return {};
  }
  return {{"Resource-Priority", value}};
}

//------------------------------------------------------------------------------
// Helper: record the call's priority (emergency, imminent peril or normal) from the <emergency-ind> and
// <imminentperil-ind> of a received mcvideo-info body.
void Client::mcvideo_note_priority(const McpttInfo &info) {
  if (!mcvideo_call_) return;
  auto &call = *mcvideo_call_;
  if (info.emergency == true)
    call.priority = "emergency";
  else if (info.imminent_peril == true)
    call.priority = "imminent";
  else if (info.emergency == false || info.imminent_peril == false)
    call.priority = "normal";
}

//------------------------------------------------------------------------------
// TS 24.281 clauses 9.2.1.2.1.3 to 9.2.1.2.1.5 (group call) and 10.2.2.2.4, 10.2.2.2.5 (private call): upgrade the
// call to emergency or imminent peril, or cancel it, by re-INVITE in the call's dialog, with the mcvideo-info body of
// TS 24.281 clauses 6.2.8.1 and 6.2.8.3.
void Client::mcvideo_change_priority(bool emergency, bool enabled) {
  if (!mcvideo_call_ || mcvideo_call_->state != "active") throw std::runtime_error("no active MCVideo call");
  auto &call = *mcvideo_call_;
  McpttInfo info;
  info.session_type = call.type;
  info.client_id = client_id();
  if (call.type == "private")
    info.calling_user = mcvideo_user();
  else
    info.request_uri = call.group;
  // TS 24.281 clause 6.2.8.1.1 step 3: an emergency comes with an alert indication (false: no alert).
  if (emergency) {
    info.emergency = enabled;
    if (enabled) info.alert = false;
  } else {
    info.imminent_peril = enabled;
  }
  std::vector<BodyPart> parts{{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)}};
  if (call.type == "private") parts.push_back({resource_lists_mime, private_call_resource_list(call.peer)});
  // The re-INVITE asks for MCVideo like the initial INVITE (Accept-Contact, P-Preferred-Service).
  auto headers = mcvideo_call_headers();
  for (const auto &header : mcvideo_priority_headers(info)) headers.push_back(header);
  sip_.reinvite(
      call.dialog, call.local, parts,
      [this, emergency, enabled](const SipResponse &response) {
        if (response.status_code < 200) return;
        mcvideo_alert_result_ = {{"action", emergency ? "emergency" : "imminent"},
                                 {"enabled", enabled},
                                 {"status", response.status_code},
                                 {"warning", std::string(get_header(response.headers, "Warning"))}};
        if (response.status_code < 300 && mcvideo_call_)
          mcvideo_call_->priority = enabled ? (emergency ? "emergency" : "imminent") : "normal";
      },
      headers);
  event("mcvideo_priority_change_requested", {{"emergency", emergency}, {"enabled", enabled}});
}

//------------------------------------------------------------------------------
// TS 24.281 clause 11.2.1.1: an MCVideo emergency alert on the configured group, with this UE's location, or its
// cancellation (TS 24.281 clause 11.2.1.2).
void Client::mcvideo_alert(bool enabled) {
  if (!mcvideo_authorized_) throw std::runtime_error("MCVideo alert requires authorisation");
  McpttInfo info;
  info.request_uri = mcvideo_group();
  info.client_id = client_id();
  info.alert = enabled; // alert-ind alone: the alert is not an emergency call (TS 24.281 clause 11.2.1.1)
  std::vector<BodyPart> parts{{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)}};
  if (enabled)
    parts.push_back(
        {mcvideo_service.location_mime, service_location_xml(configured_emergency_location(), mcvideo_service)});
  const auto boundary = "mcvideo-" + random_id();
  const auto headers = mcvideo_call_headers();
  sip_.request("MESSAGE", mcvideo_service_uri(), "multipart/mixed; boundary=" + boundary,
               multipart_body(parts, boundary), headers, [this, enabled](const SipResponse &response) {
                 if (response.status_code < 200) return;
                 mcvideo_alert_result_ = {{"action", "alert"},
                                          {"enabled", enabled},
                                          {"status", response.status_code},
                                          {"warning", std::string(get_header(response.headers, "Warning"))}};
                 if (response.status_code < 300) mcvideo_alerting_ = enabled;
               });
  event("mcvideo_alert_requested", {{"enabled", enabled}});
}

//------------------------------------------------------------------------------
// TS 24.281 clause 6.2.0.1: the SIP MESSAGE requests to the MCVideo client, by kind: ad hoc group
// emergency alert, group selection change, and
// (TS 24.281 clause 11.2.1.3) an alert of another user from the controlling function, or its cancellation.
SipResponse Client::mcvideo_message(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  if (asserted_identity(message) != mcvideo_service_uri()) {
    response.status_code = 403;
    return response;
  }
  const auto info = message_info(message, mcvideo_service);
  // TS 24.281 clause 12.1A.1.3: an ad hoc group emergency alert notification, its cancellation, or the
  // receipt of this user's own alert.
  if (info.adhoc_alert.has_value()) {
    if (info.request_uri != mcvideo_user() || info.calling_group.empty()) {
      response.status_code = 403;
      return response;
    }
    if (info.adhoc_alert_received == true) {
      if (info.client_id != client_id()) {
        response.status_code = 403;
        return response;
      }
      if (mcvideo_ah_mea_ == 2) mcvideo_ah_mea_ = 3;
      mcvideo_adhoc_alert_group_ = info.calling_group;
    } else if (*info.adhoc_alert) {
      mcvideo_adhoc_alerts_[info.calling_group] = {{"initiator", info.calling_user},
                                                   {"criteria", info.participant_criteria}};
    } else {
      mcvideo_adhoc_alerts_.erase(info.calling_group);
    }
    event("mcvideo_adhoc_alert_notification", {{"group", info.calling_group}, {"active", *info.adhoc_alert}});
    return response;
  }
  // TS 24.281 clauses 9.2.4.2.2 and 9.2.4.2.1: a group selection change request, or the response to ours.
  if (info.service.contains("request-type") && info.service.at("request-type") == "group-selection-change-request") {
    schedule([this, info] { mcvideo_selection_requested(info); });
    return response;
  }
  if (info.service.contains("response-type") && info.service.at("response-type") == "group-selection-change-response") {
    const auto outcome =
        info.service.contains("selected-group-change-outcome") ? info.service.at("selected-group-change-outcome") : "";
    mcvideo_selection_result_["outcome"] = outcome;
    mcvideo_selection_result_["group"] = info.calling_group;
    mcvideo_selection_result_["from"] = info.calling_user;
    event("mcvideo_group_selection_outcome", mcvideo_selection_result_);
    return response;
  }
  if (info.alert.has_value()) {
    const auto origin = info.originated_by.empty() ? info.calling_user : info.originated_by;
    if (*info.alert) {
      Json entry{{"group", info.calling_group.empty() ? info.request_uri : info.calling_group}};
      const auto parts = message.parts.empty() ? parse_body_parts(message.content_type, message.body) : message.parts;
      if (const auto location = parse_emergency_location_parts(parts))
        entry["location"] = {{"latitude", location->latitude}, {"longitude", location->longitude}};
      mcvideo_alerts_[origin] = entry;
    } else {
      mcvideo_alerts_.erase(origin);
    }
    event("mcvideo_alert_received", {{"from", origin}, {"active", *info.alert}});
  }
  mcvideo_note_priority(info);
  return response;
}

//------------------------------------------------------------------------------
// TS 24.281 clause 10.2.2.2.1: the final response to this client's INVITE: a failure ends the call, or is added to
// the record of a call that the dialog's end has already closed.
void Client::mcvideo_invite_answered(unsigned generation, const SipResponse &response) {
  if (response.status_code < 200) return;
  // TS 24.281 clause 10.2.2.2.1: a 300 to a call to a functional alias names, in <mcvideo-request-uri>,
  // the MCVideo user to call instead with <called-functional-alias-URI>.
  if (mcvideo_call_ && mcvideo_call_->generation == generation && mcvideo_call_->to_alias)
    mcvideo_alias_call_ = {
        {"generation", generation}, {"alias", mcvideo_call_->called_alias}, {"mode", mcvideo_call_->mode}};
  if (response.status_code == 300 && mcvideo_alias_call_.value("generation", 0u) == generation) {
    std::string selected;
    for (const auto &part :
         response.parts.empty() ? std::vector<BodyPart>{{response.content_type, response.body}} : response.parts)
      if (part.content_type.starts_with(mcvideo_service.info_mime))
        selected = parse_service_info(part.body, mcvideo_service).request_uri;
    const std::string alias = mcvideo_alias_call_.at("alias"), mode = mcvideo_alias_call_.at("mode");
    mcvideo_alias_call_ = Json::object();
    if (mcvideo_call_ && mcvideo_call_->generation == generation) mcvideo_call_.reset();
    mcvideo_redirect_ = {{"alias", alias}, {"selected", selected}};
    event("mcvideo_functional_alias_redirect", mcvideo_redirect_);
    if (selected.empty() || selected == mcvideo_user()) {
      failure("MCVideo 300 without a user to call");
      return;
    }
    schedule(
        [this, selected, mode, alias] { mcvideo_private_call(selected, mode, false, false, "private", false, alias); });
    return;
  }
  if (mcvideo_call_ && mcvideo_call_->generation == generation) {
    if (response.status_code < 300) {
      mcvideo_call_->session_uri = contact_uri_of(get_header(response.headers, "Contact"));
      if (!mcvideo_call_->ambient_type.empty())
        mcvideo_call_->may_release = ambient_release_capability(response.headers);
      // TS 24.281 clause 22.2.2.1.1, 2xx step 4: the ad hoc group identity the server assigned.
      if (mcvideo_call_->type == "adhoc")
        for (const auto &part : response.parts)
          if (part.content_type == mcvideo_service.info_mime)
            if (const auto group = parse_service_info(part.body, mcvideo_service).calling_group; !group.empty())
              mcvideo_call_->group = group;
      return;
    }
    mcvideo_end_call("refused");
  }
  if (response.status_code >= 300 && mcvideo_last_call_.value("generation", 0u) == generation) {
    mcvideo_last_call_["status"] = response.status_code;
    mcvideo_last_call_["warning"] = std::string(get_header(response.headers, "Warning"));
  }
}

//------------------------------------------------------------------------------
// Helper: a new MCVideo call with its own ports (mcvideo.media): the MCPTT call keeps media.rtp_port and floor_port.
Client::McvideoCall Client::new_mcvideo_call() {
  McvideoCall call;
  call.generation = ++mcvideo_generation_;
  const auto &ports = cfg_.at("mcvideo").at("media");
  auto &local = call.local;
  local.control = MediaControl::transmission;
  local.address = local.floor_address = resolve_ipv4(cfg_.at("media").at("advertise"));
  local.audio_port = static_cast<std::uint16_t>(ports.at("audio_port").get<unsigned>());
  local.rtcp_port = static_cast<std::uint16_t>(local.audio_port + 1);
  local.floor_port = static_cast<std::uint16_t>(ports.at("control_port").get<unsigned>());
  local.floor_ssrc = random_ssrc();
  local.audio_ssrc = random_ssrc();
  // mc_queueing (TS 24.581 clause 14.2.2): this client queues its Transmission Requests
  // unless mcvideo.queueing is false.
  local.queueing = cfg_.at("mcvideo").value("queueing", true);
  VideoMedia video;
  video.port = static_cast<std::uint16_t>(ports.at("video_port").get<unsigned>());
  video.rtcp_port = static_cast<std::uint16_t>(video.port + 1);
  video.ssrc = random_ssrc();
  local.video = video;
  const auto bind = cfg_.at("media").at("bind").get<std::string>();
  call.audio = std::make_unique<UdpSocket>(bind, local.audio_port);
  call.video = std::make_unique<UdpSocket>(bind, video.port);
  call.control = std::make_unique<UdpSocket>(bind, local.floor_port);
  call.decoder = std::make_unique<video::Decoder>();
  return call;
}

//------------------------------------------------------------------------------
// Helper: the URI inside the angle brackets of a Contact header field value.
std::string Client::contact_uri_of(std::string_view contact) {
  const auto open = contact.find('<'), close = contact.find('>');
  if (open == std::string_view::npos || close == std::string_view::npos || close < open) return {};
  return std::string(contact.substr(open + 1, close - open - 1));
}

//------------------------------------------------------------------------------
// TS 24.281 clause 10.2.2.2.1 steps 4 to 6: the MCVideo ICSI in P-Preferred-Service and the Accept-Contact header
// fields with g.3gpp.icsi-ref and g.3gpp.mcvideo, "require" and "explicit".
std::vector<SipHeader> Client::mcvideo_call_headers() const {
  return {
      {"P-Preferred-Service", mcvideo_service.icsi},
      {"Accept-Contact", std::string("*;+g.3gpp.icsi-ref=\"") + mcvideo_service.icsi_encoded + "\";require;explicit"},
      {"Accept-Contact", std::string("*;+") + mcvideo_service.feature_tag + ";require;explicit"}};
}

//------------------------------------------------------------------------------
// TS 24.281 clause 10.2.2.2.1: a private call with transmission control to one MCVideo ID, in a commencement mode
// (TS 24.281 clause 6.2.3, step 11): Answer-Mode Auto or Manual, or Priv-Answer-Mode Auto. With no_control,
// TS 24.281 clause 10.2.3.1: the offer has no transmission control, and both parties send. A one-to-one video push or pull
// (TS 24.281 clauses 13.2.2.1, 12.2.2.1) is this call with its <session-type>.
void Client::mcvideo_private_call(const std::string &target, std::string mode, bool no_control, bool emergency,
                                  const std::string &session_type, bool to_alias, const std::string &called_alias) {
  if (!mcvideo_authorized_) throw std::runtime_error("MCVideo call requires authorisation");
  if (mcvideo_call_) throw std::runtime_error("an MCVideo call is in progress");
  if (target.empty() || target == mcvideo_user()) throw std::runtime_error("private call requires another MCVideo ID");
  if (mode.empty()) mode = "auto";
  auto headers = mcvideo_call_headers();
  if (mode == "auto")
    headers.push_back({"Answer-Mode", "Auto"});
  else if (mode == "manual")
    headers.push_back({"Answer-Mode", "Manual"});
  else if (mode == "force-auto")
    headers.push_back({"Priv-Answer-Mode", "Auto"});
  else
    throw std::runtime_error("private call mode must be auto, manual or force-auto");
  mcvideo_call_ = new_mcvideo_call();
  const McvideoCallSetup setup{mcvideo_call_};
  auto &call = *mcvideo_call_;
  call.type = "private";
  call.peer = target;
  call.state = "calling";
  call.mode = mode;
  call.called_alias = to_alias ? target : called_alias;
  call.to_alias = to_alias;
  if (session_type != "private") {
    call.video_mode = session_type;
    // TS 24.281 clause 13.2.2.1 step 2 with TS 24.581 clause 6.2: the pushing caller asks to transmit in its offer.
    call.local.implicit_request = session_type == "one-to-one video push";
  }
  if (no_control) {
    call.local.control = MediaControl::none;
    call.local.queueing = false;
  }
  McpttInfo info;
  info.session_type = session_type;
  info.client_id = client_id();
  info.calling_user = mcvideo_user();
  // TS 24.281 clause 10.2.2.2.1 step 11 c) ii): <call-to-functional-alias-ind> for a call to an alias, and
  // the alias this user presents. After a 300, <called-functional-alias-URI> names the
  // alias first called and the indication is left out (300 steps 2 and 3).
  if (to_alias) info.call_to_functional_alias = true;
  info.called_functional_alias = called_alias;
  info.functional_alias = mcvideo_presented_alias();
  // TS 24.281 clause 6.2.8.3.2: an emergency private call.
  if (emergency) {
    info.emergency = true;
    info.alert = false;
    call.priority = "emergency";
    for (const auto &header : mcvideo_priority_headers(info)) headers.push_back(header);
  }
  // The PCK is for the user called; a call to a functional alias learns that user
  // only from the 300, so it is not protected.
  if (to_alias && protected_media())
    throw std::runtime_error("a protected call to a functional alias is not supported");
  const auto pck = mcvideo_offer_pck(target);
  call.dialog = sip_.invite(
      mcvideo_service_uri(), call.local,
      {{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)},
       {resource_lists_mime, private_call_resource_list(target)}},
      [this, generation = call.generation](const SipResponse &response) {
        mcvideo_invite_answered(generation, response);
      },
      headers);
  if (pck) mcvideo_bind_pck(*pck, true);
  event("mcvideo_private_call_requested",
        {{"target", target}, {"mode", mode}, {"session_type", session_type}, {"to_alias", to_alias}});
}

//------------------------------------------------------------------------------
// TS 24.281 clauses 13.2.2.4 (one-to-server video push) and 12.2.2.4 (one-from-server video pull): a call to the
// MCVideo server itself, without a resource-lists body and with automatic commencement. A push asks to transmit in
// its offer (TS 24.581 clause 6.2); a pull names the video in <video-pull-url>.
void Client::mcvideo_server_call(const std::string &session_type, const std::string &url) {
  if (!mcvideo_authorized_) throw std::runtime_error("MCVideo call requires authorisation");
  if (mcvideo_call_) throw std::runtime_error("an MCVideo call is in progress");
  auto headers = mcvideo_call_headers();
  headers.push_back({"Answer-Mode", "Auto"});
  mcvideo_call_ = new_mcvideo_call();
  const McvideoCallSetup setup{mcvideo_call_};
  auto &call = *mcvideo_call_;
  call.type = "private";
  call.video_mode = session_type;
  call.peer = mcvideo_service_uri();
  call.state = "calling";
  call.local.implicit_request = session_type == "one-to-server video push";
  McpttInfo info;
  info.session_type = session_type;
  info.client_id = client_id();
  info.calling_user = mcvideo_user();
  info.video_pull_url = url;
  call.dialog = sip_.invite(
      mcvideo_service_uri(), call.local, {{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)}},
      [this, generation = call.generation](const SipResponse &response) {
        mcvideo_invite_answered(generation, response);
      },
      headers);
  event("mcvideo_server_call_requested", {{"session_type", session_type}, {"url", url}});
}

} // namespace mcx
