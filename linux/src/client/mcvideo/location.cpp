//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// MCVideo ad hoc group emergency alert (TS 24.281 clause 12.1A.1), location procedures (clause 18.3) and area
// notices (clauses 11.2.1.4, 11.2.1.5). Member functions of Client (declared in client.hpp).
//
// Specifications: TS 24.281 V19.6.0 (Rel-19).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.281 clause 12.1A.1.1: ad hoc group emergency alert origination: a MESSAGE with <adhoc-alert-ind> true, the
// participant criteria and a location; TS 24.281 clause 12.1A.1.2: its cancellation: <adhoc-alert-ind> false, with
// the ad hoc group in <mcvideo-request-uri>.
void Client::mcvideo_adhoc_alert(const std::string &arguments) {
  if (!mcvideo_authorized_) throw std::runtime_error("ad hoc alert requires MCVideo service authorisation");
  McpttInfo info;
  info.client_id = client_id();
  const bool cancel = arguments == "cancel";
  if (cancel) {
    if (mcvideo_adhoc_alert_group_.empty()) throw std::runtime_error("no MCVideo ad hoc alert to cancel");
    info.request_uri = mcvideo_adhoc_alert_group_;
    info.adhoc_alert = false;
    // TS 24.281 clause 12.1A.1.2: cancel pending until the receipt (clause 12.1A.1.3) arrives.
    mcvideo_ah_mea_ = 4;
  } else {
    if (arguments.empty()) throw std::runtime_error("mcvideo-adhoc-alert <criteria> | mcvideo-adhoc-alert cancel");
    info.adhoc_alert = true;
    info.participant_criteria = arguments;
    mcvideo_ah_mea_ = 2;
  }
  std::vector<BodyPart> parts{{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)}};
  if (!cancel && cfg_.at("mcptt").contains("emergency_location"))
    parts.push_back(
        {mcvideo_service.location_mime, service_location_xml(configured_emergency_location(), mcvideo_service)});
  const auto boundary = "mcx-" + random_id();
  mcvideo_adhoc_alert_result_ = {{"cancel", cancel}, {"status", 0}, {"warning", ""}};
  const auto [type, body] = message_body(parts, boundary); // one part as it is, several as multipart/mixed
  sip_.request("MESSAGE", mcvideo_service_uri(), type, body, mcvideo_call_headers(),
               [this, cancel](const SipResponse &response) {
                 if (response.status_code < 200) return;
                 mcvideo_adhoc_alert_result_ = {{"cancel", cancel},
                                                {"status", response.status_code},
                                                {"warning", std::string(get_header(response.headers, "Warning"))}};
                 if (response.status_code >= 300) {
                   // A refused cancellation leaves the alert initiated.
                   if (!cancel) mcvideo_ah_mea_ = 1;
                   if (cancel && mcvideo_ah_mea_ == 4) mcvideo_ah_mea_ = 3;
                   return;
                 }
                 // The 2xx leaves the cancellation pending; the receipt ends it.
                 if (cancel) return;
                 for (const auto &part : response.parts.empty()
                                             ? std::vector<BodyPart>{{response.content_type, response.body}}
                                             : response.parts)
                   if (part.content_type.starts_with(mcvideo_service.info_mime))
                     mcvideo_adhoc_alert_group_ = parse_service_info(part.body, mcvideo_service).calling_group;
                 mcvideo_ah_mea_ = 3;
               });
  event("mcvideo_adhoc_alert_requested", {{"cancel", cancel}, {"criteria", info.participant_criteria}});
}

//------------------------------------------------------------------------------
// TS 24.281 annex F.3.3: the emergency state that selects the <EmergencyLocationInformation> of the location
// reporting configuration: an emergency alert, or an emergency call in progress.
bool Client::mcvideo_location_emergency() const {
  return mcvideo_alerting_ || (mcvideo_call_ && mcvideo_call_->priority == "emergency");
}

//------------------------------------------------------------------------------
// TS 24.281 clause 18.3.4.2: send a location information report: a SIP MESSAGE with the location-info body to the
// participating function.
void Client::mcvideo_send_location_report(location::Report report) {
  report.location.timestamp = utc_timestamp();
  const auto entry = location::to_json(report);
  sip_.request("MESSAGE", mcvideo_location_server_.empty() ? mcvideo_service_uri() : mcvideo_location_server_,
               mcvideo_service.location_mime, location::to_xml(report, location::Service::Mcvideo),
               {{"Accept-Contact", std::string("*;+") + mcvideo_service.feature_tag + ";require;explicit"},
                {"P-Preferred-Service", mcvideo_service.icsi}},
               [this](const SipResponse &response) {
                 if (response.status_code >= 300)
                   failure("MCVideo location report: " + std::to_string(response.status_code));
               });
  mcvideo_location_sent_.push_back(entry);
  while (mcvideo_location_sent_.size() > 32) mcvideo_location_sent_.erase(mcvideo_location_sent_.begin());
  event("mcvideo_location_report_sent", entry);
}

