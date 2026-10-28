//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Protected calls: private call keys (PCK), the group key subscription and protected floor control transmission.
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 24.481 V19.3.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19), RFC 6665.

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.1.2.1.1 step 11: generate a PCK for target, encapsulate it in a
// signed I_MESSAGE and offer it in a=key-mgmt. The caller initiated the call.
std::optional<keying::KeyMessage> Client::offer_private_key(const std::string &target) {
  local_.key_mgmt.clear();
  if (!protected_media()) return std::nullopt;
  if (!keys_) throw std::runtime_error("key management client unavailable");
  // TS 24.379 clause 6.2.8.3.9: a KMS URI given for this target (<PrivateCallKMSURI>) keys the PCK.
  const auto kms = target == forwarded_kms_.first ? std::exchange(forwarded_kms_, {}).second : std::string{};
  auto [key, message] = keys_->make_pck(target, kms);
  local_.key_mgmt = std::move(message);
  if (!kms.empty()) event("private_call_kms_selected", {{"target", target}, {"kms_uri", kms}});
  return key;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.1.2.1.1 step 11 and clause 11.1.1.2.1.2 step 4: the offer has been sent or accepted: select
// the PCK for this dialog. A re-INVITE must not carry it again, so the local description drops it.
void Client::bind_private_key(const std::optional<keying::KeyMessage> &key, bool initiator) {
  local_.key_mgmt.clear();
  if (!key || dialog_.empty()) return;
  protection_.set_private(*key, initiator);
  private_keys_[dialog_] = key->csb_id;
  status_["private_key_id"] = key->csb_id;
  event("private_call_key", {{"pck_id", key->csb_id}, {"initiator", initiator}});
}

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.1.2.1.2 step 4: authenticate the I_MESSAGE from its initiator
// (IDRi) and extract the PCK and PCK-ID. Throws when authentication fails.
keying::KeyMessage Client::accept_private_key(const std::string &encoded, const std::string &expected_sender) {
  if (!keys_) throw std::runtime_error("key management client unavailable");
  auto [key, sender] = keys_->accept_pck(expected_sender, encoded);
  return key;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.1.2.1.1, on SIP 200 (OK) step 2: the first-to-answer caller takes the
// PCK from the answer of the invitee that answered, or releases the call (step 2d).
bool Client::answer_private_key(const MediaDescription &media) {
  awaiting_answer_key_ = false;
  std::optional<keying::KeyMessage> key;
  std::string reason;
  try {
    if (media.key_mgmt.empty()) throw std::runtime_error("first-to-answer answer without a PCK");
    if (!keys_) throw std::runtime_error("key management client unavailable");
    auto [pck, sender] = keys_->accept_pck({}, media.key_mgmt);
    if (!answer_key_senders_.empty() &&
        std::find(answer_key_senders_.begin(), answer_key_senders_.end(), sender) == answer_key_senders_.end())
      throw std::runtime_error("PCK from a user who was not invited");
    key = pck;
  } catch (const std::exception &error) {
    reason = error.what();
  }
  if (key) {
    bind_private_key(key, true);
    return true;
  }
  // Step 2d-i: an emergency private call still requested (MEPC 2) falls back to MEPC 1, MEPP 1 and MPEA 1.
  if (mepc_ == 2) private_emergency_refused();
  McpttInfo info;
  info.release_reason = "authentication of the MIKEY-SAKE I_MESSAGE failed";
  status_["last_call_failure"] = {{"reason", info.release_reason}, {"detail", reason}};
  event("private_call_key_rejected", {{"reason", reason}, {"first_to_answer", true}});
  if (!dialog_.empty())
    schedule([this, dialog = dialog_, parts = info_parts(info)] { sip_.bye(dialog, {}, {}, parts); });
  return false;
}

//------------------------------------------------------------------------------
// Helper: the XCAP root URI of the GMS (TS 24.481 clause 6.3.13.2.1 step a), from the GMS application identity.
std::string Client::xcap_root() const {
  auto id = gms_identity();
  const auto suffix = std::string("org.openmobilealliance.groups");
  if (!id.ends_with(suffix)) throw std::runtime_error("invalid GMS application identity");
  return id.substr(0, id.size() - suffix.size());
}

//------------------------------------------------------------------------------
// TS 24.481 clause 6.3.13.2.1: subscribe to the <GKTPs> element of the group's MCS GKTP document (xcap-diff) to
// receive the GMK (TS 33.180 clause 5.7), with the initial SIP SUBSCRIBE request; without protected media, affiliate
// at once. A key subscription of the group selected before is ended in its dialog first (RFC 6665 section 4.1.2.3);
// its final NOTIFY is still answered (key_subscriptions_). The subscription is refreshed in its dialog
// (refresh_key_subscription).
void Client::subscribe_keys() {
  if (!protected_media()) {
    affiliate();
    return;
  }
  if (!key_subscription_.empty()) {
    const auto previous = std::exchange(key_subscription_, std::string{});
    try {
      sip_.request_in_dialog(previous, "SUBSCRIBE", "", "",
                             {{"Event", "xcap-diff"}, {"Expires", "0"}, {"P-Preferred-Service", mcptt_icsi}});
    } catch (const std::exception &error) {
      event("group_key_subscription_end_failed", {{"reason", error.what()}});
    }
  }
  key_refresh_at_ = 0;
  // TS 33.180 clauses 7.3.11 and 7.3.12: a selected regroup is keyed with its preconfigured group's GMK.
  const auto key_group = key_group_of(group());
  McpttInfo info;
  info.access_token = token_;
  info.client_id = client_id();
  info.request_uri = key_group;
  auto parts = info_parts(info);
  parts.push_back({"application/resource-lists+xml", keying::gktp_resource_list(key_group)});
  const auto boundary = "mcx-" + random_id();
  key_subscription_ = sip_.subscribe(
      gms_subscription_psi(), "xcap-diff", key_subscription_expires_s(), {},
      [this](const SipResponse &response) {
        if (response.status_code < 200) return;
        if (response.status_code >= 300) {
          failure("group key subscription: " + std::to_string(response.status_code));
          return;
        }
        key_refresh_at_ = key_refresh_time(response);
      },
      "multipart/mixed; boundary=" + boundary, multipart_body(parts, boundary));
  key_subscriptions_[key_subscription_] = key_group;
  update_key_subscriptions();
}

//------------------------------------------------------------------------------
// TS 33.180 clauses 7.3.11 and 7.3.12: "The GMK and the GMK-ID from the identified preconfigured group shall be used"
// for a regroup using a preconfigured group; any other group (a monitored group, a broadcast group, clause 7.3.4
// "the GMK associated to the broadcast group", and clause 7.3.5) is keyed with its own GMK.
std::string Client::key_group_of(const std::string &call_group) const {
  return client_rules::key_group(call_group, [this](const std::string &candidate) {
    if (!regroups_.contains(candidate)) return std::string{};
    return regroups_.at(candidate).value("preconfigured", std::string{});
  });
}

//------------------------------------------------------------------------------
// TS 33.180 clause 5.7 with annex E.6 (MC group IDs, Activation and Expiry times, Status): the newest usable GMK of the
// group among those received on the key subscriptions.
keying::KeyMessage Client::gmk_for(const std::string &group_id) const {
  const auto now = static_cast<std::uint64_t>(std::time(nullptr));
  const auto *best = client_rules::newest_usable(
      group_keys_,
      [&](const keying::KeyMessage &key) {
        try {
          keying::require_active(key, group_id, now);
          return true;
        } catch (const std::exception &) {
          return false;
        }
      },
      [](const keying::KeyMessage &key) { return key.properties->activation_unix_seconds; });
  if (!best) throw std::runtime_error("no active group key for " + group_id);
  return *best;
}

//------------------------------------------------------------------------------
// TS 33.180 clauses 7.3.4, 7.3.5, 7.3.11, 7.3.12 and 7.4.2: the call's media is keyed with the GMK of its group
// (key_group_of); a call whose group's GMK this client does not hold cannot be keyed (false).
bool Client::select_call_key(const std::string &call_group) {
  const auto key_group = key_group_of(call_group);
  try {
    protection_.set_group(gmk_for(key_group));
  } catch (const std::exception &error) {
    event("group_call_key_unavailable", {{"group_id", call_group}, {"key_group", key_group}, {"reason", error.what()}});
    return false;
  }
  return true;
}

//------------------------------------------------------------------------------
// Helper: after a call keyed with another group's GMK, the selected group's GMK (if held) is selected again; the
// other epochs keep their replay state (ProtectedCall).
void Client::restore_group_key() {
  if (!protected_media()) return;
  try {
    protection_.set_group(gmk_for(key_group_of(group())));
  } catch (const std::exception &) {
    // Not received yet: the selected group's next key notification selects it.
  }
}

//------------------------------------------------------------------------------
// TS 24.481 clause 6.3.13.2.1 (a GMC subscribes to the <GKTPs> elements of "one or more" groups; here one subscription
// per group, as for the selected group): the GKTP subscriptions of the monitored groups and of the preconfigured
// groups of known regroups (TS 33.180 clauses 7.3.11 and 7.3.12: "All users invited ... shall have obtained the
// preconfigured group configuration and GMK"). A subscription no longer needed ends (RFC 6665 section 4.1.2.3); its
// final NOTIFY is still answered (key_subscriptions_).
void Client::update_key_subscriptions() {
  if (!protected_media() || !keys_ || !authorized_) return;
  std::set<std::string> wanted(monitored_groups_.begin(), monitored_groups_.end());
  for (const auto &[tgi, regroup] : regroups_.items())
    if (const auto preconfigured = regroup.value("preconfigured", std::string{}); !preconfigured.empty())
      wanted.insert(preconfigured);
  wanted.erase(key_group_of(group()));
  for (auto it = extra_key_subscriptions_.begin(); it != extra_key_subscriptions_.end();) {
    if (wanted.contains(it->first)) {
      ++it;
      continue;
    }
    extra_key_refresh_at_.erase(it->second);
    try {
      sip_.request_in_dialog(it->second, "SUBSCRIBE", "", "",
                             {{"Event", "xcap-diff"}, {"Expires", "0"}, {"P-Preferred-Service", mcptt_icsi}});
    } catch (const std::exception &error) {
      event("group_key_subscription_end_failed", {{"group_id", it->first}, {"reason", error.what()}});
    }
    it = extra_key_subscriptions_.erase(it);
  }
  for (const auto &key_group : wanted) {
    if (extra_key_subscriptions_.contains(key_group)) continue;
    McpttInfo info;
    info.access_token = token_;
    info.client_id = client_id();
    info.request_uri = key_group;
    auto parts = info_parts(info);
    parts.push_back({"application/resource-lists+xml", keying::gktp_resource_list(key_group)});
    const auto boundary = "mcx-" + random_id();
    const auto dialog = sip_.subscribe(
        gms_subscription_psi(), "xcap-diff", key_subscription_expires_s(), {},
        [this, key_group](const SipResponse &response) {
          if (response.status_code < 200) return;
          const auto found = extra_key_subscriptions_.find(key_group);
          if (found == extra_key_subscriptions_.end()) return;
          if (response.status_code >= 300) {
            event("group_key_subscription_refused", {{"group_id", key_group}, {"status", response.status_code}});
            key_subscriptions_.erase(found->second);
            extra_key_refresh_at_.erase(found->second);
            extra_key_subscriptions_.erase(found);
            return;
          }
          extra_key_refresh_at_[found->second] = key_refresh_time(response);
        },
        "multipart/mixed; boundary=" + boundary, multipart_body(parts, boundary));
    extra_key_subscriptions_[key_group] = dialog;
    key_subscriptions_[dialog] = key_group;
    event("group_key_subscription", {{"group_id", key_group}});
  }
}

//------------------------------------------------------------------------------
// TS 24.481 clause 6.3.13.2.1 and RFC 6665 section 4.1.2.2: a further GKTP subscription refreshed by a re-SUBSCRIBE in
// its dialog, asking for the RFC 5875 section 4.5 default again; a refused or failed refresh ends it, and
// update_key_subscriptions subscribes again.
void Client::refresh_extra_key_subscription(const std::string &dialog) {
  extra_key_refresh_at_[dialog] = 0;
  const auto found = key_subscriptions_.find(dialog);
  if (found == key_subscriptions_.end()) return;
  const auto key_group = found->second;
  auto resubscribe = [this, key_group, dialog](const std::string &reason) {
    event("group_key_subscription_refresh_failed", {{"service", "mcptt"}, {"group_id", key_group}, {"reason", reason}});
    const auto current = extra_key_subscriptions_.find(key_group);
    if (current == extra_key_subscriptions_.end() || current->second != dialog) return;
    extra_key_subscriptions_.erase(current);
    extra_key_refresh_at_.erase(dialog);
    schedule([this] { update_key_subscriptions(); });
  };
  try {
    sip_.request_in_dialog(dialog, "SUBSCRIBE", "application/resource-lists+xml", keying::gktp_resource_list(key_group),
                           {{"Event", "xcap-diff"}, {"Expires", std::to_string(key_subscription_expires_s())}},
                           [this, dialog, resubscribe](const SipResponse &response) {
                             if (response.status_code < 200 || !extra_key_refresh_at_.contains(dialog)) return;
                             if (response.status_code >= 300) {
                               resubscribe(std::to_string(response.status_code));
                               return;
                             }
                             extra_key_refresh_at_[dialog] = key_refresh_time(response);
                           });
  } catch (const std::exception &error) {
    resubscribe(error.what());
  }
}

//------------------------------------------------------------------------------
// RFC 6665 section 4.1.2.1: the Expires of the 2xx "indicates the actual duration for which the subscription will
// remain active" (it "MUST contain an Expires header field"; without one, the duration asked for). The refresh is due
// at nine tenths of it: an implementation choice within RFC 6665 4.1.2.2 (any time before expiry).
std::int64_t Client::key_refresh_time(const SipResponse &response) const {
  std::int64_t granted = key_subscription_expires_s();
  try {
    if (const auto value = get_header(response.headers, "Expires"); !value.empty())
      granted = std::stoll(std::string(value));
  } catch (const std::exception &) {}
  if (granted <= 0) return 0;
  return std::time(nullptr) + granted * 9 / 10;
}

//------------------------------------------------------------------------------
// TS 24.481 clause 6.3.13.2.1: the "SIP re-SUBSCRIBE request" with the application/resource-lists+xml body of the
// subscribed <GKTPs> element, in the dialog of the subscription (RFC 6665 section 4.1.2.2: "on the same dialog"),
// asking for the RFC 5875 section 4.5 default again. A refused or failed refresh ends that subscription (RFC 6665
// section 4.1.2.2); a new initial SUBSCRIBE takes its place.
void Client::refresh_key_subscription(bool mcvideo) {
  auto &dialog = mcvideo ? mcvideo_key_subscription_ : key_subscription_;
  (mcvideo ? mcvideo_key_refresh_at_ : key_refresh_at_) = 0;
  if (dialog.empty()) return;
  const auto id = dialog;
  const auto group_id = mcvideo ? mcvideo_key_group_ : key_subscriptions_[id];
  auto resubscribe = [this, mcvideo, id](const std::string &reason) {
    event("group_key_subscription_refresh_failed", {{"service", mcvideo ? "mcvideo" : "mcptt"}, {"reason", reason}});
    if ((mcvideo ? mcvideo_key_subscription_ : key_subscription_) != id) return;
    if (mcvideo) {
      mcvideo_key_subscription_.clear();
      schedule([this] { mcvideo_subscribe_keys(); });
    } else {
      key_subscription_.clear();
      schedule([this] { subscribe_keys(); });
    }
  };
  const std::vector<SipHeader> headers{{"Event", "xcap-diff"},
                                       {"Expires", std::to_string(key_subscription_expires_s())}};
  try {
    sip_.request_in_dialog(
        id, "SUBSCRIBE", "application/resource-lists+xml", keying::gktp_resource_list(group_id), headers,
        [this, mcvideo, id, resubscribe](const SipResponse &response) {
          if (response.status_code < 200 || (mcvideo ? mcvideo_key_subscription_ : key_subscription_) != id) return;
          if (response.status_code >= 300) {
            resubscribe(std::to_string(response.status_code));
            return;
          }
          (mcvideo ? mcvideo_key_refresh_at_ : key_refresh_at_) = key_refresh_time(response);
          event("group_key_subscription_refreshed", {{"service", mcvideo ? "mcvideo" : "mcptt"}});
        });
  } catch (const std::exception &error) {
    resubscribe(error.what());
  }
}

//------------------------------------------------------------------------------
// TS 33.180 clause 9.4.7: send a floor control message, SRTCP-protected when floor control protection is in use; a
// missing or failing key stops the protected call.
void Client::transmit_floor(const FloorPacket &packet) {
  if (!remote_) return;
  if ((protected_media() && !media_key_ready()) || (protected_floor() && !protection_.floor_ready())) {
    stop_protected_call("protected call key expired before floor transmission");
    throw std::runtime_error("protected call key unavailable");
  }
  auto bytes = encode_floor(packet);
  if (protected_floor()) {
    try {
      bytes = protection_.protect_floor(bytes);
    } catch (const std::exception &error) {
      stop_protected_call(error.what());
      throw;
    }
  }
  floor_.send(bytes, server_target(remote_->floor_address, remote_->floor_port), remote_->floor_port);
}

//------------------------------------------------------------------------------
// Helper: a protected call lost its key: stop the media, release the call (SIP BYE) and report the failure.
void Client::stop_protected_call(const std::string &reason) {
  talking_ = requesting_ = false;
  transmit_.clear();
  transmit_offset_ = 0;
  receive_ssrc_ = audio_ssrc_ = 0;
  floor_timers_ = participant_timers();
  remote_.reset();
  status_["protected_ready"] = false;
  const auto failed_dialog = dialog_;
  if (!failed_dialog.empty()) schedule([this, failed_dialog] { sip_.bye(failed_dialog); });
  failure(reason);
}

} // namespace mcx
