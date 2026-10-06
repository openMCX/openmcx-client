// MCVideo functional aliases: activation, deactivation, take-over and status subscription (TS 24.281 clause 20.2.1).
// Member functions of Client (declared in client.hpp).
//
// Specifications: TS 24.281 V19.6.0 (Rel-19).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.281 clauses 9.2.1.2.1.1 and 10.2.2.2.1: the functional alias to present in a call, the one chosen while it
// is active.
std::string Client::mcvideo_presented_alias() const {
  return mcvideo_aliases_.contains(mcvideo_alias_in_use_) ? mcvideo_alias_in_use_ : std::string{};
}

//------------------------------------------------------------------------------
// TS 24.281 clause 20.2.1.2: the functional alias status change procedure: a PUBLISH to the participating function
// with the mcvideo-info body naming this user and the per-user PIDF (namespace urn:3gpp:ns:mcvideoPresInfoFA:1.0):
// Expires 4294967295 to activate or take over the listed aliases (step 4), 0 to deactivate them (step 5). The answer
// only says the request was taken: with the subscription to this user's status (clause 20.2.1.1) its NOTIFY gives the
// outcome; without it, the client counts an accepted request as done. <take-over> is sent only for aliases that
// NOTIFY offered as "take-over-possible" (step 6 d)); a take-over asked earlier goes as an activation and follows
// once offered.
void Client::mcvideo_functional_alias(const std::string &action, const std::vector<std::string> &aliases) {
  if (action == "use") {
    if (aliases.size() != 1) throw std::runtime_error("mcvideo-fa use <alias>|none");
    mcvideo_alias_in_use_ = aliases.front() == "none" ? std::string{} : aliases.front();
    return;
  }
  if ((action != "activate" && action != "takeover" && action != "deactivate") || aliases.empty())
    throw std::runtime_error("mcvideo-fa activate|takeover|deactivate <alias>...");
  if (!mcvideo_authorized_) throw std::runtime_error("functional alias change requires MCVideo service authorisation");
  McpttInfo info;
  info.request_uri = mcvideo_user();
  info.client_id = client_id();
  const bool offered = std::all_of(aliases.begin(), aliases.end(),
                                   [&](const auto &alias) { return mcvideo_takeover_possible_.contains(alias); });
  for (const auto &alias : aliases)
    if (action == "takeover" && !offered)
      mcvideo_takeover_wanted_.insert(alias);
    else
      mcvideo_takeover_wanted_.erase(alias);
  FunctionalAliasRequest request{mcvideo_user(), client_id(), random_id(), aliases, action == "takeover" && offered};
  request.mcvideo = true;
  mcvideo_fa_result_ = {{"action", action},
                        {"aliases", aliases},
                        {"status", 0},
                        {"p_id_fa", request.publish_id},
                        {"take_over", request.take_over},
                        {"outcomes", Json::object()}};
  mcvideo_request("presence",
                  {{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)},
                   {"application/pidf+xml", functional_alias_xml(request)}},
                  {{"Expires", action == "deactivate" ? "0" : "4294967295"}},
                  [this, action, aliases, pid = request.publish_id](const SipResponse &response) {
                    if (response.status_code < 200) return;
                    if (mcvideo_fa_result_.value("p_id_fa", "") == pid)
                      mcvideo_fa_result_["status"] = response.status_code;
                    if (response.status_code != 200)
                      for (const auto &alias : aliases) mcvideo_takeover_wanted_.erase(alias);
                    const bool followed = std::any_of(mcvideo_alias_dialogs_.begin(), mcvideo_alias_dialogs_.end(),
                                                      [](const auto &dialog) { return dialog.second.empty(); });
                    if (response.status_code == 200 && !followed)
                      for (const auto &alias : aliases) {
                        if (action == "deactivate")
                          mcvideo_aliases_.erase(alias);
                        else
                          mcvideo_aliases_.insert(alias);
                      }
                    event("mcvideo_functional_alias_result", mcvideo_fa_result_);
                  });
  event("mcvideo_functional_alias_requested", {{"action", action}, {"aliases", aliases}});
}

