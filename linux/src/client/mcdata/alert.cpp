// MCData emergency alert, functional alias status change and disposition notifications, MCData client side
// (member functions of Client).
//
// Specifications: TS 24.282 V19.7.0 (Rel-19), RFC 3428 (SIP MESSAGE), RFC 3903 (SIP PUBLISH).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.282 table G.4.1-1: the name of an MCData emergency alert (MDEA) state.
const char *Client::mdea_name(int state) {
  return state == 2   ? "MDEA 2: emergency-alert-confirm-pending"
         : state == 3 ? "MDEA 3: emergency-alert-initiated"
         : state == 4 ? "MDEA 4: emergency-alert-cancel-pending"
                      : "MDEA 1: no-alert";
}

//------------------------------------------------------------------------------
// TS 24.282 clauses 16.2.1.1 (origination) and 16.2.1.2 (cancellation, of another user's alert with originator):
// send the SIP MESSAGE request of an MCData emergency alert and follow the MDEA state on its response.
void Client::mcdata_alert(bool activate, std::string group, const std::string &originator) {
  if (!mcdata_authorized_) throw std::runtime_error("emergency alert requires MCData service authorisation");
  if (group.empty()) group = activate || mcdata_alert_group_.empty() ? mcdata_group() : mcdata_alert_group_;
  if (activate)
    require_mcdata_profile({"allow-activate-emergency-alert"}); // TS 24.282 clause 6.2.8.1.6
  else
    require_mcdata_profile({originator.empty() || originator == mcdata_user()
                                ? "allow-cancel-emergency-alert"
                                : "allow-cancel-emergency-alert-any-user"});
  mcdata::Info info;
  info.request_uri = group;
  info.alert = activate;
  info.client_id = client_id();
  if (!activate && !originator.empty() && originator != mcdata_user()) info.originated_by = originator;
  if (!mcdata_alias_in_use_.empty() && mcdata_aliases_active_.contains(mcdata_alias_in_use_))
    info.functional_alias = mcdata_alias_in_use_;
  std::vector<BodyPart> parts{{mcdata::info_mime, mcdata::info_xml(info)}};
  if (activate) {
    // TS 24.282 clause 16.2.1.1 steps 5 and 6: the location for the emergency alert trigger.
    parts.push_back({mcdata::location_mime, emergency_location_xml(mcdata_emergency_location(), true)});
    mcdata_emergency_ = true; // TS 24.282 clause 16.2.1.1 step 7
    mdea_ = 2;                // TS 24.282 clause 16.2.1.1 step 8
    mcdata_alert_group_ = group;
  } else if (info.originated_by.empty()) {
    mdea_ = 4; // TS 24.282 clause 16.2.1.2 step 7
  }
  const bool third_party = !info.originated_by.empty();
  mcdata_request(
      "", parts, {{"Accept-Contact", "*;+g.3gpp.icsi-ref=\"" + url_encode(mcdata::icsi) + "\";require;explicit"}},
      [this, activate, third_party, group](const SipResponse &response) {
        std::optional<bool> alert_state;
        if (response.content_type.starts_with(mcdata::info_mime) && !response.body.empty())
          alert_state = mcdata::parse_info(response.body).alert;
        mcdata_alert_last_result_ = {{"action", activate ? "alert" : "cancel"},
                                     {"group", group},
                                     {"status", response.status_code},
                                     {"warning", std::string(get_header(response.headers, "Warning"))},
                                     {"alert_ind", alert_state ? Json(*alert_state) : Json(nullptr)}};
        if (activate)
          mdea_ = response.status_code < 300 ? 3 : 1;
        else if (!third_party && response.status_code >= 300 && mdea_ == 4 && alert_state.value_or(true))
          mdea_ = 3;
        if (response.status_code >= 300) failure("MCData emergency alert: " + std::to_string(response.status_code));
      },
      "MESSAGE");
  event("mcdata_emergency_alert_requested",
        {{"activate", activate}, {"group", group}, {"originated_by", info.originated_by}});
}

