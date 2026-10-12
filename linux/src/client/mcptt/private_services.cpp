//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Private call services of the MCPTT client: call-back, remotely initiated private and group calls, private call
// transfer (announced and unannounced) and forwarding, requesting and handling side.
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), RFC 5359.

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// Helper: send a private call service request or response (the mcptt-info <anyExt> service elements) to a target
// MCPTT user by SIP MESSAGE, as TS 24.379 clause 11.1.5.2.1, clause 11.1.7.2.1, clause 11.1.8.2.1 and
// clause 11.1.9.2.1 describe it; done receives the final response.
void Client::send_service_message(const std::string &target, McpttInfo info,
                                  std::function<void(const SipResponse &)> done) {
  info.client_id = client_id();
  const auto boundary = "mcx-" + random_id();
  const auto body =
      multipart_body({info_parts(info).front(), {resource_lists_mime, private_call_resource_list(target)}}, boundary);
  sip_.request(
      "MESSAGE", service(), "multipart/mixed; boundary=" + boundary, body,
      {{"P-Preferred-Service", mcptt_icsi},
       {"Accept-Contact", "*;+g.3gpp.mcptt;require;explicit"},
       {"Accept-Contact", "*;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mcptt\";require;explicit"}},
      [this, done](const SipResponse &response) {
        if (response.status_code < 200) return;
        status_["last_service_result"] = {{"status", response.status_code},
                                          {"warning", std::string(get_header(response.headers, "Warning"))}};
        if (done) done(response);
      });
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.5.2.2, clause 11.1.7.2.2, clause 11.1.8.2.2 and clause 11.1.9.2.2: report the outcome of
// the call placed for a remote, transfer or forwarding request once (success on 18x or 2xx).
void Client::report_followup(bool success) {
  if (!followup_ || followup_->reported) return;
  followup_->reported = true;
  next_call_priority_.clear();
  const auto followup = *followup_;
  service_outcomes_[followup.kind] = success ? "success" : "fail";

  // TS 24.379 clause 11.1.8.2.2: a failed transfer leaves the transferred user in its call with the requester.
  if (followup.kind == "transfer" && !success && transfer_kept_ && held_) {
    if (!dialog_.empty()) sip_.bye(dialog_);
    resume_call(std::move(*held_));
    held_.reset();
    transfer_kept_ = false;
    event("transferred_call_resumed", {{"dialog", dialog_}});
  }
  // TS 24.379 clause 11.1.7.2.2 NOTE: no report when the requester itself was called successfully.
  if (followup.kind == "remote" && success && followup.requester == followup.target) return;
  McpttInfo info;
  if (followup.kind == "remote-group") {
    // TS 24.379 clause 10.1.5.2.2 step 1e: <mcptt-request-uri> names the group that was called.
    info.request_uri = followup.target;
    info.service = {{"response-type", "remotely-initiated-group-call-response"},
                    {"remotely-initiated-call-outcome", success ? "success" : "fail"}};
    send_service_message(followup.requester, info);
    return;
  }
  info.called_party = followup.target;
  if (followup.kind == "remote")
    info.service = {{"response-type", "remotely-initiated-private-call-response"},
                    {"remotely-initiated-call-outcome", success ? "success" : "fail"}};
  else if (followup.kind == "transfer")
    info.service = {{"response-type", "transfer-private-call-response"},
                    {"transfer-call-outcome", success ? "success" : "fail"}};
  else
    info.service = {{"response-type", "forward-private-call-response"},
                    {"forwarding-call-outcome", success ? "success" : "fail"}};
  send_service_message(followup.requester, info);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.5.2.2 (call-back), clause 10.1.5.2.2 and clause 11.1.7.2.2 (remotely initiated calls),
// clause 11.1.8.2.2 (transfer) and clause 11.1.9.2.2 (forwarding): the target client handles a private call service
// request, or the requesting client a response (clause 11.1.5.2.1, clause 11.1.8.2.1).
void Client::service_message(const McpttInfo &info) {
  const auto type =
      info.service.contains("request-type") ? info.service.at("request-type") : info.service.at("response-type");
  const auto from = info.calling_user;
  McpttInfo reply;
  if (type == "private-call-call-back-request") {
    pccb_target_[from] = {{"state", "PCCB-R2: private-call-pending"},
                          {"urgency", info.service.contains("urgency-ind") ? info.service.at("urgency-ind") : "normal"},
                          {"time", info.service.contains("time-of-request") ? info.service.at("time-of-request") : ""}};
    reply.service = {{"response-type", "private-call-call-back-response"}};
    send_service_message(from, reply);
  } else if (type == "private-call-call-back-cancel-request") {
    pccb_target_.erase(from);
    reply.service = {{"response-type", "private-call-call-back-cancel-response"}};
    send_service_message(from, reply);
  } else if (type == "private-call-call-back-response") {
    if (pccb_requesting_.value(from, "") == "PCCB-I2: confirm-pending") pccb_requesting_[from] = "PCCB-I3: confirmed";
  } else if (type == "private-call-call-back-cancel-response") {
    if (pccb_requesting_.value(from, "") == "PCCB-I4: cancel-pending") pccb_requesting_.erase(from);
  } else if (type == "remotely-initiated-group-call-request") {
    remote_group_call_request(info, from);
  } else if (type == "remotely-initiated-private-call-request" || type == "transfer-private-call-request" ||
             type == "forward-private-call-request") {
    private_call_service_request(info, type, from);
  } else {
    service_response_received(info, type);
  }
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.5.2.2: place a group call to the requested group and report the outcome.
void Client::remote_group_call_request(const McpttInfo &info, const std::string &from) {
  const auto group = info.calling_group;
  const bool notify =
      !(info.service.contains("notify-remote-user") && info.service.at("notify-remote-user") == "false");
  followup_ = ServiceFollowup{"remote-group", from, group};
  next_call_priority_ =
      info.service.contains("ric-app-level-priority") ? info.service.at("ric-app-level-priority") : "";
  event("group_remote_init_request",
        {{"from", from}, {"group", group}, {"notify", notify}, {"priority", next_call_priority_}});
  // The lab configures one group per client; a request for another group cannot be placed here.
  if (group != this->group() || !call_idle() || !affiliated_) {
    report_followup(false);
    return;
  }
  schedule_after(0, [this] {
    if (!call_idle()) {
      report_followup(false);
      return;
    }
    try {
      command("call");
    } catch (const std::exception &error) {
      failure(error.what());
      report_followup(false);
    }
  });
}

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.7.2.2 (remotely initiated), clause 11.1.8.2.2 (transfer) and clause 11.1.9.2.2
// (forwarding): the target client places the requested private call and reports the outcome.
void Client::private_call_service_request(const McpttInfo &info, const std::string &type, const std::string &from) {
  const bool forward = type == "forward-private-call-request";
  const auto target = forward ? info.service.at("forwarding-target-id") : info.called_party;
  if (target.empty()) return;
  std::string mode = "auto";
  if (type == "remotely-initiated-private-call-request") {
    const auto ric = info.service.contains("ric-commencement-mode") ? info.service.at("ric-commencement-mode") : "";
    mode = ric == "force-auto-mode" ? "force-auto" : ric == "manual-mode" ? "manual" : "auto";
  }
  followup_ = ServiceFollowup{type.starts_with("remotely") ? "remote"
                              : forward                    ? "forward"
                                                           : "transfer",
                              forward ? info.service.at("forwarded-by-mcptt-id") : from, target};
  next_call_service_.clear();
  next_call_priority_ =
      type == "remotely-initiated-private-call-request" && info.service.contains("ric-app-level-priority")
          ? info.service.at("ric-app-level-priority")
          : "";
  if (type == "transfer-private-call-request") next_call_service_["call-transfer-ind"] = "true";
  next_call_replaces_ = type == "transfer-private-call-request" && info.service.contains("replaces-header-value")
                            ? info.service.at("replaces-header-value")
                            : "";
  if (forward) next_call_service_["call-forwarding-ind"] = "true";
  event("private_service_request", {{"type", type}, {"from", from}, {"target", target}});
  if (forward) {
    status_["last_forwarding"] = {
        {"by", info.service.at("forwarded-by-mcptt-id")},
        {"target", target},
        {"reason", info.service.contains("forwarding-reason") ? info.service.at("forwarding-reason") : ""},
        {"kms_uri", info.private_call_kms_uri}};
    forwarded_kms_ = {target, info.private_call_kms_uri};
  }
  // TS 24.379 clause 11.1.8.2.2: the transferred user keeps its call with the requester while it calls the target.
  // The requester ends that call once the transfer succeeds (clause 11.1.8.2.1), and on failure it goes on.
  if (type == "transfer-private-call-request" && !call_idle() && call_type_ == "private" && !held_) {
    held_ = suspend_call();
    transfer_kept_ = true;
    event("transferred_call_kept", {{"dialog", held_->dialog}, {"requester", held_->private_peer}});
  }
  schedule_after(forward ? 500 : 0, [this, target, mode] {
    if (!call_idle()) {
      report_followup(false);
      return;
    }
    try {
      place_call([&] { private_call(target + " " + mode); });
    } catch (const std::exception &error) {
      failure(error.what());
      report_followup(false);
    }
  });
}

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.5.2.1 and clause 11.1.8.2.1: the requesting client receives the response to its own
// request. Responses to our own requests: record the outcome for the user.
void Client::service_response_received(const McpttInfo &info, const std::string &type) {
  for (const auto *key : {"remotely-initiated-call-outcome", "transfer-call-outcome", "forwarding-call-outcome"})
    if (info.service.contains(key)) service_outcomes_[type] = info.service.at(key);
  // TS 24.379 clause 11.1.8.2.1: a successful transfer ends the requester's call with the transferred user.
  const bool transferred = type == "transfer-private-call-response" && info.service.contains("transfer-call-outcome") &&
                           info.service.at("transfer-call-outcome") == "success";
  if (transferred && !announced_target_.empty()) {
    // TS 24.379 clause 11.1.8.2.1 (on the response) step 2a: an announced transfer ends the call with the transferred
    // user (unless it has already left it) and, below, the call with the target.
    if (held_) {
      const auto held = held_->dialog;
      held_.reset();
      sip_.bye(held);
    }
    announced_target_.clear();
    announced_replaces_.clear();
    status_["announced_transfer"]["state"] = "completed";
  }
  if (transferred && !dialog_.empty() && call_type_ == "private") {
    event("transferred_call_ended", {{"dialog", dialog_}});
    sip_.bye(dialog_);
  }
}

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.8.2.3: announced private call transfer, requesting side:
//   transfer <target> announced: hold the call (RFC 5359 section 2.1) and call the target
//   transfer <target> complete:  ask the held user to call the target, replacing our call
//   transfer <target> cancel:    end the call with the target and resume the held call
void Client::announced_transfer(const std::string &target, const std::string &action) {
  require_profile({"allow-call-transfer"});
  if (action == "announced") {
    if (held_) throw std::runtime_error("an announced transfer is already in progress");
    if (call_type_ != "private" || private_peer_.empty() || status_.value("call_state", "") != "active" || !remote_)
      throw std::runtime_error("an announced transfer requires an active private call");
    if (target == private_peer_ || target == user()) throw std::runtime_error("transfer target must be a third user");
    auto media = local_;
    media.direction = "sendonly";
    McpttInfo hold;
    hold.session_type = "private";
    sip_.reinvite(dialog_, media, info_parts(hold), [this, target, dialog = dialog_](const SipResponse &response) {
      if (response.status_code < 200) return;
      if (response.status_code >= 300 || dialog != dialog_) {
        failure("hold for announced transfer: " + std::to_string(response.status_code));
        return;
      }
      schedule([this, target, dialog] {
        if (dialog != dialog_) return;
        held_ = suspend_call();
        announced_target_ = target;
        announced_replaces_.clear();
        status_["announced_transfer"] = {
            {"state", "calling-target"}, {"target", target}, {"transferred", held_->private_peer}};
        event("private_call_held", {{"dialog", dialog}, {"transferred", held_->private_peer}});
        next_call_service_ = {{"transfer-announced-ind", "true"}};
        try {
          place_call([&] { private_call(target + " auto"); });
        } catch (const std::exception &error) {
          failure(error.what());
          resume_held();
        }
      });
    });
    return;
  }
  if (!held_ || target != announced_target_) throw std::runtime_error("no announced transfer to that target");
  if (action == "cancel") {
    resume_held();
    return;
  }
  // TS 24.379 clause 11.1.8.2.3 step 2d: without a <replaces-header-value> the call with the target ends first.
  if (announced_replaces_.empty() && !dialog_.empty()) sip_.bye(dialog_);
  McpttInfo info;
  info.called_party = target;
  info.call_to_functional_alias = false;
  info.service = {{"request-type", "transfer-private-call-request"}};
  if (!announced_replaces_.empty()) info.service["replaces-header-value"] = announced_replaces_;
  status_["announced_transfer"]["state"] = "transfer-requested";
  send_service_message(held_->private_peer, info);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 11.1.8.2.3: the announced transfer is cancelled: leave the call with the target and take the held
// call off hold (RFC 5359 section 2.1).
void Client::resume_held() {
  if (!held_) return;
  if (!dialog_.empty()) {
    const auto current = dialog_;
    sip_.bye(current);
    call_state(current, SipCallState::disconnected, 200);
  }
  resume_call(std::move(*held_));
  held_.reset();
  announced_target_.clear();
  announced_replaces_.clear();
  status_["announced_transfer"]["state"] = "cancelled";
  local_.direction = "sendrecv";
  McpttInfo resume;
  resume.session_type = "private";
  sip_.reinvite(dialog_, local_, info_parts(resume));
  event("private_call_resumed", {{"dialog", dialog_}});
}

//------------------------------------------------------------------------------
// Command: private call services, requesting side: call-back (TS 24.379 clause 11.1.5.2.1, fulfilment clause
// 11.1.5.2.3), remotely initiated group call (clause 10.1.5.2.1) and private call (clause 11.1.7.2.1), private call
// transfer (clause 11.1.8.2.1, announced clause 11.1.8.2.3) and forwarding (clause 11.1.9.2.1):
//   callback <id> [low|normal|high] | callback-cancel <id> | callback-fulfil <id>
//   remote-call <remote id> <target id> [auto|manual|force-auto] [silent] [priority=<namespace.value>]
//   remote-group-call <remote id> <group id> [silent] [priority=<namespace.value>]
//   transfer <target id> [announced|complete|cancel] | forward <target id>
void Client::private_service_command(const std::string &cmd) {
  // priority=<namespace.value>: the <ric-app-level-priority> of a remote request.
  std::string priority, kept, word;
  for (std::istringstream all(cmd); all >> word;)
    if (word.starts_with("priority="))
      priority = word.substr(9);
    else
      kept += word + " ";
  std::istringstream words(kept);
  std::string verb, first, second, third, fourth;
  words >> verb >> first >> second >> third >> fourth;
  if (first.empty() || !authorized_)
    throw std::runtime_error("private call service requires a target and authorization");
  if (!priority.empty() && verb != "remote-group-call" && verb != "remote-call")
    throw std::runtime_error("priority= applies to remotely initiated call requests");
  McpttInfo info;
  if (verb == "callback" || verb == "callback-cancel") {
    const bool cancel = verb == "callback-cancel";
    require_profile({cancel ? "allow-cancel-private-call-call-back" : "allow-request-private-call-call-back"});
    if (cancel && !pccb_requesting_.contains(first)) throw std::runtime_error("no call-back request to cancel");
    if (cancel)
      info.service = {{"request-type", "private-call-call-back-cancel-request"}};
    else
      info.service = {{"request-type", "private-call-call-back-request"},
                      {"urgency-ind", second.empty() ? "normal" : second},
                      {"time-of-request", utc_timestamp()}};
    pccb_requesting_[first] = cancel ? "PCCB-I4: cancel-pending" : "PCCB-I2: confirm-pending";
    send_service_message(first, info, [this, first, cancel](const SipResponse &response) {
      if (response.status_code >= 300)
        pccb_requesting_.erase(first);
      else if (cancel && pccb_requesting_.value(first, "") != "PCCB-I4: cancel-pending")
        pccb_requesting_.erase(first);
    });
  } else if (verb == "callback-fulfil") {
    if (!pccb_target_.contains(first)) throw std::runtime_error("no call-back request from that user");
    private_call(first + " manual"); // TS 24.379 clause 11.1.5.2.3: manual commencement
  } else if (verb == "remote-group-call") {
    // TS 24.379 clause 10.1.5.2.1: ask a remote user to place a group call.
    require_profile({"allow-request-remote-init-group-call"});
    if (second.empty()) throw std::runtime_error("usage: remote-group-call <remote id> <group id> [silent]");
    // TS 24.379 clause 10.1.5.2.1 step 1A: no remotely initiated call on a preconfigured-use-only group. A
    // group document is served to members only; otherwise the server decides (167).
    auto document = http_.request("GET",
                                  cfg_.at("endpoints").at("gms").get<std::string>() +
                                      "/xcap-root/org.openmobilealliance.groups/global/byGroupID/" + url_encode(second),
                                  {}, {{"Authorization", "Bearer " + token_}});
    if (document.status == 200 && group_preconfigured_use_only(document.body))
      throw std::runtime_error("calls are not allowed on the preconfigured group");
    info.request_uri = second; // targeted group
    info.service = {{"request-type", "remotely-initiated-group-call-request"},
                    {"notify-remote-user", third == "silent" ? "false" : "true"}};
    if (!priority.empty()) info.service["ric-app-level-priority"] = priority;
    send_service_message(first, info);
  } else if (verb == "remote-call") {
    require_profile({"allow-request-remote-init-private-call"});
    if (second.empty()) throw std::runtime_error("usage: remote-call <remote id> <target id> [mode] [silent]");
    info.called_party = second;
    info.service = {{"request-type", "remotely-initiated-private-call-request"},
                    {"notify-remote-user", third == "silent" || fourth == "silent" ? "false" : "true"}};
    if (third == "auto" || third == "manual" || third == "force-auto")
      info.service["ric-commencement-mode"] = third == "force-auto" ? "force-auto-mode" : third + "-mode";
    if (!priority.empty()) info.service["ric-app-level-priority"] = priority;
    send_service_message(first, info);
  } else if (verb == "transfer" && (second == "announced" || second == "complete" || second == "cancel")) {
    announced_transfer(first, second);
  } else if (verb == "transfer") {
    require_profile({"allow-call-transfer"});
    if (call_type_ != "private" || private_peer_.empty() || status_.value("call_state", "") != "active")
      throw std::runtime_error("transfer requires an active private call");
    info.called_party = first;
    info.service = {{"request-type", "transfer-private-call-request"}};
    info.call_to_functional_alias = false;
    send_service_message(private_peer_, info);
  } else if (verb == "forward") {
    require_profile({"allow-call-fwd-manual-any"});
    // TS 24.379 clause 11.1.9.2.1: forward a ringing incoming private call, then decline it with 175.
    if (call_type_ != "private" || !pending_answer_ || private_peer_.empty())
      throw std::runtime_error("forward requires a ringing incoming private call");
    info.call_to_functional_alias = false;
    info.service = {{"request-type", "forward-private-call-request"},
                    {"forwarding-reason", "manual-input"},
                    {"forwarded-by-mcptt-id", user()},
                    {"forwarding-target-orig-id", first}};
    send_service_message(private_peer_, info, [this](const SipResponse &response) {
      if (response.status_code >= 300 || !pending_answer_ || dialog_.empty()) return;
      SipResponse declined;
      declined.status_code = 480;
      declined.headers = {{"Warning", "399 mcx \"175 call is forwarded\""}};
      pending_answer_.reset();
      sip_.answer(dialog_, declined);
    });
  } else
    throw std::runtime_error("unknown private call service command");
  event("private_service_command", {{"verb", verb}, {"target", first}});
}

} // namespace mcx
