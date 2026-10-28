//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// SEAL location management client: location reporting configuration, periodic reports and history reports
// (TS 24.545 clauses 6.2.2, 6.2.20). Member functions of Client (declared in client.hpp).
//
// Specifications: TS 24.545.

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// Helper: the position to report to the SEAL location management server, the last measured one or else the
// configured mcptt.location.position.
std::optional<seal::Fix> Client::seal_fix() const {
  std::optional<location::Point> point = location_.last_position();
  const auto &mcptt = cfg_.at("mcptt");
  if (!point && mcptt.contains("location") && mcptt.at("location").contains("position")) {
    const auto &position = mcptt.at("location").at("position");
    point = location::Point::at(position.at(0).get<double>(), position.at(1).get<double>());
  }
  if (!point) return std::nullopt;
  return seal::Fix{point->latitude, point->longitude, utc_timestamp(), false};
}

//------------------------------------------------------------------------------
// Helper: publish the SEAL location client's state in status_["seal"].
void Client::seal_status() {
  status_["seal"] = {{"started", seal_.started},
                     {"offline", seal_.offline},
                     {"periodic", seal_.config.periodic},
                     {"reports_sent", seal_.sent},
                     {"stored", seal_.stored.size()},
                     {"stored_total", seal_.stored_total},
                     {"history_sent", seal_.history_sent},
                     {"last_status", seal_.last_status},
                     {"report_uri", seal_.config.report_uri}};
}

//------------------------------------------------------------------------------
// TS 24.545 clause 6.2.2.2.1: the location management client reads its location reporting configuration from the
// location management server (HTTP GET) and starts the periodic trigger it configures.
void Client::seal_start() {
  const auto &endpoints = cfg_.at("endpoints");
  if (!endpoints.contains("lms")) throw std::runtime_error("no location management server is configured");
  const auto url =
      endpoints.at("lms").get<std::string>() + "/seal-lm/v1/users/" + url_encode(user()) + "/configuration";
  const auto response =
      http_.request("GET", url, {}, {{"Authorization", bearer()}, {"Accept", seal::location_mime}});
  seal_.last_status = response.status;
  if (response.status != 200)
    throw std::runtime_error("SEAL location configuration: " + std::to_string(response.status));
  seal_.config = seal::parse_configuration(response.body);
  if (seal_.config.report_uri.empty() || !seal_.config.periodic)
    throw std::runtime_error("SEAL configuration without a periodic report");
  seal_.started = true;
  seal_.next = monotonic_ms();
  event("seal_configuration_received",
        {{"periodic", seal_.config.periodic}, {"minimum_interval", seal_.config.minimum_interval}});
  seal_status();
}

//------------------------------------------------------------------------------
// TS 24.545 clause 6.2.2.2.2: on the periodic trigger, report the position to the location management server
// (HTTP POST to the report URI); while offline, store it for the history report of clause 6.2.20.2.
void Client::seal_tick(std::uint64_t now) {
  if (!seal_.started || now < seal_.next) return;
  seal_.next = now + 1000ull * std::max(seal_.config.periodic, std::max(seal_.config.minimum_interval, 1u));
  const auto fix = seal_fix();
  if (!fix) return;
  if (seal_.offline) {
    seal_.stored.push_back(*fix);
    ++seal_.stored_total;
    seal_status();
    return;
  }
  try {
    const auto response = http_.request("POST", seal_.config.report_uri, seal::report_xml(user(), "periodic", *fix),
                                        {{"Authorization", bearer()}, {"Content-Type", seal::location_mime}});
    seal_.last_status = response.status;
    if (response.status == 200)
      ++seal_.sent;
    else
      event("seal_report_refused", {{"status", response.status}});
  } catch (const std::exception &error) {
    seal_.last_status = 0;
    event("seal_report_failed", {{"reason", error.what()}});
  }
  seal_status();
}

//------------------------------------------------------------------------------
// TS 24.545 clause 6.2.20.2: back online, send the positions stored while offline in a history location result
// report to the location management server.
void Client::seal_online() {
  seal_.offline = false;
  if (!seal_.stored.empty() && !seal_.config.history_uri.empty()) {
    const auto response = http_.request("POST", seal_.config.history_uri, seal::history_xml("history", seal_.stored),
                                        {{"Authorization", bearer()}, {"Content-Type", seal::location_mime}});
    seal_.last_status = response.status;
    if (response.status != 200)
      throw std::runtime_error("SEAL history location result report: " + std::to_string(response.status));
    seal_.history_sent += static_cast<unsigned>(seal_.stored.size());
    event("seal_history_sent", {{"reports", seal_.stored.size()}});
    seal_.stored.clear();
  }
  seal_status();
}

//------------------------------------------------------------------------------
// Helper: the test command "seal start | offline | online | stop"; offline is a test aid standing in for a loss of
// connectivity.
void Client::seal_command(const std::string &cmd) {
  const auto verb = cmd.substr(5);
  if (verb == "start")
    seal_start();
  else if (verb == "offline") {
    seal_.offline = true;
    seal_status();
  } else if (verb == "online")
    seal_online();
  else if (verb == "stop") {
    seal_.started = false;
    seal_status();
  } else
    throw std::runtime_error("seal start|offline|online|stop");
}

} // namespace mcx
