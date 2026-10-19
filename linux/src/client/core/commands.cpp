//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// The control commands of the client: its own test and dispatcher interface, each starting an MCPTT procedure or
// handing the command to the MCData, MCVideo, location, group management or private call service commands.
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 24.380 V19.2.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19),
// TS 23.379 V19.11.0 (Rel-19), RFC 3261.

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// Command entry point: signal the location reporting triggers of a call request (TS 24.379 clause 13.2.2), let a call
// request by a listened-to user end its ambient listening call (TS 23.379 clause 10.14.3.3, place_call()) and run
// the command.
void Client::command(const std::string &cmd) {
  // McpttSignallingEvent triggers (TS 24.379 annex F.3): a non-emergency group or private call.
  if (cmd == "call" || cmd == "rejoin" || cmd == "chat" || cmd == "call broadcast")
    location_signal(location::Event::GroupCallNonEmergency);
  if (cmd.starts_with("private ") && cmd.find("emergency") == std::string::npos)
    location_signal(location::Event::PrivateCallNonEmergency);
  // A call request by a listened-to user ends its ambient listening call.
  if (cmd == "call" || cmd.starts_with("call ") || cmd == "rejoin" || cmd == "chat" || cmd.starts_with("chat ") ||
      cmd.starts_with("private ") || cmd.starts_with("private-fa ") || cmd.starts_with("first-to-answer ") ||
      cmd.starts_with("first-to-answer-fa ") || cmd.starts_with("adhoc ") || cmd.starts_with("ambient ") ||
      cmd.starts_with("callback-fulfil "))
    place_call([&] { run_command(cmd); });
  else
    run_command(cmd);
}

