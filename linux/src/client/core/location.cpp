// Location procedures of the MCPTT and MCData clients (TS 24.379 clauses 12.1.1.4, 12.1.1.6, 13.3;
// TS 24.282 clauses 16.2.1.4, 16.2.1.5, 17.3). Member functions of Client (declared in client.hpp).
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 24.282 V19.7.0 (Rel-19).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.379 clause 13.3.4.2: send a location information report: a SIP MESSAGE with the location-info body to the
// participating function that sent the configuration (or the MCPTT server).
void Client::send_location_report(location::Report report) {
  report.location.timestamp = utc_timestamp();
  if (!location_alias_.empty()) report.location.functional_alias = std::exchange(location_alias_, {});
  const auto body = location::to_xml(report, location::Service::Mcptt);
  const auto target = location_server_.empty() ? service() : location_server_;
  sip_.request("MESSAGE", target, mcptt_location_mime, body,
               {{"Accept-Contact", "*;+g.3gpp.mcptt;require;explicit"}, {"P-Preferred-Service", mcptt_icsi}},
               [this](const SipResponse &response) {
                 if (response.status_code < 200) return;
                 if (response.status_code >= 300) failure("location report: " + std::to_string(response.status_code));
               });
  auto entry = location::to_json(report);
  location_sent_.push_back(entry);
  while (location_sent_.size() > 32) location_sent_.erase(location_sent_.begin());
  event("location_report_sent", entry);
}

//------------------------------------------------------------------------------
// Helper: the current UTC time as an ISO 8601 timestamp (YYYY-MM-DDThh:mm:ssZ) for the reports.
std::string Client::utc_timestamp() {
  const auto now = std::time(nullptr);
  std::tm tm{};
  gmtime_r(&now, &tm);
  char text[32];
  std::strftime(text, sizeof text, "%Y-%m-%dT%H:%M:%SZ", &tm);
  return text;
}