//------------------------------------------------------------------------------
// TS 24.282 clauses 16.2.1.3 (notifications) and 16.2.1.2 (receipts carrying <alert-ind-rcvd>): a received
// SIP MESSAGE request of an MCData emergency alert or its cancellation.
void Client::mcdata_alert_incoming(const mcdata::Info &info, const std::vector<BodyPart> &parts) {
  if (!info.alert) throw std::runtime_error("emergency alert message without <alert-ind>");
  const bool alert = *info.alert;
  const auto group = info.calling_group;
  if (info.alert_received == true) {
    if (info.client_id != client_id()) throw std::runtime_error("emergency alert receipt for another client");
    bounded_push(mcdata_alert_receipts_, {{"group", group}, {"alert_ind", alert}});
    if (!alert) {
      mdea_ = 1;
      mcdata_emergency_ = false;
    } else if (mdea_ == 4 || mdea_ == 2)
      mdea_ = 3;
    return;
  }
  if (alert) {
    std::optional<EmergencyLocation> location;
    for (const auto &part : parts)
      if (part.content_type == mcdata::location_mime) location = parse_emergency_location(part.body);
    mcdata_alerts_received_[group][info.calling_user] = {
        {"organization", info.organization},
        {"functional_alias", info.functional_alias},
        {"latitude", location ? Json(location->latitude) : Json(nullptr)},
        {"longitude", location ? Json(location->longitude) : Json(nullptr)},
        {"count", mcdata_alerts_received_.value(group, Json::object())
                          .value(info.calling_user, Json::object())
                          .value("count", 0) +
                      1}};
  } else {
    const auto originator = info.originated_by.empty() ? info.calling_user : info.originated_by;
    if (mcdata_alerts_received_.contains(group)) mcdata_alerts_received_[group].erase(originator);
    if (originator == mcdata_user()) {
      mdea_ = 1;
      mcdata_emergency_ = false;
    } // TS 24.282 clause 16.2.1.3 step 2b
  }
  event("mcdata_emergency_alert_notification",
        {{"group", group}, {"from", info.calling_user}, {"alert", alert}, {"originated_by", info.originated_by}});
}

