//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Ambient listening and suspended calls: the listened-to client's microphone and yielding the ambient listening call
// to another call.
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 23.379 V19.11.0 (Rel-19).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// Helper: microphone stand-in for a listened-to client in an ambient listening call (TS 24.379 clause 11.1.6): the
// configured WAV, looped, or one second of silence.
std::vector<std::int16_t> Client::ambient_audio() const {
  const auto path = ambient_source_.empty() ? cfg_.at("mcptt").value("ambient_source", std::string{}) : ambient_source_;
  return path.empty() ? std::vector<std::int16_t>(16000, 0) : read_wav(path);
}

//------------------------------------------------------------------------------
// TS 23.379 clause 10.14.3.3: a call request by or towards a listened-to user makes the server release the
// ambient listening call. Stop transmitting at once and
// keep the old dialog only to absorb that release. The snapshot lets a call
// request that is never sent restore the ambient call (see place_call).
std::optional<Client::CallSnapshot> Client::yield_ambient() {
  if (!ambient_hidden()) return std::nullopt;
  auto saved = suspend_call();
  event("ambient_call_yielded", {{"dialog", ambient_->dialog}});
  return saved;
}

//------------------------------------------------------------------------------
// Helper: keep the current call's state and leave the client idle for another call.
Client::CallSnapshot Client::suspend_call() {
  CallSnapshot saved{
      local_,           remote_,     floor_timers_, floor_sequence_, transmit_,
      transmit_offset_, audio_ssrc_, receive_ssrc_, talking_,        requesting_,
      first_to_answer_, dialog_,     call_type_,    call_group_,     private_peer_,
      adhoc_priority_,  mepc_,       mepp_,         mpea_,           status_.value("call_state", Json("idle")),
      session_uri_};
  protection_.suspend_private();
  talking_ = requesting_ = false;
  transmit_.clear();
  transmit_offset_ = 0;
  remote_.reset();
  floor_timers_ = participant_timers();
  floor_sequence_.reset();
  receive_ssrc_ = audio_ssrc_ = 0;
  local_.direction = "sendrecv";
  local_.implicit_request = implicit_offered_ = call_answered_ = false;
  early_floor_.clear();
  local_.control = MediaControl::floor;
  dialog_.clear();
  call_type_.clear();
  private_peer_.clear();
  status_["call_state"] = "idle";
  return saved;
}

//------------------------------------------------------------------------------
// Helper: the call request that yielded the ambient listening call (TS 23.379 clause 10.14.3.3) was never sent:
// restore the ambient listening call.
void Client::restore_ambient(CallSnapshot &&snapshot) {
  resume_call(std::move(snapshot));
  event("ambient_call_restored", {{"dialog", dialog_}});
}

//------------------------------------------------------------------------------
// Helper: restore a call state kept by suspend_call().
void Client::resume_call(CallSnapshot &&snapshot) {
  local_ = std::move(snapshot.local);
  remote_ = std::move(snapshot.remote);
  floor_timers_ = std::move(snapshot.floor_timers);
  floor_sequence_ = std::move(snapshot.floor_sequence);
  transmit_ = std::move(snapshot.transmit);
  transmit_offset_ = snapshot.transmit_offset;
  audio_ssrc_ = snapshot.audio_ssrc;
  receive_ssrc_ = snapshot.receive_ssrc;
  talking_ = snapshot.talking;
  requesting_ = snapshot.requesting;
  first_to_answer_ = snapshot.first_to_answer;
  dialog_ = std::move(snapshot.dialog);
  call_type_ = std::move(snapshot.call_type);
  call_group_ = std::move(snapshot.call_group);
  private_peer_ = std::move(snapshot.private_peer);
  adhoc_priority_ = std::move(snapshot.adhoc_priority);
  mepc_ = snapshot.mepc;
  mepp_ = snapshot.mepp;
  mpea_ = snapshot.mpea;
  session_uri_ = std::move(snapshot.session_uri);
  status_["session_uri"] = session_uri_;
  if (const auto key = private_keys_.find(dialog_); key != private_keys_.end()) protection_.resume_private(key->second);
  status_["call_state"] = snapshot.call_state;
  next_audio_ = monotonic_ms();
}

} // namespace mcx