//------------------------------------------------------------------------------
// Command: run one control command:
//   emergency|imminent on|off, emergency on|off alert, alert on|off [<user>]: priority changes and emergency alerts
//     (TS 24.379 clause 10.1.1.2.1.3 to clause 10.1.1.2.1.5, clause 11.1.1.2.1.4, clause 11.1.1.2.1.5, clause 12.1.1.1,
//     clause 12.1.1.2);
//   regroup create|users|remove: regroup using a preconfigured group (clause 16.2.1.1, clause 16.3.1.1,
//     clause 16.2.1.2);
//   priority <0..99>, answer-mode auto|manual, ambient-source <wav>|none: local settings;
//   group <group> [from=<group>]: select and affiliate to a group (clause 9.2.1.2), fetching its group document;
//   floor-confirm, queue-status, ptt <wav>, release: floor control (TS 24.380 clause 6.2.4.9.12, clause 6.2.4.9.9,
//     clause 6.2.4.3.5, clause 6.2.4.5.3);
//   answer, reject: answer a call in manual commencement mode (TS 24.379 clause 6.2.3.2);
//   call [broadcast] [emergency|imminent], chat [emergency|imminent], rejoin, call force: prearranged group call
//     (clause 10.1.1.2.1.1), chat group call (clause 10.1.2.2.1.1), rejoin by session identity;
//   pre-establish, chat-join <group>: test aids for the server's refusals;
//   hangup: release the call (clause 10.1.1.2.3.1, clause 11.1.3.1.1.1, ambient clause 11.1.6.2.1.3);
//   affiliate, deaffiliate: affiliation status change (clause 9.2.1.2);
//   options <SIP URI>: reachability probe (RFC 3261 section 11);
//   log-off, log-on: log off every MC service (clause 7.2.1A step 4 and NOTE 3) and de-register, then back;
//   quit; the other words go to the private call service, ad hoc, functional alias, MCData, MCVideo, SEAL, location,
//   profile and group management commands.
void Client::run_command(const std::string &cmd) {
  if (cmd == "emergency on alert" || cmd == "emergency off alert") {
    if (call_type_ != "private") throw std::runtime_error("use 'alert on|off' for group emergency alerts");
    change_private_priority(cmd.starts_with("emergency on"), true);
  } else if (cmd == "emergency on" || cmd == "emergency off") {
    change_priority(true, cmd.ends_with("on"));
  } else if (cmd == "imminent on" || cmd == "imminent off") {
    change_priority(false, cmd.ends_with("on"));
  } else if (cmd == "alert on" || cmd == "alert off" || cmd.starts_with("alert off ")) {
    change_alert(cmd == "alert on", cmd.starts_with("alert off ") ? cmd.substr(10) : std::string{});
  } else if (cmd.starts_with("regroup ")) {
    regroup_request_command(cmd);
  } else if (cmd.starts_with("priority ")) {
    floor_priority_command(cmd);
  } else if (cmd.starts_with("answer-mode ")) {
    answer_mode_command(cmd);
  } else if (cmd.starts_with("group ")) {
    select_group(cmd);
  } else if (cmd == "floor-confirm") {
    if (!floor_timers_.confirm_queued_grant()) throw std::runtime_error("no queued floor grant awaiting confirmation");
    requesting_ = false;
    talking_ = true;
    next_audio_ = monotonic_ms();
  } else if (cmd == "queue-status") {
    send_floor(FloorType::QueuePositionRequest);
  } else if (cmd == "answer" || cmd == "reject") {
    answer_command(cmd);
  } else if (cmd == "call" || cmd == "rejoin" || cmd == "call emergency" || cmd == "call imminent" ||
             cmd == "call broadcast" || cmd == "call broadcast emergency" || cmd == "call broadcast imminent" ||
             cmd == "chat" || cmd == "chat emergency" || cmd == "chat imminent" || cmd == "call force") {
    group_call_command(cmd);
  } else if (cmd == "pre-establish") {
    pre_establish_command();
  } else if (cmd.starts_with("chat-join ")) {
    chat_join_command(cmd);
  } else if (cmd == "conference-subscribe") {
    subscribe_conference(true);
  } else if (cmd.starts_with("callback ") || cmd.starts_with("callback-cancel ") ||
             cmd.starts_with("callback-fulfil ") || cmd.starts_with("remote-call ") ||
             cmd.starts_with("remote-group-call ") || cmd.starts_with("transfer ") || cmd.starts_with("forward ")) {
    private_service_command(cmd);
  } else if (cmd.starts_with("ambient ")) {
    ambient_call(cmd.substr(8));
  } else if (cmd.starts_with("ambient-source ")) {
    const auto path = cmd.substr(15);
    if (path != "none") (void)read_wav(path);
    ambient_source_ = path == "none" ? std::string{} : path;
  } else if (cmd.starts_with("sds") || cmd.starts_with("mcdata-") || cmd.starts_with("fd-") ||
             cmd.starts_with("ipcon")) {
    mcdata_command(cmd);
  } else if (cmd.starts_with("adhoc-alert ")) {
    adhoc_alert(cmd.substr(12));
  } else if (cmd.starts_with("adhoc-modify ")) {
    adhoc_modify(cmd.substr(13));
  } else if (cmd.starts_with("adhoc ")) {
    adhoc_call(cmd.substr(6));
  } else if (cmd.starts_with("private ")) {
    private_call(cmd.substr(8));
  } else if (cmd.starts_with("private-fa ")) {
    private_call(cmd.substr(11), true);
  } else if (cmd.starts_with("first-to-answer-fa ")) {
    first_to_answer_call(cmd.substr(19), true);
  } else if (cmd.starts_with("fa ")) {
    functional_alias_command(cmd.substr(3));
  } else if (cmd.starts_with("first-to-answer ")) {
    first_to_answer_call(cmd.substr(16));
  } else if (cmd.starts_with("ptt ")) {
    ptt_command(cmd);
  } else if (cmd == "release") {
    if (!ambient_hidden()) release();
  } else if (cmd == "refresh-affiliation") {
    // Test aid: refresh the MCPTT (and MCVideo) affiliation status subscriptions now, as the timer does before the
    // granted duration ends (TS 24.379 clause 9.2.1.3, TS 24.281 clause 8.2.1.3).
    refresh_affiliation_subscription(false);
    if (mcvideo_enabled()) refresh_affiliation_subscription(true);
  } else if (cmd == "refresh-reinvite") {
    // Test aid: a session refresh by re-INVITE (RFC 4028 section 7.4) with the current offer and no MC service
    // information, as a third-party client may send; the final status goes to last_refresh.
    if (dialog_.empty()) {
      failure("refresh-reinvite: no call");
    } else {
      sip_.reinvite(dialog_, local_, {}, [this](const SipResponse &response) {
        if (response.status_code < 200) return;
        status_["last_refresh"] = response.status_code;
        event("session_refresh_answered", {{"status", response.status_code}});
      });
    }
  } else if (cmd == "hangup") {
    hangup_command();
  } else if (cmd == "affiliate") {
    if (!authorized_) throw std::runtime_error("authorization required");
    desired_affiliation_ = true;
    affiliate();
  } else if (cmd == "deaffiliate") {
    deaffiliate_command();
  } else if (cmd.starts_with("profile-revoke ")) {
    profile_command(cmd);
  } else if (cmd.starts_with("group-create ") || cmd.starts_with("group-delete ") || cmd.starts_with("group-member ") ||
             cmd.starts_with("group-subscribe ") || cmd == "group-unsubscribe" ||
             cmd.starts_with("temporary-create ") || cmd.starts_with("temporary-delete ")) {
    group_management_command(cmd);
  } else if (cmd.starts_with("seal ")) {
    seal_command(cmd);
  } else if (cmd.starts_with("location ")) {
    location_command(cmd);
  } else if (cmd.starts_with("mcvideo-")) {
    mcvideo_command(cmd);
  } else if (cmd.starts_with("options ")) {
    options_command(cmd);
  } else if (cmd == "log-off") {
    log_off_command();
  } else if (cmd == "log-on") {
    log_on_command();
  } else if (cmd == "quit")
    stopping = 1;
  else
    throw std::runtime_error("unknown client command");
}

