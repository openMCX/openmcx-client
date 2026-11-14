//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// MCVideo sessions: regroups, ad hoc, ambient viewing and group calls, invitations, conference event package,
// transmission control and video media (TS 24.281 clauses 9.2, 15.2, 21, 22; TS 24.581 clause 6.2). Member functions
// of Client (declared in client.hpp).
//
// Specifications: TS 24.281 V19.6.0 (Rel-19), TS 24.581 V19.2.0 (Rel-19), TS 26.281 V19.0.0 (Rel-19),
// TS 23.281 V19.10.0 (Rel-19), TS 24.379 V19.7.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19), RFC 3264, RFC 4575, RFC 6184.

#include "client.hpp"

#include <cstring>

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.281 clauses 21.2.1.1, 21.3.1.1 and 21.2.1.2: request or remove a group or user regroup using a preconfigured
// group: a SIP MESSAGE to the MCVideo server with the mcvideo-info and mcvideo-regroup bodies (<mcvideoregroup>); the
// response is kept in mcvideo_regroup_result.
void Client::mcvideo_regroup_command(const std::string &action, const std::string &tgi,
                                     const std::string &preconfigured, const std::vector<std::string> &groups,
                                     const std::vector<std::string> &users) {
  if (!mcvideo_authorized_) throw std::runtime_error("MCVideo regroup requires authorisation");
  if (tgi.empty()) throw std::runtime_error("a regroup needs its temporary group identity");
  Regroup regroup;
  regroup.action = action;
  regroup.regroup_uri = tgi;
  // TS 24.281 clause 21.2.1.2 step 9: a removal names only the regroup; its preconfigured group if known.
  regroup.preconfigured_group = action == "remove" && mcvideo_regroups_.contains(tgi)
                                    ? mcvideo_regroups_.at(tgi).value("preconfigured", std::string{})
                                    : preconfigured;
  regroup.groups = groups;
  regroup.users = users;
  McpttInfo info;
  info.client_id = client_id();
  const std::vector<BodyPart> parts{{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)},
                                    {regroup_mime_of(mcvideo_service), regroup_xml(regroup, mcvideo_service)}};
  const auto boundary = "mcx-" + random_id();
  mcvideo_regroup_result_ = {{"action", action}, {"regroup", tgi}, {"status", 0}, {"warning", ""}};
  status_["mcvideo_regroup_result"] = mcvideo_regroup_result_;
  sip_.request("MESSAGE", mcvideo_service_uri(), "multipart/mixed; boundary=" + boundary,
               multipart_body(parts, boundary), mcvideo_call_headers(),
               [this, action, tgi](const SipResponse &response) {
                 if (response.status_code < 200) return;
                 mcvideo_regroup_result_ = {{"action", action},
                                            {"regroup", tgi},
                                            {"status", response.status_code},
                                            {"warning", std::string(get_header(response.headers, "Warning"))}};
                 status_["mcvideo_regroup_result"] = mcvideo_regroup_result_;
                 event("mcvideo_regroup_result", mcvideo_regroup_result_);
               });
}

//------------------------------------------------------------------------------
// TS 24.281 clauses 21.2.1.3 and 21.2.1.4: a notification of the creation or removal of a regroup using a
// preconfigured group: store or forget the regroup.
void Client::mcvideo_regroup_notified(const Regroup &regroup) {
  if (regroup.action == "create") {
    const auto seen = mcvideo_regroups_.contains(regroup.regroup_uri)
                          ? mcvideo_regroups_.at(regroup.regroup_uri).value("notifications", 0u)
                          : 0u;
    mcvideo_regroups_[regroup.regroup_uri] = {{"preconfigured", regroup.preconfigured_group},
                                              {"groups", regroup.groups},
                                              {"users", regroup.users},
                                              {"notifications", seen + 1}};
  } else {
    mcvideo_regroups_.erase(regroup.regroup_uri);
    // TS 24.281 clause 21.2.1.4 step 3: the client is de-affiliated from the removed regroup, so later
    // affiliation requests no longer name it.
    mcvideo_extra_groups_.erase(regroup.regroup_uri);
  }
  status_["mcvideo_regroups"] = mcvideo_regroups_;
  event("mcvideo_regroup_notification", {{"regroup", regroup.regroup_uri}, {"action", regroup.action}});
  // TS 33.180 clause 5.7: the GMK of a new regroup or temporary group, before a call on it.
  schedule([this] { mcvideo_update_key_subscriptions(); });
}

//------------------------------------------------------------------------------
// TS 24.281 clause 22.2.2.1.1: an ad hoc group call. The MCVideo users to call go in a resource-lists body (step 11),
// or the criteria in <call-participants-criteria> (step 12); the server returns the ad hoc group's identity in
// <mcvideo-calling-group-id> (2xx step 4).
void Client::mcvideo_adhoc_call(const std::vector<std::string> &targets, const std::string &criteria) {
  if (!mcvideo_authorized_) throw std::runtime_error("MCVideo call requires authorisation");
  if (mcvideo_call_) throw std::runtime_error("an MCVideo call is in progress");
  if (targets.empty() && criteria.empty()) throw std::runtime_error("an ad hoc call needs MCVideo IDs or criteria");
  mcvideo_call_ = new_mcvideo_call();
  const McvideoCallSetup setup{mcvideo_call_};
  auto &call = *mcvideo_call_;
  call.type = "adhoc";
  call.state = "calling";
  McpttInfo info;
  info.session_type = "adhoc";          // step 10a
  info.client_id = client_id();         // step 10c
  info.participant_criteria = criteria; // step 12
  std::vector<BodyPart> parts{{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)}};
  if (!targets.empty()) parts.push_back({resource_lists_mime, resource_list_xml(targets)}); // step 11
  call.dialog = sip_.invite(
      mcvideo_service_uri(), call.local, parts,
      [this, generation = call.generation](const SipResponse &response) {
        mcvideo_invite_answered(generation, response);
      },
      mcvideo_call_headers());
  event("mcvideo_adhoc_call_requested", {{"targets", targets}, {"criteria", criteria}});
}

//------------------------------------------------------------------------------
// TS 24.281 clause 15.2.1.1: an ambient viewing call, remotely initiated (this user views the target) or locally
// initiated (the target views this user). Automatic commencement; the viewed-to client transmits
// (TS 24.281 clause 6.4): the originator of a local-init call asks in its offer, the terminating client of a remote-init call in its
// answer.
void Client::mcvideo_ambient_call(const std::string &target, const std::string &type) {
  if (!mcvideo_authorized_) throw std::runtime_error("MCVideo call requires authorisation");
  if (mcvideo_call_) throw std::runtime_error("an MCVideo call is in progress");
  if (target.empty() || target == mcvideo_user())
    throw std::runtime_error("ambient viewing requires another MCVideo ID");
  auto headers = mcvideo_call_headers();
  headers.push_back({"Priv-Answer-Mode", "Auto"}); // step 14
  mcvideo_call_ = new_mcvideo_call();
  const McvideoCallSetup setup{mcvideo_call_};
  auto &call = *mcvideo_call_;
  call.type = "private";
  call.peer = target;
  call.state = "calling";
  call.ambient_type = type;
  // TS 24.281 clause 15.2.1.1, step 4 of the 200 (OK) handling, known already: the originator's role.
  call.ambient_role = type == "local-init" ? "viewed-to" : "viewing";
  call.may_release = false; // until the 200 (OK) carries the release capability (TS 24.281 clause 15.2.1.3)
  // Steps 12 and 13 with TS 24.281 clause 6.4: the offer carries mc_implicit_request in both cases: an implicit
  // request of this client (local-init), or a request to grant the terminating client (remote-init).
  call.local.implicit_request = true;
  // The viewing user only receives video, the viewed-to user only sends it: TS 24.281 clause 6.2.1 step 3c sets the
  // direction on the video media ("a=recvonly" remote-init, "a=sendonly" local-init); the audio follows it.
  call.local.direction = type == "local-init" ? "sendonly" : "recvonly";
  if (call.local.video) call.local.video->direction = call.local.direction;
  McpttInfo info;
  info.session_type = "ambient-viewing"; // step 7
  info.ambient_listening_type = type;    // step 8: <ambient-viewing-type>
  info.client_id = client_id();
  const auto pck = mcvideo_offer_pck(target); // step 10
  call.dialog = sip_.invite(
      mcvideo_service_uri(), call.local,
      {{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)},
       {resource_lists_mime, private_call_resource_list(target)}}, // step 9
      [this, generation = call.generation](const SipResponse &response) {
        mcvideo_invite_answered(generation, response);
      },
      headers);
  if (pck) mcvideo_bind_pck(*pck, true);
  event("mcvideo_ambient_call_requested", {{"target", target}, {"type", type}});
}

