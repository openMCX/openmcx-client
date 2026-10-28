//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Incoming SIP requests in the clear: notifications, messages, INFO and the terminating group, private, first-to-answer,
// ambient listening and ad hoc group calls of the MCPTT client, with the hand-over to MCData and MCVideo.
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 24.281 V19.6.0 (Rel-19), TS 24.282 V19.7.0 (Rel-19),
// TS 24.481 V19.3.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19), TS 23.379 V19.11.0 (Rel-19), RFC 3891.

#include "client.hpp"

namespace mcx {

namespace {

//------------------------------------------------------------------------------
// RFC 5621: the body of the given type, the whole body or one part of a multipart/mixed body (a regroup notice
// also carries the MC service information of the request, TS 24.379 clause 16.2.2.4 step 3 d)); null when absent.
const std::string *body_of(const SipMessage &message, std::string_view mime) {
  if (message.content_type.starts_with(mime)) return &message.body;
  for (const auto &part : message.parts)
    if (part.content_type == mime) return &part.body;
  return nullptr;
}

} // namespace

//------------------------------------------------------------------------------
// Helper: dispatch an incoming SIP request (after XML protection checks) from the trusted core: MCVideo and MCData
// requests to their services; NOTIFY for the group key (TS 24.481 clause 6.3.13.2.1, TS 33.180 clause 5.7), the
// configuration, the conference event package (TS 24.379 clause 10.1.3.2) and the affiliation status (clause
// 9.2.1.3); MESSAGE for location, regroup (clause 16.2.1.3), private call
// services, ad hoc alerts (clause 12.1A.1.3) and priority notices; INFO for priority
// (clause 6.2.8.1.13, clause 6.2.8.3.7); INVITE for re-INVITE priority changes and new calls (group: clause
// 10.1.1.2.1.2 and clause 10.1.2.2.1.6); BYE release reasons. Every handler below runs inside this try block, so an
// exception from any of them answers 400.
SipResponse Client::incoming_clear(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  try {
    if (message.source_address != resolve_ipv4(cfg_.at("trusted_core"))) {
      response.status_code = 403;
      return response;
    }
    if (message.method == "MESSAGE") return incoming_clear_message(message);
    if (message.method == "INFO") return incoming_clear_info(message);
    if (message.method == "NOTIFY") return incoming_clear_notify(message);
    if (message.method == "INVITE") return incoming_clear_invite(message);
    if (message.method == "OPTIONS") return response;
    if (message.method == "BYE") return incoming_clear_bye(message);
    response.status_code = 405;
    return response;
  } catch (const std::exception &error) {
    failure(error.what());
    response.status_code = 400;
    return response;
  }
}

//------------------------------------------------------------------------------
// Helper: dispatch a SIP MESSAGE from the trusted core (inside incoming_clear's try block): MCVideo and MCData
// messages to their services, location, regroup (TS 24.379 clause 16.2.1.3), private call
// services, ad hoc alerts (clause 12.1A.1.3) and priority notices.
SipResponse Client::incoming_clear_message(const SipMessage &message) {
  // TS 24.379 clause 14.3.2 and TS 24.281 clause 16.2.2: an MBMS bearer announcement (or MuSiK download) of the
  // MCPTT or the MCVideo participating function (core/mbms.cpp).
  for (const auto service : {MbmsService::Mcptt, MbmsService::Mcvideo})
    if (const auto *usage = mbms_usage_body(message, service)) return mbms_message(message, service, *usage);
  // The MCVideo server's alerts (TS 24.281 clause 11.2) and in-call priority INFO (TS 24.281 clause 6.2.8.1.13),
  // before any MCPTT handling parses MCPTT information.
  // TS 24.281 clause 21.2.1.3 and clause 21.2.1.4: an MCVideo regroup notice from the MCVideo server.
  if (mcvideo_enabled() && body_of(message, regroup_mime_of(mcvideo_service))) return mcvideo_regroup_message(message);
  if (mcvideo_enabled() && message.from_uri == mcvideo_service_uri()) {
    if (auto handled = mcvideo_location_incoming(message)) return *handled;
    return mcvideo_message(message);
  }
  if (message.from_uri == service())
    if (auto handled = location_incoming(message)) return *handled;
  if (mcdata_enabled() && message.from_uri == mcdata_service()) {
    if (auto handled = mcdata_location_incoming(message)) return *handled;
    return mcdata_incoming(message);
  }
  // TS 24.379 clause 16.2.1.3 and clause 16.2.1.4: the regroup this user's group has been put into.
  if (body_of(message, regroup_mime)) return regroup_message(message);
  const auto info = message_info(message);
  if (info.service.contains("request-type") || info.service.contains("response-type"))
    return private_service_received(message, info);
  // TS 24.379 clause 12.1A.1.3: alert notification (or the receipt of our own alert).
  if (info.adhoc_alert) return adhoc_alert_message(message, info);
  return priority_notice(message);
}

//------------------------------------------------------------------------------
// TS 24.281 clause 21.2.1.3 and clause 21.2.1.4: an MCVideo regroup notice from the MCVideo server.
SipResponse Client::mcvideo_regroup_message(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  if (message.from_uri != mcvideo_service_uri() || asserted_identity(message) != mcvideo_service_uri()) {
    response.status_code = 403;
    return response;
  }
  Regroup regroup;
  try {
    regroup = parse_regroup(*body_of(message, regroup_mime_of(mcvideo_service)), mcvideo_service);
  } catch (const std::exception &) {
    response.status_code = 403;
    return response;
  }
  schedule([this, regroup] { mcvideo_regroup_notified(regroup); });
  return response;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 16.2.1.3 and clause 16.2.1.4: the regroup this user's group has been put into.
SipResponse Client::regroup_message(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  Regroup regroup;
  try {
    regroup = parse_regroup(*body_of(message, regroup_mime));
  } catch (const std::exception &) {
    response.status_code = 403;
    return response;
  }
  // The participating function sends the notice; its P-Asserted-Identity is its own on a creation (TS 24.379 clause
  // 16.2.2.4 step 3f) but copied from the function that removed the regroup on a removal (clause 16.2.2.5 step 3f).
  if (message.from_uri != service() || (regroup.action != "remove" && asserted_identity(message) != service())) {
    response.status_code = 403;
    return response;
  }
  schedule([this, regroup] { regroup_notified(regroup); });
  return response;
}

//------------------------------------------------------------------------------
// Helper: a private call service request or response (call-back, TS 24.379 clause 11.1.5.2.2; transfer, clause
// 11.1.8.2.2) relayed by the MCPTT server, handled by service_message.
SipResponse Client::private_service_received(const SipMessage &message, const McpttInfo &info) {
  SipResponse response;
  response.status_code = 200;
  if (message.from_uri != service() || asserted_identity(message) != service()) {
    response.status_code = 403;
    return response;
  }
  if (info.request_uri != user() || info.calling_user.empty()) {
    response.status_code = 403;
    return response;
  }
  schedule([this, info] { service_message(info); });
  return response;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 12.1A.1.3: alert notification (or the receipt of our own alert).
SipResponse Client::adhoc_alert_message(const SipMessage &message, const McpttInfo &info) {
  SipResponse response;
  response.status_code = 200;
  if (message.from_uri != service() || asserted_identity(message) != service()) {
    response.status_code = 403;
    return response;
  }
  if (info.request_uri != user() || info.calling_group.empty()) {
    response.status_code = 403;
    return response;
  }
  if (info.adhoc_alert_received == true) {
    if (info.client_id != client_id()) {
      response.status_code = 403;
      return response;
    }
    // TS 24.379 clause 12.1A.1.3: the receipt of this user's alert, or of its cancellation (clause 12.1A.1.2),
    // which ends the alert.
    if (*info.adhoc_alert) {
      if (ah_mea_ == 2) ah_mea_ = 3;
      own_alert_group_ = info.calling_group;
    } else if (ah_mea_ == 4) {
      ah_mea_ = 1;
      own_alert_group_.clear();
    }
  } else if (*info.adhoc_alert) {
    received_adhoc_alerts_[info.calling_group] = {{"initiator", info.calling_user},
                                                  {"criteria", info.participant_criteria}};
  } else {
    received_adhoc_alerts_.erase(info.calling_group);
  }
  event("adhoc_alert_notification", {{"group", info.calling_group}, {"active", *info.adhoc_alert}});
  return response;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 12.1.1.3 (an emergency alert or call notification, as a MESSAGE) and clause 6.2.8.1.13 (an
// INFO in the group call's dialog): the alert, emergency and imminent peril state of the selected group.
SipResponse Client::priority_notice(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  if (message.from_uri != service() || asserted_identity(message) != service() ||
      (message.method == "INFO" &&
       (message.dialog_id != dialog_ || get_header(message.headers, "Info-Package") != "g.3gpp.mcptt-info"))) {
    response.status_code = 403;
    return response;
  }
  const auto info = message_info(message);
  // The selected group's notices update the live state, or, during a call on a monitored group, that call's; the
  // selected group's then waits in selected_priority_, and other monitored groups keep their own.
  const bool monitored_call = selected_priority_.has_value();
  const bool live = info.calling_group == (monitored_call ? call_group_ : group());
  const bool waiting = !live && monitored_call && info.calling_group == group();
  const bool other = !live && !waiting && monitoring(info.calling_group);
  if (info.request_uri != user() || (!live && !waiting && !other)) {
    response.status_code = 403;
    return response;
  }
  if (info.broadcast == true) {
    response.status_code = 488;
    return response;
  }
  if ((info.alert_received == true || info.emergency_received == true) && info.client_id != client_id()) {
    response.status_code = 403;
    return response;
  }
  // TS 24.379 clause 12.1.1.2: the receipt completes the pending alert request of its group, whichever state the
  // group's notices update.
  if (info.alert_received == true && info.alert && info.calling_group == alert_pending_group_) alert_pending_.clear();
  if (!live) {
    auto &state = waiting ? *selected_priority_ : monitored_state_[info.calling_group];
    priority_into(state, info, message_location(message));
    event("monitored_priority", {{"group", info.calling_group}, {"state", state}});
    return response;
  }
  update_priority(info, message_location(message));
  if (message.method == "INFO")
    status_["priority_corrections_received"] = status_.value("priority_corrections_received", 0u) + 1;
  return response;
}

//------------------------------------------------------------------------------
// Helper: dispatch a SIP INFO from the trusted core (inside incoming_clear's try block): MCVideo in-call INFO,
// private call priority (TS 24.379 clause 6.2.8.3.7), MCData release and priority (TS 24.282 clause 13.2.4.2,
// clause 6.2.8.1.13) and group call priority (TS 24.379 clause 6.2.8.1.13).
SipResponse Client::incoming_clear_info(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  if (mcvideo_call_ && !message.dialog_id.empty() && message.dialog_id == mcvideo_call_->dialog)
    return mcvideo_call_info(message);
  // TS 24.379 clause 6.2.8.3.7: the controlling function confirms or corrects alert state.
  if (call_type_ == "private") return private_priority_info(message);
  if (mcdata_enabled() && get_header(message.headers, "Info-Package") == mcdata::com_release_package) {
    // TS 24.282 clause 13.2.4.2 and clause 13.2.6.2.2: the release of a communication over the media plane.
    if (asserted_identity(message) != mcdata_service() || message.dialog_id.empty() ||
        (message.dialog_id != mcdata_media_dialog_ && message.dialog_id != ipcon_dialog_)) {
      response.status_code = 403;
      return response;
    }
    return mcdata_com_release_received(message);
  }
  if (mcdata_enabled() && message.from_uri == mcdata_service() &&
      get_header(message.headers, "Info-Package") == "g.3gpp.mcdata-info") {
    // TS 24.282 clause 6.2.8.1.13: the controlling function corrects the priority of this session.
    if (asserted_identity(message) != mcdata_service() || message.dialog_id != mcdata_media_dialog_ ||
        mcdata_media_dialog_.empty()) {
      response.status_code = 403;
      return response;
    }
    auto parts = message.parts.empty() ? parse_body_parts(message.content_type, message.body) : message.parts;
    for (const auto &part : parts)
      if (part.content_type == mcdata::info_mime) mcdata_priority_info(mcdata::parse_info(part.body));
    return response;
  }
  return priority_notice(message);
}

//------------------------------------------------------------------------------
// TS 24.281 clause 6.2.8.1.13: an INFO in the MCVideo call's dialog, with TS 24.281 clause 13.2.2.4 for the
// <video-push-url> of a one-to-server video push.
SipResponse Client::mcvideo_call_info(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  const auto info = message_info(message, mcvideo_service);
  // TS 24.281 clause 13.2.2.4: a <video-push-url> in the g.3gpp.mcvideo-info package is stored.
  if (!info.video_push_url.empty() && mcvideo_call_->video_mode == "one-to-server video push" &&
      get_header(message.headers, "Info-Package") == "g.3gpp.mcvideo-info") {
    mcvideo_push_url_ = info.video_push_url;
    status_["mcvideo_push_url"] = mcvideo_push_url_;
    event("mcvideo_push_url", {{"url", mcvideo_push_url_}});
    return response;
  }
  mcvideo_note_priority(info);
  return response;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.2.8.3.7: the controlling function confirms or corrects alert state.
SipResponse Client::private_priority_info(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  if (message.from_uri != service() || asserted_identity(message) != service() || message.dialog_id != dialog_ ||
      get_header(message.headers, "Info-Package") != "g.3gpp.mcptt-info") {
    response.status_code = 403;
    return response;
  }
  const auto info = message_info(message);
  if (info.request_uri != user() || !info.calling_group.empty()) {
    response.status_code = 403;
    return response;
  }
  if (info.alert && (mpea_ == 2 || mpea_ == 4)) {
    mpea_ = *info.alert ? 3 : 1;
    if (*info.alert)
      private_alerts_.insert(user());
    else
      private_alerts_.erase(user());
  }
  status_["priority_corrections_received"] = status_.value("priority_corrections_received", 0u) + 1;
  return response;
}

//------------------------------------------------------------------------------
// Helper: dispatch a SIP NOTIFY from the trusted core (inside incoming_clear's try block): group documents, the
// configuration, the group key (TS 24.481 clause 6.3.13.2.1, TS 33.180 clause 5.7), the conference event package
// (TS 24.379 clause 10.1.3.2), MCData and MCVideo subscriptions and the affiliation status (clause 9.2.1.3).
SipResponse Client::incoming_clear_notify(const SipMessage &message) {
  if (get_header(message.headers, "Event") == "xcap-diff")
    if (auto handled = group_document_notify(message)) return *handled;
  if (get_header(message.headers, "Event") == "xcap-diff" && !config_subscription_.empty() &&
      message.dialog_id == config_subscription_) {
    SipResponse response;
    response.status_code = asserted_identity(message) == service() ? 200 : 403;
    if (response.status_code == 200) schedule([this] { configuration_notified(); });
    return response;
  }
  if (mcvideo_enabled() && !message.dialog_id.empty() && message.dialog_id == mcvideo_key_subscription_)
    return mcvideo_key_notify(message);
  if (get_header(message.headers, "Event") == "xcap-diff") return group_key_notify(message);

  // The MCVideo call's own conference subscription (TS 24.281 clause 9.2.3).
  if (mcvideo_call_ && !message.dialog_id.empty() && message.dialog_id == mcvideo_call_->conference_subscription)
    return mcvideo_conference_notify(message);
  if (mcvideo_retired_subscriptions_.contains(message.dialog_id) &&
      asserted_identity(message) == mcvideo_service_uri()) {
    if (get_header(message.headers, "Subscription-State").starts_with("terminated"))
      mcvideo_retired_subscriptions_.erase(message.dialog_id);
    SipResponse ok;
    ok.status_code = 200;
    return ok;
  }
  // TS 24.379 clause 9A.2.1.3: this user's functional alias status.
  if (!message.dialog_id.empty() && message.dialog_id == alias_status_dialog_) return alias_status_notify(message);
  if (get_header(message.headers, "Event") == "conference") return conference_notify(message);

  // TS 24.282 clause 22.2.1.3: this user's MCData functional alias status.
  if (mcdata_enabled() && !message.dialog_id.empty() && message.dialog_id == mcdata_alias_dialog_)
    return mcdata_alias_notify(message);
  if (mcdata_enabled() && !message.dialog_id.empty() &&
      (message.dialog_id == mcdata_subscription_ || message.dialog_id == mcdata_fetch_))
    return mcdata_affiliation_notify(message);
  if (mcvideo_enabled() && !message.dialog_id.empty() && message.dialog_id == mcvideo_subscription_)
    return mcvideo_affiliation_notify(message);
  if (mcvideo_enabled() && !message.dialog_id.empty() && mcvideo_alias_dialogs_.contains(message.dialog_id))
    return mcvideo_alias_notify(message);
  return affiliation_notify(message);
}

//------------------------------------------------------------------------------
// TS 24.481 clause 6.3.13.2.1 and TS 33.180 clause 5.7: a group key notification (xcap-diff) on a key
// subscription: install the GMK of the selected group, or forget it when the subscription terminates.
SipResponse Client::group_key_notify(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  auto found = key_subscriptions_.find(message.dialog_id);
  if (found == key_subscriptions_.end() || asserted_identity(message) != service() || message.from_uri != service()) {
    response.status_code = 403;
    return response;
  }
  if (get_header(message.headers, "Subscription-State").starts_with("terminated")) {
    if (message.dialog_id == key_subscription_) {
      protection_.clear_group();
      status_["protected_ready"] = false;
      talking_ = requesting_ = false;
      transmit_.clear();
      if (!dialog_.empty()) schedule([this] { sip_.bye(dialog_); });
    }
    key_subscriptions_.erase(found);
    return response;
  }
  if (message.dialog_id != key_subscription_ || found->second != group()) return response;
  if (!keys_ || message.content_type != "application/xcap-diff+xml") {
    response.status_code = 400;
    return response;
  }
  auto document = keying::parse_gktp_xcap_diff(message.body, group(), xcap_root());
  auto key = keys_->open_group(gms_identity(), group(), document);
  protection_.set_group(key);

  // The GMK also protects MCData payloads for its MC group IDs (TS 33.180 clause 8.4).
  group_keys_[key.csb_id] = key;
  while (group_keys_.size() > 16) group_keys_.erase(group_keys_.begin());
  status_["group_key_notifications"] = status_.value("group_key_notifications", 0ULL) + 1;
  status_["group_key_id"] = key.csb_id;
  status_["protected_ready"] = true;
  if (desired_affiliation_ && !affiliated_) schedule([this] { affiliate(); });
  event("group_key_installed", {{"group_id", group()}, {"key_id", key.csb_id}});
  return response;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.3.2: a conference event package NOTIFY for the active group call (participants and
// state), or the final NOTIFY of a retired conference subscription.
SipResponse Client::conference_notify(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  // RFC 6665 section 4.1.3: the subscription of an ended call lasts until the notifier terminates it, so its
  // NOTIFYs (a roster sent as the call was released) are accepted, and the final one ends it.
  const auto retired = retired_conferences_.find(message.dialog_id);
  if (retired != retired_conferences_.end()) {
    if (asserted_identity(message) != service() ||
        (!conference_service_from(message.from_uri) && message.from_uri != retired->second)) {
      response.status_code = 403;
      return response;
    }
    if (get_header(message.headers, "Subscription-State").starts_with("terminated"))
      retired_conferences_.erase(retired);
    return response;
  }
  // RFC 6665 section 4.1.3: a NOTIFY that matches no subscription.
  if (message.dialog_id != conference_subscription_) {
    event("conference_notify_rejected", {{"reason", "no subscription"}, {"from_uri", message.from_uri}});
    response.status_code = 481;
    return response;
  }
  if ((!conference_service_from(message.from_uri) && message.from_uri != session_uri_) ||
      asserted_identity(message) != service()) {
    status_["conference_wait_reason"] = "NOTIFY identity rejected";
    event("conference_notify_rejected",
          {{"reason", "identity"}, {"from_uri", message.from_uri}, {"session_uri", session_uri_}});
    response.status_code = 403;
    return response;
  }
  const auto info = message_info(message);
  if (info.request_uri != user() || info.calling_group != active_group()) {
    response.status_code = 403;
    return response;
  }
  const auto part = std::find_if(message.parts.begin(), message.parts.end(), [](const auto &candidate) {
    return candidate.content_type == "application/conference-info+xml";
  });
  if (part == message.parts.end()) {
    response.status_code = 400;
    return response;
  }
  const auto conference = parse_conference_info(part->body);
  if (conference.group_id != active_group() ||
      (!conference.session_uri.empty() && conference.session_uri != session_uri_)) {
    response.status_code = 403;
    return response;
  }
  // RFC 4575 sections 4.6 and 5.6 (TS 24.379 clause 10.1.3.4.1: "according to IETF RFC 4575"): a full document
  // replaces the participants; a partial one applies on top of them (a user "deleted" or disconnected leaves), only
  // with the next version: after a gap the current state is fetched again by refreshing the subscription.
  const bool partial = !conference.full && !conference.deleted;
  if (partial && (!conference_version_ || conference.version != *conference_version_ + 1)) {
    if (!conference_version_ || conference.version > *conference_version_) {
      event("conference_version_gap", {{"version", conference.version}});
      sip_.request_in_dialog(conference_subscription_, "SUBSCRIBE", {}, {},
                             {{"Event", "conference"}, {"Expires", "4294967295"},
                              {"Accept", "application/conference-info+xml"}});
    }
  } else if (!conference_version_ || conference.version > *conference_version_) {
    conference_version_ = conference.version;
    status_["conference_version"] = conference.version;
    if (!partial) conference_members_.clear();
    for (const auto &member : conference.users)
      if (member.state == "deleted" || member.status == "disconnected")
        conference_members_.erase(member.user_id);
      else
        conference_members_[member.user_id] = member.status;
    status_["conference_participants"] = Json::array();
    for (const auto &[id, endpoint] : conference_members_)
      if (endpoint == "connected") status_["conference_participants"].push_back(id);
  }
  if (get_header(message.headers, "Subscription-State").starts_with("terminated")) {
    conference_subscription_.clear();
    status_["conference_active"] = false;
  } else {
    status_["conference_active"] = true;
    status_["conference_wait_reason"] = "";
  }
  return response;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.3: the affiliation status of this client for the selected group, from the MCPTT
// server's presence NOTIFY.
SipResponse Client::affiliation_notify(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  if (message.dialog_id != subscription_ || message.from_uri != service() || asserted_identity(message) != service()) {
    response.status_code = 403;
    return response;
  }
  auto substate = get_header(message.headers, "Subscription-State");
  if (!substate.starts_with("active")) {
    // The subscription ended: this client no longer learns its affiliation status, which stays as last notified (the
    // server keeps the affiliations, clause 9.2.2.2.4), unless the server ended it for want of the resource (RFC 6665
    // section 4.1.3, "noresource" or "rejected": the service authorisation ended).
    if (substate.find("reason=noresource") != std::string_view::npos ||
        substate.find("reason=rejected") != std::string_view::npos)
      affiliated_ = false;
    if (substate.starts_with("terminated")) {
      subscription_.clear();
      event("affiliation_subscription_ended", {{"state", std::string(substate)}});
    }
    response.status_code = 200;
    return response;
  }
  if (get_header(message.headers, "Event") != "presence") {
    response.status_code = 489;
    return response;
  }
  // TS 24.379 clause 9.3.1.2: one tuple per client of the user; this client's is read (none: no group).
  auto affiliation = parse_affiliation(message.body, client_id());
  if (affiliation.user_id != user()) throw std::runtime_error("notification identity mismatch");
  affiliated_ = false;
  affiliation_statuses_ = Json::object();
  for (size_t index = 0; index < affiliation.groups.size(); index++) {
    auto state = index < affiliation.statuses.size() ? affiliation.statuses[index] : std::string{};
    if (state.empty()) state = "affiliated"; // the "status" attribute is optional (clause 9.3.1.2)
    affiliation_statuses_[affiliation.groups[index]] = state;
    if (affiliation.groups[index] == group() && client_rules::counts_as_affiliated(state)) affiliated_ = true;
  }
  event("affiliation_status", {{"affiliated", affiliated_}});
  return response;
}

//------------------------------------------------------------------------------
// Helper: dispatch a SIP INVITE from the trusted core (inside incoming_clear's try block): the MCData media plane
// (TS 24.282 clause 9.2.3.2.4), MCVideo calls, re-INVITEs of the active call, RFC 3891 replacement and new private,
// ambient listening, ad hoc and group calls.
SipResponse Client::incoming_clear_invite(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  // TS 24.282 clause 9.2.3.2.4: the MCData media plane, offered by the MCData server.
  if (mcdata_enabled() && message.from_uri == mcdata_service()) return mcdata_media_invite(message);
  if (mcvideo_enabled() && message.from_uri == mcvideo_service_uri()) return mcvideo_invite(message);

  if (message.from_uri != service() || asserted_identity(message) != service() ||
      message.to_uri != cfg_.at("sip").at("identity").get<std::string>()) {
    response.status_code = 403;
    return response;
  }
  // The server released any ambient listening call before inviting us.
  if (message.dialog_id != dialog_) (void)yield_ambient();
  if (!dialog_.empty() && message.dialog_id == dialog_ && status_.value("call_state", "") == "active" &&
      call_type_ == "private")
    return private_call_reinvite(message);
  if (!dialog_.empty() && message.dialog_id == dialog_ && status_.value("call_state", "") == "active")
    return group_call_reinvite(message);
  // RFC 3891 (TS 24.379 clause 11.1.8.2.2): a transferred user's call replacing the
  // call that announced the transfer; the replaced call is ended.
  if (const auto replaces = get_header(message.headers, "Replaces"); !replaces.empty()) {
    const auto call_id = std::string(replaces.substr(0, replaces.find(';')));
    if (dialog_.empty() || call_id != dialog_ || call_type_ != "private") {
      response.status_code = 481;
      return response;
    }
    const auto replaced = dialog_;
    sip_.bye(replaced);
    call_state(replaced, SipCallState::disconnected, 200);
    replacing_call_ = true;
    event("private_call_replaced", {{"dialog", replaced}, {"by", message.dialog_id}});
  }
  if (auto invited = message_info(message);
      invited.session_type == "private" || invited.session_type == "first-to-answer")
    return incoming_private(message, invited);
  else if (invited.session_type == "ambient-listening")
    return incoming_ambient(message, invited);
  else if (invited.session_type == "adhoc")
    return incoming_adhoc(message, invited);
  return incoming_group(message);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.1.2.1.3: the other party upgraded or cancelled the emergency private call.
SipResponse Client::private_call_reinvite(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  auto info = message_info(message);
  if (!message.remote_media || !remote_ || info.request_uri != user() || info.session_type != "private" ||
      !info.calling_group.empty() || !info.emergency || info.imminent_peril) {
    response.status_code = 488;
    return response;
  }
  check_remote(*message.remote_media);
  if (message.remote_media->floor_ssrc != remote_->floor_ssrc ||
      message.remote_media->audio_port != remote_->audio_port ||
      message.remote_media->floor_port != remote_->floor_port ||
      message.remote_media->payload_type != remote_->payload_type) {
    response.status_code = 488;
    return response;
  }
  update_private_priority(info);
  response.local_media = local_;
  name_transfer_dialog(response, message, info);
  return response;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.2.2.1.2: a re-INVITE in the active group call's dialog changing its emergency or imminent
// peril state; the media must stay as negotiated.
SipResponse Client::group_call_reinvite(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  auto info = message_info(message);
  if (!message.remote_media || !remote_ || info.request_uri != user() ||
      info.calling_group != (selected_priority_ ? call_group_ : group()) ||
      (info.session_type != "prearranged" && info.session_type != "chat") ||
      (info.broadcast == true && call_type_ != "broadcast") || (!info.emergency && !info.imminent_peril)) {
    response.status_code = 488;
    return response;
  }
  check_remote(*message.remote_media);
  if (message.remote_media->floor_ssrc != remote_->floor_ssrc ||
      message.remote_media->audio_port != remote_->audio_port ||
      message.remote_media->floor_port != remote_->floor_port ||
      message.remote_media->payload_type != remote_->payload_type ||
      message.remote_media->octet_aligned != remote_->octet_aligned ||
      message.remote_media->queueing != remote_->queueing) {
    response.status_code = 488;
    return response;
  }
  local_.floor_priority = message.remote_media->floor_priority;
  update_priority(info, message_location(message));
  response.local_media = local_;
  return response;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.1.2.1.2 and clause 10.1.2.2.1.6: the MCPTT client receives a prearranged, chat or
// broadcast group call; Answer-Mode "Manual" or the local manual setting rings.
SipResponse Client::incoming_group(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  auto info = message_info(message);

  // TS 24.379 clause 16.2.1.3 step 4 (clause 16.3.1.3 for a user regroup): a client told of a regroup is
  // affiliated with it and takes its calls without selecting it first.
  const bool via_regroup = regroups_.contains(info.calling_group) && info.calling_group != group();
  // TS 24.379 clause 10.1.1.2.1.2: a call on any group this client is affiliated to, a monitored one too.
  const bool via_monitor = !via_regroup && monitored_affiliated(info.calling_group);
  if (!authorized_ || (!affiliated_ && !via_regroup && !via_monitor) || !dialog_.empty() ||
      std::time(nullptr) >= expires_) {
    response.status_code = 486;
    return response;
  }
  // A broadcast hierarchy call arrives through the selected group, named in
  // <associated-group-id> (project profile for TS 23.379 clause 10.6.2.5.2).
  const bool via_hierarchy = info.broadcast == true && !info.associated_group.empty() &&
                             info.associated_group == group() && info.calling_group != group();
  if ((info.session_type != "prearranged" && info.session_type != "chat") || info.request_uri != user() ||
      (info.calling_group != group() && !via_hierarchy && !via_regroup && !via_monitor) || !message.remote_media) {
    response.status_code = 488;
    return response;
  }
  // Answer-Mode Auto or Manual, which the participating function sends to a group call invitee; another value is
  // ignored, as is the header field's absence (RFC 5373 section 2), and the local setting applies.
  const auto mode = answer_mode_value(get_header(message.headers, "Answer-Mode"));
  local_.queueing = cfg_.at("mcptt").value("floor_queue", false);
  check_remote(*message.remote_media);
  local_.queueing = local_.queueing && message.remote_media->queueing;
  local_.floor_priority = message.remote_media->floor_priority;
  if (via_monitor) enter_monitored_call(info.calling_group);
  update_priority(info, message_location(message));
  local_.floor_ssrc = random_ssrc();
  dialog_ = message.dialog_id;
  local_.payload_type = message.remote_media->payload_type;
  local_.octet_aligned = message.remote_media->octet_aligned;
  call_type_ = info.session_type == "chat" ? "chat" : info.broadcast == true ? "broadcast" : "prearranged";
  call_group_ = via_hierarchy || via_regroup || via_monitor ? info.calling_group : std::string{};
  status_["calling_functional_alias"] = info.functional_alias;
  response.local_media = local_;
  remember_session(message.headers);
  if (mode == "Manual" || cfg_.at("mcptt").value("answer_mode", "auto") == "manual") {
    pending_answer_ = response;
    response.status_code = 180;
    response.reason = "Ringing";
  }
  status_["call_state"] = pending_answer_ ? "ringing" : "answering";
  return response;
}

//------------------------------------------------------------------------------
// Helper: a BYE from the trusted core: keep the release reason of the MCData communication (TS 24.282 clause
// 13.2.3.2.2), the first-to-answer call (TS 24.379 clause 11.1.1.2.1.2), the ambient viewing call (TS 24.281 clause
// 15.4.3) or the ambient listening call (TS 24.379 clause 11.1.6.4.3) it ends.
SipResponse Client::incoming_clear_bye(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  // TS 24.282 clause 13.2.3.2.2: the reason the MCData server gives for releasing a communication.
  if (const auto reason = std::string(get_header(message.headers, "Reason")); !reason.empty()) {
    if (!mcdata_media_dialog_.empty() && message.dialog_id == mcdata_media_dialog_ && mcdata_media_result_.is_object())
      mcdata_media_result_["release_reason"] = reason_text(reason);
    if (!ipcon_dialog_.empty() && message.dialog_id == ipcon_dialog_ && ipcon_result_.is_object())
      ipcon_result_["release_reason"] = reason_text(reason);
  }
  // TS 24.379 clause 11.1.1.2.1.2: a first-to-answer invitee released as "not selected for call".
  if (message.dialog_id == dialog_ && first_to_answer_ && !message.body.empty())
    if (message_info(message).release_reason == "not selected for call") fta_not_selected_ = true;
  // TS 24.281 clause 15.4.3: likewise for the viewing user of an ambient viewing call.
  if (mcvideo_call_ && message.dialog_id == mcvideo_call_->dialog && !mcvideo_call_->ambient_type.empty() &&
      !message.body.empty())
    mcvideo_call_->release_reason = message_info(message, mcvideo_service).release_reason;
  // TS 24.379 clause 11.1.6.4.3: a server-initiated ambient release gives the listening user a reason.
  if (ambient_ && message.dialog_id == ambient_->dialog && !message.body.empty())
    ambient_->release_reason = message_info(message).release_reason;
  return response;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.1.2.1.2: the MCPTT client receives a private or first-to-answer call. Affiliation is not
// required. Priv-Answer-Mode "Auto" forces automatic commencement and
// "Manual" (sent for a first-to-answer call) forces manual commencement;
// otherwise Answer-Mode "Manual" or the local manual setting rings (clause 6.2.3.2.1).
SipResponse Client::incoming_private(const SipMessage &message, const McpttInfo &info) {
  SipResponse response;
  response.status_code = 200;
  const bool replacing = std::exchange(replacing_call_, false);
  if (!authorized_ || !dialog_.empty() || std::time(nullptr) >= expires_) {
    response.status_code = 486;
    return response;
  }
  if (info.request_uri != user() || info.calling_user.empty() || info.calling_user == user() ||
      !info.calling_group.empty() || !message.remote_media || info.broadcast == true || info.imminent_peril ||
      info.emergency == false || (info.alert == true && info.emergency != true)) {
    response.status_code = 488;
    return response;
  }
  // RFC 5373 section 2: an answer-mode value other than Auto and Manual is ignored, like an absent header field.
  auto priv = answer_mode_value(get_header(message.headers, "Priv-Answer-Mode"));
  auto mode = answer_mode_value(get_header(message.headers, "Answer-Mode"));
  if (priv != "Auto" && priv != "Manual") priv.clear();
  if (mode != "Auto" && mode != "Manual") mode.clear();
  const bool first_to_answer = info.session_type == "first-to-answer";
  if (first_to_answer && priv != "Manual") {
    response.status_code = 400;
    return response;
  }
  local_.queueing = cfg_.at("mcptt").value("floor_queue", false);
  // TS 24.379 clause 11.1.2.2: an offer without a media-floor control section is a private call
  // without floor control, answered without one.
  local_.control = message.remote_media->control == MediaControl::none ? MediaControl::none : MediaControl::floor;
  if (no_floor()) {
    local_.queueing = false;
    local_.audio_ssrc = random_ssrc();
  }
  try {
    check_remote(*message.remote_media, true);
  } catch (...) {
    local_.control = MediaControl::floor;
    throw;
  }
  // Step 4: a protected private call carries the PCK in the offer. A first-to-answer
  // invitation carries none; the invitee sends one in its answer (step 5).
  std::optional<keying::KeyMessage> key;
  if (protected_media() && first_to_answer != message.remote_media->key_mgmt.empty()) {
    response.status_code = 488;
    return response;
  }
  if (protected_media() && !first_to_answer) {
    try {
      key = accept_private_key(message.remote_media->key_mgmt, info.calling_user);
    } catch (const std::exception &error) {
      event("private_call_key_rejected", {{"calling_user", info.calling_user}, {"reason", error.what()}});
      response.status_code = 488;
      response.headers = {{"Warning", mikey_failed}};
      return response;
    }
  }
  adopt_incoming_private(message, info, key, first_to_answer);
  response.local_media = local_;
  name_transfer_dialog(response, message, info);
  remember_session(message.headers);

  // RFC 3891: the call replacing the current one is accepted without alerting.
  const bool manual =
      !replacing && (priv == "Manual" ||
                     (priv.empty() && (mode == "Manual" || cfg_.at("mcptt").value("answer_mode", "auto") == "manual")));
  if (manual) {
    pending_answer_ = response;
    response.status_code = 180;
    response.reason = "Ringing";
  }
  status_["call_state"] = manual ? "ringing" : "answering";
  event("private_call_incoming", {{"calling_user", info.calling_user},
                                  {"commencement", manual ? "manual" : "automatic"},
                                  {"forced", !priv.empty()},
                                  {"first_to_answer", first_to_answer}});
  return response;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.1.2.1.2: take the accepted private or first-to-answer call as this client's call: its
// media parameters, floor state, dialog, PCK and emergency priority.
void Client::adopt_incoming_private(const SipMessage &message, const McpttInfo &info,
                                    const std::optional<keying::KeyMessage> &key, bool first_to_answer) {
  local_.queueing = local_.queueing && message.remote_media->queueing;
  local_.floor_priority = message.remote_media->floor_priority;
  local_.floor_ssrc = random_ssrc();
  local_.payload_type = message.remote_media->payload_type;
  local_.octet_aligned = message.remote_media->octet_aligned;
  floor_sequence_.reset();
  floor_timers_ = participant_timers();
  dialog_ = message.dialog_id;
  call_type_ = "private";
  bind_private_key(key, false);
  first_to_answer_ = first_to_answer;
  private_peer_ = info.calling_user;
  saved_mepp_.reset();
  fta_not_selected_ = false;
  status_.erase("private_priority_restored");
  if (first_to_answer && info.emergency == true) saved_mepp_ = mepp_;
  update_private_priority(info); // an emergency private call is in progress (MEPP 2)
  status_["calling_functional_alias"] = info.functional_alias;
  status_["called_functional_alias"] = info.called_functional_alias;
  // TS 24.379 clause 11.1.5.2.3: a call from the user we asked to call back fulfils the request.
  if (pccb_requesting_.value(info.calling_user, "") == "PCCB-I3: confirmed") pccb_requesting_.erase(info.calling_user);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.2.3.1.1 step 7a, clause 6.2.3.2.1 and clause 11.1.1.2.1.3 step 5A: the answer to a call
// announcing a transfer names this dialog (Call-ID, the caller's from-tag and our to-tag). It is the only MCPTT
// information a 200 (OK) of the MCPTT client carries; the invitation's is not echoed.
void Client::name_transfer_dialog(SipResponse &response, const SipMessage &message, const McpttInfo &info) {
  if (info.service.contains("transfer-announced-ind") && info.service.at("transfer-announced-ind") == "true") {
    response.parts.clear();
    response.dialog_parts = [call_id = message.call_id](std::string_view local, std::string_view remote) {
      McpttInfo named;
      named.service["replaces-header-value"] =
          call_id + ";to-tag=" + std::string(local) + ";from-tag=" + std::string(remote);
      return info_parts(named);
    };
  }
}

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.6.2.1.2: the MCPTT client receives an ambient listening call: always automatic
// commencement. A listened-to user (remote-init) gets no indication at all
// and transmits once the floor is granted; a listening user (local-init) is told.
SipResponse Client::incoming_ambient(const SipMessage &message, const McpttInfo &info) {
  SipResponse response;
  response.status_code = 200;
  if (!authorized_ || !dialog_.empty() || std::time(nullptr) >= expires_) {
    response.status_code = 486; // step 1a
    return response;
  }
  const auto type = info.ambient_listening_type;
  if (info.request_uri != user() || info.calling_user.empty() || info.calling_user == user() ||
      !info.calling_group.empty() || !message.remote_media || (type != "remote-init" && type != "local-init") ||
      info.emergency || info.imminent_peril || info.alert || info.broadcast == true) {
    response.status_code = 488;
    return response;
  }
  if (answer_mode_value(get_header(message.headers, "Priv-Answer-Mode")) != "Auto") {
    response.status_code = 400;
    return response;
  }
  local_.queueing = cfg_.at("mcptt").value("floor_queue", false);
  check_remote(*message.remote_media, true);

  // TS 33.180 clause 7.2.5: an ambient listening call is protected as a private call.
  std::optional<keying::KeyMessage> key;
  if (protected_media()) {
    if (message.remote_media->key_mgmt.empty()) {
      response.status_code = 488;
      return response;
    }
    try {
      key = accept_private_key(message.remote_media->key_mgmt, info.calling_user);
    } catch (const std::exception &error) {
      event("private_call_key_rejected", {{"calling_user", info.calling_user}, {"reason", error.what()}});
      response.status_code = 488;
      response.headers = {{"Warning", mikey_failed}};
      return response;
    }
  }
  const bool listened_to = type == "remote-init";
  local_.queueing = local_.queueing && message.remote_media->queueing;
  local_.floor_priority = message.remote_media->floor_priority;
  local_.floor_ssrc = random_ssrc();
  local_.payload_type = message.remote_media->payload_type;
  local_.octet_aligned = message.remote_media->octet_aligned;
  local_.implicit_request = false;
  local_.direction = listened_to ? "sendonly" : "recvonly";
  floor_sequence_.reset();
  floor_timers_ = participant_timers();
  if (listened_to) {
    // TS 24.379 clause 6.4: the INVITE was an implicit request to grant the floor to this client.
    floor_timers_.expect_implicit_grant();
    transmit_ = ambient_audio();
    transmit_offset_ = 0;
  }
  dialog_ = message.dialog_id;
  call_type_ = "ambient";
  bind_private_key(key, false);
  private_peer_ = info.calling_user;

  // Steps 7 to 9: cache the role and type; the annex D.3 indication decides whether we may release.
  ambient_ =
      AmbientCall{message.dialog_id,
                  type,
                  listened_to ? "listened-to" : "listening",
                  info.calling_user,
                  get_header(message.headers, "Feature-Caps").find("g.3gpp.mcptt.ambient-listening-call-release") !=
                      std::string_view::npos,
                  get_header(message.headers, "Alert-Info") == "<file:///dev/null>",
                  ""};
  response.local_media = local_;
  status_["call_state"] = listened_to ? "idle" : "answering";
  event(listened_to ? "ambient_listened_to" : "ambient_listening_incoming",
        {{"calling_user", info.calling_user}, {"type", type}, {"null_alert", ambient_->null_alert}});
  return response;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 17.2.2.1.2: the MCPTT client receives an ad hoc group call; invited users are
// implicitly affiliated, so no prior affiliation is required.
SipResponse Client::incoming_adhoc(const SipMessage &message, const McpttInfo &info) {
  SipResponse response;
  response.status_code = 200;
  if (!authorized_ || !dialog_.empty() || std::time(nullptr) >= expires_) {
    response.status_code = 486;
    return response;
  }
  if (info.request_uri != user() || info.calling_group.empty() || info.calling_user.empty() ||
      info.calling_user == user() || !message.remote_media || info.emergency || info.alert ||
      info.imminent_peril == false || info.adhoc_emergency == false || (info.adhoc_emergency && info.imminent_peril)) {
    response.status_code = 488;
    return response;
  }
  adhoc_priority_ = info.adhoc_emergency == true ? "emergency" : info.imminent_peril == true ? "imminent" : "normal";
  adhoc_broadcast_ = info.broadcast == true;
  status_["adhoc_broadcast"] = adhoc_broadcast_;
  status_["calling_functional_alias"] = info.functional_alias;
  status_["called_functional_alias"] = info.called_functional_alias;
  if (protected_media() || protected_floor()) {
    response.status_code = 488;
    response.headers = {
        {"Warning",
         "399 mcx \"100 function not allowed due to end-to-end security for adhoc group call not implemented\""}};
    return response;
  }
  // RFC 5373 section 2: an answer-mode value other than Auto and Manual is ignored.
  const auto mode = answer_mode_value(get_header(message.headers, "Answer-Mode"));
  local_.queueing = cfg_.at("mcptt").value("floor_queue", false);
  check_remote(*message.remote_media);
  local_.queueing = local_.queueing && message.remote_media->queueing;
  local_.floor_priority = message.remote_media->floor_priority;
  local_.floor_ssrc = random_ssrc();
  local_.payload_type = message.remote_media->payload_type;
  local_.octet_aligned = message.remote_media->octet_aligned;
  floor_sequence_.reset();
  floor_timers_ = participant_timers();
  dialog_ = message.dialog_id;
  call_type_ = "adhoc";
  call_group_ = info.calling_group;
  response.local_media = local_;
  remember_session(message.headers);
  const bool manual = mode == "Manual" || cfg_.at("mcptt").value("answer_mode", "auto") == "manual";
  if (manual) {
    pending_answer_ = response;
    response.status_code = 180;
    response.reason = "Ringing";
  }
  status_["call_state"] = manual ? "ringing" : "answering";
  event("adhoc_call_incoming", {{"group_id", info.calling_group}, {"calling_user", info.calling_user}});
  return response;
}

} // namespace mcx
