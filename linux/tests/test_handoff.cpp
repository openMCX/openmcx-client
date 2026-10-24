//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Unit tests of src/client/core/handoff.hpp: a background job runs while the run loop keeps going, and its result
// comes back on the run loop's thread (the IdMS sign-in beside the SIP registration, TS 33.180 clause 5.1.3.2.1).
//
// Specifications: TS 33.180 V19.4.0 (Rel-19).

#include "handoff.hpp"

#include <chrono>
#include <cstdio>
#include <stdexcept>
#include <string>
#include <thread>

namespace {

int failures = 0;

//------------------------------------------------------------------------------
// Helper: count and report a failed check.
void check(bool ok, const char *what) {
  if (ok) return;
  ++failures;
  std::fprintf(stderr, "FAILED: %s\n", what);
}

//------------------------------------------------------------------------------
// A job of 200 ms: the loop runs on meanwhile; the result is delivered once, on the loop's thread, and the job is no
// longer busy then; a second job may start; one started while busy is refused.
void test_background_job() {
  mcx::Handoff handoff;
  mcx::BackgroundJob job(handoff);
  const auto loop_thread = std::this_thread::get_id();
  int result = 0, deliveries = 0;
  bool on_loop = false;
  job.start(
      [] {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
        return 42;
      },
      [&](int value) {
        result = value;
        ++deliveries;
        on_loop = std::this_thread::get_id() == loop_thread;
      });
  check(job.busy(), "busy while the job runs");
  bool refused = false;
  try {
    job.start([] { return 0; }, [](int) {});
  } catch (const std::logic_error &) {
    refused = true;
  }
  check(refused, "a second job is refused while one runs");
  unsigned passes = 0;
  const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
  while (deliveries == 0 && std::chrono::steady_clock::now() < deadline) {
    ++passes;
    for (auto &task : handoff.take()) task();
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
  }
  check(result == 42 && deliveries == 1, "the result is delivered once");
  check(on_loop, "the result runs on the loop's thread");
  check(passes > 10, "the loop kept running during the job");
  check(!job.busy(), "not busy once delivered");
  std::string text;
  job.start([] { return std::make_exception_ptr(std::runtime_error("refused")); },
            [&](std::exception_ptr error) { text = mcx::error_text(error); });
  while (text.empty() && std::chrono::steady_clock::now() < deadline) {
    for (auto &task : handoff.take()) task();
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
  }
  check(text == "refused", "an error comes back with its message");
  check(mcx::error_text(nullptr).empty(), "no error, no message");
}

} // namespace

int main() {
  test_background_job();
  if (failures == 0) std::puts("handoff tests passed");
  return failures == 0 ? 0 : 1;
}