//------------------------------------------------------------------------------
// TS 24.281 annex D.3: the g.3gpp.mcvideo.ambient-viewing-call-release feature-capability indicator in a Feature-Caps
// header field (TS 24.281 clause 15.2.1.3 step 1).
bool Client::ambient_release_capability(const std::vector<SipHeader> &headers) {
  return get_header(headers, "Feature-Caps").find("g.3gpp.mcvideo.ambient-viewing-call-release") !=
         std::string_view::npos;
}

//------------------------------------------------------------------------------
// TS 24.281 clause 9.2.1.2.1.1: an on-demand prearranged group call to the configured group; with implicit, the
// INVITE also requests the permission to transmit (TS 24.281 clause 6.4, mc_implicit_request of
// TS 24.581 clause 14.2.5). TS 24.281 clause 9.2.2.2.1.1: with a chat group, the client joins the group's chat session instead.
void Client::mcvideo_group_call(bool implicit, const std::string &chat_group, const std::string &priority,
                                const std::string &target_group) {
  if (!mcvideo_authorized_ || !mcvideo_affiliated_)
    throw std::runtime_error("MCVideo call requires authorisation and affiliation");
  if (mcvideo_call_) throw std::runtime_error("an MCVideo call is in progress");
  // TS 24.281 clause 9.2.1.2.1.1: the group document of the called group (served to members only): a group with
  // <preconfigured-group-use-only> "true" is not called (steps 1 and 2: tell the user, skip the procedure), and a
  // temporary group's <on-network-temporary> names its constituent groups for <associated-group-id> (step 14 d) i)).
  const auto called = !chat_group.empty() ? chat_group : !target_group.empty() ? target_group : mcvideo_group();
  std::vector<std::string> constituents;
  {
    const auto document = http_.request("GET", groups_xcap() + "/global/byGroupID/" + xcap_segment(called), {},
                                        {{"Authorization", bearer()}});
    if (document.status == 200) {
      if (group_preconfigured_use_only(document.body)) {
        event("mcvideo_call_not_allowed", {{"group", called}, {"reason", "preconfigured-group-use-only"}});
        status_["mcvideo_call_not_allowed"] = called;
        throw std::runtime_error("calls are not allowed on the indicated group");
      }
      const auto &body = document.body;
      for (std::size_t at = body.find("constituent-MCPTT-group-ID>"); at != std::string::npos;
           at = body.find("constituent-MCPTT-group-ID>", at + 1)) {
        const auto start = at + std::strlen("constituent-MCPTT-group-ID>");
        if (start < 2 || body[at - 1] == '/') continue; // a closing tag
        const auto end = body.find('<', start);
        if (end != std::string::npos && end > start) constituents.push_back(body.substr(start, end - start));
      }
    }
  }
  // TS 24.281 clause 4.7.1 and TS 33.180 clause 5.7: the call's media need the called group's GMK; without it the
  // key is subscribed to (mcvideo_use_group_key) and the call placed once it arrives.
  if (protected_media() && !mcvideo_use_group_key(called)) {
    if (mcvideo_key_wait_) throw std::runtime_error("an MCVideo call is waiting for its group key");
    mcvideo_key_wait_ = McvideoKeyWait{mcvideo_key_group_of(called), monotonic_ms() + 10000,
                                       [this, implicit, chat_group, priority, target_group] {
                                         mcvideo_group_call(implicit, chat_group, priority, target_group);
                                       }};
    event("mcvideo_call_waiting_for_key", {{"group", called}});
    return;
  }
  mcvideo_call_ = new_mcvideo_call();
  const McvideoCallSetup setup{mcvideo_call_};
  auto &call = *mcvideo_call_;

  // A prearranged call to another group than the selected one, such as a regroup's
  // temporary group identity (TS 24.281 clause 21.2.1.3), names it in target_group.
  call.group = called;
  call.state = "calling";
  // TS 24.281 clause 4.7.1: a group call's media under the GMK of the called group (TS 33.180 clause 5.7: each group
  // has its own), offered as RTP/SAVP.
  if (protected_media()) {
    if (!mcvideo_use_group_key(called) || !mcvideo_protection_.group_ready())
      throw std::runtime_error("MCVideo group key unavailable for " + called);
    call.local.protected_media = true;
  }
  call.local.implicit_request = implicit;
  call.requesting = implicit;
  McpttInfo info;
  info.session_type = chat_group.empty() ? "prearranged" : "chat";
  call.type = info.session_type; // its re-INVITEs repeat it (TS 24.281 clause 9.2.2.2.1.5 step 4)
  info.request_uri = call.group;
  info.client_id = client_id();
  // TS 24.281 clauses 9.2.1.2.1.1 and 9.2.2.2.1.1: the activated functional alias this user presents.
  info.functional_alias = mcvideo_presented_alias();

  // TS 24.281 clause 9.2.1.2.1.1 step 14 d) i): a call on a group regroup names, in <associated-group-id>, the
  // constituent group it is placed from: this user's own group if it is one of them, else the first.
  {
    auto groups = mcvideo_regroups_.contains(call.group)
                      ? mcvideo_regroups_.at(call.group).value("groups", std::vector<std::string>{})
                      : std::vector<std::string>{};
    if (groups.empty()) groups = constituents; // a temporary group of the GMS (TS 24.481 clause 6.3.14)
    if (!groups.empty())
      info.associated_group =
          std::find(groups.begin(), groups.end(), mcvideo_group()) != groups.end() ? mcvideo_group() : groups.front();
  }
  // TS 24.281 clauses 6.2.8.1.1 and 6.2.8.1.9: an emergency (with alert-ind false) or imminent peril group call.
  if (priority == "emergency") {
    info.emergency = true;
    info.alert = false;
  }
  if (priority == "imminent") info.imminent_peril = true;
  // TS 24.281 clause 9.2.1.2.1.1: a broadcast group call (<broadcast-ind> true).
  if (priority == "broadcast") {
    info.broadcast = true;
    call.broadcast = true;
  }
  call.priority = priority.empty() || priority == "broadcast" ? "normal" : priority;
  auto headers = mcvideo_call_headers();
  for (const auto &header : mcvideo_priority_headers(info)) headers.push_back(header);
  // TS 24.281 clause 9.2.1.2.4.1: a re-join sends this INVITE to the MCVideo session identity instead of the PSI.
  const auto request_uri = std::exchange(mcvideo_rejoin_target_, std::string());
  call.dialog = sip_.invite(
      request_uri.empty() ? mcvideo_service_uri() : request_uri, call.local,
      {{mcvideo_service.info_mime, service_info_xml(info, mcvideo_service)}},
      [this, generation = call.generation](const SipResponse &response) {
        mcvideo_invite_answered(generation, response);
      },
      headers);
  event("mcvideo_call_requested", {{"group", call.group}});
}

//------------------------------------------------------------------------------
// TS 24.281 clause 9.2.1.2.4.1: re-join the last group session this user left: the group call INVITE of clause
// 9.2.1.2.1.1 with the session identity as its Request-URI, without an implicit transmission request.
void Client::mcvideo_rejoin_call() {
  if (mcvideo_rejoin_uri_.empty()) throw std::runtime_error("no MCVideo group session to re-join");
  mcvideo_rejoin_target_ = mcvideo_rejoin_uri_;
  try {
    if (mcvideo_rejoin_type_ == "chat")
      mcvideo_group_call(false, mcvideo_rejoin_group_);
    else
      mcvideo_group_call(false, {}, {}, mcvideo_rejoin_group_);
  } catch (...) {
    mcvideo_rejoin_target_.clear();
    throw;
  }
}

