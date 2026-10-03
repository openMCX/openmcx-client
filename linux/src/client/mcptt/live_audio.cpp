// Live audio for the dispatcher position (openMCX, not 3GPP; media.live, off unless configured): the dispatcher's
// web service sends the operator's microphone as 16 kHz 16-bit little-endian mono PCM to a local UDP port, which
// "ptt live" transmits as the user's speech (TS 24.380 clause 6.2.4.3.5) once the floor is granted; and every
// received speech frame goes as PCM to another local port, for the operator's loudspeaker.
//
// Configuration: media.live {listen (default 127.0.0.1), port, send_host (default 127.0.0.1), send_port}.
//
// Specifications: TS 24.380 V19.2.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19).

#include "client.hpp"

namespace mcx {

namespace {
// 20 ms of 16 kHz speech: one AMR-WB frame.
constexpr std::size_t frame_samples = 320;
// At most half a second of microphone speech waits for the floor.
constexpr std::size_t live_backlog_samples = 8000;
// Microphone datagrams taken in one pass of the run loop.
constexpr int live_datagrams_per_pass = 64;
constexpr unsigned byte_bits = 8;
constexpr std::uint16_t low_byte = 0xFFU;
} // namespace

//------------------------------------------------------------------------------
// Helper: open the microphone port of media.live and keep the loudspeaker's address; without media.live nothing.
void Client::open_live_audio() {
  const auto live = cfg_.at("media").value("live", Json::object());
  if (live.empty()) return;
  live_ =
      std::make_unique<UdpSocket>(live.value("listen", std::string("127.0.0.1")), live.at("port").get<std::uint16_t>());
  live_host_ = live.value("send_host", std::string("127.0.0.1"));
  live_port_ = live.at("send_port").get<std::uint16_t>();
}

//------------------------------------------------------------------------------
// Helper: "ptt live": the microphone frames of media.live are this user's speech until "release".
void Client::start_live_transmission() {
  if (!live_) throw std::runtime_error("ptt live needs media.live");
  live_talk_ = true;
  live_in_.clear();
  transmit_.clear();
}

//------------------------------------------------------------------------------
// Helper: one received speech frame to the dispatcher's service as 16-bit little-endian PCM.
void Client::forward_live_audio(const std::vector<std::int16_t> &pcm) {
  std::vector<std::uint8_t> bytes(pcm.size() * 2);
  for (size_t index = 0; index < pcm.size(); index++) {
    const auto sample = static_cast<std::uint16_t>(pcm[index]);
    bytes[2 * index] = static_cast<std::uint8_t>(sample & low_byte);
    bytes[2 * index + 1] = static_cast<std::uint8_t>(sample >> byte_bits);
  }
  live_->send(bytes, live_host_, live_port_);
}

//------------------------------------------------------------------------------
// Helper: microphone PCM (16-bit little-endian) from the dispatcher's service; kept only while transmitting, at most
// half a second (8000 samples), so that a slow floor grant does not delay the speech.
void Client::receive_live_audio() {
  for (int index = 0; live_ && index < live_datagrams_per_pass; index++) {
    auto datagram = live_->receive();
    if (!datagram) break;
    if (!live_talk_) continue;
    const auto &bytes = datagram->bytes;
    for (size_t at = 0; at + 1 < bytes.size(); at += 2)
      live_in_.push_back(static_cast<std::int16_t>(static_cast<unsigned>(bytes[at]) |
                                                   (static_cast<unsigned>(bytes[at + 1]) << byte_bits)));
    while (live_in_.size() > live_backlog_samples) live_in_.pop_front();
  }
}

//------------------------------------------------------------------------------
// Helper: the next 20 ms frame of the microphone, silence when none has arrived yet; the transmission lasts until
// "release".
void Client::live_speech_tick(std::uint64_t now) {
  std::array<std::int16_t, frame_samples> frame{};
  for (size_t index = 0; index < frame.size() && !live_in_.empty(); index++) {
    frame[index] = live_in_.front();
    live_in_.pop_front();
  }
  send_speech_frame(frame, false, now);
}

} // namespace mcx
