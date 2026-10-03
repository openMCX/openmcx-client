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
    require_profile({"allow-activate-emergency-alert"}); // TS 24.282 clause 6.2.8.1.6
  else
    require_profile({originator.empty() || originator == mcdata_user() ? "allow-cancel-emergency-alert"
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
// deactivates or takes over functional aliases of the MCData user.
void Client::mcdata_functional_alias(const std::string &action, const std::vector<std::string> &aliases) {
  if (!mcdata_authorized_) throw std::runtime_error("functional alias change requires MCData service authorisation");
  auto wanted = mcdata_aliases_wanted_;
  for (const auto &alias : aliases) {
    if (action == "deactivate")
      wanted.erase(alias);
    else
      wanted.insert(alias);
  }
  if (action == "takeover") require_profile({"allow-takeover-functional-alias-other-user"});
  mcdata::Info info;
  info.request_uri = mcdata_user();
  std::vector<BodyPart> parts{{mcdata::info_mime, mcdata::info_xml(info)}};
  FunctionalAliasRequest request{mcdata_user(),        client_id(), random_id(), {wanted.begin(), wanted.end()},
                                 action == "takeover", true};
  parts.push_back({"application/pidf+xml", functional_alias_xml(request)});
  mcdata_request("presence", parts, {{"Expires", wanted.empty() ? "0" : "4294967295"}},
                 [this, action, aliases, wanted](const SipResponse &response) {
                   mcdata_fa_last_result_ = {
                       {"action", action}, {"aliases", aliases}, {"status", response.status_code}};
                   if (response.status_code != 200) {
                     failure("MCData functional alias " + action + ": " + std::to_string(response.status_code));
                     return;
                   }
                   mcdata_aliases_wanted_ = mcdata_aliases_active_ = wanted;
                 });
  event("mcdata_functional_alias_requested", {{"action", action}, {"aliases", aliases}});
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