//------------------------------------------------------------------------------
// Helper: a private call, or one of the one-to-one video calls built on it (TS 24.281 clauses 12.2.2, 13.2.2, 15.2.1).
bool Client::one_to_one_video(const std::string &session_type) {
  return session_type == "private" || session_type == "one-to-one video pull" ||
         session_type == "one-to-one video push" || session_type == "ambient-viewing";
}

//------------------------------------------------------------------------------
// TS 24.281 clauses 9.2.1.2.1.2 and 10.2.2.2.2: the MCVideo server invites this client to a group or private call
// (or one built on it); answered at once (automatic commencement, TS 24.281 clause 6.2.3) or after the user, with this
// client's MCVideo media.
SipResponse Client::mcvideo_invite(const SipMessage &message) {
  SipResponse response;
  if (asserted_identity(message) != mcvideo_service_uri()) {
    response.status_code = 403;
    return response;
  }
  if (mcvideo_call_ && mcvideo_call_->dialog != message.dialog_id) {
    response.status_code = 486;
    return response;
  }
  const auto info = message_info(message, mcvideo_service);
  if (mcvideo_call_) { // a re-INVITE of this call
    mcvideo_note_priority(info);
    return mcvideo_answer_invite(message, info);
  }
  // A new call: refused (488, 136, ...) or failing, it leaves no call behind.
  mcvideo_adopt_invited_call(message, info);
  const McvideoCallSetup setup{mcvideo_call_};
  auto answer = mcvideo_answer_invite(message, info);
  if (answer.status_code >= 300) {
    if (mcvideo_call_->pck_id) mcvideo_protection_.end_private(mcvideo_call_->pck_id);
    mcvideo_call_.reset();
  }
  return answer;
}

//------------------------------------------------------------------------------
// TS 24.281 clauses 9.2.1.2.1.2 and 10.2.2.2.2 (continued): the answer to the INVITE of the call in mcvideo_call_.
SipResponse Client::mcvideo_answer_invite(const SipMessage &message, const McpttInfo &info) {
  SipResponse response;
  // TS 24.581 clauses 14.3.2 and 14.3.3: the answer carries only parameters of the offer: the offered
  // mc_priority (at most), and queueing only if both sides queue.
  if (!message.remote_media || message.remote_media->control == MediaControl::floor || !message.remote_media->video ||
      (message.remote_media->control == MediaControl::none && !one_to_one_video(info.session_type))) {
    response.status_code = 488;
    return response;
  }
  // The server's transmission control SSRC for this client (mcvideo_media): an offer with one refused is rejected
  // with 488 (RFC 3261 section 13.3.1.3: "A UAS rejecting an offer contained in an INVITE SHOULD return a 488 (Not
  // Acceptable Here) response").
  if (message.remote_media->control != MediaControl::none &&
      floor_ssrc_refused(message.remote_media->floor_ssrc, mcvideo_call_->local.floor_ssrc,
                         mcvideo_call_->remote ? std::optional(mcvideo_call_->remote->floor_ssrc) : std::nullopt,
                         protected_floor() ? &mcvideo_protection_ : nullptr)) {
    response.status_code = 488;
    return response;
  }
  auto answer = mcvideo_call_->local;

  // TS 24.281 clause 4.7.1: protected media only with the matching key: the GMK in a group call.
  if (message.remote_media->protected_media)
    if (auto refused = mcvideo_accept_protection(message, info, answer)) return *refused;
  if (message.remote_media->control == MediaControl::none)
    answer.control = MediaControl::none; // TS 24.281 clause 10.2.3.1
  answer.floor_priority = message.remote_media->floor_priority;
  answer.queueing = message.remote_media->queueing && mcvideo_call_->local.queueing;

  // RFC 3264 section 6.1: a sendonly offer is answered recvonly and a recvonly one sendonly
  // (the server's offers to the two parties of an ambient viewing call).
  if (message.remote_media->direction == "sendonly")
    answer.direction = "recvonly";
  else if (message.remote_media->direction == "recvonly")
    answer.direction = "sendonly";
  // The same for the video media (TS 24.281 clause 6.2.1 step 3c puts the ambient viewing direction there).
  if (answer.video && message.remote_media->video) {
    const auto &offered = message.remote_media->video->direction;
    answer.video->direction = offered == "sendonly" ? "recvonly" : offered == "recvonly" ? "sendonly" : answer.direction;
  }
  // TS 24.281 clause 12.2.2.2 step 1: the pulled callee requests the permission to transmit implicitly;
  // so does the viewed-to callee of a remote-init ambient viewing call (TS 24.281 clause 15.2.1.1 step 13).
  // TS 24.581 clause 14.3.1: "The SDP fmtp attribute shall not include parameters that were not present in the
  // associated SDP offer", and in an answer mc_implicit_request accepts the offerer's request (clause 14): the answer
  // carries it only when the offer did; otherwise the callee asks with a Transmission Request once the call is active.
  const bool callee_requests =
      (mcvideo_call_->video_mode == "one-to-one video pull" || mcvideo_call_->ambient_role == "viewed-to") &&
      answer.control != MediaControl::none;
  answer.implicit_request = callee_requests && message.remote_media->implicit_request;
  mcvideo_call_->request_on_active = callee_requests && !message.remote_media->implicit_request;
  answer.video->payload_type = message.remote_media->video->payload_type;
  mcvideo_call_->local = answer;
  response.status_code = 200;
  response.local_media = answer;
  // TS 24.281 clauses 6.2.3.1.1 step 2 and 6.2.3.1.2 (automatic commencement, private and group calls) and
  // 6.2.3.2.1 step 2 (the 180 of manual commencement, a copy of this response): the option tag "timer" in Require.
  response.headers.push_back({"Require", "timer"});

  // TS 24.281 clause 6.2.3: a private call rings unless automatic commencement applies (Priv-Answer-Mode
  // Auto forces it; Answer-Mode Manual, or mcvideo.answer_mode manual, asks the user).
  const bool forced = answer_mode_value(get_header(message.headers, "Priv-Answer-Mode")) == "Auto";
  const bool manual = answer_mode_value(get_header(message.headers, "Answer-Mode")) == "Manual" ||
                      cfg_.at("mcvideo").value("answer_mode", std::string("auto")) == "manual";
  // TS 24.281 clause 15.2.1.2 step 6: an ambient viewing call is always answered automatically.
  if (mcvideo_call_->type == "private" && mcvideo_call_->ambient_type.empty() && !forced && manual) {
    mcvideo_call_->pending_answer = response;
    mcvideo_call_->state = "ringing";
    event("mcvideo_call_ringing", {{"from", mcvideo_call_->peer}});
    // As for MCPTT: the provisional response carries the same answer, sent as 200 later.
    auto ringing = response;
    ringing.status_code = 180;
    ringing.reason = "Ringing";
    return ringing;
  }
  event("mcvideo_call_invited", {{"group", mcvideo_call_->group}});
  return response;
}

