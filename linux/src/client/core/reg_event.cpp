//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// The client's subscription to its own registration state, the reg event package (RFC 3680, RFC 6665; TS 24.229
// clause 5.1.1.3, which TS 24.379, TS 24.281 and TS 24.282 clause 5.2 apply to the MC client as a SIP UA): subscribed
// after the 200 (OK) to the initial registration, refreshed, cancelled with the de-registration, and its NOTIFYs acted
// on: a shortened registration is renewed in time (clause 5.1.1.5A, the network-initiated re-authentication) and a
// deregistration by the network ends the registration (clause 5.1.1.7).
//
// Specifications: TS 24.229 V19.7.0 (Rel-19); RFC 3680; RFC 6665.

#include "client.hpp"

#include <charconv>
#include <optional>

namespace mcx {
namespace {
// TS 24.229 clause 5.1.1.3 item e): "an Expires header field set to 600 000 seconds".
constexpr unsigned reg_subscription_expires_s = 600000;
constexpr std::string_view reginfo_mime = "application/reginfo+xml";

//------------------------------------------------------------------------------
// Helper: the expires parameter of a Subscription-State header field, if any.
std::optional<std::uint64_t> state_expires(std::string_view state) {
  const auto at = state.find("expires=");
  if (at == std::string_view::npos) return std::nullopt;
  std::uint64_t value = 0;
  const auto digits = state.substr(at + 8);
  const auto [end, error] = std::from_chars(digits.data(), digits.data() + digits.size(), value);
  if (error != std::errc{}) return std::nullopt;
  return value;
}

//------------------------------------------------------------------------------
// Helper: the decimal value of an Expires header field, 0 when absent or malformed.
std::uint64_t expires_value(std::string_view value) {
  std::uint64_t out = 0;
  (void)std::from_chars(value.data(), value.data() + value.size(), out);
  return out;
}
} // namespace

//------------------------------------------------------------------------------
// TS 24.229 clause 5.1.1.3: "Upon receipt of a 2xx response to the initial registration, the UE shall subscribe to the
// reg event package for the public user identity registered": the Request-URI, From and To the default public user
// identity (the configured identity), Event "reg", Expires 600 000. Also after a registration whose subscription is
// gone ("The UE shall subscribe to the reg event package upon registering a new contact address via an initial
// registration procedure").
void Client::reg_subscribe() {
  if (!reg_subscription_.empty() || logged_off_ || !registered_) return;
  const auto generation = ++reg_generation_;
  reg_version_.reset();
  reg_duration_s_ = 0;
  reg_notify_expiry_ = false;
  reg_subscription_ = sip_.subscribe(
      sip_identity(), "reg", reg_subscription_expires_s, {{"Accept", std::string(reginfo_mime)}},
      [this, generation](const SipResponse &response) {
        if (response.status_code < 200 || generation != reg_generation_) return;
        if (response.status_code < 300) return reg_subscription_granted(response, generation);
        schedule([this, generation, code = response.status_code] {
          if (generation != reg_generation_) return;
          reg_subscription_.clear();
          status_["reg_event"] = {{"subscription_status", code}};
          event("reg_subscription_failed", {{"status", code}});
        });
      });
  event("reg_subscribed", {{"expires", reg_subscription_expires_s}});
}

//------------------------------------------------------------------------------
// TS 24.229 clause 5.1.1.3 item 2): the expiration time "as indicated in the "expires" header field parameter of the
// Subscription-State header field, if present, of the NOTIFY request. Otherwise the expiration time is retrieved from
// the Expires header field of the 2xx response to SUBSCRIBE request": the 2xx counts only when no NOTIFY gave the time
// since the SUBSCRIBE went out (the NOTIFY may come first).
void Client::reg_subscription_granted(const SipResponse &response, std::uint64_t generation) {
  const auto expires = expires_value(get_header(response.headers, "Expires"));
  schedule([this, generation, expires] {
    if (generation != reg_generation_ || expires == 0) return;
    status_["reg_event"] = {{"subscription_status", 200}, {"expires", expires}};
    if (!reg_notify_expiry_) reg_schedule_refresh(expires);
  });
}

//------------------------------------------------------------------------------
// TS 24.229 clause 5.1.1.3: the refresh of the subscription, replacing any refresh scheduled before: "600 seconds
// before the expiration time if the initial subscription was for greater than 1200 seconds, or when half of the time
// has expired if the initial subscription was for 1200 seconds or less" (client_rules::refresh_delay_s); the initial
// subscription's duration is its first known expiry.
void Client::reg_schedule_refresh(std::uint64_t expires) {
  if (reg_duration_s_ == 0) reg_duration_s_ = expires;
  const auto token = ++reg_refresh_token_;
  schedule_after(client_rules::refresh_delay_s(reg_duration_s_, expires) * 1000, [this, token] {
    if (token == reg_refresh_token_) reg_refresh();
  });
  reg_valid_until_ = monotonic_ms() + expires * 1000;
}

//------------------------------------------------------------------------------
// TS 24.229 clause 5.1.1.3: "If a SUBSCRIBE request to refresh a subscription fails with a non-481 response, the UE
// shall still consider the original subscription valid for the duration of the most recently known "Expires" value
// according to RFC 6665. Otherwise, the UE shall consider the subscription invalid and start a new initial
// subscription according to RFC 6665." No refresh once logged off or deregistered.
void Client::reg_refresh() {
  if (reg_subscription_.empty() || logged_off_ || !registered_) return;
  const auto id = reg_subscription_;
  const auto generation = reg_generation_;
  reg_notify_expiry_ = false;
  sip_.request_in_dialog(id, "SUBSCRIBE", {}, {},
                         {{"Event", "reg"},
                          {"Expires", std::to_string(reg_subscription_expires_s)},
                          {"Accept", std::string(reginfo_mime)}},
                         [this, generation](const SipResponse &response) {
                           if (response.status_code < 200 || generation != reg_generation_) return;
                           if (response.status_code < 300) {
                             event("reg_subscription_refreshed");
                             return reg_subscription_granted(response, generation);
                           }
                           schedule([this, generation, code = response.status_code] {
                             if (generation != reg_generation_) return;
                             event("reg_subscription_refresh_failed", {{"status", code}});
                             if (code == 481) {
                               reg_subscription_.clear();
                               return reg_subscribe();
                             }
                             // Valid until its last known expiry; then a new initial subscription.
                             const auto now = monotonic_ms();
                             const auto left = reg_valid_until_ > now ? reg_valid_until_ - now : 0;
                             const auto token = ++reg_refresh_token_;
                             schedule_after(left, [this, token, generation] {
                               if (token != reg_refresh_token_ || generation != reg_generation_) return;
                               reg_subscription_.clear();
                               reg_subscribe();
                             });
                           });
                         });
}

//------------------------------------------------------------------------------
// TS 24.229 clause 5.1.1.6.1: "If all public user identities are deregistered and all security association or TLS
// session is removed, then the UE shall consider subscription to the reg event package cancelled": with the client's
// own de-registration (log-off) or the network's, the subscription, its refresh and any reregistration asked by the
// network end. The final NOTIFY that the S-CSCF may still send on its dialog is answered 200 (RFC 6665 section
// 4.1.2.3, ending_subscriptions_).
void Client::reg_cancel() {
  if (!reg_subscription_.empty()) ending_subscriptions_.insert(reg_subscription_);
  reg_subscription_.clear();
  ++reg_generation_;
  ++reg_refresh_token_;
  ++reg_reregister_token_;
  reg_version_.reset();
}

//------------------------------------------------------------------------------
// A NOTIFY of the reg event subscription (RFC 6665 section 4.1.3, RFC 3680 sections 4.8 and 5.2): the expiry of
// Subscription-State, the document in version order, and the end of the subscription. The document is acted on
// after the response (reg_event_document).
SipResponse Client::reg_notify(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  const auto state = std::string(get_header(message.headers, "Subscription-State"));
  const auto generation = reg_generation_;
  if (!message.body.empty()) {
    RegInfo info;
    try {
      info = parse_reginfo(message.body);
    } catch (const std::exception &error) {
      event("reg_notify_invalid", {{"reason", error.what()}});
      response.status_code = 400;
      return response;
    }
    if (!client_rules::reginfo_version_new(reg_version_, info.version)) {
      // RFC 3680 section 5.2: "If the value in the document is less than the local version, the document is discarded
      // without processing" (one equal to it is the same document again).
      event("reg_notify_discarded", {{"version", info.version}, {"local_version", *reg_version_}});
    } else {
      // RFC 3680 section 5.2: "If the version is more than one higher than the local version, the subscriber SHOULD
      // generate a refresh request to trigger a full state notification".
      const bool gap = reg_version_ && info.version > *reg_version_ + 1;
      reg_version_ = info.version;
      schedule([this, info = std::move(info), gap, generation] {
        if (generation != reg_generation_) return;
        if (gap) {
          event("reg_version_gap", {{"version", info.version}});
          reg_refresh();
        }
        reg_event_document(info);
      });
    }
  }
  const auto expires = state_expires(state);
  if (expires) reg_notify_expiry_ = true;
  schedule([this, state, expires, generation] {
    if (generation != reg_generation_) return;
    if (state.starts_with("terminated")) {
      // RFC 6665 section 4.1.3: the subscriber "MUST consider the subscription terminated".
      reg_subscription_.clear();
      ++reg_refresh_token_;
      ++reg_generation_;
      event("reg_subscription_ended", {{"state", state}});
      return;
    }
    if (expires) reg_schedule_refresh(*expires);
  });
  return response;
}

//------------------------------------------------------------------------------
// TS 24.229 clauses 5.1.1.5A and 5.1.1.7: the registration state of this client's contact in a reginfo document.
// - <registration> active and this client's <contact> with event "shortened": "use the expires attribute ... to adjust
//   the expiration time" and "start the re-authentication procedures at the appropriate time ... by initiating a
//   reregistration as described in subclause 5.1.1.4": at the clause 5.1.1.4.1 time ("600 seconds before the
//   expiration time if the previous registration was for greater than 1200 seconds, or when half of the time has
//   expired if the previous registration was for 1200 seconds or less") for the adjusted expiration time;
// - this client's <contact> "terminated" with the event "unregistered", "rejected" or "deactivated" (clause 5.1.1.7
//   items 1 and 2; other events are not a deregistration of this clause): network_deregistration().
// Nothing during the client's own log-off, whose de-REGISTER ends the registration.
void Client::reg_event_document(const RegInfo &info) {
  if (logged_off_) return;
  const auto own = client_rules::contact_address(sip_.contact_target());
  for (const auto &registration : info.registrations) {
    if (registration.aor != sip_identity()) continue;
    for (const auto &contact : registration.contacts) {
      if (client_rules::contact_address(contact.uri) != own) continue;
      if (registration.state == "active" && contact.state == "active" && contact.event == "shortened" &&
          contact.expires) {
        const auto delay = client_rules::refresh_delay_s(registration_duration_s_, *contact.expires);
        event("reg_shortened", {{"expires", *contact.expires}, {"reregister_in_s", delay}});
        const auto token = ++reg_reregister_token_;
        schedule_after(delay * 1000, [this, token] {
          if (token == reg_reregister_token_ && !logged_off_ && registered_) reregister();
        });
        continue;
      }
      if (contact.state == "terminated" &&
          (contact.event == "unregistered" || contact.event == "rejected" || contact.event == "deactivated"))
        return network_deregistration(contact.event);
    }
  }
}

//------------------------------------------------------------------------------
// TS 24.229 clause 5.1.1.7: the network deregistered this client's public user identity: "the UE shall remove all
// registration details relating to the respective public user identity" and delete the security associations towards
// the P-CSCF (SipEngine::registration_ended, after the NOTIFY's server transaction); the reg event subscription ends
// with it (reg_cancel). "In case of a "rejected" event attribute, the UE shall release all dialogs related to those
// public user identities": first, while the security associations still carry the BYEs; the subscription dialogs and
// the service state go locally (drop_service_state), and the client stays logged off until "log-on". "In case of a
// "deactivated" event attribute, the UE shall start the initial registration procedure as described in subclause
// 5.1.1.2"; a REGISTER procedure already under way decides for itself (clause 5.1.1.4.1).
void Client::network_deregistration(const std::string &reason) {
  event("network_deregistered", {{"reg_event", reason}});
  status_["network_deregistered"] = reason;
  if (reason == "rejected") {
    logged_off_ = true;
    ++authorization_retry_.generation;
    ++mcdata_authorization_retry_.generation;
    ++mcvideo_authorization_retry_.generation;
    release();
    if (!dialog_.empty()) sip_.bye(dialog_);
    if (mcvideo_call_) {
      const auto dialog = mcvideo_call_->dialog;
      mcvideo_end_call("network deregistration");
      sip_.bye(dialog);
    }
    mcdata_media_close({});
    ipcon_close();
    end_service_subscriptions(false);
    authorized_ = affiliated_ = mcvideo_authorized_ = mcvideo_affiliated_ = mcdata_authorized_ = mcdata_affiliated_ =
        false;
    drop_service_state();
  }
  registered_ = false;
  authorized_ = false;
  reg_cancel();
  sip_.registration_ended();
  if (reason == "deactivated") {
    try {
      register_and_authorize();
    } catch (const std::exception &error) {
      event("reregistration_deferred", {{"reason", error.what()}});
    }
  }
}

//------------------------------------------------------------------------------
// TS 24.229 clause 5.1.1.5A: the reregistration of a shortened registration (clause 5.1.1.4), with the client's
// registration handler (registration_answered).
void Client::reregister() {
  try {
    sip_.register_account(register_expires(),
                          [this](const SipResponse &response) { registration_answered(response); });
  } catch (const std::exception &error) {
    // A REGISTER procedure under way (the engine's own refresh) already renews the registration.
    event("reregistration_deferred", {{"reason", error.what()}});
  }
}

} // namespace mcx