//------------------------------------------------------------------------------
// Local setting: "priority <0..99>", the floor priority this client asks for (TS 24.380 clause 6.2.4.3.5).
void Client::floor_priority_command(const std::string &cmd) {
  const auto value = cmd.substr(9);
  std::size_t used = 0;
  const auto priority = std::stoul(value, &used);
  if (used != value.size() || priority > 99) throw std::runtime_error("floor priority must be 0..99");
  cfg_["mcptt"]["floor_priority"] = priority;
}

//------------------------------------------------------------------------------
// Local setting: "answer-mode auto|manual", the commencement mode of incoming calls (TS 24.379 clause 6.2.3.2).
void Client::answer_mode_command(const std::string &cmd) {
  if (!dialog_.empty()) throw std::runtime_error("answer mode requires idle state");
  const auto mode = cmd.substr(12);
  if (mode != "auto" && mode != "manual") throw std::runtime_error("answer mode must be auto or manual");
  cfg_["mcptt"]["answer_mode"] = mode;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 16.2.1.1, clause 16.3.1.1 and clause 16.2.1.2: the regroup command:
//   regroup create <temporary group> <preconfigured group> <group>[,<group>...]
//   regroup users <temporary group> <preconfigured group> <MCPTT ID>[,<MCPTT ID>...]
//   regroup remove <temporary group>
void Client::regroup_request_command(const std::string &cmd) {
  std::istringstream words(cmd);
  std::string verb, action, tgi, preconfigured, list;
  words >> verb >> action >> tgi >> preconfigured >> list;
  if ((action != "create" && action != "users" && action != "remove") || tgi.empty())
    throw std::runtime_error("usage: regroup create|users <tgi> <preconfigured> <groups|users> | regroup remove <tgi>");
  std::vector<std::string> entries;
  if (action != "remove") {
    if (preconfigured.empty() || list.empty())
      throw std::runtime_error("a regroup needs a preconfigured group and members");
    for (std::size_t start = 0; start <= list.size();) {
      const auto end = std::min(list.find(',', start), list.size());
      if (end > start) entries.push_back(list.substr(start, end - start));
      start = end + 1;
    }
  } else {
    // TS 24.379 clause 16.2.1.2 step 9: only the regroup and the action; the remover need not
    // have been told about the regroup.
    preconfigured = regroups_.contains(tgi) ? regroups_.at(tgi).value("preconfigured", std::string{}) : std::string{};
  }
  if (action == "users")
    regroup_command("create", tgi, preconfigured, {}, entries);
  else
    regroup_command(action, tgi, preconfigured, entries);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.2: "group <group> [from=<group>]" selects a group and affiliates to it, fetching its
// group document first.
void Client::select_group(const std::string &cmd) {
  if (!authorized_ || !dialog_.empty()) throw std::runtime_error("group selection requires authorized idle state");
  auto selected = cmd.substr(6);
  // Test aid: "group <tgi> from=<group>" names the constituent group a call
  // on a regroup is placed from, in place of the one used before it, and
  // "from=" names none, so that step 5a and step 6b can be exercised.
  std::optional<std::string> from;
  if (const auto at = selected.find(" from="); at != std::string::npos) {
    from = selected.substr(at + 6);
    selected = selected.substr(0, at);
  }
  // TS 24.379 clause 16.1: a regroup this client has been told about is used at once, with the
  // configuration of its preconfigured group; it has no document of its own.
  const bool regroup = regroups_.contains(selected);

  // TS 24.379 clause 10.1.1.2.1.1 step 14d (clause 10.1.2.2.1.1 step 13d for a chat group): a call on a group
  // regroup names the constituent group it is placed from, which is the group this client was using before it
  // moved to the regroup.
  if (regroup) {
    const auto groups = regroups_.at(selected).value("groups", std::vector<std::string>{});
    calling_constituent_ = std::find(groups.begin(), groups.end(), group()) != groups.end() ? group() : std::string{};
  } else {
    calling_constituent_.clear();
  }
  if (from) calling_constituent_ = *from;

  // The configured groups (mcptt.allowed_groups) and those of the user profile (TS 24.484 clause 8.3.2).
  const auto allowed = cfg_.at("mcptt").value("allowed_groups", std::vector<std::string>{group()});
  if (!regroup && std::find(allowed.begin(), allowed.end(), selected) == allowed.end() &&
      std::find(profile_groups_.begin(), profile_groups_.end(), selected) == profile_groups_.end())
    throw std::runtime_error("group is not provisioned for selection");
  const auto authorising = regroup ? regroups_.at(selected).value("preconfigured", selected) : selected;
  auto document =
      http_.request("GET",
                    cfg_.at("endpoints").at("gms").get<std::string>() +
                        "/xcap-root/org.openmobilealliance.groups/global/byGroupID/" + url_encode(authorising),
                    {}, {{"Authorization", "Bearer " + token_}});
  if (document.status != 200) throw std::runtime_error("group authorization retrieval failed");
  cfg_["mcptt"]["group_id"] = selected;
  emergency_ = imminent_ = false;
  alerts_.clear();
  alert_pending_.clear();
  status_["alert_locations"] = Json::object();
  desired_affiliation_ = true;
  affiliated_ = false;
  session_uri_.clear();
  status_["session_uri"] = "";
  protection_.clear_group();
  status_["protected_ready"] = false;
  subscribe_keys();
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.2.3.2: "answer" or "reject" an incoming call in manual commencement mode.
void Client::answer_command(const std::string &cmd) {
  if (!pending_answer_ || dialog_.empty()) throw std::runtime_error("no incoming call awaiting answer");
  auto response = *pending_answer_;
  pending_answer_.reset();
  if (cmd == "reject" && (call_type_ == "private" || call_type_ == "adhoc")) {
    // TS 24.379 clause 6.2.3.2.1: a declined private call invitation.
    response = {};
    response.status_code = 480;
    response.headers = {{"Warning", "399 mcx \"110 user declined the call invitation\""}};
  } else if (cmd == "reject") {
    response.status_code = 486;
    response.reason = "Busy Here";
  } else if (call_type_ == "private" && first_to_answer_ && protected_media() && response.local_media) {
    // TS 24.379 clause 11.1.1.2.1.2 step 5: the invitee encapsulates a PCK to the caller
    // in its SDP answer. The caller initiated the call (TS 33.180 annex E.1.3).
    const auto key = offer_private_key(private_peer_);
    response.local_media->key_mgmt = local_.key_mgmt;
    bind_private_key(key, false);
  }
  sip_.answer(dialog_, response);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.1.2.1.1 and clause 10.1.2.2.1.1: "call [broadcast] [emergency|imminent]", "chat
// [emergency|imminent]", "rejoin" or "call force": build the MCPTT information and priority headers of a
// prearranged or chat group call, then invite.
void Client::group_call_command(const std::string &cmd) {
  const bool chat = cmd.starts_with("chat");
  // Test aid: "call force" sends a normal call even while unaffiliated, to
  // exercise the server's 403 warning 120 path.
  const bool force = cmd == "call force";
  // Ad hoc members are implicitly affiliated, so rejoining one needs no group affiliation.
  // Chat joins and emergency or imminent peril calls may affiliate implicitly
  // (TS 24.379 clause 9.2.2.3.6); the server decides eligibility and rejects others with 120.
  const bool broadcast = cmd.starts_with("call broadcast");
  const bool emergency = cmd.ends_with(" emergency"), imminent = cmd.ends_with(" imminent");
  const bool may_affiliate_implicitly = chat || emergency || imminent;
  if (!authorized_ ||
      (!affiliated_ && !may_affiliate_implicitly && !force && !(cmd == "rejoin" && last_call_type_ == "adhoc")) ||
      !dialog_.empty())
    throw std::runtime_error("call requires affiliation and idle state");
  McpttInfo info;

  // TS 24.379 clause 10.1.2.2.1.1: initiating and joining a chat group call use the
  // same INVITE with session-type "chat"; the server never invites members.
  info.session_type = chat ? "chat" : "prearranged";
  info.request_uri = group();
  info.client_id = client_id();

  // TS 24.379 clause 10.1.1.2.1.1 step 14d and clause 10.1.2.2.1.1 step 13d: the constituent group this call on a
  // regroup is placed from, in <associated-group-id>; the function of that group passes it on to the controlling
  // function in <mcptt-calling-group-id>.
  if (regroups_.contains(group())) info.associated_group = calling_constituent_;
  info.functional_alias = functional_alias_;
  // TS 24.379 clause 10.1.2.2.1.1: a chat join may carry emergency or imminent peril.
  if (emergency || (!chat && alerts_.contains(user()))) {
    info.emergency = true;
    info.alert = false;
  }
  if (imminent && !info.emergency) info.imminent_peril = true;

  // TS 24.379 clause 6.2.8.2: <broadcast-ind> true on an on-demand prearranged call,
  // optionally an emergency or imminent-peril one (local policy).
  if (broadcast) info.broadcast = true;
  auto headers = priority_headers(info);
  // A remotely initiated call's application priority replaces the normal value.
  if (auto priority = std::exchange(next_call_priority_, {});
      !priority.empty() && !info.emergency && !info.imminent_peril) {
    std::erase_if(headers, [](const SipHeader &header) { return header.name == "Resource-Priority"; });
    headers.push_back({"Resource-Priority", priority});
    event("ric_priority_applied", {{"target", group()}, {"resource_priority", priority}});
  }
  invite_group_call(cmd, chat, info, headers);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.1.2.1.1 and clause 10.1.2.2.1.1: set up the floor and media of a group call (an ad hoc
// rejoin by session identity: clause 17.2.5.1.1) and send its INVITE.
void Client::invite_group_call(const std::string &cmd, bool chat, McpttInfo &info,
                               const std::vector<SipHeader> &headers) {
  const unsigned maximum_priority =
      cfg_.at("mcptt").value("maximum_floor_priority", cfg_.at("mcptt").value("floor_priority", 0u));
  if (maximum_priority > 99) throw std::runtime_error("maximum floor priority must be 0..99");
  local_.floor_priority = static_cast<std::uint8_t>(maximum_priority);
  local_.floor_ssrc = random_ssrc();
  local_.queueing = cfg_.at("mcptt").value("floor_queue", false);
  if (cmd == "rejoin" && session_uri_.empty()) throw std::runtime_error("no previous session identity");
  const bool adhoc_rejoin = cmd == "rejoin" && last_call_type_ == "adhoc";
  if (adhoc_rejoin) {
    // TS 24.379 clause 17.2.5.1.1: rejoin the ad hoc session by its session identity.
    info.session_type = "adhoc";
    info.request_uri = last_call_group_;
    info.emergency.reset();
    info.alert.reset();
    info.imminent_peril.reset();
  }
  floor_sequence_.reset();
  floor_timers_ = participant_timers();
  call_type_ = adhoc_rejoin ? "adhoc" : chat ? "chat" : info.broadcast == true ? "broadcast" : "prearranged";
  call_group_ = adhoc_rejoin ? last_call_group_ : std::string{};
  dialog_ = sip_.invite(
      cmd == "rejoin" ? session_uri_ : service(), local_, info_parts(info),
      [this](const SipResponse &response) {
        if (response.status_code >= 200 && response.status_code < 300) {
          remember_session(response.headers);
          priority_response(response);
        } else if (response.status_code >= 300) {
          status_["last_call_failure"] = {{"status", response.status_code},
                                          {"warning", std::string(get_header(response.headers, "Warning"))}};
          failure("call setup: " + std::to_string(response.status_code));
        }
        // TS 24.379 clause 10.1.5.2.2: report the outcome of a remotely initiated group call.
        if (response.status_code >= 200 && followup_ && followup_->kind == "remote-group")
          report_followup(response.status_code < 300);
      },
      headers);
  status_["last_call_failure"] = nullptr;
  status_["call_state"] = "calling";
}

//------------------------------------------------------------------------------
// Test aid: an INVITE to the pre-established session PSI (TS 24.379 clause 8.2.1).
// Pre-established sessions are not implemented; this exercises the server's
// clause 8.2.2 step 5 refusal (403, warning 100).
void Client::pre_establish_command() {
  if (!authorized_ || !dialog_.empty())
    throw std::runtime_error("pre-established session setup requires authorized idle state");
  const auto &mcptt = cfg_.at("mcptt");
  const auto target = mcptt.value("pre_established_uri", service() + ";mcptt-pre-established");
  McpttInfo info;
  info.client_id = client_id();
  local_.floor_priority.reset();
  local_.floor_ssrc = random_ssrc();
  floor_sequence_.reset();
  floor_timers_ = participant_timers();
  status_["last_call_failure"] = nullptr;
  dialog_ = sip_.invite(target, local_, info_parts(info), [this](const SipResponse &response) {
    if (response.status_code >= 300) {
      status_["last_call_failure"] = {{"status", response.status_code},
                                      {"warning", std::string(get_header(response.headers, "Warning"))}};
      failure("pre-established session setup: " + std::to_string(response.status_code));
    } else if (response.status_code >= 200 && !dialog_.empty()) {
      sip_.bye(dialog_); // unexpected acceptance: do not keep an unmanaged session
    }
  });
  status_["call_state"] = "calling";
}

//------------------------------------------------------------------------------
// Test aid: join another provisioned chat group while staying affiliated to
// the selected group (TS 24.379 clause 10.1.2.2.1.1), to exercise the server's
// implicit affiliation checks such as the N2 limit (486, warning 102). An
// accepted join is released at once; the outcome is last_call_failure.
void Client::chat_join_command(const std::string &cmd) {
  const auto target_group = cmd.substr(10);
  const auto allowed = cfg_.at("mcptt").value("allowed_groups", std::vector<std::string>{group()});
  if (std::find(allowed.begin(), allowed.end(), target_group) == allowed.end())
    throw std::runtime_error("group is not provisioned for selection");
  if (!authorized_ || !dialog_.empty()) throw std::runtime_error("chat join requires authorized idle state");
  McpttInfo info;
  info.session_type = "chat";
  info.request_uri = target_group;
  info.client_id = client_id();
  local_.floor_priority.reset();
  local_.floor_ssrc = random_ssrc();
  floor_sequence_.reset();
  floor_timers_ = participant_timers();
  status_["last_call_failure"] = nullptr;
  dialog_ = sip_.invite(service(), local_, info_parts(info), [this](const SipResponse &response) {
    if (response.status_code >= 300) {
      status_["last_call_failure"] = {{"status", response.status_code},
                                      {"warning", std::string(get_header(response.headers, "Warning"))}};
      failure("chat join: " + std::to_string(response.status_code));
    } else if (response.status_code >= 200 && !dialog_.empty()) {
      status_["last_call_failure"] = {{"status", response.status_code}, {"warning", ""}};
      // Not from within PJSIP's handling of the 200 (OK): ending the session there aborts on its reference count.
      schedule([this, id = dialog_] {
        if (dialog_ == id) sip_.bye(id);
      });
    }
  });
  status_["call_state"] = "calling";
}

//------------------------------------------------------------------------------
// TS 24.380 clause 6.2.4.3.5: "ptt <wav>" requests the floor and transmits the file once granted (without floor
// control, TS 24.379 clause 11.1.2: at once); "ptt live" transmits the dispatcher's microphone (media.live).
void Client::ptt_command(const std::string &cmd) {
  if (!remote_ || status_.value("call_state", "") != "active") throw std::runtime_error("PTT requires active call");
  if (talking_ || requesting_) throw std::runtime_error("PTT already active");
  if (cmd == "ptt live") {
    start_live_transmission();
  } else {
    wav_path_ = cmd.substr(4);
    transmit_ = read_wav(wav_path_);
  }
  transmit_offset_ = 0;
  if (no_floor()) {
    // TS 24.379 clause 11.1.2: nothing to request; speak at once with the announced SSRC.
    audio_ssrc_ = local_.audio_ssrc;
    talking_ = true;
    next_audio_ = monotonic_ms();
    event("speech_started", {{"floor_control", false}});
    return;
  }
  requesting_ = true;
  send_floor(FloorType::Request);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 10.1.1.2.3.1, clause 11.1.3.1.1.1 and, for an ambient listening call, clause 11.1.6.2.1.3:
// "hangup" releases the floor and the call.
void Client::hangup_command() {
  if (call_type_ == "ambient" && ambient_) {
    // TS 24.379 clause 11.1.6.2.1.3 step 1: release only with the server's annex D.3 indication. A
    // remotely initiated call is invisible to its listened-to user: no-op.
    if (!ambient_->release_allowed) {
      if (ambient_hidden()) return;
      throw std::runtime_error("ambient listening call release not permitted");
    }
    if (!ambient_hidden()) release();
    if (!dialog_.empty()) sip_.bye(dialog_);
    return;
  }
  release();
  if (!dialog_.empty()) sip_.bye(dialog_);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.2: "deaffiliate" ends the call and withdraws the affiliation (PUBLISH, Expires 0).
void Client::deaffiliate_command() {
  desired_affiliation_ = false;
  if (!ambient_hidden()) {
    release();
    if (!dialog_.empty()) sip_.bye(dialog_);
  }
  auto [type, body] = publish_body(true);
  sip_.publish(service(), type, body,
               {{"P-Preferred-Service", mcptt_icsi},
                {"Event", "presence"},
                {"Expires", "0"},
                {"SIP-If-Match", affiliation_etag_}},
               [this](const SipResponse &response) {
                 if (response.status_code == 200) {
                   affiliated_ = false;
                   affiliation_etag_.clear();
                 }
               });
}

//------------------------------------------------------------------------------
// RFC 3261 section 11: "options <SIP URI>", a reachability probe.
void Client::options_command(const std::string &cmd) {
  // A reachability probe (RFC 3261 section 11) through the SIP core, for example of an
  // MC service server's PSI; the final status is recorded under "options".
  const auto target = cmd.substr(8);
  if (!target.starts_with("sip:") || target.find_first_of(" <>\r\n") != std::string::npos)
    throw std::runtime_error("options needs a SIP URI");
  sip_.request("OPTIONS", target, {}, {}, {}, [this, target](const SipResponse &response) {
    if (response.status_code >= 200) status_["options"][target] = response.status_code;
  });
}

} // namespace mcx