//------------------------------------------------------------------------------
// TS 24.281 clauses 9.2.1.2.1.2 and 10.2.2.2.2: a new MCVideo call from the server's invitation: its dialog,
// group or peer, type, functional aliases, ambient viewing role (clause 15.2.1.2) or video mode, and priority.
void Client::mcvideo_adopt_invited_call(const SipMessage &message, const McpttInfo &info) {
  mcvideo_call_ = new_mcvideo_call();
  mcvideo_call_->dialog = message.dialog_id;
  mcvideo_call_->group = info.calling_group.empty() ? info.request_uri : info.calling_group;
  mcvideo_call_->state = "joining";
  mcvideo_call_->session_uri = contact_uri_of(get_header(message.headers, "Contact"));
  // The caller's functional alias and the alias this user was called by (TS 24.281 clause 10.2.2.2.2).
  mcvideo_call_->calling_alias = info.functional_alias;
  mcvideo_call_->called_alias = info.called_functional_alias;

  // TS 24.281 clause 22.2.2.1.2: an invitation to an ad hoc group call names its group in <mcvideo-calling-group-id>.
  if (info.session_type == "adhoc") mcvideo_call_->type = "adhoc";
  if (one_to_one_video(info.session_type)) {
    mcvideo_call_->type = "private";
    mcvideo_call_->group.clear();
    mcvideo_call_->peer = info.calling_user;
    if (info.session_type == "ambient-viewing") {
      // TS 24.281 clause 15.2.1.2 steps 7 and 10: the type and this client's role. The viewed-to user of
      // a remote-init call gets no indication (steps 8 and 9, NOTE 4); may release only with
      // the release capability (TS 24.281 clause 15.2.1.3 step 1, annex D.3).
      mcvideo_call_->ambient_type = info.ambient_listening_type;
      mcvideo_call_->ambient_role = info.ambient_listening_type == "remote-init" ? "viewed-to" : "viewing";
      mcvideo_call_->may_release = ambient_release_capability(message.headers);
    } else if (info.session_type != "private") {
      // TS 24.281 clause 13.2.2.2 step 1: the user is shown that this is a video push call.
      mcvideo_call_->video_mode = info.session_type;
      event("mcvideo_video_mode", {{"session_type", info.session_type}, {"from", info.calling_user}});
    }
  }
  mcvideo_note_priority(info);
}

//------------------------------------------------------------------------------
// TS 24.281 clause 4.7.1: protected media only with the matching key: the GMK in a group call, the caller's PCK
// in a one-to-one call (clause 10.2.2.2.2 step 3). The refusal (488), or empty with the answer protected.
std::optional<SipResponse> Client::mcvideo_accept_protection(const SipMessage &message, const McpttInfo &info,
                                                             MediaDescription &answer) {
  SipResponse response;
  if (one_to_one_video(info.session_type)) {
    // TS 24.281 clause 10.2.2.2.2 step 3 with TS 33.180 clause 7.4.1: the caller's PCK, authenticated as from the
    // calling MCVideo ID; a failure is 488 with warning 136.
    if (!mcvideo_call_->pck_id) {
      std::optional<keying::KeyMessage> key;
      try {
        if (message.remote_media->key_mgmt.empty() || !mcvideo_keys_)
          throw std::runtime_error("protected call without a PCK");
        key = mcvideo_keys_->accept_pck(info.calling_user, message.remote_media->key_mgmt).first;
      } catch (const std::exception &error) {
        event("mcvideo_private_call_key_rejected", {{"reason", error.what()}});
        mcvideo_end_call("key rejected");
        response.status_code = 488;
        response.headers = {{"Warning", mikey_failed}};
        return response;
      }
      mcvideo_bind_pck(*key, false);
    }
  } else if (!mcvideo_use_group_key(mcvideo_call_->group) || !mcvideo_protection_.group_ready()) {
    // TS 24.281 clause 4.7.1: the media of the call's group need that group's GMK.
    event("mcvideo_group_key_missing", {{"group_id", mcvideo_call_->group}});
    response.status_code = 488;
    return response;
  }
  answer.protected_media = true;
  return std::nullopt;
}

//------------------------------------------------------------------------------
// Helper: the negotiated media of the call's dialog; transmission control packets that came before it are handled
// now.
bool Client::mcvideo_media(std::string_view dialog, const MediaDescription &media) {
  if (!mcvideo_call_ || mcvideo_call_->dialog != dialog) return false;
  if (media.control != mcvideo_call_->local.control || !media.video)
    throw std::runtime_error("MCVideo call without MCVideo media");
  // TS 24.581 clause 4.3.3.1: the server's SSRC for this client's transmission control messages; refused when it is
  // this client's own or, under the MCVideo CSK, one already used for another stream (mcx::floor_ssrc_refused, RFC
  // 7714 section 8.4, RFC 3711 section 9.1); the call's own SSRC again (a re-INVITE, a refresh) is the stream going
  // on. The refusal ends this MCVideo call and its dialog (the media callback in client.cpp), as check_remote does
  // for an MCPTT call.
  if (media.control != MediaControl::none &&
      (std::exchange(mcvideo_refuse_control_ssrc_, false) ||
       floor_ssrc_refused(media.floor_ssrc, mcvideo_call_->local.floor_ssrc,
                          mcvideo_call_->remote ? std::optional(mcvideo_call_->remote->floor_ssrc) : std::nullopt,
                          protected_floor() ? &mcvideo_protection_ : nullptr)))
    throw std::runtime_error("directional transmission control SSRC collision");
  mcvideo_call_->remote = media;
  if (!mcvideo_call_->early_control.empty())
    schedule([this, generation = mcvideo_call_->generation] {
      if (!mcvideo_call_ || mcvideo_call_->generation != generation) return;
      auto held = std::move(mcvideo_call_->early_control);
      mcvideo_call_->early_control.clear();
      event("mcvideo_early_control", {{"packets", held.size()}});
      for (const auto &datagram : held) {
        if (!mcvideo_call_) break;
        try {
          mcvideo_control_packet(datagram);
        } catch (const std::exception &error) {
          event("mcvideo_control_rejected", {{"reason", error.what()}});
        }
      }
    });
  return true;
}

//------------------------------------------------------------------------------
// Helper: the SIP dialog of the call is confirmed (the call is active) or disconnected (the call ends).
bool Client::mcvideo_call_state(std::string_view dialog, SipCallState state) {
  if (!mcvideo_call_ || mcvideo_call_->dialog != dialog) return false;
  if (state == SipCallState::confirmed) {
    mcvideo_call_->state = "active";
    event("mcvideo_call_active", {{"group", mcvideo_call_->group}});
    // TS 24.281 clause 18.3.4.1: the MCVideo signalling event triggers of a non-emergency call.
    if (mcvideo_call_->priority == "normal")
      mcvideo_location_.signal(mcvideo_call_->type == "private" ? location::Event::PrivateCallNonEmergency
                                                                : location::Event::GroupCallNonEmergency,
                               monotonic_ms());
    if (mcvideo_call_->local.control == MediaControl::none) mcvideo_start_sending(mcvideo_call_->local.video->ssrc);
    if (std::exchange(mcvideo_call_->request_on_active, false) && !mcvideo_call_->granted)
      schedule([this] {
        try {
          if (mcvideo_call_ && !mcvideo_call_->granted && !mcvideo_call_->requesting) mcvideo_request_transmission();
        } catch (const std::exception &error) {
          event("mcvideo_transmission_request_failed", {{"reason", error.what()}});
        }
      });
    // TS 24.281 clause 9.2.3.2: at once, unless mcvideo.conference_auto_subscribe is false (the user subscribes with
    // mcvideo-conference-subscribe).
    if (cfg_.at("mcvideo").value("conference_auto_subscribe", true))
      schedule([this] { mcvideo_subscribe_conference(); });
  } else if (state == SipCallState::disconnected) {
    // A call to a functional alias may end in a 300, whose response comes after this.
    if (mcvideo_call_->to_alias)
      mcvideo_alias_call_ = {{"generation", mcvideo_call_->generation},
                             {"alias", mcvideo_call_->called_alias},
                             {"mode", mcvideo_call_->mode}};
    mcvideo_end_call("disconnected", true);
  }
  return true;
}

//------------------------------------------------------------------------------
// TS 24.281 clause 9.2.3.2: SUBSCRIBE to the conference event package of the session.
void Client::mcvideo_subscribe_conference() {
  // The conference event package describes group sessions only.
  if (!mcvideo_call_ || mcvideo_call_->type == "private" || mcvideo_call_->session_uri.empty() ||
      !mcvideo_call_->conference_subscription.empty())
    return;
  auto headers = mcvideo_call_headers();
  headers.push_back({"Accept", "application/conference-info+xml"});
  // TS 24.281 clause 9.2.3.2 step 8: an <mcvideoinfo> body with the group as <mcvideo-request-uri>.
  McpttInfo info;
  info.request_uri = mcvideo_call_->group;
  info.client_id = client_id();
  mcvideo_call_->conference_subscription = sip_.subscribe(
      mcvideo_call_->session_uri, "conference", 4294967295u, headers,
      [this](const SipResponse &response) {
        if (response.status_code >= 300 && mcvideo_call_) {
          mcvideo_call_->conference_subscription.clear();
          event("mcvideo_conference_subscription_rejected", {{"status", response.status_code}});
        }
      },
      mcvideo_service.info_mime, service_info_xml(info, mcvideo_service));
}

