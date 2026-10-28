//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// The client's floor control SRTCP under the shared core (openmcx-server include/mcx/protected_call.hpp), as the
// client uses it:
// - a CSK key download during a call (TS 33.180 clause 5.8, src/client/core/sip_dispatch.cpp): the call's streams
//   (client_rules::call_floor_ssrcs) continue their SRTCP index under the new CSK, in both directions, and are
//   accepted by the other side (RFC 3711 section 3.4: "after a re-key, the SRTCP index MUST NOT be reset to zero
//   again");
// - the server's SSRC for this client's floor control messages (check_remote, mcvideo_media; TS 24.380 clause
//   4.3.3.1): refused when it is this client's own or one used under the CSK for another stream (RFC 7714 section
//   8.4, RFC 3711 section 9.1), kept for the call going on, accepted when unused.
// Skipped (exit 77) without OpenMCX's libsrtp patch (openmcx-server dependencies/libsrtp): the indices checked start
// at 0.
//
// Specifications: TS 33.180 V19.5.0 (Rel-19), TS 24.380 V19.2.0 (Rel-19); RFC 3711, RFC 7714.

#include "client_rules.hpp"
#include "mcx/protected_call.hpp"

#include <srtp2/srtp.h>

#include <cstdio>
#include <ctime>
#include <stdexcept>

namespace {
using Bytes = std::vector<std::uint8_t>;
int failures = 0;

//------------------------------------------------------------------------------
// Helper: count and report a failed check.
void check(bool ok, const char *what) {
  if (ok) return;
  ++failures;
  std::fprintf(stderr, "FAILED: %s\n", what);
}

//------------------------------------------------------------------------------
// A CSK (TS 33.180 annex G purpose tag '2') with the CSK-ID [id], usable for an hour (TS 33.180 annex E.6).
mcx::keying::KeyMessage csk(std::uint32_t id, std::uint8_t seed) {
  mcx::keying::KeyMessage message;
  message.csb_id = id;
  for (unsigned index = 0; index < 16; ++index) {
    message.key[index] = std::uint8_t(seed + index);
    message.random[index] = std::uint8_t(seed + index + 16);
  }
  const auto now = std::uint64_t(std::time(nullptr));
  message.properties = mcx::keying::KeyProperties{2, 1, now - 30, now + 3600, "", {}};
  return message;
}

//------------------------------------------------------------------------------
// An RTCP APP packet (RFC 3550 section 6.7) with the SSRC [ssrc].
Bytes rtcp(std::uint32_t ssrc) {
  return {0x80, 0xcc, 0, 4, std::uint8_t(ssrc >> 24), std::uint8_t(ssrc >> 16), std::uint8_t(ssrc >> 8),
          std::uint8_t(ssrc), 0x4d, 0x43, 0x50, 0x54, 1, 2, 3, 4, 5, 6, 7, 8};
}

//------------------------------------------------------------------------------
// RFC 7714 section 9: the SRTCP index of a protected packet, before its 4-octet MKI (TS 33.180 clause 9.4.6).
std::uint32_t srtcp_index(const Bytes &packet) {
  const auto at = packet.size() - 8;
  return ((std::uint32_t(packet[at]) << 24) | (std::uint32_t(packet[at + 1]) << 16) |
          (std::uint32_t(packet[at + 2]) << 8) | packet[at + 3]) &
         0x7fffffffu;
}

//------------------------------------------------------------------------------
// Helper: whether the action throws.
template <class Action> bool throws(Action action) {
  try {
    action();
  } catch (const std::exception &) {
    return true;
  }
  return false;
}

// This client's SSRC for the server's messages and the server's for this client's.
constexpr std::uint32_t local = 0x0a0a0a0a, remote = 0x0b0b0b0b;

//------------------------------------------------------------------------------
// A key download in a call: both sides take the new CSK with the call's two streams going on.
void test_key_download_in_call() {
  mcx::ProtectedCall client, server;
  client.set_floor(csk(0x20000001, 0));
  server.set_floor(csk(0x20000001, 0));
  Bytes up, down;
  for (int n = 0; n < 4; ++n) server.unprotect_floor(up = client.protect_floor(rtcp(remote)));
  for (int n = 0; n < 2; ++n) client.unprotect_floor(down = server.protect_floor(rtcp(local)));
  const auto streams = mcx::client_rules::call_floor_ssrcs(local, remote);
  client.set_floor(csk(0x20000002, 0x40), streams);
  server.set_floor(csk(0x20000002, 0x40), streams);
  const auto next_up = client.protect_floor(rtcp(remote)), next_down = server.protect_floor(rtcp(local));
  check(srtcp_index(next_up) == srtcp_index(up) + 1, "client's index continues under the new CSK");
  check(srtcp_index(next_down) == srtcp_index(down) + 1, "server's index continues under the new CSK");
  check(server.unprotect_floor(next_up) == rtcp(remote), "server accepts the continuing index");
  check(client.unprotect_floor(next_down) == rtcp(local), "client accepts the continuing index");
  check(throws([&] { client.unprotect_floor(next_down); }), "a replay stays refused");
}

//------------------------------------------------------------------------------
// The server's SSRC for this client's messages in a new call, under the same CSK as an earlier call in which the
// server sent with this client's SSRC 0x0c0c0c0c.
void test_server_ssrc() {
  mcx::ProtectedCall client, server;
  client.set_floor(csk(0x20000001, 0));
  server.set_floor(csk(0x20000001, 0));
  constexpr std::uint32_t earlier = 0x0c0c0c0c, unused = 0x0d0d0d0d;
  client.unprotect_floor(server.protect_floor(rtcp(earlier)));
  check(mcx::floor_ssrc_refused(earlier, local, std::nullopt, &client), "an SSRC used under the CSK is refused");
  check(mcx::floor_ssrc_refused(local, local, std::nullopt, &client), "this client's own SSRC is refused");
  check(!mcx::floor_ssrc_refused(unused, local, std::nullopt, &client), "an unused SSRC is accepted");
  // The call going on (a re-INVITE) after this client sent with the server's SSRC.
  server.unprotect_floor(client.protect_floor(rtcp(unused)));
  check(!mcx::floor_ssrc_refused(unused, local, unused, &client), "the call's own SSRC again is accepted");
  check(!mcx::floor_ssrc_refused(earlier, local, std::nullopt, nullptr), "floor control in the clear: not checked");
}
} // namespace

int main() {
#ifndef SRTP_OPENMCX_SRTCP_INDEX_ZERO
  std::puts("skipped: libsrtp without OpenMCX's patch (openmcx-server dependencies/libsrtp)");
  return 77;
#else
  try {
    test_key_download_in_call();
    test_server_ssrc();
  } catch (const std::exception &error) {
    std::fprintf(stderr, "FAILED: %s\n", error.what());
    ++failures;
  }
  if (failures) std::fprintf(stderr, "%d check(s) failed\n", failures);
  return failures ? 1 : 0;
#endif
}
