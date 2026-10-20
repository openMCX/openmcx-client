//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Log-off from the MC services and log-on again (commands "log-off" and "log-on").
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 24.281 V19.6.0 (Rel-19), TS 24.282 V19.7.0 (Rel-19), TS 23.280
// V19.10.1 (Rel-19), TS 33.180 V19.4.0 (Rel-19), TS 24.229, IETF RFC 3903, RFC 6665.

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// Command "log-off": TS 24.379 clause 7.2.1A step 4 and NOTE 3 (TS 24.281 and TS 24.282 clause 7.2.1A likewise): the
// client logs off each MC service it is authorised for by removing its service settings, a SIP PUBLISH for
// poc-settings with Expires 0 that names the publication in SIP-If-Match and has no body (RFC 3903 section 4.5); the
// server removes the affiliations and the binding (clause 7.3.5; TS 23.280 clause 10.8). First the call ends, and the
// subscriptions that live with the authorisation end (RFC 6665 section 4.1.2.3, a SUBSCRIBE with Expires 0):
// affiliation status, group keys, configuration and functional alias status. When every service has answered, the
// dynamic keys go and the client leaves the IMS (finish_log_off). Nothing authorises a service again before
// "log-on": no retry, token renewal or subscription expiry.
void Client::log_off_command() {
  if (logged_off_) throw std::runtime_error("already logged off");
  logged_off_ = true;
  ++authorization_retry_.generation;
  ++mcdata_authorization_retry_.generation;
  ++mcvideo_authorization_retry_.generation;
  release();
  if (!dialog_.empty()) sip_.bye(dialog_);
  end_service_subscriptions();
  struct Removal {
    std::string name, uri, icsi, etag;
  };
  std::vector<Removal> removals;
  if (mcvideo_authorized_ && !mcvideo_auth_etag_.empty())
    removals.push_back({"mcvideo", mcvideo_service_uri(), mcvideo_service.icsi, mcvideo_auth_etag_});
  if (mcdata_authorized_ && !mcdata_auth_etag_.empty())
    removals.push_back({"mcdata", mcdata_service(), mcdata::icsi, mcdata_auth_etag_});
  if (authorized_ && !auth_etag_.empty()) removals.push_back({"mcptt", service(), mcptt_icsi, auth_etag_});
  authorized_ = affiliated_ = mcvideo_authorized_ = mcvideo_affiliated_ = mcdata_authorized_ = mcdata_affiliated_ =
      false;
  status_["log_off"] = Json::object();
  event("log_off_started", {{"services", removals.size()}});
  if (removals.empty()) return finish_log_off();
  auto pending = std::make_shared<std::size_t>(removals.size());
  for (const auto &removal : removals)
    sip_.publish(removal.uri, "", "",
                 {{"P-Preferred-Service", removal.icsi},
                  {"Event", "poc-settings"},
                  {"Expires", "0"},
                  {"SIP-If-Match", removal.etag}},
                 [this, name = removal.name, pending](const SipResponse &response) {
                   if (response.status_code < 200) return;
                   status_["log_off"][name] = response.status_code;
                   if (response.status_code != 200) failure(name + " log-off: " + std::to_string(response.status_code));
                   event("service_logged_off", {{"service", name}, {"status", response.status_code}});
                   if (--*pending == 0) finish_log_off();
                 });
}