//------------------------------------------------------------------------------
// TS 24.281 clause 9.2.3.2 with RFC 6665 section 4.1.2.3: the user ends the conference event subscription: a
// SUBSCRIBE in its dialog with Expires 0.
void Client::mcvideo_unsubscribe_conference() {
  if (!mcvideo_call_ || mcvideo_call_->conference_subscription.empty())
    throw std::runtime_error("no MCVideo conference subscription");
  const auto dialog = std::exchange(mcvideo_call_->conference_subscription, std::string());
  McpttInfo info;
  info.request_uri = mcvideo_call_->group;
  info.client_id = client_id();
  sip_.request_in_dialog(dialog, "SUBSCRIBE", mcvideo_service.info_mime, service_info_xml(info, mcvideo_service),
                         {{"Event", "conference"}, {"Expires", "0"}, {"Accept", "application/conference-info+xml"}},
                         [this](const SipResponse &response) {
                           event("mcvideo_conference_unsubscribed", {{"status", response.status_code}});
                         });
  event("mcvideo_conference_unsubscribing");
}

//------------------------------------------------------------------------------
// TS 24.281 clause 9.2.3.2 with RFC 4575: the NOTIFY with a full or partial conference-info document from the
// session.
SipResponse Client::mcvideo_conference_notify(const SipMessage &message) {
  SipResponse response;
  response.status_code = 200;
  if (asserted_identity(message) != mcvideo_service_uri()) {
    response.status_code = 403;
    return response;
  }
  // RFC 6665 section 4.1.3: a NOTIFY for a subscription no longer known (its call has ended).
  if (!mcvideo_call_) {
    response.status_code = 481;
    return response;
  }
  auto &call = *mcvideo_call_;
  for (const auto &part :
       message.parts.empty() ? std::vector<BodyPart>{{message.content_type, message.body}} : message.parts) {
    if (part.content_type != "application/conference-info+xml") continue;
    const auto info = parse_conference_info(part.body);
    // RFC 4575 section 4.6: a partial notification applies only with the next version; after a gap (or an old one)
    // the current state is fetched again by refreshing the subscription.
    const bool partial = !info.full && !info.deleted;
    if (call.conference_version && info.version <= *call.conference_version) continue;
    if (partial && (!call.conference_version || info.version != *call.conference_version + 1)) {
      event("mcvideo_conference_version_gap", {{"version", info.version}});
      if (!call.conference_subscription.empty())
        sip_.request_in_dialog(call.conference_subscription, "SUBSCRIBE", {}, {},
                               {{"Event", "conference"}, {"Expires", "4294967295"},
                                {"Accept", "application/conference-info+xml"}});
      continue;
    }
    call.conference_version = info.version;
    if (info.full) {
      call.participants.clear();
      call.participant_aliases.clear();
    }
    for (const auto &user : info.users) {
      call.participant_aliases.erase(user.user_id);
      // RFC 4575 section 5.6: a user "deleted" from a partial notification leaves.
      if (user.state == "deleted" || user.status == "disconnected")
        call.participants.erase(user.user_id);
      else {
        call.participants[user.user_id] = user.status;
        // TS 24.281 clause 6.3.3.4 step 2 b) iii): the participant's functional alias.
        if (!user.functional_alias.empty()) call.participant_aliases[user.user_id] = user.functional_alias;
      }
    }
    event("mcvideo_conference_notified", {{"participants", call.participants.size()}, {"full", info.full}});
  }
  if (get_header(message.headers, "Subscription-State").starts_with("terminated")) call.conference_subscription.clear();
  return response;
}

//------------------------------------------------------------------------------
// Helper: end the MCVideo call: keep its figures in mcvideo_last_call_, drop its PCK and retire its conference
// subscription.
void Client::mcvideo_end_call(const std::string &reason, bool dialog_ended) {
  if (!mcvideo_call_) return;
  auto &call = *mcvideo_call_;
  // [dialog_ended]: the SIP stack reported the dialog's end; otherwise the dialog stays the MCVideo service's until it
  // does (mcvideo_closing_).
  if (!dialog_ended && !call.dialog.empty()) mcvideo_closing_.insert(call.dialog);
  // TS 24.281 clause 9.2.1.2.4.1: a group session can be re-joined while it goes on.
  if ((call.type == "prearranged" || call.type == "chat") && !call.session_uri.empty()) {
    mcvideo_rejoin_uri_ = call.session_uri;
    mcvideo_rejoin_group_ = call.group;
    mcvideo_rejoin_type_ = call.type;
  }
  mcvideo_last_call_ = {{"group", call.group},
                        {"type", call.type},
                        {"video_mode", call.video_mode},
                        {"ambient_type", call.ambient_type},
                        {"ambient_role", call.ambient_role},
                        {"release_reason", call.release_reason},
                        {"peer", call.peer},
                        {"generation", call.generation},
                        {"reason", reason},
                        {"frames_sent", call.sent},
                        {"frames_received", call.received},
                        {"sent_frames", call.sent_frames},
                        {"received_frames", call.received_frames},
                        {"undecoded", call.undecoded},
                        {"lost_units", call.depacketizer.lost_units()}};
  event("mcvideo_call_ended", {{"reason", reason}});
  if (call.pck_id) mcvideo_protection_.end_private(call.pck_id);
  if (!call.conference_subscription.empty()) mcvideo_retired_subscriptions_.insert(call.conference_subscription);
  mcvideo_call_.reset();
  // Back to the selected group's GMK (TS 33.180 clause 5.7) for the next call.
  if (protected_media()) mcvideo_use_group_key(mcvideo_group());
}

//------------------------------------------------------------------------------
// TS 24.581 clause 6.2.4.5.2: in 'U: has permission to transmit', send RTP media: the test pattern (or the pictures
// of mcvideo.live) with this SSRC, starting with an IDR picture so that receivers can decode at once.
void Client::mcvideo_start_sending(std::uint32_t ssrc) {
  auto &call = *mcvideo_call_;
  call.granted = true;
  call.video_ssrc = ssrc;
  if (!call.encoder) {
    call.encoder = std::make_unique<video::Encoder>(mcvideo_encoder_options());
    call.reference = std::make_unique<video::Decoder>();
  }
  call.encoder->request_key_frame();
  call.next_frame = monotonic_ms();
}

//------------------------------------------------------------------------------
// TS 24.581 clause 6.2.4: the transmission participant's messages to the transmission control server.
void Client::mcvideo_send_control(transmission::Type type, std::vector<FloorField> fields) {
  auto &call = *mcvideo_call_;
  if (!call.remote) throw std::runtime_error("MCVideo call has no negotiated media yet");
  transmission::Packet packet{type, false, call.remote->floor_ssrc, std::move(fields)};
  auto bytes = transmission::encode(packet);
  // TS 24.281 clause 4.7.1: transmission control is SRTCP under this client's CSK (CS-ID 8, TS 33.180 table E.1.3-1).
  if (protected_floor()) bytes = mcvideo_protection_.protect_floor(bytes);
  call.control->send(bytes, server_target(call.remote->floor_address, call.remote->floor_port), call.remote->floor_port);
}

//------------------------------------------------------------------------------
// TS 24.581 clause 6.2.4.3.2: in 'U: has no permission to transmit', send Transmission Request.
void Client::mcvideo_request_transmission() {
  if (!mcvideo_call_ || mcvideo_call_->state != "active") throw std::runtime_error("no active MCVideo call");
  mcvideo_call_->requesting = true;
  mcvideo_send_control(transmission::Type::Request, mcvideo_indicator_fields());
  event("mcvideo_transmission_requested");
}

