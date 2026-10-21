//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Functional aliases: the MCPTT client's functional alias status change (activation, takeover, deactivation) and the
// determination of its own functional alias status.
//
// Specifications: TS 24.379 V19.7.0 (Rel-19).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.379 clause 9A.2.1.2: the functional alias status change procedure: send a SIP PUBLISH with the
// functional alias request to activate, take over or deactivate aliases. The answer only says the request was taken;
// the outcome per alias comes in the NOTIFY of the status subscription (clause 9A.2.1.3), which clause 9A.2.1.1 sets up
// first. A take-over asked by the user carries <take-over> only for the aliases the NOTIFY offered as
// "take-over-possible" (clause 9A.2.1.2 step 6 d) and NOTE 4); the others are asked for as activations and taken over
// once offered. Command:
//   fa activate|takeover|deactivate <alias>...   fa use <alias>|none   fa bind|unbind <alias> <group>[,<group>...]
// Test aid: "fa publish 0|4294967295 [<alias>...]" sends exactly the aliases and the Expires given, as another
// client would, so that the server's handling of the whole set (clause 9A.2.2.2.3 steps 12 and 13) can be exercised.
void Client::functional_alias_command(const std::string &arguments) {
  std::istringstream words(arguments);
  std::string action, alias;
  words >> action;
  std::vector<std::string> aliases;
  while (words >> alias) aliases.push_back(alias);
  if (action == "use") {
    if (aliases.size() != 1) throw std::runtime_error("usage: fa use <alias>|none");
    functional_alias_ = aliases.front() == "none" ? std::string{} : aliases.front();
    return;
  }
  if (action == "bind" || action == "unbind") {
    if (aliases.size() != 2 || !authorized_)
      throw std::runtime_error("usage: fa bind|unbind <alias> <group>[,<group>...]");
    std::vector<std::string> groups;
    for (std::istringstream list(aliases[1]); std::getline(list, alias, ',');)
      if (!alias.empty()) groups.push_back(alias);
    return alias_binding(action == "bind", aliases[0], groups);
  }
  if (action == "publish") {
    if (aliases.empty() || (aliases.front() != "0" && aliases.front() != "4294967295") || !authorized_)
      throw std::runtime_error("usage: fa publish 0|4294967295 [<alias>...]");
    const std::set<std::string> listed(aliases.begin() + 1, aliases.end());
    return send_alias_publish("publish", {aliases.begin() + 1, aliases.end()}, listed, aliases.front(), false);
  }
  if ((action != "activate" && action != "takeover" && action != "deactivate") || aliases.empty() || !authorized_)
    throw std::runtime_error("usage: fa activate|takeover|deactivate <alias>...");
  if (action == "takeover") require_profile({"allow-takeover-functional-alias-other-user"});

  // TS 24.379 clause 9A.2.1.1: the status determination for the user before the status change.
  if (!alias_status_answered_) {
    pending_alias_commands_.push_back(arguments);
    if (alias_status_dialog_.empty()) alias_status_subscribe();
    return;
  }
  if (action != "takeover") {
    for (const auto &requested : aliases) takeover_wanted_.erase(requested);
    publish_functional_alias(action, aliases, false);
    return;
  }
  std::vector<std::string> possible, activations;
  for (const auto &requested : aliases)
    (takeover_possible_aliases_.contains(requested) ? possible : activations).push_back(requested);
  if (!possible.empty()) publish_functional_alias(action, possible, true);
  if (!activations.empty()) {
    takeover_wanted_.insert(activations.begin(), activations.end());
    publish_functional_alias(action, activations, false);
  }
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9A.2.1.2: the SIP PUBLISH of one status change. The participating function takes the aliases
// listed as the user's whole set (clause 9A.2.2.2.3 steps 12 to 14: one left out is deactivated, Expires 0
// deactivates all), so the PUBLISH lists every alias wanted active (step 6 a): those the last NOTIFY lists "activated" or
// "activating", or the set of a change still awaiting its NOTIFY, with this change applied; Expires 4294967295 (step 4), or 0 when none is wanted
// (step 5; the PIDF then lists no alias, clause 9A.3.1.2 item 7).
void Client::publish_functional_alias(const std::string &action, const std::vector<std::string> &aliases,
                                      bool take_over) {
  auto wanted = alias_publish_open_.empty() ? standing_aliases_ : aliases_wanted_;
  for (const auto &alias : aliases)
    if (action == "deactivate")
      wanted.erase(alias);
    else
      wanted.insert(alias);
  send_alias_publish(action, aliases, wanted, wanted.empty() ? "0" : "4294967295", take_over);
}

//------------------------------------------------------------------------------
// Helper: the PUBLISH listing `listed`, the per-user PIDF with a new <p-id-fa> (step 6 e)) and <take-over> when
// `take_over`; `aliases` are the ones the change is about, whose outcome the NOTIFY gives.
void Client::send_alias_publish(const std::string &action, const std::vector<std::string> &aliases,
                                const std::set<std::string> &listed, const std::string &expires, bool take_over) {
  FunctionalAliasRequest request{user(), client_id(), random_id(), {listed.begin(), listed.end()}, take_over};
  aliases_wanted_ = listed;
  alias_publish_open_ = request.publish_id;
  McpttInfo info;
  info.request_uri = user();
  info.client_id = client_id();
  const auto boundary = "mcx-" + random_id();
  const auto body =
      multipart_body({info_parts(info).front(), {"application/pidf+xml", functional_alias_xml(request)}}, boundary);
  status_["last_fa_result"] = {{"action", action},
                               {"aliases", aliases},
                               {"status", 0},
                               {"take_over", take_over},
                               {"p_id_fa", request.publish_id},
                               {"outcomes", Json::object()}};
  sip_.publish(service(), "multipart/mixed; boundary=" + boundary, body,
               {{"Event", "presence"}, {"Expires", expires}, {"P-Preferred-Service", mcptt_icsi}},
               [this, action, aliases, pid = request.publish_id](const SipResponse &response) {
                 if (response.status_code < 200) return;
                 if (status_["last_fa_result"].value("p_id_fa", "") == pid) {
                   status_["last_fa_result"]["status"] = response.status_code;
                   status_["last_fa_result"]["warning"] = std::string(get_header(response.headers, "Warning"));
                 }
                 if (response.status_code == 200) {
                   for (const auto &requested : aliases) {
                     std::erase(requested_aliases_, requested);
                     if (action != "deactivate") requested_aliases_.push_back(requested);
                   }
                 } else {
                   if (alias_publish_open_ == pid) alias_publish_open_.clear(); // the standing set again
                   for (const auto &requested : aliases) takeover_wanted_.erase(requested);
                   failure("functional alias " + action + ": " + std::to_string(response.status_code));
                 }
               });
  event("functional_alias_requested", {{"action", action}, {"aliases", aliases}, {"take_over", take_over}});
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9A.2.1.3 steps 1 to 4: subscribe to this user's functional alias status: the mcptt-info body names
// the user with <request-type> "functional-alias-status-determination", Expires 4294967295 for the state and its
// changes. The status changes held back wait for the answer; a refusal lets them go without it.
void Client::alias_status_subscribe() {
  McpttInfo info;
  info.request_uri = user();
  info.client_id = client_id();
  info.service["request-type"] = "functional-alias-status-determination";
  const auto part = info_parts(info).front();
  alias_status_dialog_ = sip_.subscribe(
      service(), "presence", 4294967295u, {{"P-Preferred-Service", mcptt_icsi}, {"Accept", "application/pidf+xml"}},
      [this](const SipResponse &response) {
        if (response.status_code < 200) return;
        status_["functional_alias_subscription_result"] = response.status_code;
        if (response.status_code >= 300) alias_status_dialog_.clear();
        alias_status_answered_ = true;
        for (const auto &pending : std::exchange(pending_alias_commands_, {}))
          schedule([this, pending] { functional_alias_command(pending); });
      },
      part.content_type, part.body);
  event("functional_alias_subscription_requested");
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9A.2.1.3 and clause 9A.3.1.2: the NOTIFY with this user's functional alias status. It replaces the
// aliases known active ("activated", or "deactivating" while still active), those standing ("activated" or "activating":
// the base of the next PUBLISH) and those that may be taken over; the <p-id-fa> of the last status change gives its
// outcome per alias. A change fires the location trigger
// (<FunctionalAliasActivation> or <FunctionalAliasDeactivation>).
SipResponse Client::alias_status_notify(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  if (asserted_identity(message) != service()) {
    response.status_code = 403;
    return response;
  }
  for (const auto &part :
       message.parts.empty() ? std::vector<BodyPart>{{message.content_type, message.body}} : message.parts) {
    if (part.content_type != "application/pidf+xml") continue;
    FunctionalAliasStatus notified;
    try {
      notified = parse_functional_alias_status(part.body);
    } catch (const std::exception &) {
      response.status_code = 400;
      return response;
    }
    if (notified.mcdata || notified.mcvideo || notified.per_alias || notified.entity != user()) {
      response.status_code = 400;
      return response;
    }
    std::set<std::string> active, standing, possible;
    std::map<std::string, std::string> statuses;
    for (const auto &entry : notified.entries) {
      statuses[entry.alias] = entry.status;
      if (entry.status == "activated" || entry.status == "deactivating")
        active.insert(entry.alias);
      else if (entry.status == "take-over-possible")
        possible.insert(entry.alias);
      if (entry.status == "activated" || entry.status == "activating") standing.insert(entry.alias);
    }
    for (const auto &alias : active)
      if (!active_aliases_.contains(alias)) {
        location_alias_ = alias;
        location_signal(location::Event::FunctionalAliasActivation);
      }
    for (const auto &alias : active_aliases_)
      if (!active.contains(alias)) {
        location_alias_ = alias;
        location_signal(location::Event::FunctionalAliasDeactivation);
      }
    active_aliases_ = active;
    standing_aliases_ = standing;
    takeover_possible_aliases_ = possible;
    auto &last = status_["last_fa_result"];
    const bool handled =
        !notified.publish_id.empty() && last.is_object() && last.value("p_id_fa", "") == notified.publish_id;
    if (!notified.publish_id.empty() && notified.publish_id == alias_publish_open_) alias_publish_open_.clear();
    // The take-over the user asked for, once the alias is offered; a refused one is dropped.
    const auto take_over = settle_alias_outcomes(last, statuses, handled, takeover_wanted_);
    if (!take_over.empty()) schedule([this, take_over] { publish_functional_alias("takeover", take_over, true); });
    event("functional_alias_notified", {{"p_id_fa", notified.publish_id},
                                        {"active", active_aliases_},
                                        {"take_over_possible", takeover_possible_aliases_}});
  }
  if (get_header(message.headers, "Subscription-State").starts_with("terminated")) {
    alias_status_dialog_.clear();
    alias_status_answered_ = false;
  }
  return response;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9A.2.1.3 (TS 24.282 clause 22.2.1.3, TS 24.281 clause 20.2.1.3): the outcome of each alias of the
// last status change (`last`: action, aliases, outcomes) from the statuses of a NOTIFY. The NOTIFY with the change's
// p-id-fa (`handled`) decides every alias; an alias still under way there is kept pending, and a later NOTIFY decides
// it. Returns the aliases decided "take-over-possible" for which the user asked a take-over (removed from `wanted`).
std::vector<std::string> Client::settle_alias_outcomes(Json &last, const std::map<std::string, std::string> &statuses,
                                                       bool handled, std::set<std::string> &wanted) {
  std::vector<std::string> take_over;
  if (!last.is_object() || !last.contains("aliases")) return take_over;
  const bool deactivation = last.value("action", "") == "deactivate";
  std::vector<std::string> open;
  if (handled)
    open = last["aliases"].get<std::vector<std::string>>();
  else if (last.contains("pending"))
    open = last["pending"].get<std::vector<std::string>>();
  if (open.empty()) return take_over;
  std::vector<std::string> pending;
  for (const auto &alias : open) {
    const auto found = statuses.find(alias);
    const auto outcome = client_rules::alias_outcome(found == statuses.end() ? "" : found->second, deactivation);
    if (!outcome) {
      pending.push_back(alias);
      continue;
    }
    last["outcomes"][alias] = *outcome;
    if (wanted.erase(alias) && *outcome == "take-over-possible") take_over.push_back(alias);
  }
  last["pending"] = pending;
  return take_over;
}

//------------------------------------------------------------------------------
// TS 24.379 clauses 9A.4.2.1.2 (binding) and 9A.4.2.1.3 (unbinding): the MCPTT client sends a SIP MESSAGE with
// <request-type> "fa-group-binding-req", the <binding-ind> and the functional alias, and the groups in a resource-lists
// body. The user profile must allow it (<allow-functional-alias-binding-with-group>), and a binding names an alias this
// client activated. The result, with any Warning, is kept in alias_binding_result.
void Client::alias_binding(bool bind, const std::string &alias, const std::vector<std::string> &groups) {
  if (groups.empty()) throw std::runtime_error("usage: fa bind|unbind <alias> <group>[,<group>...]");
  require_profile({"allow-functional-alias-binding-with-group"});
  if (bind && !active_aliases_.contains(alias))
    throw std::runtime_error("a functional alias must be activated to be bound");
  McpttInfo info;
  info.client_id = client_id();
  info.functional_alias = functional_alias_;
  info.service["request-type"] = "fa-group-binding-req";
  info.service["binding-ind"] = bind ? "true" : "false";
  info.service[bind ? "binding-fa-uri" : "unbinding-fa-uri"] = alias;
  const std::vector<BodyPart> parts{{mcptt_mime, mcptt_info_xml(info)},
                                    {resource_lists_mime, resource_list_xml(groups)}};
  const auto boundary = "mcx-" + random_id();
  const auto action = bind ? "bind" : "unbind";
  status_["alias_binding_result"] = {{"action", action}, {"alias", alias}, {"groups", groups},
                                     {"status", 0},      {"warning", ""},  {"request", ++alias_binding_requests_}};
  sip_.request("MESSAGE", service(), "multipart/mixed; boundary=" + boundary, multipart_body(parts, boundary),
               {{"Accept-Contact", "*;+g.3gpp.mcptt;require;explicit"},
                {"Accept-Contact", "*;+g.3gpp.icsi-ref=\"" + url_encode(mcptt_icsi) + "\";require;explicit"},
                {"P-Preferred-Service", mcptt_icsi}},
               [this](const SipResponse &response) {
                 if (response.status_code < 200) return;
                 auto &result = status_["alias_binding_result"];
                 result["status"] = response.status_code;
                 result["warning"] = std::string(get_header(response.headers, "Warning"));
                 event("functional_alias_binding_result", result);
               });
  event("functional_alias_binding_requested", {{"action", action}, {"alias", alias}, {"groups", groups}});
}

} // namespace mcx