//------------------------------------------------------------------------------
// Helper: RFC 6665 section 4.1.2.3: the SUBSCRIBE with Expires 0 that ends each subscription held with the service
// authorisations: the affiliation status (TS 24.379 clause 9.2.1.3, TS 24.281 clause 8.2.1.3, TS 24.282 clause
// 8.2.4), the group keys (TS 33.180 clause 5.7), the configuration (TS 24.484) and the functional alias status
// (TS 24.379 clause 9A.2.1.3, TS 24.282 clause 22.2.1.3).
void Client::end_service_subscriptions() {
  const auto end = [this](std::string &dialog, const char *event_name, const std::string &icsi) {
    if (dialog.empty()) return;
    sip_.request_in_dialog(dialog, "SUBSCRIBE", "", "",
                           {{"Event", event_name}, {"Expires", "0"}, {"P-Preferred-Service", icsi}});
    dialog.clear();
  };
  end(subscription_, "presence", mcptt_icsi);
  end(mcvideo_subscription_, "presence", mcvideo_service.icsi);
  end(mcdata_subscription_, "presence", mcdata::icsi);
  key_subscriptions_.erase(key_subscription_);
  end(key_subscription_, "xcap-diff", mcptt_icsi);
  end(mcvideo_key_subscription_, "xcap-diff", mcvideo_service.icsi);
  end(config_subscription_, "xcap-diff", mcptt_icsi);
  end(alias_status_dialog_, "presence", mcptt_icsi);
  end(mcdata_alias_dialog_, "presence", mcdata::icsi);
  subscription_expires_ = subscription_refresh_at_ = 0;
  mcvideo_subscription_expires_ = mcvideo_subscription_refresh_at_ = 0;
}

//------------------------------------------------------------------------------
// Helper: the end of the log-off, once every service has answered. The entity tags are discarded (the next
// authorisation is an initial publication, RFC 3903 section 4.1). TS 33.180 clause 9 (key storage): the dynamic keys
// go at log-off (the CSKs, the PCKs and every key derived from them or from the GMKs, which come again with the group
// key subscription at log-on); the tokens, the KMS key material and the certificates stay through log-off and
// log-on. No MC service is left, so the client de-registers (TS 24.229 clause 5.1.1.6; TS 24.379 clause 7.2.1 NOTE
// 1 re-registers without the MCPTT feature tags only when other services remain).
void Client::finish_log_off() {
  auth_etag_.clear();
  affiliation_etag_.clear();
  mcvideo_auth_etag_.clear();
  mcvideo_affiliation_etag_.clear();
  mcdata_auth_etag_.clear();
  mcdata_affiliation_etag_.clear();
  mcdata_alias_answered_ = false;
  mcdata_aliases_active_.clear();
  mcdata_aliases_standing_.clear();
  mcdata_alias_publish_open_.clear();
  protection_ = ProtectedCall{};
  mcvideo_protection_ = ProtectedCall(true);
  csk_.reset();
  csk_upload_.clear();
  mcvideo_csk_.reset();
  mcvideo_csk_upload_.clear();
  mcdata_csk_.reset();
  mcdata_csk_upload_.clear();
  mcdata_csk_accepted_ = false;
  private_keys_.clear();
  mcdata_pcks_.clear();
  status_.erase("csk_id");
  status_.erase("mcvideo_csk_id");
  status_["logged_off"] = true;
  if (registered_) {
    registered_ = false;
    sip_.unregister_account([this](const SipResponse &response) {
      if (response.status_code < 200) return;
      status_["deregistered"] = response.status_code;
      event("sip_deregistered", {{"status", response.status_code}});
    });
  }
  event("logged_off", {{"services", status_["log_off"]}});
}

//------------------------------------------------------------------------------
// Command "log-on": after "log-off", the client registers again and is authorised for its services (TS 24.379 clause
// 7.2.2, with new CSKs, TS 33.180 clause 5.4) with the access token it kept; the affiliations follow as at start-up.
void Client::log_on_command() {
  if (!logged_off_) throw std::runtime_error("not logged off");
  logged_off_ = false;
  status_["logged_off"] = false;
  status_.erase("log_off");
  status_.erase("deregistered");
  if (mcdata_keys_ && mcdata_protects_signalling()) {
    auto [csk, upload] = mcdata_keys_->make_csk(mcdata_service());
    mcdata_csk_ = csk;
    mcdata_csk_upload_ = std::move(upload);
  }
  if (mcvideo_keys_) {
    auto [video_csk, video_upload] = mcvideo_keys_->make_csk(mcvideo_service_uri());
    mcvideo_protection_.set_floor(video_csk);
    mcvideo_csk_upload_ = std::move(video_upload);
    mcvideo_csk_ = video_csk;
    status_["mcvideo_csk_id"] = video_csk.csb_id;
  }
  event("log_on_started");
  register_and_authorize();
}

} // namespace mcx