//------------------------------------------------------------------------------
// TS 23.281 clauses 7.7.1.3.7 and 7.7.1.3.6.3 with TS 24.581 clauses 9.2.22 and 9.2.24: ask the
// server to start or end another participant's transmission. The Remote ID is
// left out (it has no field ID in TS 24.581 table 9.2.3.1-1): the server knows the sender.
void Client::mcvideo_remote_transmission(const std::string &target, bool start) {
  if (!mcvideo_call_ || mcvideo_call_->state != "active") throw std::runtime_error("no active MCVideo call");
  if (target.empty() || target == mcvideo_user())
    throw std::runtime_error("remote transmission requires another MCVideo ID");
  mcvideo_remote_result_ = {{"request", start ? "transmit" : "cancel"}, {"target", target}};
  status_["mcvideo_remote_result"] = mcvideo_remote_result_;
  mcvideo_send_control(start ? transmission::Type::RemoteRequest : transmission::Type::RemoteCancelRequest,
                       {user_id_field(target)});
  event("mcvideo_remote_transmission_requested", {{"target", target}, {"start", start}});
}

//------------------------------------------------------------------------------
// TS 24.581 clause 6.2.4.5.3: the user ends the transmission ("mcvideo-end", or its older name "mcvideo-release"):
// send Transmission end request (TS 24.581 clause 9.2.20); the server answers with Transmission end response. On-network
// the transmission participant never sends Transmission Release (TS 24.581 clause 9.2.7): clause 6.2.4 has no state
// that sends it. T101 retransmission is not implemented. A pending or queued request is cancelled the same way
// (TS 24.581 clauses 6.2.4.4.7 and 6.2.4.9.4: "Send Transmission end request message").
void Client::mcvideo_end_transmission() {
  if (!mcvideo_call_ || (!mcvideo_call_->granted && !mcvideo_call_->requesting))
    throw std::runtime_error("not transmitting or requesting MCVideo");
  const bool cancelling = !mcvideo_call_->granted;
  mcvideo_call_->granted = false;
  mcvideo_call_->requesting = false;
  mcvideo_send_control(transmission::Type::EndRequest);
  event(cancelling ? "mcvideo_transmission_request_cancelled" : "mcvideo_transmission_ended");
}

//------------------------------------------------------------------------------
// TS 24.581 clauses 6.2.4 and 6.2.5: the transmission participant receives a transmission control message from the
// server (TS 24.581 clause 9.2).
void Client::mcvideo_control_packet(const Datagram &datagram) {
  auto &call = *mcvideo_call_;
  if (!call.remote) {
    if (call.early_control.size() < 8) call.early_control.push_back(datagram);
    return;
  }
  if (!sent_by_server(datagram.source, call.remote->floor_address, call.remote->floor_port)) return;
  const auto packet =
      transmission::decode(protected_floor() ? mcvideo_protection_.unprotect_floor(datagram.bytes) : datagram.bytes);
  if (!transmission::from_server(packet.type) || packet.ssrc != call.local.floor_ssrc) return;
  mcvideo_control_message(packet);
}

