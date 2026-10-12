// MCData client: incoming SIP MESSAGE requests (SDS, notifications, FD, emergency and in-progress state
// notifications), the MCData user commands and the MCData status (member functions of Client).
//
// Specifications: TS 24.282 V19.7.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19), RFC 3428 (SIP MESSAGE).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.282 clauses 9.2.2.2.2 and 9.2.1.2 (SDS), 12.2.1.2 (notification), 10.2.4.2.2 (FD using HTTP), 16.2.1.3
// (emergency alert) and 6.3.7.1.2 (in-progress state of a group): a SIP MESSAGE request at the terminating client.
SipResponse Client::mcdata_incoming(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  const auto service = get_header(message.headers, "P-Asserted-Service");
  if (asserted_identity(message) != mcdata_service() ||
      (service != mcdata::sds_icsi && service != mcdata::icsi && service != mcdata::fd_icsi)) {
    response.status_code = 403;
    return response;
  }
  const auto parts = message.parts.empty() ? parse_body_parts(message.content_type, message.body) : message.parts;
  if (auto handled = mcdata_group_state_received(parts)) return *handled;
  if (service == mcdata::icsi) {
    response.status_code = 403;
    return response;
  }
  if (auto handled = mcdata_discovery_received(parts)) return *handled;
  const BodyPart *info_part = nullptr, *signalling = nullptr, *data = nullptr;
  std::vector<const BodyPart *> signalling_parts;
  for (const auto &part : parts) {
    if (part.content_type == mcdata::info_mime)
      info_part = &part;
    else if (part.content_type == mcdata::signalling_mime)
      signalling_parts.push_back(signalling = &part);
    else if (part.content_type == mcdata::payload_mime)
      data = &part;
  }
  if (!info_part || !signalling) {
    response.status_code = 400;
    return response;
  }
  const auto info = mcdata::parse_info(info_part->body);
  if (info.request_uri != mcdata_user()) {
    response.status_code = 403;
    return response;
  }
  // TS 24.282 clauses 10.2.4.2.2 and 12.4.4: file distribution over HTTP.
  switch (mcdata::message_type(signalling->body)) {
  case mcdata::MessageType::FdSignalling: return mcdata_fd_request_received(parts, *signalling, info);
  case mcdata::MessageType::FdNotification: return mcdata_fd_notifications_received(signalling_parts, info);
  case mcdata::MessageType::FdHttpTermination: return mcdata_fd_termination_received(message, *signalling, info);
  case mcdata::MessageType::FdNetworkNotification: return mcdata_fd_unavailable_received(*signalling, info);
  default: break;
  }
  if (service != mcdata::sds_icsi) {
    response.status_code = 403;
    return response;
  }
  if (mcdata::message_type(signalling->body) == mcdata::MessageType::SdsNotification)
    return mcdata_sds_notifications_received(signalling_parts, info);
  if (info.calling_user.empty() || signalling_parts.size() != 1) {
    response.status_code = 403;
    return response;
  }
  if (!data) {
    response.status_code = 400;
    return response;
  }
  return mcdata_sds_received(parts, *data, *signalling, info);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.3.7.1.2 and clause 16.2.1.3: an MCData information part announcing the in-progress emergency
// or imminent peril state of a group, or an emergency alert; empty when the MESSAGE carries neither.
std::optional<SipResponse> Client::mcdata_group_state_received(const std::vector<BodyPart> &parts) {
  SipResponse response;
  response.status_code = 200;
  for (const auto &part : parts)
    if (part.content_type == mcdata::info_mime) {
      const auto alert_info = mcdata::parse_info(part.body);
      // TS 24.282 clause 6.3.7.1.2: the in-progress emergency or imminent peril state of a group,
      // sent to its affiliated members that are not participating.
      if (!alert_info.calling_group.empty() && (alert_info.emergency || alert_info.imminent_peril)) {
        if (alert_info.request_uri != mcdata_user()) {
          response.status_code = 403;
          return response;
        }
        mcdata_group_priority_received(alert_info, false); // not a session of this client
        if (alert_info.alert == true) mcdata_alert_incoming(alert_info, parts);
        bounded_push(mcdata_group_notifications_, {{"group", alert_info.calling_group},
                                                   {"from", alert_info.calling_user},
                                                   {"emergency", mdeg_ == 2},
                                                   {"imminent", mdig_ == 2}});
        event("mcdata_in_progress_state_notified", {{"group", alert_info.calling_group},
                                                    {"from", alert_info.calling_user},
                                                    {"emergency", mdeg_ == 2},
                                                    {"imminent", mdig_ == 2}});
        return response;
      }
      // "SIP MESSAGE request for emergency notification" (TS 24.282 clause 6.2.1.1).
      if (alert_info.alert) {
        if (alert_info.request_uri != mcdata_user() || alert_info.calling_group.empty()) {
          response.status_code = 403;
          return response;
        }
        mcdata_alert_incoming(alert_info, parts);
        return response;
      }
    }
  return std::nullopt;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 10.2.1.3: "SIP MESSAGE request for absolute URI discovery response"; empty when the MESSAGE
// is not one.
std::optional<SipResponse> Client::mcdata_discovery_received(const std::vector<BodyPart> &parts) {
  SipResponse response;
  response.status_code = 200;
  for (const auto &part : parts)
    if (part.content_type == mcdata::info_mime) {
      const auto discovery = mcdata::parse_info(part.body);
      if (discovery.request_type == "msf-disc-res") {
        if (!discovery.controller_psi.empty()) mcdata_discovered_content_server_ = discovery.controller_psi;
        mcdata_discovery_result_ = {
            {"group", discovery.calling_group}, {"status", 200}, {"warning", ""}, {"uri", discovery.controller_psi}};
        event("mcdata_media_storage_discovered",
              {{"uri", discovery.controller_psi}, {"group", discovery.calling_group}});
        return response;
      }
    }
  return std::nullopt;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 10.2.4.2.2: an FD request using HTTP at the terminating client, with the PCK of a protected
// one-to-one file (TS 33.180 clause 8.3).
SipResponse Client::mcdata_fd_request_received(const std::vector<BodyPart> &parts, const BodyPart &signalling,
                                               const mcdata::Info &info) {
  SipResponse response;
  response.status_code = 200;
  // TS 33.180 clause 8.3: the PCK that protects a one-to-one file comes with the request.
  for (const auto &part : parts)
    if (part.content_type == "application/mikey") {
      try {
        mcdata_accept_pck(part.body, info.calling_user);
      } catch (const std::exception &error) {
        ++mcdata_protection_failures_;
        event("mcdata_pck_rejected", {{"from", info.calling_user}, {"reason", error.what()}});
        response.status_code = 606;
        response.headers = {{"Warning", "399 mcx \"136 authentication of the MIKEY-SAKKE I_MESSAGE failed\""}};
        return response;
      }
    }
  // TS 24.282 clause 10.2.1.2.1: exactly one FILEURL payload, otherwise the message is ignored.
  mcdata::FdSignalling request;
  try {
    request = mcdata::decode_fd_signalling(signalling.body);
  } catch (const std::exception &error) {
    event("mcdata_file_request_ignored", {{"reason", error.what()}});
    return response;
  }
  mcdata_file_incoming(info, request);
  return response;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 12.2.1.2: FD notifications, one per signalling body: an aggregated group notification
// (TS 24.282 clause 12.2.3 step 15c) has several, each naming its notifier in the Sender IE.
SipResponse Client::mcdata_fd_notifications_received(const std::vector<const BodyPart *> &signalling_parts,
                                                     const mcdata::Info &info) {
  SipResponse response;
  response.status_code = 200;
  std::vector<mcdata::FdNotification> notices;
  for (const auto *body : signalling_parts) notices.push_back(mcdata::decode_fd_notification(body->body));
  const auto senders = client_rules::notifiers(notices, info.calling_user, info.calling_group);
  if (!senders) {
    response.status_code = 403;
    return response;
  }
  for (std::size_t index = 0; index < notices.size(); ++index) {
    const auto &notice = notices[index];
    const auto &from = (*senders)[index];
    const auto id = mcdata::uuid_text(notice.message);
    bounded_push(mcdata_file_notifications_, {{"from", from},
                                              {"group", info.calling_group},
                                              {"type", static_cast<int>(notice.type)},
                                              {"message_id", id},
                                              {"aggregated", notices.size()}});
    event("mcdata_file_notification_received",
          {{"message_id", id}, {"type", static_cast<int>(notice.type)}, {"aggregated", notices.size()}});
  }
  return response;
}

//------------------------------------------------------------------------------
// The server's termination messages: the answer to a release (TS 24.282 clause 6.3.6.1,
// clause 10.2.4.4.2 step 17b), a transmission stopped by policy (clause 13.2.3.3.4), the
// intent to release (clause 13.2.4.5.2.1) and the answer to an extension request (clause 13.2.4.5.2.3).
SipResponse Client::mcdata_fd_termination_received(const SipMessage &message, const BodyPart &signalling,
                                                   const mcdata::Info &info) {
  SipResponse response;
  response.status_code = 200;
  const auto answer = mcdata::decode_fd_http_termination(signalling.body);
  const auto id = mcdata::uuid_text(answer.message);
  const auto reason = std::string(get_header(message.headers, "Reason"));
  std::string state;
  switch (answer.type) {
  case mcdata::TerminationType::Response:
    state = answer.release == mcdata::ReleaseResponse::Success ? "terminated" : "termination-failed";
    break;
  case mcdata::TerminationType::TransmissionStopped: state = "stopped"; break;
  case mcdata::TerminationType::IntentToRelease: state = "release-pending"; break;
  case mcdata::TerminationType::ExtensionResponse:
    state = answer.extension == mcdata::ExtensionResponse::Accepted ? "extended" : "extension-refused";
    break;
  default: break;
  }
  for (auto &entry : mcdata_files_sent_)
    if (entry.at("message_id") == id && !state.empty()) entry["state"] = state;
  bounded_push(mcdata_file_notifications_,
               {{"from", mcdata_service()},
                {"group", info.calling_group},
                {"termination", static_cast<int>(answer.type)},
                {"release", answer.release ? Json(static_cast<int>(*answer.release)) : Json(nullptr)},
                {"extension", answer.extension ? Json(static_cast<int>(*answer.extension)) : Json(nullptr)},
                {"reason", reason},
                {"message_id", id}});
  event("mcdata_file_termination_result",
        {{"message_id", id}, {"type", static_cast<int>(answer.type)}, {"state", state}, {"reason", reason}});
  return response;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 12.4.4: the file is no longer available to download.
SipResponse Client::mcdata_fd_unavailable_received(const BodyPart &signalling, const mcdata::Info &info) {
  SipResponse response;
  response.status_code = 200;
  const auto notice = mcdata::decode_fd_network_notification(signalling.body);
  const auto id = mcdata::uuid_text(notice.message);
  if (const auto found = mcdata_files_.find(id);
      found != mcdata_files_.end() && found->second.value("state", "") != "downloaded") {
    found->second["state"] = notice.type == mcdata::FdNetworkNotificationType::Expired ? "expired" : "deleted";
    mcdata_tdu2_.erase(id);
  }
  bounded_push(mcdata_file_notifications_, {{"from", mcdata_service()},
                                            {"group", info.calling_group},
                                            {"network_notification", static_cast<int>(notice.type)},
                                            {"message_id", id}});
  event("mcdata_file_unavailable", {{"message_id", id}, {"type", static_cast<int>(notice.type)}});
  return response;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 12.2.1.2, one notification per signalling body: an aggregated group notification
// (clause 12.2.3 step 15c) has several, each naming its notifier in the Sender IE.
SipResponse Client::mcdata_sds_notifications_received(const std::vector<const BodyPart *> &signalling_parts,
                                                      const mcdata::Info &info) {
  SipResponse response;
  response.status_code = 200;
  std::vector<mcdata::SdsNotification> notices;
  for (const auto *body : signalling_parts) notices.push_back(mcdata::decode_sds_notification(body->body));
  const auto senders = client_rules::notifiers(notices, info.calling_user, info.calling_group);
  if (!senders) {
    response.status_code = 403;
    return response;
  }
  for (std::size_t index = 0; index < notices.size(); ++index) {
    const auto &notice = notices[index];
    const auto &from = (*senders)[index];
    bounded_push(mcdata_notifications_, {{"from", from},
                                         {"group", info.calling_group},
                                         {"type", static_cast<int>(notice.type)},
                                         {"message_id", mcdata::uuid_text(notice.message)},
                                         {"conversation_id", mcdata::uuid_text(notice.conversation)},
                                         {"aggregated", notices.size()}});
    event("mcdata_notification_received",
          {{"from", from}, {"type", static_cast<int>(notice.type)}, {"aggregated", notices.size()}});
  }
  return response;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.2.2.2 and clause 9.2.1.2: a standalone SDS message at the terminating client: open its
// payload, discard what no known application takes, and render or store it.
SipResponse Client::mcdata_sds_received(const std::vector<BodyPart> &parts, const BodyPart &data,
                                        const BodyPart &signalling, const mcdata::Info &info) {
  SipResponse response;
  response.status_code = 200;
  const auto sds = mcdata::decode_sds_signalling(signalling.body);
  // TS 24.282 clause 9.2.2.2.2 step 3 and clause 6.6.3.2: a PCK sent with the message, then the
  // protected DATA PAYLOAD (bit 7 of its message type) decrypted end to end.
  std::string data_body = data.body;
  std::optional<std::uint32_t> dppk;
  bool authenticated = false;
  if (auto failed = mcdata_open_sds_payload(parts, info, data_body, dppk, authenticated)) return *failed;
  const auto payload = mcdata::decode_data_payload(data_body);
  const auto id = mcdata::uuid_text(sds.message);
  if (mcdata_received_.contains(id)) return response; // retransmitted request
  Json received = {
      {"message_id", id},
      {"conversation_id", mcdata::uuid_text(sds.conversation)},
      {"in_reply_to", sds.in_reply_to ? Json(mcdata::uuid_text(*sds.in_reply_to)) : Json(nullptr)},
      {"from", info.calling_user},
      {"group", info.calling_group},
      {"request_type", info.request_type},
      {"application_id", sds.application_id ? Json(*sds.application_id) : Json(nullptr)},
      {"disposition", static_cast<int>(sds.disposition)},
      {"displayed", false},
      {"notified", Json::array()},
      {"date_time", sds.date_time},
      // TS 24.282 clause 9.2.2.2.2 step 3A: the priority applied by the controlling function.
      {"used_priority", info.used_priority ? Json(*info.used_priority) : Json(nullptr)},
      // TS 24.282 clause 9.2.1.2 step 6c: the sender's functional alias and the alias this user was reached through.
      {"functional_alias", info.functional_alias},
      {"called_functional_alias", info.called_functional_alias},
      {"protected", dppk.has_value()},
      {"dppk_id", dppk ? Json(*dppk) : Json(nullptr)},
      {"authenticated", authenticated}};
  // TS 24.282 clause 9.2.1.2 steps 6 and 7: user content is rendered; application content goes to a
  // known application and is discarded otherwise.
  const auto applications = cfg_.at("mcdata").value("applications", std::vector<unsigned>{});
  if (sds.application_id &&
      std::find(applications.begin(), applications.end(), *sds.application_id) == applications.end()) {
    ++mcdata_discarded_;
    event("mcdata_sds_discarded", {{"message_id", id}, {"application_id", *sds.application_id}});
    return response;
  }
  if (mcdata_storage_limit_ && mcdata_received_.size() >= *mcdata_storage_limit_) {
    // TS 24.282 clause 12.2.1.1: tell the participating function, which can store it for re-delivery.
    mcdata_notify(received, mcdata::DispositionNotification::Undelivered);
    bounded_push(mcdata_undelivered_, {{"message_id", id}, {"from", info.calling_user}, {"group", info.calling_group}});
    event("mcdata_sds_undelivered", {{"message_id", id}, {"from", info.calling_user}});
    return response;
  }
  if (!mcdata_render_enhanced_status(payload, id, info)) return response;
  mcdata_deliver_sds(received, sds, payload, id, info);
  return response;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.2.2.2 step 3 and clause 6.6.3.2: accept the PCK sent with the message, verify a signed
// DATA PAYLOAD and decrypt a protected one end to end; the failure response, or empty when the payload is open.
std::optional<SipResponse> Client::mcdata_open_sds_payload(const std::vector<BodyPart> &parts, const mcdata::Info &info,
                                                           std::string &data_body, std::optional<std::uint32_t> &dppk,
                                                           bool &authenticated) {
  SipResponse response;
  response.status_code = 200;
  for (const auto &part : parts)
    if (part.content_type == "application/mikey") {
      try {
        mcdata_accept_pck(part.body, info.calling_user);
      } catch (const std::exception &error) {
        ++mcdata_protection_failures_;
        event("mcdata_pck_rejected", {{"from", info.calling_user}, {"reason", error.what()}});
        response.status_code = 606;
        response.headers = {{"Warning", "399 mcx \"136 authentication of the MIKEY-SAKKE I_MESSAGE failed\""}};
        return response;
      }
    }
  if (!data_body.empty() && (static_cast<std::uint8_t>(data_body[0]) & 0x80)) {
    try {
      data_body = mcdata_verify(data_body, info.calling_user);
      authenticated = true;
    } catch (const std::exception &error) {
      ++mcdata_signature_failures_;
      event("mcdata_signature_rejected", {{"from", info.calling_user}, {"reason", error.what()}});
      response.status_code = 606;
      return response;
    }
  }
  if (!data_body.empty() && (static_cast<std::uint8_t>(data_body[0]) & 0x40)) {
    try {
      dppk =
          keying::payload_key_id(std::span(reinterpret_cast<const std::uint8_t *>(data_body.data()), data_body.size()));
      data_body = mcdata_open(data_body, info);
    } catch (const std::exception &error) {
      ++mcdata_protection_failures_;
      event("mcdata_payload_undecryptable",
            {{"from", info.calling_user}, {"group", info.calling_group}, {"reason", error.what()}});
      response.status_code = 606;
      return response;
    }
  }
  return std::nullopt;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 14.2.1.2: an enhanced status is rendered when its "id" matches an operational
// value of the group, and discarded otherwise; false when the message is discarded.
bool Client::mcdata_render_enhanced_status(const mcdata::DataPayload &payload, const std::string &id,
                                           const mcdata::Info &info) {
  for (const auto &part : payload.payloads) {
    if (part.type != mcdata::ContentType::EnhancedStatus) continue;
    std::optional<std::string> value;
    try {
      if (!info.calling_group.empty())
        for (const auto &[known, text] : mcdata_group_status_values(info.calling_group, false).values)
          if (known == part.data) value = text;
    } catch (const std::exception &error) {
      event("mcdata_enhanced_status_unavailable", {{"group", info.calling_group}, {"reason", error.what()}});
    }
    if (!value) {
      ++mcdata_discarded_;
      event("mcdata_enhanced_status_discarded", {{"message_id", id}, {"group", info.calling_group}, {"id", part.data}});
      return false;
    }
    bounded_push(mcdata_enhanced_status_, {{"message_id", id},
                                           {"from", info.calling_user},
                                           {"group", info.calling_group},
                                           {"id", part.data},
                                           {"value", *value}});
    event("mcdata_enhanced_status_received",
          {{"from", info.calling_user}, {"group", info.calling_group}, {"value", *value}});
  }
  return true;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.1.2 and clause 9.2.1.3: keep the received message (the last 50) and answer its disposition
// request.
void Client::mcdata_deliver_sds(Json &received, const mcdata::SdsSignalling &sds, const mcdata::DataPayload &payload,
                                const std::string &id, const mcdata::Info &info) {
  received["delivered_to"] = sds.application_id ? "application" : "user";
  Json contents = Json::array();
  for (const auto &part : payload.payloads)
    contents.push_back({{"content_type", static_cast<int>(part.type)},
                        {"text", part.type == mcdata::ContentType::Text ? Json(part.data) : Json(nullptr)},
                        {"bytes", part.data.size()}});
  received["payloads"] = contents;
  mcdata_received_[id] = received;
  mcdata_received_order_.push_back(id);
  while (mcdata_received_order_.size() > 50) {
    mcdata_received_.erase(mcdata_received_order_.front());
    mcdata_received_order_.pop_front();
  }
  // TS 24.282 clause 9.2.1.3: disposition requests.
  if (sds.disposition == mcdata::DispositionRequest::Delivery)
    schedule([this, id] {
      if (mcdata_received_.contains(id))
        mcdata_notify(mcdata_received_.at(id), mcdata::DispositionNotification::Delivered);
    });
  else if (sds.disposition == mcdata::DispositionRequest::DeliveryAndRead)
    // TS 24.282 table F.2.3-1: TDU1 (delivery and read), default 120 ms, configurable (mcdata.tdu1_ms).
    mcdata_tdu1_[id] = monotonic_ms() + cfg_.at("mcdata").value("tdu1_ms", 120u);
  event("mcdata_sds_received", {{"message_id", id},
                                {"from", info.calling_user},
                                {"group", info.calling_group},
                                {"disposition", static_cast<int>(sds.disposition)}});
}

//------------------------------------------------------------------------------
// TS 24.282 clauses 10.2.1.2.3 and 9.2.1.3: the MCData timers TDU2 (FD non-mandatory download) and TDU1 (delivery
// and read), after the media plane and IP connectivity loops.
void Client::mcdata_tick() {
  mcdata_media_tick();
  ipcon_tick();
  const auto now = monotonic_ms();
  for (auto it = mcdata_tdu2_.begin(); it != mcdata_tdu2_.end();) {
    // TDU2 expiry: the offer can still be accepted or rejected, not deferred (TS 24.282 clause 10.2.1.2.3 NOTE 3).
    if (now < it->second) {
      ++it;
      continue;
    }
    event("mcdata_file_offer_timer_expired", {{"message_id", it->first}});
    it = mcdata_tdu2_.erase(it);
  }
  for (auto it = mcdata_tdu1_.begin(); it != mcdata_tdu1_.end();) {
    if (now < it->second) {
      ++it;
      continue;
    }
    // TDU1 expiry (TS 24.282 clause 9.2.1.3): send DELIVERED now; READ follows the display indication.
    if (mcdata_received_.contains(it->first))
      mcdata_notify(mcdata_received_.at(it->first), mcdata::DispositionNotification::Delivered);
    it = mcdata_tdu1_.erase(it);
  }
}

//------------------------------------------------------------------------------
// The MCData user commands, each starting the MCData client procedure of TS 24.282 named with it:
//   sds [delivery|read|both] [app=<n>] [priority=<n>] <MCData ID> <text>
//   sds-group [delivery|read|both] [app=<n>] [priority=<n>] <group ID> <text>
//   sds-adhoc [delivery|read|both] [app=<n>] [priority=<n>] <ID,ID,...|criteria=<criteria>|group=<ad hoc group>> <text>
//   sds-fa [delivery|read|both] [app=<n>] [priority=<n>] <functional alias> <text>
//   mcdata-fa activate|takeover|deactivate <alias>...   mcdata-fa use <alias>|none
//   sds-reply <message ID> <text>     (same conversation, InReplyTo)
//   sds-display <message ID>          (display indication, TS 24.282 clause 9.2.1.3)
//   mcdata-affiliate | mcdata-deaffiliate
//   mcdata-storage full|unlimited|<n>   (received messages the client can hold)
//   mcdata-location request|configure ...  (TS 24.282 clauses 17.3.2.2 and 17.3.3.2)
//   mcdata-msgstore <operation> ...        (TS 24.282 clause 21.2)
//   mcdata-msgstore-ctrl enable|disable ... (TS 24.282 clause 21.3.2)
void Client::mcdata_command(const std::string &cmd) {
  if (!mcdata_enabled()) throw std::runtime_error("MCData is not configured");
  std::istringstream words(cmd);
  std::string verb;
  words >> verb;
  if (verb == "mcdata-affiliate" || verb == "mcdata-deaffiliate") return mcdata_affiliate(verb == "mcdata-affiliate");
  if (verb == "mcdata-location") return mcdata_location_command(cmd);
  if (verb == "mcdata-msgstore") return msgstore_command(words);
  if (verb == "mcdata-msgstore-ctrl") return msgstore_control_command(words);
  if (verb == "fd-send") return mcdata_fd_send_command(words);
  if (verb == "sds-session") return mcdata_sds_session_command(words);
  if (verb == "fd-media") return mcdata_fd_media_command(words);
  if (verb == "mcdata-release") return mcdata_release_command(words);
  if (verb == "sds-status") return mcdata_sds_status_command(words);
  if (verb == "sds-status-values") return mcdata_sds_status_values_command(words);
  if (verb == "ipcon") return mcdata_ipcon_command(words);
  if (verb == "ipcon-ping") return mcdata_ipcon_ping_command(words);
  if (verb == "sds-media") return mcdata_sds_media_command(words);
  if (verb == "fd-discover") {
    // fd-discover [group=<id>]   (TS 24.282 clause 10.2.1.3)
    std::string word;
    words >> word;
    return mcdata_discover_content_server(word.starts_with("group=") ? word.substr(6) : std::string{});
  }
  if (verb == "fd-release-intent") return mcdata_fd_release_intent_command(words);
  if (verb == "fd-extension-response") return mcdata_fd_extension_response_command(words);
  if (verb == "fd-cancel" || verb == "fd-release" || verb == "fd-extend") return mcdata_fd_cancel_command(verb, words);
  if (verb == "fd-accept" || verb == "fd-reject" || verb == "fd-defer") {
    std::string id;
    words >> id;
    if (id.empty()) throw std::runtime_error("usage: " + verb + " <message ID>");
    return mcdata_file_decision(id, verb.substr(3));
  }
  if (verb == "mcdata-affiliation-status") return mcdata_affiliation_status_command(words);
  if (verb == "mcdata-storage") return mcdata_storage_command(words);
  if (verb == "sds-display") {
    std::string id;
    words >> id;
    return mcdata_display(id);
  }
  if (verb == "sds-reply") return mcdata_sds_reply_command(words);
  if (verb == "mcdata-alert") return mcdata_alert_command(words);
  if (verb == "mcdata-fa") return mcdata_fa_command(words);
  mcdata_sds_command(verb, words);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 10.2.4.2.1: FD using HTTP, originating:
// fd-send [mandatory] [completed] [app=<n>] [available=<seconds>] [url=<file URL>] <MCData ID|group=<id>|fa=<alias>> <path>
void Client::mcdata_fd_send_command(std::istringstream &words) {
  FdRequest request;
  std::string word;
  while (words >> word) {
    if (word == "mandatory")
      request.mandatory = true;
    else if (word == "completed")
      request.completion = true;
    else if (word.starts_with("app="))
      request.application = static_cast<std::uint8_t>(std::stoul(word.substr(4)));
    else if (word.starts_with("available="))
      request.available_for = std::stoull(word.substr(10));
    // url=<file URL> sends an FD request for a file that is already stored
    // (or, for negative tests, one that is not).
    else if (word.starts_with("url="))
      request.url_override = word.substr(4);
    // ref=<object ID>: a file of the user's message store, uploaded by reference (TS 24.282 clause 10.2.2.1).
    else if (word.starts_with("ref="))
      request.store_object = word.substr(4);
    else
      break;
  }
  if (word.starts_with("group=")) {
    request.group = true;
    request.target = word.substr(6);
  } else if (word.starts_with("fa=")) {
    request.to_alias = true;
    request.target = word.substr(3);
  } else
    request.target = word;
  words >> request.path;
  if (request.target.empty() || (request.path.empty() && request.url_override.empty() && request.store_object.empty()))
    throw std::runtime_error(
        "usage: fd-send [mandatory] [completed] [app=<n>] [available=<s>] [url=<file URL>] <target> <path>");
  return mcdata_send_file(request);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.4.2.3 (SDS session) and clause 13.2.2.2 (release over the media plane):
// sds-session <MCData ID|group=<id>|adhoc=<ad hoc group>> | send [delivery|read] <text> | close
void Client::mcdata_sds_session_command(std::istringstream &words) {
  std::string word;
  words >> word;
  if (word == "send") {
    std::string next, text;
    words >> next;
    auto disposition = mcdata::DispositionRequest::None;
    if (next == "delivery")
      disposition = mcdata::DispositionRequest::Delivery;
    else if (next == "read")
      disposition = mcdata::DispositionRequest::Read;
    if (disposition == mcdata::DispositionRequest::None) text = next;
    std::string rest;
    std::getline(words, rest);
    if (!rest.empty() && rest.front() == ' ') rest.erase(0, 1);
    if (!rest.empty()) text = text.empty() ? rest : text + " " + rest;
    if (text.empty()) throw std::runtime_error("usage: sds-session send [delivery|read] <text>");
    return mcdata_session_send(text, disposition);
  }
  if (word == "close") {
    if (!mcdata_media_) throw std::runtime_error("no MCData SDS session is open");
    std::string scope;
    words >> scope;
    mcdata_media_result_["state"] = "closed";
    // TS 24.282 clause 13.2.2.2.4.3 step 3A: "close all" asks an ad hoc group communication to end
    // for every participant, which the server grants to an authorised user.
    if (scope == "all") return mcdata_media_close({{"Reason", "SIP;cause=200;text=\"User requested release\""}});
    return mcdata_media_close();
  }
  if (word == "emergency" || word == "imminent") {
    std::string action;
    words >> action;
    return mcdata_session_priority(word == "emergency", action != "cancel");
  }
  if (word.empty())
    throw std::runtime_error("usage: sds-session <MCData ID|group=<id>> [emergency|imminent] | send ... | "
                             "emergency [cancel] | imminent [cancel] | close");
  std::string option, extra;
  words >> option >> extra;
  if (!option.empty() && option != "emergency" && option != "imminent")
    throw std::runtime_error("usage: sds-session <MCData ID|group=<id>> [emergency [alert]|imminent]");
  if (!extra.empty() && (extra != "alert" || option != "emergency"))
    throw std::runtime_error("an emergency alert accompanies an emergency communication");
  return mcdata_open_session(word.starts_with("group=") ? word.substr(6) : word, word.starts_with("group="),
                             option.empty() ? "normal" : option, extra == "alert");
}

//------------------------------------------------------------------------------
// fd-media [mandatory] [completed] <MCData ID|group=<id>|adhoc=<ad hoc group>> <path>   (TS 24.282 clauses 10.2.5 and 24.2.2)
void Client::mcdata_fd_media_command(std::istringstream &words) {
  std::string word, target, path;
  bool mandatory = false, completion = false;
  while (words >> word) {
    if (word == "mandatory" && target.empty())
      mandatory = true;
    else if (word == "completed" && target.empty())
      completion = true;
    else if (target.empty())
      target = word;
    else if (path.empty())
      path = word;
    else
      throw std::runtime_error("usage: fd-media [mandatory] [completed] <MCData ID|group=<id>> <path>");
  }
  if (target.empty() || path.empty())
    throw std::runtime_error("usage: fd-media [mandatory] [completed] <MCData ID|group=<id>> <path>");
  const bool group = target.starts_with("group=");
  return mcdata_send_file_media(group ? target.substr(6) : target, group, path, mandatory, completion);
}

//------------------------------------------------------------------------------
// sds-status <group> <operational value id> [force]   (enhanced status, TS 24.282 clause 14.2.1.1)
void Client::mcdata_sds_status_command(std::istringstream &words) {
  std::string group, id, force;
  words >> group >> id >> force;
  if (group.empty() || id.empty()) throw std::runtime_error("usage: sds-status <group> <operational value id> [force]");
  return mcdata_send_enhanced_status(group, id, force == "force");
}

//------------------------------------------------------------------------------
// TS 24.282 clause 14.2.1.1: sds-status-values <group>: the group's operational values, into status.json
void Client::mcdata_sds_status_values_command(std::istringstream &words) {
  std::string group;
  words >> group;
  if (group.empty()) throw std::runtime_error("usage: sds-status-values <group>");
  const auto &values = mcdata_group_status_values(group, true);
  Json listed = Json::array();
  for (const auto &[id, text] : values.values) listed.push_back({{"id", id}, {"value", text}});
  status_["mcdata_enhanced_status_values"][group] = {{"allowed", values.allowed}, {"values", listed}};
}

//------------------------------------------------------------------------------
// ipcon <MCData ID|fa=<functional alias>> | ipcon close   (TS 24.282 clause 20)
void Client::mcdata_ipcon_command(std::istringstream &words) {
  std::string target;
  words >> target;
  if (target == "close") {
    if (!ipcon_socket_) throw std::runtime_error("no IP connectivity session is open");
    return ipcon_close();
  }
  if (target.empty()) throw std::runtime_error("usage: ipcon <MCData ID|fa=<functional alias>> | ipcon close");
  return target.starts_with("fa=") ? ipcon_open(target.substr(3), true) : ipcon_open(target, false);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 20: ipcon-ping <destination IPv4 address> [count] [size]   (the built-in IP application)
void Client::mcdata_ipcon_ping_command(std::istringstream &words) {
  std::string destination;
  unsigned count = 3;
  std::size_t size = 32;
  words >> destination;
  if (!(words >> count)) count = 3;
  if (!(words >> size)) size = 32;
  if (destination.empty()) throw std::runtime_error("usage: ipcon-ping <destination address> [count] [size]");
  return ipcon_ping(destination, count, size);
}

//------------------------------------------------------------------------------
// sds-media <MCData ID|group=<id>|adhoc=<ad hoc group>> <text>   (standalone SDS over the media plane, TS 24.282 clause 9.2.3)
// An ad hoc group is adhoc=ID,ID,..., adhoc=criteria=<criteria> or adhoc=group=<ad hoc group>.
void Client::mcdata_sds_media_command(std::istringstream &words) {
  std::string target, text;
  words >> target;
  std::getline(words, text);
  if (!text.empty() && text.front() == ' ') text.erase(0, 1);
  if (target.empty() || text.empty()) throw std::runtime_error("usage: sds-media <MCData ID|group=<id>> <text>");
  const bool group = target.starts_with("group=");
  return mcdata_send_media_sds(group ? target.substr(6) : target, text, group);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 13.2.6.3.2.1: an authorised user announces the release of another user's file:
// fd-release-intent <message ID> [reason]
void Client::mcdata_fd_release_intent_command(std::istringstream &words) {
  std::string id, reason;
  words >> id;
  std::getline(words >> std::ws, reason);
  if (id.empty()) throw std::runtime_error("usage: fd-release-intent <message ID> [reason]");
  return mcdata_cancel_file(id, mcdata::TerminationType::IntentToRelease, {}, reason);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 13.2.6.3.2.3: the authorised user answers the originator's extension request:
// fd-extension-response accept|reject <message ID>
void Client::mcdata_fd_extension_response_command(std::istringstream &words) {
  std::string answer, id;
  words >> answer >> id;
  if ((answer != "accept" && answer != "reject") || id.empty())
    throw std::runtime_error("usage: fd-extension-response accept|reject <message ID>");
  return mcdata_cancel_file(id, mcdata::TerminationType::ExtensionResponse,
                            answer == "accept" ? mcdata::ExtensionResponse::Accepted
                                               : mcdata::ExtensionResponse::Rejected);
}

//------------------------------------------------------------------------------
// fd-cancel releases this client's own distribution (TS 24.282 clause 6.2.2.4), fd-release
// another user's as an authorised user (clause 13.2.5.3.2.1) and fd-extend asks to
// keep a file available after the server's intent to release (clause 13.2.4.5.2.2).
void Client::mcdata_fd_cancel_command(const std::string &verb, std::istringstream &words) {
  std::string id;
  words >> id;
  if (id.empty()) throw std::runtime_error("usage: " + verb + " <message ID>");
  return mcdata_cancel_file(id, verb == "fd-release"  ? mcdata::TerminationType::AuthorisedUserRequest
                                : verb == "fd-extend" ? mcdata::TerminationType::ExtensionRequest
                                                      : mcdata::TerminationType::Request);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 8.2.3 and clause 8.2.2: the MCData affiliation status:
// mcdata-affiliation-status fetch [<MCData ID>] | unsubscribe | subscribe
void Client::mcdata_affiliation_status_command(std::istringstream &words) {
  std::string action, target;
  words >> action >> target;
  if (action == "fetch") {
    mcdata_fetch_result_ = nullptr;
    mcdata_fetch_ = mcdata_status_subscribe(target.empty() ? mcdata_user() : target, true);
  } else if (action == "unsubscribe" && !mcdata_subscription_.empty()) {
    sip_.request_in_dialog(mcdata_subscription_, "SUBSCRIBE", "", "",
                           {{"Event", "presence"}, {"Expires", "0"}, {"Accept", "application/pidf+xml"}});
  } else if (action == "subscribe") {
    mcdata_subscribe();
  } else {
    throw std::runtime_error("usage: mcdata-affiliation-status fetch [<MCData ID>] | unsubscribe | subscribe");
  }
}

//------------------------------------------------------------------------------
// TS 24.282 clause 12.2.1.1: mcdata-storage full|unlimited|<n>, the received messages the client can hold (a full
// store answers "undelivered").
void Client::mcdata_storage_command(std::istringstream &words) {
  std::string value;
  words >> value;
  if (value == "unlimited")
    mcdata_storage_limit_.reset();
  else if (value == "full")
    mcdata_storage_limit_ = mcdata_received_.size();
  else if (!value.empty() && value.find_first_not_of("0123456789") == std::string::npos)
    mcdata_storage_limit_ = std::stoul(value);
  else
    throw std::runtime_error("usage: mcdata-storage full|unlimited|<n>");
  event("mcdata_storage_limit", {{"limit", mcdata_storage_limit_ ? Json(*mcdata_storage_limit_) : Json(nullptr)}});
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.1.1: sds-reply <message ID> <text>, an SDS in the same conversation (InReplyTo).
void Client::mcdata_sds_reply_command(std::istringstream &words) {
  std::string id, text;
  words >> id;
  std::getline(words >> std::ws, text);
  const auto found = mcdata_received_.find(id);
  if (found == mcdata_received_.end() || text.empty())
    throw std::runtime_error("usage: sds-reply <received message ID> <text>");
  const auto &original = found->second;
  const auto group = original.value("group", std::string{});
  const auto type = original.value("request_type", std::string{});
  // A reply goes to the sender, the group, or the same ad hoc group.
  SdsRequest reply;
  reply.type = type == "ad-hoc-group-sds" ? type : group.empty() ? "one-to-one-sds" : "group-sds";
  reply.target = type == "ad-hoc-group-sds" ? "group=" + group
                 : group.empty()            ? original.at("from").get<std::string>()
                                            : group;
  reply.text = text;
  reply.conversation = mcdata::parse_uuid(original.at("conversation_id").get<std::string>());
  reply.in_reply_to = mcdata::parse_uuid(id);
  return mcdata_send_sds(reply);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 16.2.1.1 and clause 16.2.1.2: the MCData emergency alert:
// mcdata-alert [group=<id>]   or   mcdata-alert cancel [group=<id>] [<originator>]
void Client::mcdata_alert_command(std::istringstream &words) {
  std::string word, group, originator;
  bool cancel = false;
  while (words >> word) {
    if (word == "cancel")
      cancel = true;
    else if (word.starts_with("group="))
      group = word.substr(6);
    else if (cancel && originator.empty())
      originator = word;
    else
      throw std::runtime_error("usage: mcdata-alert [group=<id>] | mcdata-alert cancel [group=<id>] [<originator>]");
  }
  return mcdata_alert(!cancel, group, originator);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 22.2.1.2: mcdata-fa activate|takeover|deactivate <alias>...   mcdata-fa use <alias>|none
void Client::mcdata_fa_command(std::istringstream &words) {
  std::string action, alias;
  words >> action;
  std::vector<std::string> aliases;
  while (words >> alias) aliases.push_back(alias);
  if (action == "use") {
    if (aliases.size() != 1) throw std::runtime_error("usage: mcdata-fa use <alias>|none");
    mcdata_alias_in_use_ = aliases.front() == "none" ? std::string{} : aliases.front();
    return;
  }
  if ((action != "activate" && action != "takeover" && action != "deactivate") || aliases.empty())
    throw std::runtime_error("usage: mcdata-fa activate|takeover|deactivate <alias>...");
  return mcdata_functional_alias(action, aliases);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.1.1 and clause 9.2.2.2.1: send a standalone SDS over the signalling control plane:
//   sds|sds-group|sds-adhoc|sds-fa [delivery|read|both] [app=<n>] [priority=<n>] <target> <text>
void Client::mcdata_sds_command(const std::string &verb, std::istringstream &words) {
  if (verb != "sds" && verb != "sds-group" && verb != "sds-adhoc" && verb != "sds-fa")
    throw std::runtime_error("unknown MCData command");
  auto disposition = mcdata::DispositionRequest::None;
  std::optional<std::uint8_t> application;
  std::optional<unsigned> priority;
  bool tamper = false, tamper_signature = false;
  std::string word, target, text;
  while (words >> word) {
    if (word == "delivery")
      disposition = mcdata::DispositionRequest::Delivery;
    else if (word == "read")
      disposition = mcdata::DispositionRequest::Read;
    else if (word == "both")
      disposition = mcdata::DispositionRequest::DeliveryAndRead;
    else if (word.starts_with("app="))
      application = static_cast<std::uint8_t>(std::stoul(word.substr(4)));
    else if (word.starts_with("priority="))
      priority = static_cast<unsigned>(std::stoul(word.substr(9)));
    else if (word == "tamper-key")
      tamper = true;
    else if (word == "tamper-sig")
      tamper_signature = true;
    else {
      target = word;
      break;
    }
  }
  std::getline(words >> std::ws, text);
  if (target.empty() || text.empty())
    throw std::runtime_error("usage: " + verb + " [delivery|read|both] [app=<n>] [priority=<n>] <target> <text>");
  SdsRequest request;
  request.type = verb == "sds-group" ? "group-sds" : verb == "sds-adhoc" ? "ad-hoc-group-sds" : "one-to-one-sds";
  request.target = target;
  request.text = text;
  request.disposition = disposition;
  request.application = application;
  request.priority = priority;
  request.to_alias = verb == "sds-fa"; // one-to-one SDS to a functional alias
  request.tamper_key = tamper;
  request.tamper_signature = tamper_signature;
  mcdata_send_sds(request);
}

//------------------------------------------------------------------------------
// Helper: the MCData part of status.json: protection counters, MCData states (TS 24.282 annex G.4) and the results
// of the last procedures.
void Client::mcdata_status() {
  status_["xml_protection"] = {
      {"enabled", xml_protection()}, {"encrypted", xml_encrypted_},         {"decrypted", xml_decrypted_},
      {"refused", xml_refused_},     {"integrity", integrity_protection()}, {"signed", xml_signed_},
      {"verified", xml_verified_}};
  status_["mcdata_xml_protection"] = {{"enabled", mcdata_xml_key().has_value()},
                                      {"encrypted", mcdata_xml_encrypted_},
                                      {"decrypted", mcdata_xml_decrypted_}};
  status_["mcdata_signalling_protection"] = {{"enabled", mcdata_signalling_key() != nullptr},
                                             {"csk_id", mcdata_csk_ ? Json(mcdata_csk_->csb_id) : Json(nullptr)},
                                             {"sealed", mcdata_signalling_sealed_},
                                             {"opened", mcdata_signalling_opened_},
                                             {"refused", mcdata_signalling_refused_}};
  status_["mcdata_authentication"] = {{"enabled", mcdata_authenticates()},
                                      {"signed", mcdata_signed_},
                                      {"verified", mcdata_verified_},
                                      {"failures", mcdata_signature_failures_}};
  status_["mcdata_protection"] = {{"enabled", mcdata_protected()},          {"pck_contexts", mcdata_pcks_.size()},
                                  {"group_keys", group_keys_.size()},       {"sent", mcdata_protected_sent_},
                                  {"received", mcdata_protected_received_}, {"failures", mcdata_protection_failures_}};
  status_["mcdata_authorized"] = mcdata_authorized_;
  status_["mcdata_affiliated"] = mcdata_affiliated_;
  Json inbox = Json::array();
  for (const auto &id : mcdata_received_order_) inbox.push_back(mcdata_received_.at(id));
  status_["mcdata_inbox"] = inbox;
  status_["mcdata_notifications"] = mcdata_notifications_;
  status_["mcdata_sent"] = mcdata_sent_;
  status_["mcdata_last_result"] = mcdata_last_result_;
  status_["mcdata_discarded"] = mcdata_discarded_;
  status_["mcdata_media_result"] = mcdata_media_result_;
  status_["mcdata_ipconn"] = ipcon_result_;
  status_["mcdata_release"] = mcdata_release_;
  status_["mcdata_enhanced_status"] = mcdata_enhanced_status_;
  status_["mcdata_ipconn_counters"] = {{"sent", ipcon_sent_},
                                       {"received", ipcon_received_},
                                       {"dropped", ipcon_dropped_},
                                       {"echo_answered", ipcon_echo_answered_},
                                       {"outstanding", ipcon_outstanding_.size()}};
  status_["mcdata_ipconn_pings"] = ipcon_pings_;
  status_["mcdata_media_received"] = mcdata_media_received_;
  status_["mcdata_media_sent"] = mcdata_media_sent_;
  status_["mcdata_media_notifications"] = mcdata_media_notifications_;
  status_["mcdata_media_session"] = mcdata_media_session_;
  status_["mcdata_media_files"] = mcdata_media_files_;
  status_["mcdata_group_notifications"] = mcdata_group_notifications_;
  status_["mcdata_session_emergency"] = mcdata_session_emergency_;
  status_["mcdata_session_emergency_state"] = mdepc_name(mdepc_);
  status_["mcdata_session_imminent"] = mcdata_session_imminent_;
  status_["mcdata_session_group"] = mcdata_session_group_;
  status_["mcdata_group_emergency_state"] = mdeg_name(mdeg_);
  status_["mcdata_group_emergency_communication_state"] = mdegc_name(mdegc_);
  status_["mcdata_group_imminent_state"] = mdig_name(mdig_);
  status_["mcdata_group_imminent_communication_state"] = mdigc_name(mdigc_);
  status_["mcdata_files_sent"] = mcdata_files_sent_;
  status_["mcdata_content_server"] = mcdata_discovered_content_server_;
  status_["mcdata_discovery_result"] = mcdata_discovery_result_;
  status_["mcdata_fd_last_result"] = mcdata_fd_last_result_;
  status_["mcdata_file_notifications"] = mcdata_file_notifications_;
  Json files = Json::array();
  for (const auto &id : mcdata_files_order_)
    if (const auto found = mcdata_files_.find(id); found != mcdata_files_.end()) files.push_back(found->second);
  status_["mcdata_files"] = files;
  status_["mcdata_affiliations"] = mcdata_groups_;
  status_["mcdata_affiliation_subscribed"] = !mcdata_subscription_.empty() && mcdata_status_known_;
  status_["mcdata_affiliation_p_id"] = {{"sent", mcdata_sent_pid_}, {"received", mcdata_received_pid_}};
  status_["mcdata_affiliation_fetch"] = mcdata_fetch_result_;
  status_["mcdata_storage_limit"] = mcdata_storage_limit_ ? Json(*mcdata_storage_limit_) : Json(nullptr);
  status_["mcdata_undelivered"] = mcdata_undelivered_;
  status_["mcdata_functional_aliases"] = mcdata_aliases_active_;
  status_["mcdata_functional_alias_in_use"] = mcdata_alias_in_use_;
  status_["mcdata_fa_last_result"] = mcdata_fa_last_result_;
  status_["mcdata_functional_alias_subscription_result"] = mcdata_alias_subscription_result_;
  status_["mcdata_functional_aliases_take_over_possible"] = mcdata_takeover_possible_;
  status_["mcdata_alert_state"] = mdea_name(mdea_);
  status_["mcdata_emergency"] = mcdata_emergency_;
  status_["mcdata_alerts_received"] = mcdata_alerts_received_;
  status_["mcdata_alert_last_result"] = mcdata_alert_last_result_;
  status_["mcdata_alert_receipts"] = mcdata_alert_receipts_;
}

} // namespace mcx