//------------------------------------------------------------------------------
// TS 24.379 clauses 13.3.2.1 and 13.3.3.1: a location reporting configuration or a location information request
// from the participating function, and the reports answering this user's own requests.
std::optional<SipResponse> Client::location_incoming(const SipMessage &message) {
  auto parts = message.parts.empty() && !message.body.empty() ? parse_body_parts(message.content_type, message.body)
                                                              : message.parts;
  SipResponse response;
  response.status_code = 200;
  // TS 24.379 clauses 12.1.1.4 and 12.1.1.6: area entry and exit notifications.
  if (const auto info_part = std::find_if(parts.begin(), parts.end(),
                                          [](const BodyPart &part) { return part.content_type == mcptt_mime; });
      info_part != parts.end()) {
    McpttInfo info;
    try {
      info = parse_mcptt_info(info_part->body);
    } catch (const std::exception &) {
      return std::nullopt;
    }
    if (info.emergency_alert_area || info.group_geo_area) {
      if (asserted_identity(message) != service() || info.request_uri != user()) {
        response.status_code = 403;
        return response;
      }
      schedule([this, info] { area_notice(info); });
      return response;
    }
  }
  const auto loc = std::find_if(parts.begin(), parts.end(),
                                [](const BodyPart &part) { return part.content_type == mcptt_location_mime; });
  if (loc == parts.end()) return std::nullopt;
  location::Content content;
  try {
    content = location::parse(loc->body);
  } catch (const std::exception &) {
    // An emergency alert's location (TS 24.379 clause 6.2.9.1) is left to the priority procedures.
    return std::nullopt;
  }
  const bool with_info =
      std::any_of(parts.begin(), parts.end(), [](const BodyPart &part) { return part.content_type == mcptt_mime; });
  if (std::holds_alternative<location::Report>(content) && with_info) return std::nullopt;
  if (asserted_identity(message) != service()) {
    response.status_code = 403;
    return response;
  }
  const auto now = monotonic_ms();
  if (const auto *configuration = std::get_if<location::Configuration>(&content)) {
    location_.set_emergency(location_emergency());
    location_.configure(*configuration, now);
    location_server_ = asserted_identity(message);
    if (location_logon_pending_ && location_.configured()) {
      location_logon_pending_ = false;
      location_signal(location::Event::InitialLogOn);
    }
    event("location_configuration_received", {{"full", configuration->full},
                                              {"triggers", configuration->triggers.size()},
                                              {"emergency_triggers", configuration->emergency_triggers.size()},
                                              {"cancelled", !location_.configured()}});
  } else if (const auto *request = std::get_if<location::Request>(&content)) {
    event("location_request_received", {{"request_id", request->id}, {"refresh", request->refresh}});
    schedule([this, id = request->id] {
      location_.set_emergency(location_emergency());
      send_location_report(location_.answer(id, monotonic_ms()));
    });
  } else {
    auto entry = location::to_json(std::get<location::Report>(content));
    location_received_.push_back(entry);
    while (location_received_.size() > 64) location_received_.erase(location_received_.begin());
    event("location_report_received", entry);
  }
  return response;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 12.1.1.6: entry into an emergency alert area starts an MCPTT emergency alert;
// TS 24.379 clause 12.1.1.4: entry into or exit from a group geographic area affiliates to or leaves the group.
// Limitation: the MCPTT client holds one group, so it acts only on a notice naming that group.
void Client::area_notice(const McpttInfo &info) {
  Json entry = Json::object();
  if (info.emergency_alert_area) {
    entry = {{"kind", "emergency_alert_area"}, {"entered", *info.emergency_alert_area}};
    // TS 24.379 clause 12.1.1.6 step 1 a) ii): an emergency alert, unless already in the emergency state.
    if (*info.emergency_alert_area && !location_emergency() && alert_pending_.empty()) {
      try {
        change_alert(true);
        entry["alert"] = true;
      } catch (const std::exception &error) {
        entry["alert_error"] = error.what();
      }
    }
  } else {
    entry = {{"kind", "group_geographic_area"}, {"entered", *info.group_geo_area}, {"group_id", info.associated_group}};
    // TS 24.379 clause 12.1.1.4 step 2: affiliation to, or de-affiliation from, the group (TS 24.379 clause 9.2.1.2).
    if (info.associated_group == group()) {
      try {
        run_command(*info.group_geo_area ? "affiliate" : "deaffiliate");
        entry["affiliation"] = *info.group_geo_area;
      } catch (const std::exception &error) {
        entry["affiliation_error"] = error.what();
      }
    } else
      entry["affiliation_error"] = "the client affiliates to its configured group only";
  }
  area_notices_received_.push_back(entry);
  event("location_area_notice", entry);
}

//------------------------------------------------------------------------------
// TS 24.379 clauses 13.3.2.2 and 13.3.3.2: an authorised user's configuration change or request; with the test aids
// "location position|radio|report" that set this UE's position or radio cell and send a report.
// location request [refresh] [filter <area> in|out] [fa <alias>] [no-list] [<MCPTT ID>...]
// location configure <MCPTT ID>[,<MCPTT ID>...] <configuration JSON>
void Client::location_command(const std::string &cmd) {
  std::istringstream in(cmd);
  std::string word, verb;
  in >> word >> verb;
  const auto now = monotonic_ms();
  if (verb == "position") return location_position_command(in, now);
  if (verb == "radio") return location_radio_command(in, now);
  if (verb == "report") { // a report the user decides to send
    location_.set_emergency(location_emergency());
    send_location_report(location_.answer({}, now));
    return;
  }
  if (!authorized_) throw std::runtime_error("authorization required");
  std::vector<BodyPart> parts;
  McpttInfo info;
  std::vector<std::string> targets;
  if (verb == "request")
    location_request_parts(in, parts, info, targets);
  else if (verb == "configure")
    location_configure_parts(in, parts, targets);
  else
    throw std::runtime_error("location position|radio|report|request|configure");
  if (!targets.empty()) parts.insert(parts.begin(), {resource_lists_mime, resource_list_xml(targets)});
  if (!info.location_of_functional_alias.empty()) {
    info.request_uri = user();
    parts.insert(parts.begin(), info_parts(info).front());
  }
  const auto [type, body] = message_body(parts, "mcx-" + random_id());
  sip_.request(
      "MESSAGE", service(), type, body,
      {{"P-Preferred-Service", mcptt_icsi},
       {"Accept-Contact", "*;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mcptt\";require;explicit"}},
      [this, verb](const SipResponse &response) {
        if (response.status_code < 200) return;
        status_["location_last_result"] = {{"action", verb},
                                           {"status", response.status_code},
                                           {"warning", std::string(get_header(response.headers, "Warning"))}};
      });
  event("location_" + verb + "_sent", {{"targets", targets}, {"functional_alias", info.location_of_functional_alias}});
}

//------------------------------------------------------------------------------
// Test aid: "location position <latitude> <longitude> [altitude]" sets this UE's position for the MCPTT, MCData
// and MCVideo location reporting.
void Client::location_position_command(std::istringstream &in, std::uint64_t now) {
  double lat = 0, lon = 0;
  if (!(in >> lat >> lon)) throw std::runtime_error("location position <latitude> <longitude> [altitude]");
  auto point = location::Point::at(lat, lon);
  int altitude = 0;
  if (in >> altitude) point.altitude = static_cast<std::int16_t>(altitude);
  location_.position(point, now);
  mcdata_location_.position(point, now);
  mcvideo_location_.position(point, now);
  status_["location_position"] = {lat, lon};
}

//------------------------------------------------------------------------------
// Test aid: "location radio <dimension> <value>" sets this UE's radio cell, tracking area or other radio dimension
// for the MCPTT, MCData and MCVideo location reporting.
void Client::location_radio_command(std::istringstream &in, std::uint64_t now) {
  std::string dimension, value;
  if (!(in >> dimension >> value)) throw std::runtime_error("location radio <dimension> <value>");
  static const std::map<std::string, location::Dimension> names = {
      {"cell", location::Dimension::Cell},
      {"nr_cell", location::Dimension::NrCell},
      {"tracking_area", location::Dimension::TrackingArea},
      {"tracking_area_5g", location::Dimension::TrackingArea5G},
      {"plmn", location::Dimension::Plmn},
      {"mbms_sa", location::Dimension::MbmsSa},
      {"mbsfn", location::Dimension::Mbsfn},
      {"mbsfsa_5g", location::Dimension::Mbsfsa5G},
      {"rat", location::Dimension::Rat}};
  if (!names.contains(dimension)) throw std::runtime_error("unknown radio dimension");
  location_.radio(names.at(dimension), value, now);
  mcdata_location_.radio(names.at(dimension), value, now);
  mcvideo_location_.radio(names.at(dimension), value, now);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 13.3.3.2: an authorised user's location information request:
// location request [refresh] [filter <area> in|out] [fa <alias>] [no-list] [<MCPTT ID>...]
void Client::location_request_parts(std::istringstream &in, std::vector<BodyPart> &parts, McpttInfo &info,
                                    std::vector<std::string> &targets) {
  std::string word;
  bool no_list = false; // test aid: a request naming no one (warning 192)
  location::Request request;
  request.id = random_id();
  while (in >> word) {
    if (word == "refresh")
      request.refresh = true;
    else if (word == "no-list")
      no_list = true;
    else if (word == "filter") {
      std::string area, side;
      if (!(in >> area >> side) || (side != "in" && side != "out")) throw std::runtime_error("filter <area> in|out");
      request.filter = location::Filter{area, side == "in"};
    } else if (word == "fa") {
      if (!(in >> info.location_of_functional_alias)) throw std::runtime_error("fa <functional alias>");
    } else
      targets.push_back(word);
  }
  if (targets.empty() && info.location_of_functional_alias.empty() && !no_list)
    throw std::runtime_error("location request needs targets");
  parts.push_back({mcptt_location_mime, location::to_xml(request, location::Service::Mcptt)});
  status_["location_last_request"] = request.id;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 13.3.2.2: an authorised user's location reporting configuration change:
// location configure <MCPTT ID>[,<MCPTT ID>...] <configuration JSON>
void Client::location_configure_parts(std::istringstream &in, std::vector<BodyPart> &parts,
                                      std::vector<std::string> &targets) {
  std::string list;
  in >> list;
  std::string json;
  std::getline(in, json);
  for (std::size_t start = 0; start <= list.size();) {
    const auto end = list.find(',', start);
    const auto id = list.substr(start, end == std::string::npos ? std::string::npos : end - start);
    if (!id.empty()) targets.push_back(id);
    if (end == std::string::npos) break;
    start = end + 1;
  }
  if (targets.empty()) throw std::runtime_error("location configure <MCPTT IDs> <JSON>");
  unsigned next = 0;
  const auto configuration =
      location::configuration_from_json(Json::parse(json), [&] { return "c" + std::to_string(++next); });
  parts.push_back({mcptt_location_mime, location::to_xml(configuration, location::Service::Mcptt)});
}

//------------------------------------------------------------------------------
// TS 24.379 clause 13.3.4.1: report triggering: take the configured start position once, then send a location
// information report when a reporting trigger fires.
void Client::location_tick(std::uint64_t now) {
  if (!location_started_) {
    location_started_ = true;
    const auto settings = cfg_.at("mcptt").value("location", Json::object());
    if (settings.contains("position")) {
      const auto point =
          location::Point::at(settings.at("position").at(0).get<double>(), settings.at("position").at(1).get<double>());
      location_.position(point, now);
      mcdata_location_.position(point, now);
      mcvideo_location_.position(point, now);
    }
  }
  location_.set_emergency(location_emergency());
  if (authorized_)
    if (auto report = location_.poll(now)) send_location_report(*report);
}

//------------------------------------------------------------------------------
// Helper: the MCPTT location state for the status document.
Json Client::location_status() const {
  Json out = {{"configured", location_.configured()},
              {"emergency", location_.emergency()},
              {"pending_triggers", location_.pending()},
              {"sent", location_sent_},
              {"received", location_received_},
              {"area_notices", area_notices_received_},
              {"server", location_server_}};
  if (location_.configured()) out["configuration"] = location::to_json(location_.configuration());
  return out;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.2.5.1: the location of an MCData emergency: the current coordinate once the UE has one,
// otherwise the configured one.
EmergencyLocation Client::mcdata_emergency_location() const {
  if (const auto position = mcdata_location_.last_position()) return {position->latitude, position->longitude};
  const auto &location = cfg_.at("mcdata").contains("emergency_location") ? cfg_.at("mcdata").at("emergency_location")
                                                                          : cfg_.at("mcptt").at("emergency_location");
  return {location.at("latitude").get<std::uint32_t>(), location.at("longitude").get<std::uint32_t>()};
}

//------------------------------------------------------------------------------
// Helper: send an MCData location SIP MESSAGE (report, request or configuration), its signalling protected like
// the other MCData signalling.
void Client::mcdata_send_location(std::vector<BodyPart> parts, std::vector<SipHeader> headers,
                                  const std::string &target, std::function<void(const SipResponse &)> done) {
  parts = mcdata_seal_signalling(std::move(parts)); // the location XML (TS 24.282 annex D.4) is encrypted like the rest
  std::string type, body;
  if (parts.size() == 1) {
    type = parts.front().content_type;
    body = parts.front().body;
  } else {
    const auto boundary = "mcdata-" + random_id();
    type = "multipart/mixed; boundary=" + boundary;
    body = multipart_body(parts, boundary);
  }
  sip_.request("MESSAGE", target, type, body, headers, [this, done](const SipResponse &response) {
    if (response.status_code >= 200) done(mcdata_opened_response(response));
  });
}

//------------------------------------------------------------------------------
// TS 24.282 clause 17.3.4.2: send an MCData location information report to the participating function.
void Client::mcdata_send_location_report(location::Report report) {
  report.location.timestamp = utc_timestamp();
  const auto entry = location::to_json(report);
  mcdata_send_location({{mcdata::location_mime, location::to_xml(report, location::Service::Mcdata)}},
                       {{"Accept-Contact", "*;+g.3gpp.mcdata;require;explicit"}, {"P-Preferred-Service", mcdata::icsi}},
                       mcdata_location_server_.empty() ? mcdata_service() : mcdata_location_server_,
                       [this](const SipResponse &response) {
                         if (response.status_code >= 300)
                           failure("MCData location report: " + std::to_string(response.status_code));
                       });
  mcdata_location_sent_.push_back(entry);
  while (mcdata_location_sent_.size() > 32) mcdata_location_sent_.erase(mcdata_location_sent_.begin());
  event("mcdata_location_report_sent", entry);
}

//------------------------------------------------------------------------------
// TS 24.282 clauses 17.3.2.1 and 17.3.3.1: a location reporting configuration or a location information request;
// the reports answering this user's requests; and the area notices of TS 24.282 clauses 16.2.1.4 and 16.2.1.5.
std::optional<SipResponse> Client::mcdata_location_incoming(const SipMessage &message) {
  auto parts = message.parts.empty() && !message.body.empty() ? parse_body_parts(message.content_type, message.body)
                                                              : message.parts;
  SipResponse response;
  response.status_code = 200;
  const auto info_part = std::find_if(parts.begin(), parts.end(),
                                      [](const BodyPart &part) { return part.content_type == mcdata::info_mime; });
  const auto loc = std::find_if(parts.begin(), parts.end(),
                                [](const BodyPart &part) { return part.content_type == mcdata::location_mime; });
  if (info_part != parts.end() && loc == parts.end()) {
    mcdata::Info info;
    try {
      info = mcdata::parse_info(info_part->body);
    } catch (const std::exception &) {
      return std::nullopt;
    }
    if (!info.emergency_alert_area && !info.group_geo_area) return std::nullopt;
    if (asserted_identity(message) != mcdata_service() || info.request_uri != mcdata_user()) {
      response.status_code = 403;
      return response;
    }
    schedule([this, info] { mcdata_area_notice(info); });
    return response;
  }
  if (loc == parts.end()) return std::nullopt;
  location::Content content;
  try {
    content = location::parse(loc->body);
  } catch (const std::exception &) {
    return std::nullopt;
  }
  if (std::holds_alternative<location::Report>(content) && info_part != parts.end()) return std::nullopt;
  if (asserted_identity(message) != mcdata_service()) {
    response.status_code = 403;
    return response;
  }
  const auto now = monotonic_ms();
  if (const auto *configuration = std::get_if<location::Configuration>(&content)) {
    mcdata_location_.set_emergency(mcdata_location_emergency());
    mcdata_location_.configure(*configuration, now);
    mcdata_location_server_ = asserted_identity(message);
    if (mcdata_location_logon_pending_ && mcdata_location_.configured()) {
      mcdata_location_logon_pending_ = false;
      mcdata_location_.signal(location::Event::InitialLogOn, now);
    }
    event("mcdata_location_configuration_received", {{"full", configuration->full},
                                                     {"triggers", configuration->triggers.size()},
                                                     {"cancelled", !mcdata_location_.configured()}});
  } else if (const auto *request = std::get_if<location::Request>(&content)) {
    event("mcdata_location_request_received", {{"request_id", request->id}});
    schedule([this, id = request->id] {
      mcdata_location_.set_emergency(mcdata_location_emergency());
      mcdata_send_location_report(mcdata_location_.answer(id, monotonic_ms()));
    });
  } else {
    auto entry = location::to_json(std::get<location::Report>(content));
    mcdata_location_received_.push_back(entry);
    while (mcdata_location_received_.size() > 64) mcdata_location_received_.erase(mcdata_location_received_.begin());
    event("mcdata_location_report_received", entry);
  }
  return response;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 16.2.1.5: entry into an emergency alert area starts an MCData emergency alert;
// TS 24.282 clause 16.2.1.4: entry into or exit from a group geographic area affiliates to or leaves the group.
// Limitation: the MCData client affiliates its configured group set as a whole, so it acts only on a notice naming
// its configured group.
void Client::mcdata_area_notice(const mcdata::Info &info) {
  Json entry;
  if (info.emergency_alert_area) {
    entry = {{"kind", "emergency_alert_area"}, {"entered", *info.emergency_alert_area}};
    // An MCData emergency alert, unless already in the emergency state (TS 24.282 clause 16.2.1.1). The alert
    // procedure first checks the user's right (clause 6.2.8.1.6, the MCData user profile); without it the user is
    // told (this event) and nothing is sent.
    if (*info.emergency_alert_area && !mcdata_location_emergency()) {
      try {
        mcdata_alert(true, {}, {});
        entry["alert"] = true;
      } catch (const std::exception &error) {
        entry["alert"] = false;
        entry["alert_error"] = error.what();
      }
    }
  } else {
    entry = {{"kind", "group_geographic_area"}, {"entered", *info.group_geo_area}, {"group_id", info.associated_group}};
    if (info.associated_group == mcdata_group()) {
      try {
        mcdata_affiliate(*info.group_geo_area);
        entry["affiliation"] = *info.group_geo_area;
      } catch (const std::exception &error) {
        entry["affiliation_error"] = error.what();
      }
    } else
      entry["affiliation_error"] = "the client affiliates to its configured MCData group only";
  }
  mcdata_area_notices_received_.push_back(entry);
  event("mcdata_location_area_notice", entry);
}

//------------------------------------------------------------------------------
// TS 24.282 clauses 17.3.2.2 and 17.3.3.2: "mcdata-location request|configure", as for the MCPTT command.
void Client::mcdata_location_command(const std::string &cmd) {
  if (!mcdata_authorized_) throw std::runtime_error("MCData service authorisation required");
  std::istringstream in(cmd);
  std::string word, verb;
  in >> word >> verb;
  std::vector<BodyPart> parts;
  mcdata::Info info;
  std::vector<std::string> targets;
  bool no_list = false;
  if (verb == "request") {
    location::Request request;
    request.id = random_id();
    while (in >> word) {
      if (word == "refresh")
        request.refresh = true;
      else if (word == "no-list")
        no_list = true;
      else if (word == "filter") {
        std::string area, side;
        if (!(in >> area >> side) || (side != "in" && side != "out")) throw std::runtime_error("filter <area> in|out");
        request.filter = location::Filter{area, side == "in"};
      } else if (word == "fa") {
        if (!(in >> info.location_of_functional_alias)) throw std::runtime_error("fa <functional alias>");
      } else
        targets.push_back(word);
    }
    if (targets.empty() && info.location_of_functional_alias.empty() && !no_list)
      throw std::runtime_error("mcdata-location request needs targets");
    parts.push_back({mcdata::location_mime, location::to_xml(request, location::Service::Mcdata)});
    status_["mcdata_location_last_request"] = request.id;
  } else if (verb == "configure") {
    std::string list, json;
    in >> list;
    std::getline(in, json);
    for (std::size_t start = 0; start <= list.size();) {
      const auto end = list.find(',', start);
      const auto id = list.substr(start, end == std::string::npos ? std::string::npos : end - start);
      if (!id.empty()) targets.push_back(id);
      if (end == std::string::npos) break;
      start = end + 1;
    }
    if (targets.empty()) throw std::runtime_error("mcdata-location configure <MCData IDs> <JSON>");
    unsigned next = 0;
    const auto configuration =
        location::configuration_from_json(Json::parse(json), [&] { return "c" + std::to_string(++next); });
    parts.push_back({mcdata::location_mime, location::to_xml(configuration, location::Service::Mcdata)});
  } else
    throw std::runtime_error("mcdata-location request|configure");
  if (!targets.empty()) parts.insert(parts.begin(), {resource_lists_mime, resource_list_xml(targets)});
  if (!info.location_of_functional_alias.empty()) {
    info.request_uri = mcdata_user();
    parts.insert(parts.begin(), {mcdata::info_mime, mcdata::info_xml(info)});
  }
  mcdata_send_location(
      std::move(parts),
      {{"P-Preferred-Service", mcdata::icsi},
       {"Accept-Contact", "*;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mcdata\";require;explicit"}},
      mcdata_service(), [this, verb](const SipResponse &response) {
        status_["mcdata_location_last_result"] = {{"action", verb},
                                                  {"status", response.status_code},
                                                  {"warning", std::string(get_header(response.headers, "Warning"))}};
      });
}

//------------------------------------------------------------------------------
// TS 24.282 clause 17.3.4.1: report triggering: send a location information report when a reporting trigger fires.
void Client::mcdata_location_tick(std::uint64_t now) {
  if (!mcdata_enabled()) return;
  mcdata_location_.set_emergency(mcdata_location_emergency());
  if (mcdata_authorized_)
    if (auto report = mcdata_location_.poll(now)) mcdata_send_location_report(*report);
}

//------------------------------------------------------------------------------
// Helper: the MCData location state for the status document.
Json Client::mcdata_location_status() const {
  Json out = {{"configured", mcdata_location_.configured()},
              {"emergency", mcdata_location_.emergency()},
              {"sent", mcdata_location_sent_},
              {"received", mcdata_location_received_},
              {"area_notices", mcdata_area_notices_received_},
              {"server", mcdata_location_server_}};
  if (mcdata_location_.configured()) out["configuration"] = location::to_json(mcdata_location_.configuration());
  return out;
}

} // namespace mcx