//------------------------------------------------------------------------------
// TS 24.581 clause 6.2: a transmission control message from the transmission control server, received by unicast or
// (TS 24.581 clause 10.3.3, mcvideo/multicast.cpp) over the MBMS subchannel.
void Client::mcvideo_control_message(const transmission::Packet &packet) {
  auto &call = *mcvideo_call_;
  using transmission::Field;
  // TS 24.581 clause 6.2 (e.g. 6.2.4.4.6, 6.2.4.6.4, 6.2.4.9.2, 6.2.5.4.5, 6.2.5.5.5), step 1 for every message the
  // server sends with the x bit set (clause 9.2.2.1): a Transmission control Ack (clause 9.2.31) with the Source field
  // '0' (the participant) and the Message Name and Message Type (clauses 9.2.3.18 and 9.2.3.10: the subtype with its
  // first bit zero) of the acknowledged message, read back from the message's own encoding.
  if (packet.ack_required && packet.type != transmission::Type::Ack) {
    auto plain = packet;
    plain.ack_required = false;
    const auto bytes = transmission::encode(plain);
    mcvideo_send_control(
        transmission::Type::Ack,
        {{static_cast<std::uint8_t>(Field::Source), {0, 0}},
         {static_cast<std::uint8_t>(Field::MessageName), {bytes[8], bytes[9], bytes[10], bytes[11], 0, 0}},
         {static_cast<std::uint8_t>(Field::MessageType), {static_cast<std::uint8_t>(bytes[0] & 0x0f), 0}}});
  }
  // TS 24.581 clauses 9.2.21 and 9.2.27: the transmitting user's identity and SSRCs in an end response or a reception
  // end request or response (an unknown audio SSRC is left out).
  const auto transmitter_fields = [](const std::string &user, std::optional<std::uint32_t> audio, std::uint32_t video) {
    std::vector<FloorField> fields;
    if (!user.empty()) fields.push_back(transmission::transmitting_user_field(user));
    if (audio) fields.push_back(audio_ssrc_field(*audio));
    fields.push_back(transmission::video_ssrc_field(video));
    return fields;
  };
  switch (packet.type) {
  case transmission::Type::Granted: {
    // TS 24.581 clauses 6.2.4.4.6 and 9.2.5: the SSRCs to transmit with.
    call.requesting = false;
    call.queue_position.reset();
    call.transmitter = mcvideo_user();
    std::uint32_t ssrc = call.video_ssrc;
    if (const auto *field = transmission::find(packet, Field::VideoSsrc)) ssrc = transmission::read_video_ssrc(*field);
    mcvideo_start_sending(ssrc);
    event("mcvideo_transmission_granted", {{"video_ssrc", call.video_ssrc}});
    break;
  }
  case transmission::Type::ArbitrationTaken:
    if (const auto *field = transmission::find(packet, Field::TransmittingUserId))
      call.transmitter = transmission::read_transmitting_user(*field);
    call.granted = false;
    event("mcvideo_transmission_taken", {{"by", call.transmitter}});
    break;
  case transmission::Type::Rejected:
    call.granted = call.requesting = false;
    call.queue_position.reset();
    if (const auto *field = transmission::find(packet, Field::RejectCause))
      call.revoke_cause = read_reject_cause(*field).cause;
    event("mcvideo_transmission_rejected", {{"cause", call.revoke_cause}});
    break;
  case transmission::Type::RemoteResponse:
  case transmission::Type::RemoteCancelResponse: {
    // TS 24.581 clauses 9.2.23 and 9.2.25: the answer to a remote request, with its Result (TS 24.581 clause 9.2.3.17).
    const auto *field = transmission::find(packet, Field::Result);
    mcvideo_remote_result_["result"] = field ? Json(transmission::read_result(*field)) : Json();
    mcvideo_remote_result_["answered"] = packet.type == transmission::Type::RemoteResponse ? "transmit" : "cancel";
    status_["mcvideo_remote_result"] = mcvideo_remote_result_;
    event("mcvideo_remote_transmission_answered", mcvideo_remote_result_);
    break;
  }
  case transmission::Type::Revoked: {
    // TS 24.581 clause 6.2.4.5.5: stop sending media (step 3), then by the revoke reason (clause 9.2.10.2): cause #7
    // (queue the transmission) enters 'U: queued transmission' with a Queue Position Request (step 4b); any other
    // terminates the RTP stream with a Transmission End Request (step 4a), which the server answers with Transmission
    // End Response. T101/T102 retransmission is not implemented (as for mcvideo-end).
    if (const auto *field = transmission::find(packet, Field::RejectCause))
      call.revoke_cause = read_reject_cause(*field).cause;
    ++call.revocations;
    const bool was_granted = call.granted;
    call.granted = false;
    if (call.revoke_cause == 7) {
      call.requesting = true;
      mcvideo_send_control(transmission::Type::QueuePositionRequest);
    } else if (was_granted) {
      mcvideo_send_control(transmission::Type::EndRequest);
    }
    event("mcvideo_transmission_revoked", {{"cause", call.revoke_cause}, {"queued", call.revoke_cause == 7}});
    break;
  }
  case transmission::Type::EndRequest:
    // TS 24.581 clause 6.2.4.5.7: the server ends this participant's permission to transmit: inform the user (step 1),
    // stop sending media (step 3), send Transmission End Response (step 4, clause 9.2.21) and enter 'U: has no
    // permission to transmit' (step 5).
    ++call.revocations;
    call.granted = call.requesting = false;
    call.queue_position.reset();
    mcvideo_send_control(transmission::Type::EndResponse, transmitter_fields(mcvideo_user(), {}, call.video_ssrc));
    event("mcvideo_transmission_revoked", {{"by", "server"}});
    break;
  case transmission::Type::ReceptionEndRequest: {
    // TS 24.581 clause 6.2.5.5.5: the server ends the reception: inform the user (step 2), stop displaying (step 4) and
    // send Media reception end response (step 5) naming the stream (clause 9.2.27), as the request names it.
    std::string user = call.transmitter;
    std::optional<std::uint32_t> audio = call.transmitter_audio_ssrc;
    std::uint32_t video = call.transmitter_video_ssrc;
    if (const auto *field = transmission::find(packet, Field::TransmittingUserId))
      user = transmission::read_transmitting_user(*field);
    if (const auto *field = transmission::find(packet, Field::AudioSsrc)) audio = read_audio_ssrc(*field);
    if (const auto *field = transmission::find(packet, Field::VideoSsrc)) video = transmission::read_video_ssrc(*field);
    call.receiving = false;
    ++call.reception_ends;
    mcvideo_send_control(transmission::Type::ReceptionEndResponse, transmitter_fields(user, audio, video));
    event("mcvideo_reception_ended", {{"by", "server"}});
    break;
  }
  case transmission::Type::ReceptionOverrideNotification:
    // TS 24.581 clause 6.2.5.5.4: the reception is overridden: inform the user (step 1) and send Media reception end
    // request (step 3, clause 9.2.26) for the stream received; the server answers with Media reception end response.
    ++call.reception_ends;
    mcvideo_send_control(
        transmission::Type::ReceptionEndRequest,
        transmitter_fields(call.transmitter, call.transmitter_audio_ssrc, call.transmitter_video_ssrc));
    event("mcvideo_reception_overridden", {{"by", call.transmitter}});
    break;
  case transmission::Type::EndNotify:
    // TS 24.581 clause 6.2.5.3.4: inform the user that another user's transmission ended.
    event("mcvideo_transmission_end_notified", {{"by", call.transmitter}});
    break;
  case transmission::Type::MediaTransmissionNotification: {
    // TS 24.581 clause 6.2.5.3.2: the server announces another participant's transmission (on-network, clauses
    // 6.3.6.3.3 and 6.3.7.3.3, it replaces Transmission Arbitration Taken and Media Reception Notification).
    // Step 1, the Transmission control Ack, is sent above for every message that asks for one.
    ++call.reception_notifications;
    // Step 3: the transmitting user and its SSRCs; the user is the sender a protected group call's video is keyed by.
    if (const auto *field = transmission::find(packet, Field::TransmittingUserId))
      call.transmitter = transmission::read_transmitting_user(*field);
    if (const auto *field = transmission::find(packet, Field::AudioSsrc))
      call.transmitter_audio_ssrc = read_audio_ssrc(*field);
    if (const auto *field = transmission::find(packet, Field::VideoSsrc))
      call.transmitter_video_ssrc = transmission::read_video_ssrc(*field);
    call.granted = false;
    // Step 5: Reception Mode '0' (automatic, TS 24.581 clause 9.2.3.22) gives permission to receive at once;
    // otherwise the user asks for it (clause 6.2.5.3.3), at once unless mcvideo.auto_receive is false.
    const auto *mode = transmission::find(packet, Field::ReceptionMode);
    const bool automatic = mode && transmission::read_reception_mode(*mode);
    call.receiving = automatic;
    if (automatic) call.awaiting_key = true; // the relayed stream starts at an arbitrary picture
    event("mcvideo_transmission_taken", {{"by", call.transmitter}}); // step 2: notify the user
    event("mcvideo_reception_notified", {{"by", call.transmitter}, {"automatic", automatic}});
    if (!automatic && cfg_.at("mcvideo").value("auto_receive", true))
      mcvideo_send_control(transmission::Type::ReceiveMediaRequest, mcvideo_indicator_fields());
    break;
  }
  case transmission::Type::MediaReceptionNotification:
    // TS 24.581 clauses 6.2.5.3.2 and 9.2.16: a new transmission; accepted at once unless mcvideo.auto_receive is false.
    ++call.reception_notifications;
    call.receiving = false;
    if (const auto *field = transmission::find(packet, Field::UserId)) call.transmitter = read_user_id(*field);
    event("mcvideo_reception_notified", {{"by", call.transmitter}});
    if (cfg_.at("mcvideo").value("auto_receive", true))
      mcvideo_send_control(transmission::Type::ReceiveMediaRequest, mcvideo_indicator_fields());
    break;
  case transmission::Type::ReceiveMediaResponse:
    // TS 24.581 clauses 6.2.5.4.5 and 9.2.15: without a Reject Cause the reception is accepted.
    if (const auto *field = transmission::find(packet, Field::RejectCause)) {
      call.reception_reject_cause = read_reject_cause(*field).cause;
      call.receiving = false;
    } else {
      call.receiving = true;
      call.awaiting_key = true; // the relayed stream resumes at an arbitrary picture
    }
    event("mcvideo_reception_answered", {{"receiving", call.receiving}});
    break;
  case transmission::Type::ReceptionEndResponse:
    call.receiving = false;
    event("mcvideo_reception_ended");
    break;
  case transmission::Type::EndResponse: event("mcvideo_transmission_end_confirmed"); break;
  case transmission::Type::QueuePositionInfo:
    if (const auto *field = transmission::find(packet, Field::QueueInfo))
      call.queue_position = read_queue_info(*field).position;
    event("mcvideo_transmission_queued", {{"position", call.queue_position.value_or(0)}});
    break;
  case transmission::Type::Idle:
  case transmission::Type::ArbitrationRelease:
    call.transmitter.clear();
    call.granted = false;
    break;
  default: break;
  }
}

//------------------------------------------------------------------------------
// TS 26.281 clauses 4.1.1 and 4.1.3 with RFC 6184: reassemble, decode and record each received H.264 picture.
void Client::mcvideo_video_packet(const Datagram &datagram, bool multicast) {
  auto &call = *mcvideo_call_;
  // Video over an MBMS subchannel (mcvideo/multicast.cpp) comes from the server's media address on any port.
  if (!call.remote || !call.remote->video ||
      !(multicast ? from_server_address(datagram.source, call.remote->address)
                  : sent_by_server(datagram.source, call.remote->address, call.remote->video->port)))
    return;
  if (mcvideo_duplicate(datagram.bytes, multicast)) return;
  // TS 24.281 clause 4.7.1: SRTP under the GMK, keyed per sender: the user the server names as transmitting.
  // A private call's PCK needs no sender; a group call's GMK is keyed by the sender.
  const auto sender = call.type == "private" ? call.peer : call.transmitter;
  if (call.local.protected_media && sender.empty()) throw std::runtime_error("protected video from an unknown sender");
  const auto packet = decode_rtp(
      call.local.protected_media ? mcvideo_protection_.unprotect_media(datagram.bytes, sender) : datagram.bytes);
  if (packet.payload_type != call.local.video->payload_type) throw std::runtime_error("unexpected video payload type");

  // A new transmitting user is a new stream (TS 24.581 clause 9.2.5: its own SSRC, and its
  // own sequence numbers): start reassembling and decoding afresh.
  if (packet.ssrc != call.received_ssrc) {
    call.received_ssrc = packet.ssrc;
    call.depacketizer = video::Depacketizer{};
    call.decoder = std::make_unique<video::Decoder>();
    call.awaiting_key = true;
  }
  for (const auto &unit : call.depacketizer.push(packet.sequence, packet.timestamp, packet.marker, packet.payload)) {
    if (call.awaiting_key && !video::is_key_frame(unit)) {
      ++call.skipped_before_key;
      continue;
    }
    call.awaiting_key = false;
    forward_live_video(unit, packet.timestamp); // mcvideo.live_receive: to the client page
    const auto picture = call.decoder->decode(unit);
    if (!picture) {
      ++call.undecoded;
      continue;
    }
    ++call.received;
    if (call.received_frames.size() < 400)
      call.received_frames[std::to_string(packet.ssrc) + ":" + std::to_string(packet.timestamp)] =
          video::checksum(*picture);
  }
}

