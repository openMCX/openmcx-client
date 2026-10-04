// Protected calls: private call keys (PCK), the group key subscription and protected floor control transmission.
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 24.481 V19.3.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19).

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
// receive the GMK (TS 33.180 clause 5.7); without protected media, affiliate at once.
void Client::subscribe_keys() {
  if (!protected_media()) {
    affiliate();
    return;
  }
  McpttInfo info;
  info.access_token = token_;
  info.client_id = client_id();
  info.request_uri = group();
  auto parts = info_parts(info);
  parts.push_back({"application/resource-lists+xml", keying::gktp_resource_list(group())});
  const auto boundary = "mcx-" + random_id();
  key_subscription_ = sip_.subscribe(
      service(), "xcap-diff", 60, {},
      [this](const SipResponse &response) {
        if (response.status_code >= 300) failure("group key subscription: " + std::to_string(response.status_code));
      },
      "multipart/mixed; boundary=" + boundary, multipart_body(parts, boundary));
  key_subscriptions_[key_subscription_] = group();
  next_key_refresh_ = monotonic_ms() + 50000;
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