//------------------------------------------------------------------------------
// TS 24.281 clause 20.2.1.3: the functional alias status determination procedure: a SUBSCRIBE to the presence event
// package for the aliases active for this user or for the users of one alias.
void Client::mcvideo_alias_subscribe(const std::string &alias, bool fetch) {
  if (!mcvideo_authorized_) throw std::runtime_error("functional alias status requires MCVideo service authorisation");
  // TS 24.281 clause 20.2.1.3 steps 2 and 2A: <mcvideo-request-uri> names this user or the alias, with its <req-type>.
  McpttInfo info;
  info.request_uri = alias.empty() ? mcvideo_user() : alias;
  info.client_id = client_id();
  info.service["req-type"] = alias.empty() ? "functional-alias-status-determination" : "fa-user-status-determination";
  // Steps 4 and 5: 4294967295 for the state and its changes, 0 for the state once.
  const auto dialog = sip_.subscribe(
      mcvideo_service_uri(), "presence", fetch ? 0u : 4294967295u,
      {{"P-Preferred-Service", mcvideo_service.icsi}, {"Accept", "application/pidf+xml"}},
      [this, alias](const SipResponse &response) {
        if (response.status_code < 200) return;
        status_["mcvideo_functional_alias_subscription_result"] = {{"alias", alias}, {"status", response.status_code}};
        if (response.status_code >= 300)
          std::erase_if(mcvideo_alias_dialogs_, [&](const auto &subscription) { return subscription.second == alias; });
      },
      mcvideo_service.info_mime, service_info_xml(info, mcvideo_service));
  mcvideo_alias_dialogs_[dialog] = alias;
  event("mcvideo_functional_alias_subscription_requested", {{"alias", alias}, {"fetch", fetch}});
}

//------------------------------------------------------------------------------
// TS 24.281 clause 20.2.1.3: de-subscribe from the functional alias status: a SUBSCRIBE in the dialog with Expires 0.
void Client::mcvideo_alias_unsubscribe(const std::string &alias) {
  const auto found = std::find_if(mcvideo_alias_dialogs_.begin(), mcvideo_alias_dialogs_.end(),
                                  [&](const auto &subscription) { return subscription.second == alias; });
  if (found == mcvideo_alias_dialogs_.end()) throw std::runtime_error("no functional alias status subscription");
  sip_.request_in_dialog(found->first, "SUBSCRIBE", "", "",
                         {{"Event", "presence"}, {"Expires", "0"}, {"P-Preferred-Service", mcvideo_service.icsi}});
}

//------------------------------------------------------------------------------
// TS 24.281 clause 20.2.1.3: the NOTIFY with the per-user or per-functional alias status (clause 20.3.1.2).
// The per-user status replaces this client's view of its active aliases, so a
// take-over by another user or a deactivation by the server is seen.
SipResponse Client::mcvideo_alias_notify(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  if (asserted_identity(message) != mcvideo_service_uri()) {
    response.status_code = 403;
    return response;
  }
  const auto subscription = mcvideo_alias_dialogs_.find(message.dialog_id);
  if (subscription == mcvideo_alias_dialogs_.end()) { // RFC 6665 section 4.1.3: no such subscription
    response.status_code = 481;
    return response;
  }
  const auto alias = subscription->second;
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
    if (!notified.mcvideo) {
      response.status_code = 400;
      return response;
    }
    if (alias.empty() && !notified.per_alias && notified.entity == mcvideo_user()) {
      std::set<std::string> active, possible;
      for (const auto &entry : notified.entries)
        if (entry.status == "activated" || entry.status == "deactivating")
          active.insert(entry.alias);
        else if (entry.status == "take-over-possible")
          possible.insert(entry.alias);
      mcvideo_aliases_ = active;
      mcvideo_takeover_possible_ = possible;
      mcvideo_alias_status_known_ = true;
      // The outcome of this client's last change, by its <p-id-fa>; the take-over the user asked for follows
      // once the alias is offered.
      if (!notified.publish_id.empty() && mcvideo_fa_result_.is_object() &&
          mcvideo_fa_result_.value("p_id_fa", "") == notified.publish_id) {
        std::vector<std::string> take_over;
        for (const auto &changed : mcvideo_fa_result_["aliases"].get<std::vector<std::string>>()) {
          mcvideo_fa_result_["outcomes"][changed] = active.contains(changed)     ? "activated"
                                                    : possible.contains(changed) ? "take-over-possible"
                                                    : mcvideo_fa_result_["action"] == "deactivate" ? "deactivated"
                                                                                                   : "refused";
          if (mcvideo_takeover_wanted_.erase(changed) && possible.contains(changed)) take_over.push_back(changed);
        }
        event("mcvideo_functional_alias_result", mcvideo_fa_result_);
        if (!take_over.empty()) schedule([this, take_over] { mcvideo_functional_alias("takeover", take_over); });
      }
    } else if (!alias.empty() && notified.entity == alias && (notified.per_alias || notified.entries.empty())) {
      std::vector<std::string> users;
      for (const auto &entry : notified.entries) users.push_back(entry.user);
      mcvideo_alias_users_[alias] = users;
    } else {
      response.status_code = 400;
      return response;
    }
    mcvideo_fa_notified_ = {{"alias", alias}, {"p_id_fa", notified.publish_id}, {"entries", notified.entries.size()}};
    event("mcvideo_functional_alias_notified", mcvideo_fa_notified_);
  }
  if (get_header(message.headers, "Subscription-State").starts_with("terminated"))
    mcvideo_alias_dialogs_.erase(message.dialog_id);
  return response;
}

} // namespace mcx