//------------------------------------------------------------------------------
// TS 26.281 clause 4.1.1: H.264 frames of the test pattern, or of mcvideo.live (15 per second unless configured), while
// granted, in RTP by RFC 6184.
void Client::mcvideo_tick(std::uint64_t now) {
  if (mcvideo_key_wait_) mcvideo_key_wait_done({}, false); // past its deadline only
  if (!mcvideo_call_) return;
  auto &call = *mcvideo_call_;
  if (!call.granted || !call.remote || !call.remote->video || !call.encoder || now < call.next_frame) return;
  const auto rate = call.encoder->options().frame_rate;
  call.next_frame = now + 1000 / rate;
  const auto timestamp = call.frame * (90000 / rate);
  const auto unit = call.encoder->encode(mcvideo_picture(call.encoder->options(), call.frame), now);
  ++call.frame;
  if (unit.empty()) return;
  for (const auto &payload : video::packetize(unit)) {
    RtpPacket rtp{
        call.remote->video->payload_type, payload.marker, call.sequence++, timestamp, call.video_ssrc, payload.bytes};
    auto bytes = encode_rtp(rtp);
    if (call.local.protected_media) try {
        bytes = mcvideo_protection_.protect_media(bytes, mcvideo_user());
      } catch (const PacketKeyExpired &) {
        // TS 33.180 annex E.6.10: past the PCK's Expiry time the packet is not sent (never in the clear); the call
        // itself is left as it is.
        status_["mcvideo_key_expired_packets"] = status_.value("mcvideo_key_expired_packets", 0ULL) + 1;
        continue;
      }
    call.video->send(bytes, server_target(call.remote->address, call.remote->video->port), call.remote->video->port);
  }
  ++call.sent;
  // The sender's own reconstruction: what a receiver must show for this timestamp.
  if (const auto local = call.reference->decode(unit); local && call.sent_frames.size() < 400)
    call.sent_frames[std::to_string(call.video_ssrc) + ":" + std::to_string(timestamp)] = video::checksum(*local);
}

//------------------------------------------------------------------------------
// Helper: read the call's transmission control, video and audio sockets.
void Client::mcvideo_poll() {
  for (int index = 0; mcvideo_call_ && index < 16; ++index) {
    auto datagram = mcvideo_call_->control->receive();
    if (!datagram) break;
    try {
      mcvideo_control_packet(*datagram);
    } catch (const std::exception &error) {
      event("mcvideo_control_rejected", {{"reason", error.what()}});
    }
  }
  for (int index = 0; mcvideo_call_ && index < 256; ++index) {
    auto datagram = mcvideo_call_->video->receive();
    if (!datagram) break;
    try {
      mcvideo_video_packet(*datagram);
    } catch (const std::exception &error) {
      event("mcvideo_video_rejected", {{"reason", error.what()}});
    }
  }
  for (int index = 0; mcvideo_call_ && index < 64; ++index)
    if (!mcvideo_call_->audio->receive()) break; // the audio component is not played
}

//------------------------------------------------------------------------------
// Helper: publish the MCVideo call's state in the status document.
void Client::mcvideo_call_status() {
  if (!mcvideo_call_) {
    // A finished call's figures stay only in mcvideo_last_call.
    for (const auto *key : {"mcvideo_transmitter",
                            "mcvideo_frames_sent",
                            "mcvideo_frames_received",
                            "mcvideo_sent_frames",
                            "mcvideo_received_frames",
                            "mcvideo_undecoded",
                            "mcvideo_queue_position",
                            "mcvideo_revocations",
                            "mcvideo_reject_cause",
                            "mcvideo_session_uri",
                            "mcvideo_participants",
                            "mcvideo_receiving",
                            "mcvideo_reception_notifications",
                            "mcvideo_reception_ends",
                            "mcvideo_transmitter_ssrcs",
                            "mcvideo_reception_reject_cause",
                            "mcvideo_skipped_before_key",
                            "mcvideo_call_type",
                            "mcvideo_peer",
                            "mcvideo_transmission_control",
                            "mcvideo_priority",
                            "mcvideo_video_mode",
                            "mcvideo_ambient_type",
                            "mcvideo_ambient_role",
                            "mcvideo_ambient_release",
                            "mcvideo_group",
                            "mcvideo_calling_functional_alias",
                            "mcvideo_called_functional_alias",
                            "mcvideo_participant_aliases"})
      status_.erase(key);
    status_["mcvideo_call_state"] = "idle";
    status_["mcvideo_transmitting"] = false;
  } else {
    const auto &call = *mcvideo_call_;
    status_["mcvideo_call_state"] = call.state;
    status_["mcvideo_call_type"] = call.type;
    status_["mcvideo_group"] = call.group; // the group, or the ad hoc group's identity
    status_["mcvideo_video_mode"] = call.video_mode;
    status_["mcvideo_ambient_type"] = call.ambient_type;
    status_["mcvideo_ambient_role"] = call.ambient_role;
    status_["mcvideo_ambient_release"] = call.may_release;
    status_["mcvideo_priority"] = call.priority;
    status_["mcvideo_transmission_control"] = call.local.control != MediaControl::none;
    status_["mcvideo_peer"] = call.peer;
    status_["mcvideo_transmitting"] = call.granted;
    status_["mcvideo_transmitter"] = call.transmitter;
    status_["mcvideo_frames_sent"] = call.sent;
    status_["mcvideo_frames_received"] = call.received;
    status_["mcvideo_sent_frames"] = call.sent_frames;
    status_["mcvideo_received_frames"] = call.received_frames;
    status_["mcvideo_undecoded"] = call.undecoded;
    status_["mcvideo_skipped_before_key"] = call.skipped_before_key;
    status_["mcvideo_queue_position"] = call.queue_position ? Json(*call.queue_position) : Json();
    status_["mcvideo_revocations"] = call.revocations;
    status_["mcvideo_reject_cause"] = call.revoke_cause;
    status_["mcvideo_session_uri"] = call.session_uri;
    status_["mcvideo_receiving"] = call.receiving;
    status_["mcvideo_reception_notifications"] = call.reception_notifications;
    status_["mcvideo_reception_ends"] = call.reception_ends;
    status_["mcvideo_transmitter_ssrcs"] = {{"audio", call.transmitter_audio_ssrc},
                                            {"video", call.transmitter_video_ssrc}};
    status_["mcvideo_reception_reject_cause"] = call.reception_reject_cause;
    Json participants = Json::object();
    for (const auto &[id, state] : call.participants) participants[id] = state;
    status_["mcvideo_participants"] = participants;
    status_["mcvideo_calling_functional_alias"] = call.calling_alias;
    status_["mcvideo_called_functional_alias"] = call.called_alias;
    status_["mcvideo_participant_aliases"] = call.participant_aliases;
  }
  status_["mcvideo_last_call"] = mcvideo_last_call_;
  if (live_video_fd_ >= 0) {
    const auto options = mcvideo_encoder_options();
    status_["mcvideo_live"] = {{"width", options.width},
                               {"height", options.height},
                               {"frame_rate", options.frame_rate},
                               {"pictures", live_video_frames_},
                               {"age_ms", live_video_at_ ? Json(monotonic_ms() - live_video_at_) : Json()}};
  }
}

} // namespace mcx