//------------------------------------------------------------------------------
// TS 24.281 clauses 18.3.2.1 and 18.3.3.1: a location reporting configuration or a location information request
// from the participating function; the reports answering this user's requests; and the notices of entry into or
// exit from an area of TS 24.281 clauses 11.2.1.4 and 11.2.1.5.
std::optional<SipResponse> Client::mcvideo_location_incoming(const SipMessage &message) {
  auto parts = message.parts.empty() && !message.body.empty() ? parse_body_parts(message.content_type, message.body)
                                                              : message.parts;
  SipResponse response;
  response.status_code = 200;
  const auto info_part = std::find_if(
      parts.begin(), parts.end(), [](const BodyPart &part) { return part.content_type == mcvideo_service.info_mime; });
  const auto loc = std::find_if(parts.begin(), parts.end(), [](const BodyPart &part) {
    return part.content_type == mcvideo_service.location_mime;
  });
  if (info_part != parts.end() && loc == parts.end()) {
    McpttInfo info;
    try {
      info = parse_service_info(info_part->body, mcvideo_service);
    } catch (const std::exception &) {
      return std::nullopt;
    }
    if (!info.emergency_alert_area && !info.group_geo_area) return std::nullopt;
    if (asserted_identity(message) != mcvideo_service_uri() || info.request_uri != mcvideo_user()) {
      response.status_code = 403;
      return response;
    }
    schedule([this, info] { mcvideo_area_notice(info); });
    return response;
  }
  if (loc == parts.end()) return std::nullopt;
  location::Content content;
  location::Service service = location::Service::Mcptt;
  try {
    content = location::parse(loc->body, &service);
  } catch (const std::exception &) {
    response.status_code = 400;
    return response;
  }
  if (service != location::Service::Mcvideo) {
    response.status_code = 400;
    return response;
  }
  // A report beside an mcvideo-info body is an emergency alert's location (TS 24.281 clause 11.2.1.1 steps 5 and 6).
  if (std::holds_alternative<location::Report>(content) && info_part != parts.end()) return std::nullopt;
  if (asserted_identity(message) != mcvideo_service_uri()) {
    response.status_code = 403;
    return response;
  }
  const auto now = monotonic_ms();
  if (const auto *configuration = std::get_if<location::Configuration>(&content)) {
    mcvideo_location_.set_emergency(mcvideo_location_emergency());
    mcvideo_location_.configure(*configuration, now);
    mcvideo_location_server_ = asserted_identity(message);
    if (mcvideo_location_logon_pending_ && mcvideo_location_.configured()) {
      mcvideo_location_logon_pending_ = false;
      mcvideo_location_.signal(location::Event::InitialLogOn, now);
    }
    event("mcvideo_location_configuration_received", {{"full", configuration->full},
                                                      {"triggers", configuration->triggers.size()},
                                                      {"cancelled", !mcvideo_location_.configured()}});
  } else if (const auto *request = std::get_if<location::Request>(&content)) {
    event("mcvideo_location_request_received", {{"request_id", request->id}});
    schedule([this, id = request->id] {
      mcvideo_location_.set_emergency(mcvideo_location_emergency());
      mcvideo_send_location_report(mcvideo_location_.answer(id, monotonic_ms()));
    });
  } else {
    auto entry = location::to_json(std::get<location::Report>(content));
    mcvideo_location_received_.push_back(entry);
    while (mcvideo_location_received_.size() > 64) mcvideo_location_received_.erase(mcvideo_location_received_.begin());
    event("mcvideo_location_report_received", entry);
  }
  return response;
}

//------------------------------------------------------------------------------
// TS 24.281 clause 11.2.1.4 step 2 with clause 8.2.1.2: affiliate to, or de-affiliate from, the group the
// participating function names, and no other. The selected group is affiliated as a whole; another group joins or
// leaves the set of extra groups, and the rest of the affiliation stays.
void Client::mcvideo_area_affiliation(const std::string &group, bool entered) {
  if (group.empty()) throw std::runtime_error("the notice names no group");
  if (group == mcvideo_group()) {
    // The selected group is in every affiliation request this client sends (mcvideo_affiliate): it can leave
    // only with the extra groups.
    if (!entered && !mcvideo_extra_groups_.empty())
      throw std::runtime_error("the selected MCVideo group stays affiliated with the other groups");
    return mcvideo_affiliate(entered);
  }
  if (entered) return mcvideo_affiliate(true, group);
  if (mcvideo_extra_groups_.erase(group) == 0) throw std::runtime_error("not affiliated to the group");
  // The remaining set again, without the group (TS 24.281 clause 8.2.1.2: the whole set in each request).
  mcvideo_affiliate(true);
}

