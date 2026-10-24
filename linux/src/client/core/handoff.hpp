//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Background work of the MC client: one job at a time on a worker thread, its result handed back to the client's run
// loop, so that a slow HTTP exchange (the IdMS sign-in) does not hold up the SIP processing (TS 33.180 clause
// 5.1.3.2.1: the SIP registration is not delayed for lack of an access token).
//
// Specifications: TS 33.180 V19.4.0 (Rel-19).

#pragma once

#include <exception>
#include <functional>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <type_traits>
#include <utility>
#include <vector>

namespace mcx {

//------------------------------------------------------------------------------
// The tasks worker threads hand to the run loop: posted from any thread, taken (and run) by the run loop alone.
class Handoff {
public:
  void post(std::function<void()> task) {
    const std::lock_guard lock(mutex_);
    tasks_.push_back(std::move(task));
  }

  std::vector<std::function<void()>> take() {
    const std::lock_guard lock(mutex_);
    return std::exchange(tasks_, {});
  }

private:
  std::mutex mutex_;
  std::vector<std::function<void()>> tasks_;
};

//------------------------------------------------------------------------------
// One background job at a time: start() runs `work` on a thread of its own and hands `done(result)` to the run loop
// through the Handoff. busy() and start() belong to the run loop's thread; the job must touch no state of the run
// loop's. The destructor waits for a job still running.
class BackgroundJob {
public:
  explicit BackgroundJob(Handoff &handoff) : handoff_(handoff) {}

  BackgroundJob(const BackgroundJob &) = delete;
  BackgroundJob &operator=(const BackgroundJob &) = delete;

  ~BackgroundJob() {
    if (thread_.joinable()) thread_.join();
  }

  bool busy() const { return busy_; }

  template <typename Work, typename Done> void start(Work work, Done done) {
    if (busy_) throw std::logic_error("a background job is already running");
    if (thread_.joinable()) thread_.join();
    busy_ = true;
    thread_ = std::thread([this, work = std::move(work), done = std::move(done)]() mutable {
      auto result = work();
      handoff_.post([this, result = std::move(result), done = std::move(done)]() mutable {
        busy_ = false;
        done(std::move(result));
      });
    });
  }

private:
  Handoff &handoff_;
  std::thread thread_;
  bool busy_ = false;
};

//------------------------------------------------------------------------------
// Helper: the message of a captured exception.
inline std::string error_text(const std::exception_ptr &error) {
  try {
    if (error) std::rethrow_exception(error);
  } catch (const std::exception &caught) {
    return caught.what();
  } catch (...) {
    return "unknown error";
  }
  return {};
}

} // namespace mcx
