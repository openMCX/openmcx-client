//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Live video for an agent (OpenMCX, not 3GPP; mcvideo.live, off unless configured): a camera, a drone or a robot
// writes its pictures as raw I420 (planar YUV 4:2:0, width x height luma then the two half-size chroma planes) to a
// FIFO, and while this client has the permission to transmit (TS 24.581 clause 6.2.4.5.2) each frame it sends is the
// latest complete picture, encoded as H.264 (TS 26.281 clause 4.1.1), in place of the test pattern. Until the first
// picture arrives a black one is sent; when the writer stops, its last picture goes on. The FIFO is opened for
// reading and writing, so that it never reports the end of the file between two writers.
//
// Configuration: mcvideo.live {fifo (created if missing), width (320), height (240), frame_rate (15),
// bitrate (300000), key_frame_interval (30)}. The picture size is even, at most 1280 x 720 (level 3.1).
//
// Specifications: TS 26.281 V19.0.0 (Rel-19), TS 24.581 V19.2.0 (Rel-19).

#include "client.hpp"

#include <fcntl.h>
#include <unistd.h>

#include <sys/stat.h>

namespace mcx {

namespace {
constexpr unsigned max_width = 1280, max_height = 720;
// Bytes read from the FIFO in one pass of the run loop: a few pictures of the largest size.
constexpr std::size_t live_read_limit = 8 * max_width * max_height * 3 / 2;
constexpr int pipe_size = 1 << 20;
constexpr std::uint8_t black_luma = 16, neutral_chroma = 128;

std::size_t picture_bytes(const video::EncoderOptions &options) {
  return static_cast<std::size_t>(options.width) * options.height * 3 / 2;
}
} // namespace

//------------------------------------------------------------------------------
// Helper: the encoder settings of mcvideo.live (the picture size, frame rate, bit rate, IDR interval), else the
// defaults of TS 26.281 clause 4.1.1's minimum.
video::EncoderOptions Client::mcvideo_encoder_options() const {
  video::EncoderOptions options;
  const auto live = cfg_.at("mcvideo").value("live", Json::object());
  options.width = live.value("width", options.width);
  options.height = live.value("height", options.height);
  options.frame_rate = live.value("frame_rate", options.frame_rate);
  options.bitrate = live.value("bitrate", options.bitrate);
  options.key_frame_interval = live.value("key_frame_interval", options.key_frame_interval);
  return options;
}

//------------------------------------------------------------------------------
// Helper: open (and create) the FIFO of mcvideo.live; without mcvideo.live nothing.
void Client::open_live_video() {
  const auto live = cfg_.at("mcvideo").value("live", Json::object());
  if (live.empty()) return;
  const auto options = mcvideo_encoder_options();
  if (options.width == 0 || options.height == 0 || options.width % 2 || options.height % 2 ||
      options.width > max_width || options.height > max_height)
    throw std::runtime_error("mcvideo.live: the picture size is even, at most 1280 x 720");
  if (options.frame_rate == 0 || options.frame_rate > 30) throw std::runtime_error("mcvideo.live: frame_rate 1..30");
  const auto path = live.at("fifo").get<std::string>();
  if (::mkfifo(path.c_str(), 0600) != 0 && errno != EEXIST)
    throw std::runtime_error("mcvideo.live: cannot create " + path);
  live_video_fd_ = ::open(path.c_str(), O_RDWR | O_NONBLOCK | O_CLOEXEC);
  if (live_video_fd_ < 0) throw std::runtime_error("mcvideo.live: cannot open " + path);
  (void)::fcntl(live_video_fd_, F_SETPIPE_SZ, pipe_size); // fewer passes per picture; best effort
}

//------------------------------------------------------------------------------
// Helper: read what the writer wrote and keep the latest complete picture; the rest of a picture waits for its end.
void Client::receive_live_video() {
  if (live_video_fd_ < 0) return;
  const auto options = mcvideo_encoder_options();
  const auto size = picture_bytes(options);
  std::array<std::uint8_t, 65536> buffer{};
  for (std::size_t total = 0; total < live_read_limit;) {
    const auto count = ::read(live_video_fd_, buffer.data(), buffer.size());
    if (count <= 0) break;
    total += static_cast<std::size_t>(count);
    live_video_partial_.insert(live_video_partial_.end(), buffer.begin(), buffer.begin() + count);
    if (live_video_partial_.size() < size) continue;
    // Only the last complete picture matters; the bytes after it start the next one.
    const auto complete = live_video_partial_.size() / size * size;
    const auto start = live_video_partial_.begin() + static_cast<std::ptrdiff_t>(complete - size);
    const auto luma = static_cast<std::ptrdiff_t>(options.width) * options.height;
    video::Frame frame{options.width, options.height, {}, {}, {}};
    frame.y.assign(start, start + luma);
    frame.u.assign(start + luma, start + luma + luma / 4);
    frame.v.assign(start + luma + luma / 4, start + luma + luma / 2);
    live_video_frame_ = std::move(frame);
    live_video_frames_ += static_cast<unsigned>(complete / size);
    live_video_at_ = monotonic_ms();
    live_video_partial_.erase(live_video_partial_.begin(),
                              live_video_partial_.begin() + static_cast<std::ptrdiff_t>(complete));
  }
}

//------------------------------------------------------------------------------
// Helper: the picture to transmit: the latest live picture (black before the first), or the test pattern.
video::Frame Client::mcvideo_picture(const video::EncoderOptions &options, std::uint32_t index) const {
  if (live_video_fd_ < 0) return video::test_pattern(options.width, options.height, index);
  if (live_video_frame_) return *live_video_frame_;
  const auto luma = static_cast<std::size_t>(options.width) * options.height;
  return video::Frame{options.width, options.height, std::vector<std::uint8_t>(luma, black_luma),
                      std::vector<std::uint8_t>(luma / 4, neutral_chroma),
                      std::vector<std::uint8_t>(luma / 4, neutral_chroma)};
}

} // namespace mcx