//------------------------------------------------------------------------------
// TS 24.281 clause 11.2.1.5: entry into an emergency alert area starts an MCVideo emergency alert;
// TS 24.281 clause 11.2.1.4: entry into or exit from a group geographic area affiliates to or leaves the group. The participating
// function sends these notices by TS 24.281 clause 18.2.4.1 steps 2 and 3.
void Client::mcvideo_area_notice(const McpttInfo &info) {
  Json entry;
  if (info.emergency_alert_area) {
    entry = {{"kind", "emergency_alert_area"}, {"entered", *info.emergency_alert_area}};
    if (*info.emergency_alert_area && !mcvideo_location_emergency()) {
      try {
        mcvideo_alert(true);
        entry["alert"] = true;
      } catch (const std::exception &error) {
        entry["alert_error"] = error.what();
      }
    }
  } else {
    entry = {{"kind", "group_geographic_area"}, {"entered", *info.group_geo_area}, {"group_id", info.associated_group}};
    try {
      mcvideo_area_affiliation(info.associated_group, *info.group_geo_area);
      entry["affiliation"] = *info.group_geo_area;
    } catch (const std::exception &error) {
      entry["affiliation_error"] = error.what();
    }
  }
  mcvideo_area_notices_received_.push_back(entry);
  event("mcvideo_location_area_notice", entry);
}

//------------------------------------------------------------------------------
// TS 24.281 clauses 18.3.3.2 (location information request) and 18.3.2.2 (location reporting configuration) from an
// authorized MCVideo client: "mcvideo-location request|configure", as for the MCPTT command, with the MCVideo IDs of
// the targets in a resource-lists body.
void Client::mcvideo_location_command(const std::string &cmd) {
  if (!mcvideo_authorized_) throw std::runtime_error("MCVideo service authorisation required");
  std::istringstream in(cmd);
  std::string word, verb;
  in >> word >> verb;
  std::vector<BodyPart> parts;
  McpttInfo info;
  std::vector<std::string> targets;
  bool no_list = false; // test aid: a request naming no one (warning 191)
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
      throw std::runtime_error("mcvideo-location request needs targets");
    parts.push_back({mcvideo_service.location_mime, location::to_xml(request, location::Service::Mcvideo)});
    mcvideo_location_request_ = request.id;
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
    if (targets.empty()) throw std::runtime_error("mcvideo-location configure <MCVideo IDs> <JSON>");
    unsigned next = 0;
    const auto configuration =
        location::configuration_from_json(Json::parse(json), [&] { return "c" + std::to_string(++next); });
    parts.push_back({mcvideo_service.location_mime, location::to_xml(configuration, location::Service::Mcvideo)});
  } else
    throw std::runtime_error("mcvideo-location request|configure");
  if (!targets.empty()) parts.insert(parts.begin(), {resource_lists_mime, resource_list_xml(targets)});
  if (!info.location_of_functional_alias.empty()) {
    info.request_uri = mcvideo_user();
    parts.insert(parts.begin(), {mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)});
  }
  const auto boundary = "mcx-" + random_id();
  const auto [type, body] = message_body(parts, boundary);
  sip_.request("MESSAGE", mcvideo_service_uri(), type, body, mcvideo_call_headers(),
               [this, verb](const SipResponse &response) {
                 if (response.status_code < 200) return;
                 mcvideo_location_result_ = {{"action", verb},
                                             {"status", response.status_code},
                                             {"warning", std::string(get_header(response.headers, "Warning"))}};
               });
  event("mcvideo_location_" + verb + "_sent",
        {{"targets", targets}, {"functional_alias", info.location_of_functional_alias}});
}

//------------------------------------------------------------------------------
// TS 24.281 clause 18.3.4.1: report triggering: send a location information report when a reporting trigger fires
// and the minimumReportInterval allows.
void Client::mcvideo_location_tick(std::uint64_t now) {
  if (!mcvideo_enabled()) return;
  mcvideo_location_.set_emergency(mcvideo_location_emergency());
  if (mcvideo_authorized_)
    if (auto report = mcvideo_location_.poll(now)) mcvideo_send_location_report(*report);
}

//------------------------------------------------------------------------------
// Helper: the MCVideo location state for the status document.
Json Client::mcvideo_location_status() const {
  Json out = {{"configured", mcvideo_location_.configured()},
              {"emergency", mcvideo_location_.emergency()},
              {"sent", mcvideo_location_sent_},
              {"received", mcvideo_location_received_},
              {"area_notices", mcvideo_area_notices_received_},
              {"server", mcvideo_location_server_},
              {"last_request", mcvideo_location_request_},
              {"last_result", mcvideo_location_result_}};
  if (mcvideo_location_.configured()) out["configuration"] = location::to_json(mcvideo_location_.configuration());
  return out;
}

} // namespace mcx
