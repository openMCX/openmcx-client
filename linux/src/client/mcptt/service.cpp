//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// MCPTT service authorisation, group affiliation, the affiliation and conference event subscriptions, and helpers.
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 24.380 V19.2.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// Helper: whether a URI is this client's MCPTT server (sip: or, over TLS, sips:), the sender of conference
// notifications.
bool Client::conference_service_from(std::string_view uri) const {
  if (uri == service()) return true;
  auto secure = service();
  if (secure.starts_with("sip:")) secure.insert(3, "s");
  return status_.value("sip_transport", "") == "tls" && uri == secure;
}

//------------------------------------------------------------------------------
// TS 24.380 clause 11.1.1 and clause 11.2.1: the on-network floor participant's timers and counters, from the
// configuration.
FloorParticipantTimers Client::participant_timers() const {
  const auto &settings = cfg_.at("mcptt");
  FloorParticipantTimerOptions options;
  options.request_retry_ms = settings.value("floor_request_retry_ms", 1000u);
  options.release_retry_ms = settings.value("floor_release_retry_ms", 1000u);
  options.queue_retry_ms = settings.value("floor_queue_retry_ms", 1000u);
  options.media_idle_ms = settings.value("floor_media_idle_ms", 4000u);
  options.queued_grant_action_ms = settings.value("floor_queued_grant_action_ms", 2000u);
  options.max_transmissions = settings.value("floor_transmissions", 3u);
  return FloorParticipantTimers(options);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.3.2: keep the MCPTT session identity from the Contact of a call's response and subscribe to
// the conference event package of the group session.
void Client::remember_session(const std::vector<SipHeader> &headers) {
  auto contact = std::string(get_header(headers, "Contact"));
  auto left = contact.find('<'), right = contact.find('>');
  if (left == contact.npos || right == contact.npos || right <= left) return;
  auto uri = contact.substr(left + 1, right - left - 1);
  auto secure_service = service();
  if (secure_service.starts_with("sip:")) secure_service.insert(3, "s");
  if (uri.starts_with(service() + ";mcptt-session=") || uri.starts_with(secure_service + ";mcptt-session=")) {
    session_uri_ = uri;
    status_["session_uri"] = uri;
    schedule([this] { subscribe_conference(); });
    // The session identity of an active call known only now: a mapped MBMS subchannel is joined with it.
    if (mbms_enabled_ && !multicast_socket_ && status_.value("call_state", "") == "active")
      schedule([this] { multicast_join(); });
  }
}

//------------------------------------------------------------------------------
// Helper: run a task on the client's loop after a delay (at once when the delay is 0).
void Client::schedule_after(std::uint64_t delay_ms, std::function<void()> fn) {
  if (!delay_ms) return schedule(std::move(fn));
  timed_.emplace(monotonic_ms() + delay_ms, std::move(fn));
}

//------------------------------------------------------------------------------
// Helper: TS 24.379, TS 24.282 and TS 24.281 clause 7.2.2 leave the client not authorised after a refused service
// authorisation and leave retrying to it. A refusal that can be transient (client_rules::authorization_retry_delay_ms)
// is published again after a growing delay, reported by a service_authorization_retry event; false when the refusal
// is final. The retry is dropped when another authorisation PUBLISH was sent meanwhile or the client is stopping.
bool Client::retry_authorization(AuthorizationRetry &retry, const std::string &service_name,
                                 const SipResponse &response, std::function<void()> again) {
  const auto delay = client_rules::authorization_retry_delay_ms(response.status_code, retry.attempts,
                                                                get_header(response.headers, "Retry-After"));
  if (!delay || stopping || logged_off_) return false;
  ++retry.attempts;
  event(
      "service_authorization_retry",
      {{"service", service_name}, {"status", response.status_code}, {"attempt", retry.attempts}, {"delay_ms", *delay}});
  schedule_after(*delay, [this, &retry, generation = retry.generation, again = std::move(again)] {
    if (!stopping && retry.generation == generation) again();
  });
  return true;
}

//------------------------------------------------------------------------------
// Helper: record and report a procedure failure (status last_error, event client_error).
void Client::failure(const std::string &what) {
  status_["last_error"] = what;
  event("client_error", {{"reason", what}});
}

//------------------------------------------------------------------------------
// TS 24.379 clause 7.2.2 (poc-settings and the CSK upload of TS 33.180 clause 5.4) or clause 9.2.1.2 (the
// affiliation pidf+xml): the multipart body of the service authorisation or affiliation SIP PUBLISH.
std::pair<std::string, std::string> Client::publish_body(bool affiliation) {
  McpttInfo info;
  info.client_id = client_id();
  info.access_token = token_;
  std::vector<BodyPart> parts = info_parts(info);
  if (affiliation) {
    info.request_uri = user();
    parts = info_parts(info);
    // TS 24.379 clause 9.2.1.2: the selected group and the groups monitored besides it.
    std::vector<std::string> groups{group()};
    groups.insert(groups.end(), monitored_groups_.begin(), monitored_groups_.end());
    Affiliation request{user(), client_id(), random_id(), groups, {}};
    parts.push_back({"application/pidf+xml", affiliation_xml(request, false)});
  } else {
    parts.push_back({"application/poc-settings+xml", poc_settings_xml(client_id())});
    if (!csk_upload_.empty()) parts.push_back({"application/mikey", csk_upload_});
  }
  auto boundary = "mcx-" + random_id();
  return {"multipart/mixed; boundary=" + boundary, multipart_body(parts, boundary)};
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.3: the affiliation status SUBSCRIBE names this MCPTT user in the
// mcptt-info body (step 2) and restricts the notifications to this MCPTT client
// with a simple-filter body (step 7, clause 9.3.2.2).
std::pair<std::string, std::string> Client::affiliation_subscribe_body() {
  McpttInfo info;
  info.request_uri = user();
  info.client_id = client_id();
  auto parts = info_parts(info);
  parts.push_back(
      {"application/simple-filter+xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                        "<filter-set xmlns=\"urn:ietf:params:xml:ns:simple-filter\"><ns-bindings>"
                                        "<ns-binding prefix=\"pidf\" urn=\"urn:ietf:params:xml:ns:pidf\"/>"
                                        "<ns-binding prefix=\"mcpttPI10\" urn=\"urn:3gpp:ns:mcpttPresInfo:1.0\"/>"
                                        "</ns-bindings><filter id=\"" +
                                            random_id().substr(0, 12) +
                                            "\"><what><include>"
                                            "//pidf:presence/pidf:tuple[@id=&quot;" +
                                            xml_escape(client_id()) +
                                            "&quot;]"
                                            "</include></what></filter></filter-set>"});
  auto boundary = "mcx-" + random_id();
  return {"multipart/mixed; boundary=" + boundary, multipart_body(parts, boundary)};
}

//------------------------------------------------------------------------------
// TS 24.379 clause 7.2.2: service authorisation by SIP PUBLISH (poc-settings) with the CSK upload (TS 33.180 clause
// 5.4); on 200 (OK) start MCData and MCVideo authorisation, the configuration subscription and the affiliation
// status subscription (clause 9.2.1.3), then the group key subscription.
void Client::authorize() {
  if (logged_off_) return;
  // TS 24.379 clause 7.2.2 step 3: a CSK for media and floor protection and for XML confidentiality or integrity
  // protection.
  if (keys_ && (protected_media() || protected_floor() || xml_protection() || integrity_protection())) {
    auto [key, upload] = keys_->make_csk(service());
    protection_.set_floor(key);
    csk_upload_ = std::move(upload);
    status_["csk_id"] = key.csb_id;
    csk_ = key;
  }
  if (authorise_by_register()) {
    register_authorization([this](const SipResponse &response) {
      if (response.status_code < 200) return;
      if (response.status_code != 200) {
        authorization_lost("service authorization", response);
        return;
      }
      service_authorized();
    });
    return;
  }
  auto [type, body] = publish_body(false);

  // TS 24.379 clause 7.2.1A step 2: the MCPTT ICSI in P-Preferred-Service.
  std::vector<SipHeader> headers{
      {"P-Preferred-Service", mcptt_icsi}, {"Event", "poc-settings"}, {"Expires", "4294967295"}};
  const bool refresh = !auth_etag_.empty();
  if (refresh) headers.push_back({"SIP-If-Match", auth_etag_});
  ++authorization_retry_.generation;
  sip_.publish(service(), type, body, headers, [this, refresh](const SipResponse &response) {
    if (response.status_code < 200) return;
    if (response.status_code != 200) {
      // TS 24.379 clause 7.2.2: not authorised. RFC 3903 section 5: after a 412 to a refresh, an initial
      // publication; a refusal that can be transient is published again later (authorization_lost). A 412 to an
      // initial publication, which has no entity-tag, is final.
      if (refresh || response.status_code != 412)
        authorization_lost("service authorization", response);
      else
        failure("service authorization: " + std::to_string(response.status_code));
      return;
    }
    auth_etag_ = get_header(response.headers, "SIP-ETag");
    service_authorized();
  });
}

//------------------------------------------------------------------------------
// The steps after a successful service authorisation, by PUBLISH (clause 7.2.2) or by REGISTER (clause 7.2.1):
// MCData and MCVideo authorisation, the configuration, group document (TS 24.481 clause 6.3.13.2.1) and affiliation
// status subscriptions (clause 9.2.1.3), then the group key subscription.
void Client::service_authorized() {
  authorization_retry_.attempts = 0;
  authorized_ = true;
  // TS 33.180 clause 5.4: the server holds the CSK now; the renewals of the authorisation carry none (the server
  // refuses a CSK it already has), and a new authorisation makes a new one (authorize).
  csk_upload_.clear();
  event("service_authorized");
  // The InitialLogOn trigger fires once the configuration it belongs to arrives (TS 24.379 clause 13.2.2).
  location_logon_pending_ = !location_.configured();
  location_signal(location::Event::InitialLogOn);
  if (mcdata_enabled()) schedule([this] { mcdata_authorize(); });
  if (mcvideo_enabled()) schedule([this] { mcvideo_authorize(); });
  if (config_subscription_.empty() && cfg_.at("mcptt").value("subscribe_configuration", true))
    schedule([this] { subscribe_configuration(); });
  schedule([this] { subscribe_own_group_documents(); });
  schedule([this] { subscribe_affiliation(true); });
}

//------------------------------------------------------------------------------
// TS 24.379 clause 7.2.1: whether this client authorises in its REGISTER (mcptt.authorise_by_register).
bool Client::authorise_by_register() const { return cfg_.at("mcptt").value("authorise_by_register", false); }

//------------------------------------------------------------------------------
// TS 24.379 clause 7.2.1 steps 5 and 6 (TS 33.180 clause 9.2.1.3): the REGISTER body, the mcptt-info with the access
// token and the client ID, and the CSK upload when there is one.
std::pair<std::string, std::string> Client::register_body() {
  McpttInfo info;
  info.client_id = client_id();
  info.access_token = token_;
  std::vector<BodyPart> parts = info_parts(info);
  if (!csk_upload_.empty()) parts.push_back({"application/mikey", csk_upload_});
  auto boundary = "mcx-" + random_id();
  return {"multipart/mixed; boundary=" + boundary, multipart_body(parts, boundary)};
}

//------------------------------------------------------------------------------
// TS 24.379 clause 7.2.1: a REGISTER on the existing registration, carrying the service authorisation body.
void Client::register_authorization(std::function<void(const SipResponse &)> handler) {
  auto [type, body] = register_body();
  queue_register(std::move(type), std::move(body), std::move(handler));
  event("service_authorization_by_register");
}

//------------------------------------------------------------------------------
// Helper: a REGISTER with a service authorisation body, sent once the one before it is answered.
void Client::queue_register(std::string type, std::string body, std::function<void(const SipResponse &)> handler) {
  register_queue_.push_back([this, type = std::move(type), body = std::move(body), handler = std::move(handler)] {
    sip_.register_account(register_expires(), type, body, [this, handler](const SipResponse &response) {
      if (response.status_code < 200) return;
      register_busy_ = false;
      handler(response);
      schedule([this] { next_register(); });
    });
  });
  next_register();
}

//------------------------------------------------------------------------------
// Helper: send the next queued REGISTER; one the registration cannot send yet (a refresh in progress) is tried again
// a second later.
void Client::next_register() {
  if (register_busy_ || register_queue_.empty()) return;
  register_busy_ = true;
  try {
    register_queue_.front()();
    register_queue_.pop_front();
  } catch (const std::exception &error) {
    register_busy_ = false;
    event("register_deferred", {{"reason", error.what()}});
    schedule_after(1000, [this] { next_register(); });
  }
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.3: the affiliation status determination procedure: SUBSCRIBE to this user's affiliation
// status, Expires 4294967295 (step 4). The initial subscription is followed by the key subscriptions.
void Client::subscribe_affiliation(bool initial) {
  auto [type, body] = affiliation_subscribe_body();
  subscription_ = sip_.subscribe(
      service(), "presence", 4294967295u, {{"P-Preferred-Service", mcptt_icsi}, {"Accept", "application/pidf+xml"}},
      [this, initial](const SipResponse &response) {
        if (response.status_code < 200) return;
        if (response.status_code != 200) {
          failure("affiliation subscription: " + std::to_string(response.status_code));
          return;
        }
        subscription_granted(response, subscription_expires_, subscription_refresh_at_);
        if (initial) schedule([this] { subscribe_keys(); });
      },
      type, body);
}

//------------------------------------------------------------------------------
// RFC 6665 section 4.1.2.1: the notifier may grant a shorter duration than the Expires requested (the 200 (OK)
// carries it); the subscriber refreshes the subscription before it ends. The refresh is due a minute before the end,
// or half-way through a shorter duration.
void Client::subscription_granted(const SipResponse &response, std::int64_t &expires, std::int64_t &refresh_at) const {
  std::int64_t granted = 4294967295;
  try {
    if (const auto value = get_header(response.headers, "Expires"); !value.empty())
      granted = std::stoll(std::string(value));
  } catch (const std::exception &) {}
  const std::int64_t now = std::time(nullptr);
  expires = now + granted;
  refresh_at = now + granted - std::min<std::int64_t>(granted / 2, 60);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.3 and TS 24.281 clause 8.2.1.3: re-subscription by an in-dialog SUBSCRIBE with Expires
// 4294967295 and Accept application/pidf+xml, before the granted duration ends. A refused or failed refresh ends
// that subscription; a new one takes its place. The affiliations themselves (made by PUBLISH) and any call are kept.
void Client::refresh_affiliation_subscription(bool mcvideo) {
  auto &dialog = mcvideo ? mcvideo_subscription_ : subscription_;
  (mcvideo ? mcvideo_subscription_refresh_at_ : subscription_refresh_at_) = 0;
  if (dialog.empty()) return;
  const std::string service_name = mcvideo ? "mcvideo" : "mcptt";
  auto resubscribe = [this, mcvideo, service_name](const std::string &reason) {
    event("affiliation_subscription_refresh_failed", {{"service", service_name}, {"reason", reason}});
    if (mcvideo) {
      mcvideo_subscription_.clear();
      schedule([this] { mcvideo_subscribe(); });
    } else {
      subscription_.clear();
      schedule([this] { subscribe_affiliation(false); });
    }
  };
  const auto id = dialog;
  try {
    sip_.request_in_dialog(
        id, "SUBSCRIBE", {}, {}, {{"Event", "presence"}, {"Expires", "4294967295"}, {"Accept", "application/pidf+xml"}},
        [this, mcvideo, id, service_name, resubscribe](const SipResponse &response) {
          if (response.status_code < 200 || (mcvideo ? mcvideo_subscription_ : subscription_) != id) return;
          if (response.status_code >= 300) {
            resubscribe(std::to_string(response.status_code));
            return;
          }
          if (mcvideo)
            subscription_granted(response, mcvideo_subscription_expires_, mcvideo_subscription_refresh_at_);
          else
            subscription_granted(response, subscription_expires_, subscription_refresh_at_);
          event("affiliation_subscription_refreshed", {{"service", service_name}});
        });
  } catch (const std::exception &error) {
    resubscribe(error.what());
  }
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.2: the affiliation status change procedure: affiliate to the configured group by SIP
// PUBLISH (presence).
void Client::affiliate() {
  if (!desired_affiliation_ || !authorized_ || group().empty()) return;
  auto [type, body] = publish_body(true);
  // TS 24.379 clause 9.2.1.2: the MCPTT ICSI in P-Preferred-Service, as for every MCPTT PUBLISH.
  std::vector<SipHeader> headers{{"P-Preferred-Service", mcptt_icsi}, {"Event", "presence"}, {"Expires", "4294967295"}};
  const bool refresh = !affiliation_etag_.empty();
  if (refresh) headers.push_back({"SIP-If-Match", affiliation_etag_});
  sip_.publish(service(), type, body, headers, [this, refresh](const SipResponse &response) {
    if (response.status_code < 200) return;
    if (response.status_code != 200) {
      failure("affiliation publication: " + std::to_string(response.status_code));
      // RFC 3903 section 5: after a 412 to a refresh, discard the entity-tag and publish anew.
      if (refresh && response.status_code == 412) {
        affiliation_etag_.clear();
        schedule([this] { affiliate(); });
      }
      return;
    }
    affiliation_etag_ = get_header(response.headers, "SIP-ETag");
    event("affiliation_published");
  });
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.3.2: subscribe to the conference event package of the active group session.
// force: diagnostic override used to exercise the server's broadcast rejection.
void Client::subscribe_conference(bool force) {
  if (!cfg_.at("mcptt").value("conference_notifications", true)) {
    status_["conference_wait_reason"] = "disabled";
    return;
  }
  // The conference event package describes group sessions only.
  if (call_type_ == "private" || call_type_ == "ambient") {
    status_["conference_wait_reason"] = "private call";
    return;
  }
  // TS 24.379 clause 10.1.3.2 and clause 10.1.3.4.1 step 4: no conference subscription in a broadcast group call.
  if (call_type_ == "broadcast" && !force) {
    status_["conference_wait_reason"] = "broadcast group call";
    return;
  }
  if (status_.value("call_state", "") != "active") {
    status_["conference_wait_reason"] = "call not active";
    return;
  }
  if (!remote_ || dialog_.empty() || session_uri_.empty()) {
    status_["conference_wait_reason"] = !remote_          ? "remote media missing"
                                        : dialog_.empty() ? "dialog missing"
                                                          : "session URI missing";
    return;
  }
  if (!conference_subscription_.empty()) return;
  status_["conference_wait_reason"] = "awaiting subscription response";
  status_["conference_subscription_attempts"] = status_.value("conference_subscription_attempts", 0u) + 1;
  McpttInfo info;
  info.request_uri = active_group();
  info.client_id = client_id();
  conference_version_.reset();
  conference_members_.clear();
  const auto generation = ++conference_generation_;
  conference_subscription_ = sip_.subscribe(
      session_uri_, "conference", 4294967295u,
      {{"Accept", "application/conference-info+xml"},
       {"P-Preferred-Service", "urn:urn-7:3gpp-service.ims.icsi.mcptt"},
       {"Accept-Contact", "*;+g.3gpp.mcptt;require;explicit"},
       {"Accept-Contact", "*;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mcptt\";require;explicit"}},
      [this, generation](const SipResponse &response) {
        if (generation != conference_generation_) return;
        if (response.status_code >= 200 && response.status_code < 300 && !status_.value("conference_active", false))
          status_["conference_wait_reason"] = "awaiting conference NOTIFY";
        if (response.status_code >= 300) {
          status_["conference_wait_reason"] = "subscription rejected";
          conference_subscription_.clear();
          failure("conference subscription: " + std::to_string(response.status_code));
        }
      },
      "application/vnd.3gpp.mcptt-info+xml", mcptt_info_xml(info));
}

} // namespace mcx