//------------------------------------------------------------------------------
// TS 24.282 clause 22.2.1.2: the functional alias status change procedure, a SIP PUBLISH request that activates,
// deactivates or takes over functional aliases of the MCData user. The first change subscribes to this user's status
// (clause 22.2.1.3) and waits for the answer, so that the NOTIFY with its p-id-fa gives the outcome.
void Client::mcdata_functional_alias(const std::string &action, const std::vector<std::string> &aliases) {
  if (!mcdata_authorized_) throw std::runtime_error("functional alias change requires MCData service authorisation");
  if (action == "takeover") require_mcdata_profile({"allow-takeover-functional-alias-other-user"});
  if (mcdata_alias_dialog_.empty() || !mcdata_alias_answered_) {
    mcdata_pending_alias_changes_.emplace_back(action, aliases);
    if (mcdata_alias_dialog_.empty()) mcdata_alias_subscribe();
    return;
  }
  mcdata_publish_functional_alias(action, aliases);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 22.2.1.2: the PUBLISH with the mcdata-info naming this user and the per-user PIDF (namespace
// urn:3gpp:ns:mcdataPresInfoFA:1.0) listing every alias wanted active; Expires 0 when none is. <take-over> is sent only
// when every alias to take over was offered as "take-over-possible" (step 6 d)); a take-over asked earlier goes as an
// activation and follows once offered. The answer only says the request was taken: the NOTIFY gives the outcome.
void Client::mcdata_publish_functional_alias(const std::string &action, const std::vector<std::string> &aliases) {
  auto wanted = mcdata_aliases_active_;
  for (const auto &alias : aliases) {
    if (action == "deactivate")
      wanted.erase(alias);
    else
      wanted.insert(alias);
  }
  const bool offered = std::all_of(aliases.begin(), aliases.end(),
                                   [&](const auto &alias) { return mcdata_takeover_possible_.contains(alias); });
  for (const auto &alias : aliases)
    if (action == "takeover" && !offered)
      mcdata_takeover_wanted_.insert(alias);
    else
      mcdata_takeover_wanted_.erase(alias);
  mcdata::Info info;
  info.request_uri = mcdata_user();
  std::vector<BodyPart> parts{{mcdata::info_mime, mcdata::info_xml(info)}};
  FunctionalAliasRequest request{
      mcdata_user(), client_id(), random_id(), {wanted.begin(), wanted.end()}, action == "takeover" && offered, true};
  parts.push_back({"application/pidf+xml", functional_alias_xml(request)});
  mcdata_fa_last_result_ = {{"action", action},
                            {"aliases", aliases},
                            {"status", 0},
                            {"p_id_fa", request.publish_id},
                            {"take_over", request.take_over},
                            {"outcomes", Json::object()}};
  mcdata_aliases_wanted_ = wanted;
  mcdata_request("presence", parts, {{"Expires", wanted.empty() ? "0" : "4294967295"}},
                 [this, action, aliases, pid = request.publish_id](const SipResponse &response) {
                   if (response.status_code < 200) return;
                   if (mcdata_fa_last_result_.value("p_id_fa", "") == pid)
                     mcdata_fa_last_result_["status"] = response.status_code;
                   if (response.status_code != 200) {
                     for (const auto &alias : aliases) mcdata_takeover_wanted_.erase(alias);
                     failure("MCData functional alias " + action + ": " + std::to_string(response.status_code));
                   }
                 });
  event("mcdata_functional_alias_requested",
        {{"action", action}, {"aliases", aliases}, {"take_over", request.take_over}});
}

//------------------------------------------------------------------------------
// TS 24.282 clause 22.2.1.3: the functional alias status determination procedure: a SUBSCRIBE to the presence event
// package with the mcdata-info naming this user and <request-type> "functional-alias-status-determination" (steps
// 2 and 2A), Expires 4294967295 for the state and its changes (step 4).
void Client::mcdata_alias_subscribe() {
  mcdata::Info info;
  info.request_uri = mcdata_user();
  info.request_type = "functional-alias-status-determination";
  mcdata_alias_answered_ = false;
  mcdata_alias_dialog_ = sip_.subscribe(
      mcdata_service(), "presence", 4294967295u,
      {{"P-Preferred-Service", mcdata::icsi}, {"Accept", "application/pidf+xml"}},
      [this](const SipResponse &response) {
        if (response.status_code < 200) return;
        mcdata_alias_subscription_result_ = response.status_code;
        mcdata_alias_answered_ = true;
        if (response.status_code >= 300) {
          mcdata_alias_dialog_.clear();
          failure("MCData functional alias subscription: " + std::to_string(response.status_code));
        }
        for (const auto &[action, aliases] : std::exchange(mcdata_pending_alias_changes_, {}))
          schedule([this, action, aliases] { mcdata_publish_functional_alias(action, aliases); });
      },
      mcdata::info_mime,
      mcdata_encrypt_xml({{mcdata::info_mime, mcdata::info_xml(info)}}, mcdata_xml_key()).front().body);
  event("mcdata_functional_alias_subscription_requested");
}

//------------------------------------------------------------------------------
// TS 24.282 clause 22.2.1.3 with the syntax of clause 22.3.1.2: the NOTIFY with this user's functional alias status.
// It replaces the aliases known active ("activated", or "deactivating" while still active) and those that may be
// taken over; the <p-id-fa> of the last status change gives its outcome per alias.
SipResponse Client::mcdata_alias_notify(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  if (asserted_identity(message) != mcdata_service()) {
    response.status_code = 403;
    return response;
  }
  for (const auto &part :
       message.parts.empty() ? std::vector<BodyPart>{{message.content_type, message.body}} : message.parts) {
    if (part.content_type != "application/pidf+xml") continue;
    FunctionalAliasStatus notified;
    try {
      notified = parse_functional_alias_status(part.body);
    } catch (const std::exception &) {
      response.status_code = 400;
      return response;
    }
    if (!notified.mcdata || notified.per_alias || notified.entity != mcdata_user()) {
      response.status_code = 400;
      return response;
    }
    std::set<std::string> active, possible;
    std::map<std::string, std::string> statuses;
    for (const auto &entry : notified.entries) {
      statuses[entry.alias] = entry.status;
      if (entry.status == "activated" || entry.status == "deactivating")
        active.insert(entry.alias);
      else if (entry.status == "take-over-possible")
        possible.insert(entry.alias);
    }
    mcdata_aliases_active_ = mcdata_aliases_wanted_ = active;
    mcdata_takeover_possible_ = possible;
    const bool handled = !notified.publish_id.empty() && mcdata_fa_last_result_.is_object() &&
                         mcdata_fa_last_result_.value("p_id_fa", "") == notified.publish_id;
    const auto take_over = settle_alias_outcomes(mcdata_fa_last_result_, statuses, handled, mcdata_takeover_wanted_);
    if (!take_over.empty()) schedule([this, take_over] { mcdata_publish_functional_alias("takeover", take_over); });
    event("mcdata_functional_alias_notified",
          {{"p_id_fa", notified.publish_id}, {"active", active}, {"take_over_possible", possible}});
  }
  if (get_header(message.headers, "Subscription-State").starts_with("terminated")) {
    mcdata_alias_dialog_.clear();
    mcdata_alias_answered_ = false;
  }
  return response;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 12.2.1.1 with clause 6.2.3.1: a disposition notification to the sender of a received message.
void Client::mcdata_notify(Json &received, mcdata::DispositionNotification type) {
  mcdata::Info info;
  info.calling_group = received.value("group", std::string{});
  mcdata::SdsNotification notice;
  notice.type = type;
  notice.date_time = static_cast<std::uint64_t>(std::time(nullptr));
  notice.conversation = mcdata::parse_uuid(received.at("conversation_id").get<std::string>());
  notice.message = mcdata::parse_uuid(received.at("message_id").get<std::string>());
  notice.sender = mcdata_user(); // Sender MCData user ID IE: identifies this notifier in an aggregate
  if (!received.at("application_id").is_null())
    notice.application_id = received.at("application_id").get<std::uint8_t>();
  mcdata_request(
      "",
      {{mcdata::info_mime, mcdata::info_xml(info)},
       {resource_lists_mime, private_call_resource_list(received.at("from").get<std::string>())},
       {mcdata::signalling_mime, mcdata::encode(notice)}},
      mcdata_sds_headers(),
      [this](const SipResponse &response) {
        if (response.status_code >= 300) failure("SDS notification: " + std::to_string(response.status_code));
      },
      "MESSAGE");
  received["notified"].push_back(static_cast<int>(type));
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.1.3: a display indication for a received message sends the read, or the delivered and
// read notification (stopping TDU1).
void Client::mcdata_display(const std::string &id) {
  const auto found = mcdata_received_.find(id);
  if (found == mcdata_received_.end()) throw std::runtime_error("no received SDS message with that ID");
  auto &received = found->second;
  if (received.value("displayed", false)) return;
  received["displayed"] = true;
  const auto requested = received.at("disposition").get<int>();
  if (requested == static_cast<int>(mcdata::DispositionRequest::Read))
    mcdata_notify(received, mcdata::DispositionNotification::Read);
  else if (requested == static_cast<int>(mcdata::DispositionRequest::DeliveryAndRead)) {
    const bool running = mcdata_tdu1_.erase(id) > 0;
    mcdata_notify(received,
                  running ? mcdata::DispositionNotification::DeliveredAndRead : mcdata::DispositionNotification::Read);
  }
}

} // namespace mcx
