//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Received talk bursts for an agent (OpenMCX, not 3GPP; media.speech_dir, off unless configured): the speech of each
// talk burst received in an MCPTT call (TS 24.380 clause 6.2.4.3.4) is kept, decoded to 16 kHz 16-bit mono PCM, and
// written as one WAV file once the burst ends, with a speech_received event that names the file and the talker. A
// burst ends when the floor passes to another user or goes idle (the received SSRC changes or is cleared, also by
// the end of received media, clause 6.2.4.3.2 and clause 6.2.4.3.3), without floor control after a gap in the media
// (media.speech_gap_ms, default 1500), at media.speech_max_s seconds (default 120), and with the call.
//
// Configuration: media.speech_dir (the directory; created if missing), media.speech_gap_ms, media.speech_max_s.
//
// Specifications: TS 24.380 V19.2.0 (Rel-19), TS 24.379 V19.7.0 (Rel-19).

#include "client.hpp"

namespace mcx {

namespace {
constexpr std::size_t sample_rate = 16000;
// A burst shorter than this (a key click, a lost tail) is not reported.
constexpr std::size_t min_burst_samples = 1600;
} // namespace

//------------------------------------------------------------------------------
// Helper: keep a received speech frame of the talker (its SSRC and identity); a new talker ends the burst before.
void Client::capture_speech(const std::vector<std::int16_t> &pcm, std::uint32_t ssrc, const std::string &from,
                            std::uint64_t now) {
  const auto &media = cfg_.at("media");
  if (!media.contains("speech_dir")) return;
  if (speech_ && speech_->ssrc != ssrc) end_speech("talker changed");
  if (!speech_) speech_ = SpeechBurst{ssrc, from, {}, now};
  if (speech_->from.empty()) speech_->from = from;
  speech_->pcm.insert(speech_->pcm.end(), pcm.begin(), pcm.end());
  speech_->last = now;
  if (speech_->pcm.size() >= sample_rate * media.value("speech_max_s", 120u)) end_speech("longest burst");
}

//------------------------------------------------------------------------------
// Helper: end the burst when the floor passed on or went idle, or, without floor control, after a gap in the media.
void Client::speech_capture_tick(std::uint64_t now) {
  if (!speech_) return;
  const auto gap = cfg_.at("media").value("speech_gap_ms", 1500u);
  if (receive_ssrc_ != speech_->ssrc)
    end_speech("floor released");
  else if (no_floor() && now - speech_->last > gap)
    end_speech("media gap");
}

//------------------------------------------------------------------------------
// Helper: write the burst to speech_dir and report it (speech_received); a burst under 0.1 s is dropped.
void Client::end_speech(const char *reason) {
  if (!speech_) return;
  auto burst = std::move(*speech_);
  speech_.reset();
  if (burst.pcm.size() < min_burst_samples) return;
  const std::filesystem::path dir = cfg_.at("media").at("speech_dir").get<std::string>();
  std::filesystem::create_directories(dir);
  const auto path = dir / ("speech-" + random_id() + ".wav");
  write_wav(path.string() + ".tmp", burst.pcm);
  std::filesystem::rename(path.string() + ".tmp", path);
  status_["speech_bursts"] = status_.value("speech_bursts", 0u) + 1;
  // A call on the selected group leaves call_group_ empty.
  const bool private_call = call_type_ == "private" || call_type_ == "ambient";
  event("speech_received", {{"file", path.string()},
                            {"from", burst.from},
                            {"call_type", call_type_},
                            {"group", private_call          ? std::string{}
                                      : call_group_.empty() ? group()
                                                            : call_group_},
                            {"peer", private_peer_},
                            {"dialog", dialog_},
                            {"duration_ms", burst.pcm.size() * 1000 / sample_rate},
                            {"ended", reason}});
}

} // namespace mcx
