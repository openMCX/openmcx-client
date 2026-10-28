//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// The OpenMCX MC client (mcx-client): an MCPTT client (TS 24.379) with its floor participant
// (TS 24.380), and as configured an MCData client (TS 24.282, TS 24.582) and an MCVideo client
// (TS 24.281, TS 24.581), their key management and protection (TS 33.180), location reporting
// (TS 24.379 clause 13.3, TS 24.545), group and configuration management (TS 24.481, TS 24.484).
//
// The class is declared here; its member functions are defined in the files of this directory,
// one per topic.
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 24.380 V19.2.0 (Rel-19), TS 24.282 V19.7.0 (Rel-19),
// TS 24.582 V19.0.0 (Rel-19), TS 24.281 V19.6.0 (Rel-19), TS 24.581 V19.2.0 (Rel-19), TS 26.281 V19.0.0 (Rel-19),
// TS 33.180 V19.4.0 (Rel-19), TS 24.481 V19.3.0 (Rel-19), TS 24.482 V19.1.0 (Rel-19), TS 24.484 V19.6.0 (Rel-19),
// TS 24.545, RFC 3891.
#pragma once

#include "activation.hpp"
#include "client_rules.hpp"
#include "handoff.hpp"
#include "mcx/floor.hpp"
#include "mcx/http.hpp"
#include "mcx/ipconn.hpp"
#include "mcx/key_client.hpp"
#include "mcx/key_documents.hpp"
#include "mcx/location.hpp"
#include "mcx/mcdata.hpp"
#include "mcx/media.hpp"
#include "mcx/msrp.hpp"
#include "mcx/mbms.hpp"
#include "mcx/multicast.hpp"
#include "mcx/protected_call.hpp"
#include "mcx/runtime.hpp"
#include "mcx/seal_location.hpp"
#include "mcx/transmission.hpp"
#include "mcx/video.hpp"
#include "mcx/warning.hpp"
#include "mcx/xml_security.hpp"

#include <openssl/evp.h>
#include <openssl/rand.h>

#include <algorithm>
#include <atomic>
#include <csignal>
#include <cstring>
#include <ctime>
#include <deque>
#include <exception>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <mutex>
#include <set>
#include <sstream>
#include <thread>

namespace mcx {
// Set by SIGINT and SIGTERM (core/main.cpp): the run loop ends.
inline volatile std::sig_atomic_t stopping = 0;

//------------------------------------------------------------------------------
// The SIGINT and SIGTERM handler (OpenMCX process control, not 3GPP): ends the run loop.
inline void stop_signal(int /*signal_number*/) { stopping = 1; }

//------------------------------------------------------------------------------
// TS 24.379 clause 7.2.1AA, TS 24.282 clause 7.2.1AA and TS 24.281 clause 7.2.1AA: the SIP engine as an MCPTT
// client uses it, registering the MCData and MCVideo services it is configured for.
inline SipConfig client_sip_config(const Json &cfg, bool disable_tls) {
  auto config = sip_config(cfg, disable_tls);
  // TS 24.379 clause 6.2.3.1.1 step 5: the 200 (OK) to an incoming INVITE says refresher=uas.
  config.uas_refresher = true;
  // TS 24.379 clause 7.2.1AA and TS 24.282 clause 7.2.1AA: the REGISTER Contact names each service the
  // client uses, MCData with its SDS, FD and IPCONN services, and their ICSIs. registration_features, when set,
  // is the whole REGISTER Contact (SipEngine::register_account): other_services below then only adds MCVideo to the
  // Contact of responses, so the MCVideo tag is registered once.
  if (cfg.contains("mcdata")) {
    std::string icsis = url_encode(mcptt_icsi);
    for (const char *icsi : {mcdata::icsi, mcdata::sds_icsi, mcdata::fd_icsi, mcdata::ipconn_icsi})
      icsis += "," + url_encode(icsi);
    std::string tags = ";+g.3gpp.mcptt;+g.3gpp.mcdata.sds;+g.3gpp.mcdata.fd;+g.3gpp.mcdata.ipconn";
    if (cfg.contains("mcvideo")) {
      tags += std::string(";+") + mcvideo_service.feature_tag;
      icsis += std::string(",") + mcvideo_service.icsi_encoded;
    }
    config.registration_features = tags + ";+g.3gpp.icsi-ref=\"" + icsis + "\"";
  }
  // TS 24.281 clause 7.2.1AA: a client that also uses MCVideo registers its tag and ICSI.
  if (cfg.contains("mcvideo")) config.other_services.push_back(&mcvideo_service);

  // sip.auth "aka" (TS 33.203): the ISIM keeps its sequence numbers (TS 33.102 annex C.2) next to the status file
  // unless sip.isim.sqn_file names another file.
  if (config.aka.enabled && config.aka.sqn_file.empty())
    config.aka.sqn_file =
        (std::filesystem::path(cfg.value("status_file", std::string("."))).parent_path() / "isim-sqn.json").string();

  // sip.outbound: RFC 5626, the SIP core reaches this client over its registration's connection, its instance being
  // the MC client ID (TS 24.379 clause 4.10), as the Android client does (a client behind NAT, or on the server's
  // computer). Off by default: a plain Contact the core connects to.
  if (cfg.at("sip").value("outbound", false)) config.outbound_instance = cfg.at("mcptt").at("client_id");
  return config;
}

//------------------------------------------------------------------------------
// The MC client: one MCPTT client with, as configured, its MCData and MCVideo clients (see the file header).
class Client {
  Json cfg_, status_;
  HttpClient http_;
  // IdMS (sign-in, code exchange, refresh): the configured CA and the system's, for an external IdMS (Keycloak, for
  // example) with a certificate of a public CA (HttpTrust::configured_and_system); the other common services the
  // configured CA only.
  HttpClient idms_http_;
  Control control_;
  SipEngine sip_;
  std::unique_ptr<KeyClient> keys_;
  ProtectedCall protection_;
  std::string csk_upload_, key_subscription_;
  std::map<std::string, std::string> key_subscriptions_;
  std::uint64_t next_key_refresh_ = 0;

  // ---- Protected calls and private call keys (core/protected_call.cpp) ----
  bool protected_media() const { return cfg_.value("security", Json::object()).value("protect_media", false); }

  bool protected_floor() const { return cfg_.value("security", Json::object()).value("protect_floor", false); }

  // mcptt.documents_on_notify: the MCPTT documents are read on the subscription NOTIFYs only (sign_in_documents).
  bool documents_on_notify() const {
    const auto &mcptt = cfg_.value("mcptt", Json::object());
    return mcptt.value("documents_on_notify", false) && mcptt.value("subscribe_configuration", true) &&
           !mcptt.value("group_from_profile", false);
  }

  bool private_media() const { return call_type_ == "private" || call_type_ == "ambient"; }

  // A private call without floor control (TS 24.379 clause 11.1.2): the SDP has no
  // m=application, both parties speak at once and no floor message is exchanged.
  bool no_floor() const { return local_.control == MediaControl::none; }

  // A private or ambient listening call is protected by its PCK, a group call by the GMK.
  bool media_key_ready() const { return private_media() ? protection_.private_ready() : protection_.group_ready(); }

  // The PCK of each private call dialog (TS 33.180 clause 5.6), removed when the dialog ends.
  std::map<std::string, std::uint32_t> private_keys_;
  // A first-to-answer caller receives the PCK in the SDP answer (TS 33.180 clause 7.2.4).
  bool awaiting_answer_key_ = false;
  std::vector<std::string> answer_key_senders_;
  // Warning 136 as TS 24.379 table 4.4.2-2 and TS 24.281 table 4.4.2-2 spell it ("MIKEY-SAKKE"; the <release-reason>
  // value of annex F.1 keeps the schema's "MIKEY-SAKE").
  static constexpr const char *mikey_failed = "399 mcx \"136 authentication of the MIKEY-SAKKE I_MESSAGE failed\"";

  std::optional<keying::KeyMessage> offer_private_key(const std::string &target);

  void bind_private_key(const std::optional<keying::KeyMessage> &key, bool initiator);

  keying::KeyMessage accept_private_key(const std::string &encoded, const std::string &expected_sender);

  bool answer_private_key(const MediaDescription &media);

  std::string gms_identity() const { return cfg_.at("security").at("gms_uri"); }

  std::string xcap_root() const;

  void subscribe_keys();

  void subscribe_affiliation(bool initial);

  void refresh_affiliation_subscription(bool mcvideo);

  void subscription_granted(const SipResponse &response, std::int64_t &expires, std::int64_t &refresh_at) const;

  void transmit_floor(const FloorPacket &packet);

  void stop_protected_call(const std::string &reason);

  MediaDescription local_;
  std::optional<MediaDescription> remote_;
  UdpSocket audio_, floor_;
  // Live audio for the dispatcher (media.live, mcptt/live_audio.cpp; off unless configured): "ptt live" transmits
  // 16 kHz 16-bit mono PCM that arrives on a local UDP port, and every received frame is sent as PCM to another
  // local port; the dispatcher's web service bridges both to the browser. Without media.live the client behaves as
  // before.
  std::unique_ptr<UdpSocket> live_;
  std::deque<std::int16_t> live_in_;
  bool live_talk_ = false;
  std::string live_host_;
  std::uint16_t live_port_ = 0;

  // Received talk bursts for an agent (media.speech_dir, mcptt/speech_capture.cpp; off unless configured): the
  // speech of each received talk burst, written as a WAV file and reported (speech_received) when the burst ends.
  struct SpeechBurst {
    std::uint32_t ssrc = 0;
    std::string from;
    std::vector<std::int16_t> pcm;
    std::uint64_t last = 0; // when its last frame arrived
  };

  std::optional<SpeechBurst> speech_;
  AmrWbCodec codec_;
  FloorSequenceTracker floor_sequence_;
  FloorParticipantTimers floor_timers_;
  std::deque<std::function<void()>> pending_;
  std::string token_, dialog_, subscription_, auth_etag_, affiliation_etag_, wav_path_, session_uri_,
      conference_subscription_;
  std::optional<std::uint32_t> conference_version_;
  // RFC 4575: the participants of the conference notifications (MCPTT ID -> endpoint status), a partial notification
  // applied on top of them.
  std::map<std::string, std::string> conference_members_;
  std::uint64_t conference_generation_ = 0;
  std::map<std::string, std::string> retired_conferences_;
  std::optional<SipResponse> pending_answer_;
  std::int64_t expires_ = 0, subscription_expires_ = 0, subscription_refresh_at_ = 0;
  std::int64_t mcvideo_subscription_expires_ = 0, mcvideo_subscription_refresh_at_ = 0;
  unsigned token_renewals_ = 0;
  std::uint64_t next_audio_ = 0, last_save_ = 0;
  std::vector<std::int16_t> transmit_, received_;
  // Received audio not yet written to received_wav (save_audio writes it only then).
  bool received_unsaved_ = false;
  // The last floor control message received, for the floor_lost event (TS 24.380 table 8.2.2.1-1).
  std::string last_floor_message_;
  size_t transmit_offset_ = 0;
  std::uint16_t sequence_ = 0;
  std::uint32_t timestamp_ = 0, audio_ssrc_ = 0, receive_ssrc_ = 0;
  bool talking_ = false, requesting_ = false, registered_ = false, authorized_ = false, affiliated_ = false,
       desired_affiliation_ = true;
  bool emergency_ = false, imminent_ = false, priority_pending_ = false;
  // Current call type ("prearranged", "broadcast", "chat", "adhoc" or "private") and, for a private call, the peer MCPTT ID.
  std::string call_type_, private_peer_;
  // A private call set up as a first-to-answer call (TS 24.379 clause 11.1.1).
  bool first_to_answer_ = false;
  // Emergency private call states (TS 24.379 clause 6.2.8.3): MEPC (emergency
  // private call, annex G.11), MEPP (emergency private priority of this call, annex G.10)
  // and MPEA (private emergency alert, annex G.12), each held as its state number.
  int mepc_ = 1, mepp_ = 1, mpea_ = 1;
  // TS 24.379 clause 11.1.1.2.1.2 step 3b: MEPP saved when invited to an emergency first-to-answer
  // call, restored if this client is cancelled or released as not selected.
  std::optional<int> saved_mepp_;
  bool fta_not_selected_ = false;
  // Ad hoc group call priority learned at setup: "normal", "emergency" or "imminent".
  std::string adhoc_priority_ = "normal";
  // TS 24.379 clause 17.2.6.1.1 step 1: the participant criteria of the ad hoc call this client originated (or last
  // set), sent again with a change of its participant list.
  std::string adhoc_criteria_;
  bool adhoc_broadcast_ = false;
  // Functional aliases (TS 24.379 clause 9A): the alias presented on outgoing calls,
  // the last status-change request, and the alias a redirected call was made to.
  std::string functional_alias_, pending_called_alias_, mode_for_redirect_ = "auto";
  std::vector<std::string> requested_aliases_;
  // TS 24.379 clause 9A.2.1.3: the subscription to this user's functional alias status (its dialog, set up before
  // the first status change, clause 9A.2.1.1), whether it was answered, the status changes waiting for that answer,
  // the aliases the last NOTIFY listed active and those it offered for take-over, and the aliases the user asked to
  // take over before they were offered. Every PUBLISH lists the whole set wanted active (clause 9A.2.1.2 step 6 a):
  // aliases_wanted_ is the last one sent, the base of the next change until the NOTIFY of alias_publish_open_
  // (its p-id-fa) says what became of it; then the base is standing_aliases_, those the last NOTIFY lists "activated"
  // or "activating" (one listed "deactivating" is given up: listing it again activates it, clause 9A.2.2.2.3 step 12 a).
  std::string alias_status_dialog_;
  bool alias_status_answered_ = false;
  std::vector<std::string> pending_alias_commands_;
  std::set<std::string> active_aliases_, takeover_possible_aliases_, takeover_wanted_, aliases_wanted_,
      standing_aliases_;
  std::string alias_publish_open_;
  // TS 24.379 clauses 9A.4.2.1.2 and 9A.4.2.1.3: the binding requests sent (status alias_binding_result).
  unsigned alias_binding_requests_ = 0;

  void alias_binding(bool bind, const std::string &alias, const std::vector<std::string> &groups);
  // Private call call-back entries (TS 24.379 clause 11.1.5, tables G.13-1 and G.13-2): requesting
  // side MCPTT ID -> "PCCB-I2/3/4" state, target side MCPTT ID -> request data.
  Json pccb_requesting_ = Json::object(), pccb_target_ = Json::object();

  //------------------------------------------------------------------------------
  // TS 24.379 clauses 11.1.7.2.2, 11.1.8.2.2 and 11.1.9.2.2: a private call placed for a remotely initiated
  // call, transfer or forwarding request; its outcome is reported back.
  struct ServiceFollowup {
    std::string kind, requester, target;
    bool reported = false;
  };

  std::optional<ServiceFollowup> followup_;

  //------------------------------------------------------------------------------
  // Ambient listening call (TS 24.379 clause 11.1.6.2): the cached ambient listening
  // type and client role, the peer, whether the server accepts our BYE (annex D.3
  // Feature-Caps), whether a null alert was received, and the release reason.
  struct AmbientCall {
    std::string dialog, type, role, peer;
    bool release_allowed = false, null_alert = false;
    std::string release_reason;
  };

  std::optional<AmbientCall> ambient_;
  Json last_ambient_ = nullptr;
  std::string ambient_source_;

  // ---- Ambient listening and suspended calls (mcptt/call_suspension.cpp) ----
  bool ambient_listened_to() const { return ambient_ && ambient_->role == "listened-to"; }

  // This client is being listened to on its current dialog. TS 24.379 clause 11.1.6.2.0: its
  // user is given no indication of the call, so it presents as idle.
  bool ambient_hidden() const { return ambient_listened_to() && !dialog_.empty() && dialog_ == ambient_->dialog; }

  std::vector<std::int16_t> ambient_audio() const;

  //------------------------------------------------------------------------------
  // TS 24.379 clauses 11.1.6.2 and 11.1.8.2.3: a call's state, kept while the client places another call: a
  // listened-to client's ambient call, or a private call on hold for an announced transfer.
  struct CallSnapshot {
    MediaDescription local;
    std::optional<MediaDescription> remote;
    FloorParticipantTimers floor_timers;
    FloorSequenceTracker floor_sequence;
    std::vector<std::int16_t> transmit;
    size_t transmit_offset;
    std::uint32_t audio_ssrc, receive_ssrc;
    bool talking, requesting, first_to_answer;
    std::string dialog, call_type, call_group, private_peer, adhoc_priority;
    int mepc, mepp, mpea;
    Json call_state;
  };

  // Idle for placing a call: a hidden ambient listening dialog does not count.
  bool call_idle() const { return dialog_.empty() || ambient_hidden(); }

  std::optional<CallSnapshot> yield_ambient();

  CallSnapshot suspend_call();

  void restore_ambient(CallSnapshot &&snapshot);

  void resume_call(CallSnapshot &&snapshot);

  // Places a call from a possibly listened-to client. Only a sent INVITE makes
  // the server release the ambient listening call, so a request that fails
  // before it is sent keeps the client in that call.
  template <typename Place> void place_call(Place &&place) {
    auto saved = yield_ambient();
    try {
      place();
    } catch (...) {
      if (saved && dialog_.empty()) restore_ambient(std::move(*saved));
      throw;
    }
  }

  // ---- Private call services, priority and emergency (mcptt/private_services.cpp, mcptt/priority.cpp) ----
  std::map<std::string, std::string> next_call_service_;
  // The Resource-Priority a remotely initiated call request asked the next call to carry
  // (<ric-app-level-priority>, TS 24.379 clause 10.1.5.2.2 step 2b, clause 11.1.7.2.2 step 3b).
  std::string next_call_priority_;
  // The forwarding target of a forward request and its <PrivateCallKMSURI>.
  std::pair<std::string, std::string> forwarded_kms_;
  // KMS Redirect Responses followed for the current private call (at most one), and
  // the KRR a response carries (TS 33.180 annex D.4).
  int krr_retries_ = 0;

  static std::optional<std::string> krr_body(const SipResponse &response);

  // Announced private call transfer (TS 24.379 clause 11.1.8.2.3), requesting side: the
  // call with the transferred user on hold, the target, and the target's dialog
  // (<replaces-header-value>) from the answer to the announcing call. Transferred
  // side: the Replaces value the next call carries (clause 11.1.1.2.1.1 step 14b1).
  // Target side: the incoming call replaces the current one (RFC 3891).
  std::optional<CallSnapshot> held_;
  // held_ is the transferred user's call with the requester, kept during a transfer (TS 24.379 clause 11.1.8.2.2).
  bool transfer_kept_ = false;
  std::string announced_target_, announced_replaces_, next_call_replaces_;
  bool replacing_call_ = false;
  Json service_outcomes_ = Json::object();
  // Ad hoc group emergency alert state (TS 24.379 clause 12.1A.1, annex G.20): "AH-MEA 1..3".
  int ah_mea_ = 1;
  // Granted <ruleset> actions from the retrieved user profile document.
  std::set<std::string> profile_actions_;
  bool profile_loaded_ = false;
  unsigned profile_index_ = 1;
  // TS 24.484 clauses 8.4, 9.4 and 10.4: the Resource-Priority values of the service configuration documents, by
  // service ("mcptt", "mcvideo", "mcdata") and level ("emergency", "imminent", "normal").
  std::map<std::string, std::map<std::string, std::string>> service_priorities_;
  void read_service_configurations(std::vector<std::string> services = {});
  Json service_priorities(const std::string &service) const;
  // The MCVideo user profile document (TS 24.484 clause 9.3) as read at start-up.
  Json mcvideo_profile_ = Json::object();
  // Granted <ruleset> actions of the MCData user profile document (TS 24.484 clause 10.3) read at start-up: the
  // MCData rights the MCData client checks (TS 24.282 clause 6.2.8.1).
  std::set<std::string> mcdata_profile_actions_;
  bool mcdata_profile_loaded_ = false;

  void require_profile(std::initializer_list<const char *> actions) const;
  void require_mcdata_profile(std::initializer_list<const char *> actions) const;

  // Ad hoc group emergency alerts (TS 24.379 clause 12.1A.1): the group of this user's own
  // alert, and the alerts received from others by group.
  std::string own_alert_group_;
  Json received_adhoc_alerts_ = Json::object();
  std::set<std::string> private_alerts_;

  static const char *mepc_name(int state);

  static const char *mepp_name(int state);

  static const char *mpea_name(int state);

  static bool info_pending(const SipResponse &response);

  void update_private_priority(const McpttInfo &info);

  // Group identity of the current call: the configured group, or the server
  // generated ad hoc group; the last ad hoc call is kept for rejoin.
  std::string call_group_, last_call_type_, last_call_group_;

  std::string active_group() const { return call_group_.empty() ? group() : call_group_; }

  std::set<std::string> alerts_;
  std::string alert_pending_;
  // The group of the pending alert request (its <mcptt-request-uri>), whose receipt completes it.
  std::string alert_pending_group_;

  // TS 24.379 clause 9.2.1.2: the groups monitored besides the selected one ("monitor"), affiliated in the same
  // PUBLISH, with each group's affiliation status from the server (clause 9.2.1.3) and the priority and emergency
  // alerts of each monitored group (clause 12.1.1.3), kept apart from the selected group's. During a call on a
  // monitored group the call's state is the live one and the selected group's waits in selected_priority_.
  std::vector<std::string> monitored_groups_;
  Json affiliation_statuses_ = Json::object(), monitored_state_ = Json::object();
  std::optional<Json> selected_priority_;

  bool monitoring(const std::string &group) const;

  bool monitored_affiliated(const std::string &group) const;

  void monitor_command(const std::string &cmd);

  Json live_priority() const;

  void set_live_priority(const Json &state);

  static void priority_into(Json &state, const McpttInfo &info, std::optional<EmergencyLocation> location);

  void enter_monitored_call(const std::string &group);

  void leave_monitored_call();

  static std::optional<EmergencyLocation> message_location(const SipMessage &message);

  EmergencyLocation configured_emergency_location() const;

  void update_priority(const McpttInfo &info, std::optional<EmergencyLocation> location = {});

  // ---- Regroups (mcptt/regroup.cpp) ----
  // TS 24.379 clause 16: regroups this client has been told about, and the result of its
  // own last create or remove request.
  Json regroups_ = Json::object();
  Json regroup_result_ = Json::object();

  void regroup_notified(const Regroup &regroup);

  // TS 24.379 clause 10.1.1.2.1.1 step 14d and clause 10.1.2.2.1.1 step 13d (<associated-group-id>): the
  // constituent group a call on a group regroup is placed from, empty when the selected group is not a group regroup.
  std::string calling_constituent_;

  void regroup_command(const std::string &action, const std::string &tgi, const std::string &preconfigured,
                       const std::vector<std::string> &groups, const std::vector<std::string> &users = {});

  // ---- Priority changes and emergency alerts (mcptt/priority.cpp) ----
  void priority_response(const SipResponse &response);

  std::vector<SipHeader> priority_headers(const McpttInfo &info) const;

  void change_private_priority(bool enable, bool with_alert);

  void change_priority(bool emergency, bool enabled);

  void change_alert(bool enabled, const std::string &origin = {}, const std::string &shown_group = {});

  // ---- MCPTT service authorisation, affiliation and helpers (mcptt/service.cpp) ----
  std::string user() const { return cfg_.at("mcptt").at("user_id"); }

  std::string group() const { return cfg_.at("mcptt").at("group_id"); }

  std::string client_id() const { return cfg_.at("mcptt").at("client_id"); }

  std::string service() const { return cfg_.at("mcptt").at("service_uri"); }

  bool conference_service_from(std::string_view uri) const;

  FloorParticipantTimers participant_timers() const;

  void remember_session(const std::vector<SipHeader> &headers);

  void schedule(std::function<void()> fn) { pending_.push_back(std::move(fn)); }

  std::multimap<std::uint64_t, std::function<void()>> timed_;

  void schedule_after(std::uint64_t delay_ms, std::function<void()> fn);

  void failure(const std::string &what);

  // The registration expiry the client asks for (RFC 3261 section 10.2.1.1): sip.register_expires, an hour by
  // default; a dispatcher position asks for less, so that a revoked credential ends its session at the next refresh.
  // With IMS AKA (sip.auth "aka") the default is 600 000 s, the value TS 24.229 clause 5.1.1.2.1 e) asks a UE for.
  unsigned register_expires() const {
    constexpr unsigned an_hour = 3600, ims_aka_default = 600000;
    const auto &sip = cfg_.at("sip");
    return sip.value("register_expires", sip.value("auth", "digest") == "aka" ? ims_aka_default : an_hour);
  }

  // TS 33.203 clause 7.1 rule 5: the status file's "sip_security": the IMS AKA SA_table of the set in use, or the
  // digest mechanism.
  Json sip_security_status() const {
    const auto status = sip_.security_status();
    if (!status)
      return {{"mechanism", cfg_.at("sip").value("auth", "digest") == "aka" ? "ims-aka" : "digest"},
              {"security_associations", false}};
    return {{"mechanism", "ims-aka"},
            {"security_associations", true},
            {"algorithm", status->algorithm},
            {"sec_mechanism", status->mechanism},
            {"alg", status->alg},
            {"ealg", status->ealg},
            {"port_uc", status->port_uc},
            {"port_us", status->port_us},
            {"port_pc", status->port_pc},
            {"port_ps", status->port_ps},
            {"spi_uc", status->spi_uc},
            {"spi_us", status->spi_us},
            {"spi_pc", status->spi_pc},
            {"spi_ps", status->spi_ps},
            {"associated_uris", status->associated_uris},
            {"service_routes", status->service_routes}};
  }

  std::pair<std::string, std::string> publish_body(bool affiliation);

  std::pair<std::string, std::string> affiliation_subscribe_body();

  void authorize();
  // TS 24.379 clause 7.2.1: the service authorisation in a REGISTER instead of the PUBLISH of clause 7.2.2, when
  // mcptt.authorise_by_register is set; the SIP core passes it to the server in a third-party REGISTER.
  [[nodiscard]] bool authorise_by_register() const;
  [[nodiscard]] std::pair<std::string, std::string> register_body();
  void register_authorization(std::function<void(const SipResponse &)> handler);
  // TS 24.282 and TS 24.281 clause 7.2.1: each MC service authorises in a REGISTER of its own on the one registration,
  // with its own information and CSK upload; the REGISTERs go one at a time (a registration has one transaction).
  void queue_register(std::string type, std::string body, std::function<void(const SipResponse &)> handler);
  void next_register();
  std::deque<std::function<void()>> register_queue_;
  bool register_busy_ = false;
  // The steps after a successful service authorisation (clause 7.2.2 or 7.2.1).
  void service_authorized();

  // Log-off and log-on (core/log_off.cpp): TS 24.379, TS 24.281 and TS 24.282 clause 7.2.1A step 4 (Expires 0), and
  // the return to service. logged_off_: after "log-off", until "log-on"; nothing authorises a service meanwhile.
  bool logged_off_ = false;
  void log_off_command();
  void end_service_subscriptions();
  void finish_log_off();
  void log_on_command();
  // RFC 3261 section 10.2 and TS 24.379 clause 7.2.2: SIP registration, then the service authorisation.
  void register_and_authorize();

  // TS 33.180 clause 5.1.3.1 step A, in parallel with the registration, and the limited service state of clause
  // 5.1.1 while it has not succeeded.
  void sign_in();
  void sign_in_documents();
  void sign_in_failed(const std::string &reason);
  bool signed_in_ = false;
  unsigned sign_in_failures_ = 0;
  std::string service_state() const;

  // TS 24.379, TS 24.282 and TS 24.281 clause 7.2.2: the retries of a refused initial service authorisation of one
  // service. attempts counts the retries since the last success; generation changes with every authorisation
  // PUBLISH, so that a retry scheduled for an older one is dropped.
  struct AuthorizationRetry {
    unsigned attempts = 0;
    std::uint64_t generation = 0;
  };

  AuthorizationRetry authorization_retry_, mcdata_authorization_retry_, mcvideo_authorization_retry_;

  bool retry_authorization(AuthorizationRetry &retry, const std::string &service_name, const SipResponse &response,
                           std::function<void()> again);

  void affiliate();

  void subscribe_conference(bool force = false);

  // ---- MCPTT XML integrity protection, core/xml_protection.cpp (TS 24.379 clause 6.6.3; TS 33.180 clause 9.3.5) ----
  static constexpr const char *signed_mime = "application/vnd.3gpp.mcptt-signed+xml";
  unsigned xml_signed_ = 0, xml_verified_ = 0;

  bool integrity_protection() const;

  static bool signable_part(const std::string &type);

  // ---- MCPTT XML confidentiality protection, core/xml_protection.cpp (TS 24.379 clause 6.6.2; TS 33.180
  // clause 9.3.4) ----
  // Every MCPTT message to and from this client's server has the XML elements and
  // attribute URIs of clause 4.8 encrypted under the CSK (the XPK). The MCData
  // service protects its own messages under the MCData CSK.
  std::optional<keying::KeyMessage> csk_;
  unsigned xml_encrypted_ = 0, xml_decrypted_ = 0, xml_refused_ = 0;

  bool xml_protection() const;

  xmlsec::Key xml_key() const { return {csk_->key, csk_->csb_id, user()}; }

  static bool mcptt_xml_part(const std::string &type);

  static const xmlsec::Profile &mcptt_xml_profile();

  bool mcdata_transmission(const SipTransmission &transmission) const;

  bool mcvideo_transmission(const SipTransmission &transmission) const;

  // TS 24.281 clauses 6.6.2.3.1 and 6.6.3.3.1: MCVideo signalling, under the MCVideo CSK and in the
  // MCVideo element names (clause 4.8); the same configuration switches it on as for MCPTT.
  xmlsec::Key mcvideo_xml_key() const { return {mcvideo_csk_->key, mcvideo_csk_->csb_id, mcvideo_user()}; }

  static bool mcvideo_xml_part(const std::string &type);

  std::vector<BodyPart> mcvideo_protect_outgoing(std::vector<BodyPart> parts);

  std::vector<BodyPart> protect_outgoing(const SipTransmission &transmission, std::vector<BodyPart> parts);

  std::vector<BodyPart> protect_incoming(const SipTransmission &, std::vector<BodyPart> parts);

  // ---- Incoming SIP requests (core/sip_dispatch.cpp) ----
  // TS 33.180 clause 9.5: signalling from the MCData server under this client's CSK is
  // opened before any procedure reads it; clear signalling is then refused.
  SipResponse incoming(const SipMessage &message);

  // ---- Group management client, core/group_management.cpp (TS 24.481 clauses 6.3.2 to 6.3.15) ----
  // XCAP writes to the GMS through the HTTP proxy, and SIP subscriptions to group
  // documents through the MCPTT service's PSI.
  std::map<std::string, std::vector<std::string>> group_doc_subscriptions_; // dialog -> groups
  unsigned xcap_seq_ = 0;
  Json group_documents_ = Json::object();

  std::string groups_xcap() const;

  void xcap_request(const std::string &method, const std::string &url, const std::string &content_type = {},
                    const std::string &body = {});

  static std::string group_document_xml(const std::string &group, const std::vector<std::string> &members);

  void profile_command(const std::string &cmd);

  void group_management_command(const std::string &cmd);

  static std::string temporary_group_request_xml(const std::string &group_id, const std::string &groups, bool chat,
                                                 bool protect);

  std::string group_documents_subscribe(const std::vector<std::string> &args);

  // TS 24.481 clause 6.3.13.2.1 (test view TS 37.579-1 table 5.3.2.7.1-1 steps 1b1 and 1c3): the subscription to the
  // group documents of the user's groups made at service authorisation (mcptt.subscribe_group_documents, on by
  // default), its dialog kept among group_doc_subscriptions_.
  std::string startup_group_subscription_;
  void subscribe_own_group_documents();

  void group_documents_resubscribe(const std::vector<std::string> &args);

  void group_documents_unsubscribe();

  // ---- Service configuration, core/group_management.cpp (TS 24.484 clause 6.3.13.2.2) ----
  // The CMC subscribes to its UE configuration, user profile and service configuration documents in one SUBSCRIBE
  // through the subscription proxy function of the CMS (whose PSI this client's MCPTT server provides), and reads
  // the documents again when it is notified.
  std::string config_subscription_;
  unsigned config_notifies_ = 0, config_refreshes_ = 0;

  // TS 24.484 clause 8.3.1A: the user's MCPTT user profile document, mcptt-user-profile-<index>.xml.
  std::string profile_document_path() const {
    return "org.3gpp.mcptt.user-profile/users/" + url_encode(user()) + "/mcptt-user-profile-" +
           std::to_string(profile_index_) + ".xml";
  }

  // TS 24.484 clause 7.2.2.1: the "user-profile-index" of <Default-user-profile>, as the activation gives it
  // (mcptt.profile_index); 1 when absent.
  unsigned configured_profile_index() const;

  // The groups of the user profile, <OnNetwork>/<MCPTTGroupInfo> (TS 24.484 clause 8.3.2): groups the user may
  // select besides the configured ones (the dispatcher's group picker).
  std::vector<std::string> profile_groups_;

  void take_profile_groups(const std::string &profile);

  void subscribe_configuration();

  void configuration_notified();

  // TS 24.484 clause 8.2.1A: the MCPTT UE configuration document of this UE, and its retrieval.
  std::string ue_configuration_document_path() const;
  void read_ue_configuration();

  // TS 24.484 clause 8.4.1: the MCPTT service configuration document.
  static constexpr const char *service_configuration_document_path =
      "org.3gpp.mcptt.service-config/global/service-config.xml";

  std::optional<SipResponse> group_document_notify(const SipMessage &message);

  // ---- MCData message store client and message notification client, mcdata/message_store.cpp (TS 24.282 clauses
  // 21.2, 21.3.2) ----
  // The message store is reached at endpoints.msgstore (MCData-7) and the notification
  // server at endpoints.notification (MCData-10), both with the MCData access token.

  //------------------------------------------------------------------------------
  // TS 24.282 clauses 21.2.19 to 21.2.22: the notification channel of the message notification client.
  struct StoreChannel {
    std::string resource, channel_url, subscription;
    std::shared_ptr<std::atomic<bool>> running;
    std::thread poller;
  };

  StoreChannel store_channel_;
  std::mutex store_mutex_;

public:
  ~Client();

private:
  std::vector<Json> store_notifications_in_; // filled by the poll thread
  Json store_status_ = {{"objects", Json::object()},
                        {"notifications", Json::array()},
                        {"events", 0},
                        {"creation_cursor", "0"},
                        {"mod_seq", 0}};

  std::string msgstore_root() const;

  std::string store_url(const std::string &kind, const std::string &id) const;

  HttpResponse store_request(const std::string &method, const std::string &url, const Json &body = nullptr,
                             const std::string &content_type = "application/json", const std::string &raw = {});

  static std::string last_id(const std::string &url) { return url.substr(url.find_last_of('/') + 1); }

  void store_remember(const Json &object);

  void store_sync();

  void store_subscribe();

  void store_unsubscribe();

  void store_tick();

  void msgstore_command(std::istringstream &words);

  // mcdata-msgstore payload: an object's payload, kept as its size, hash and text.
  void msgstore_payload(const std::vector<std::string> &args);
  // mcdata-msgstore search (TS 24.282 clause 21.2.2.1).
  void msgstore_search(const std::vector<std::string> &args);
  // mcdata-msgstore folders: the folder hierarchy (TS 24.282 clause 21.2.23.1).
  void msgstore_folders();
  // mcdata-msgstore folder-search (TS 24.282 clause 21.2.11.1).
  void msgstore_folder_search(const std::vector<std::string> &args);
  // mcdata-msgstore folder: a folder's objects (TS 24.282 clause 21.2.18.1).
  void msgstore_folder(const std::vector<std::string> &args);
  // mcdata-msgstore folder-create (TS 24.282 clause 21.2.8.1).
  void msgstore_folder_create(const std::vector<std::string> &args);
  // mcdata-msgstore upload (TS 24.282 clause 21.2.15.1).
  void msgstore_upload(const std::vector<std::string> &args);
  // mcdata-msgstore retrieve (TS 24.282 clause 21.2.24.1).
  void msgstore_retrieve(const std::vector<std::string> &args);
  // mcdata-msgstore restart: the notification stream from an index (TS 24.282 clause 21.2.14A.1).
  void msgstore_restart(const std::vector<std::string> &args);

  void msgstore_control_command(std::istringstream &words);

  std::string mcdata_upload_by_reference(const std::string &object, const std::string &group, std::string &name,
                                         std::uint64_t &size, std::string &digest);

  // ---- Location procedures of the MCPTT client, core/location.cpp (TS 24.379 clauses 13.3, 12.1.1.4, 12.1.1.6) ----
  // The UE's position and radio identities are simulated: mcptt.location.position
  // ([latitude, longitude]) and the "location position|radio" commands.
  location::Reporter location_;
  std::string location_server_; // the P-Asserted-Identity of the configuration (clause 13.3.4.2 step 1)
  bool location_started_ = false, location_logon_pending_ = false;
  std::string location_alias_; // the functional alias whose change fired a report
  Json location_received_ = Json::array(), location_sent_ = Json::array(), area_notices_received_ = Json::array();

  bool location_emergency() const { return emergency_ || imminent_ || mepp_ == 2 || alerts_.contains(user()); }

  void location_signal(location::Event event) { location_.signal(event, monotonic_ms()); }

  void send_location_report(location::Report report);

  static std::string utc_timestamp();

  std::optional<SipResponse> location_incoming(const SipMessage &message);

  void area_notice(const McpttInfo &info);

  // ---- SEAL location management client, core/seal_location.cpp (TS 24.545 V19.4.0 clauses 6.2.2, 6.2.20) ----
  // Besides the MCPTT location procedures, the client reports its position to the
  // SEAL location management server over HTTP, as that server configures it: it
  // reads its configuration (clause 6.2.2.2.1) and reports on the periodic trigger
  // (clause 6.2.2.2.2). While offline (a test aid standing in for a loss of connectivity)
  // it stores the positions the trigger produces, and when it is back it sends
  // them in a history location result report (clause 6.2.20.2).

  //------------------------------------------------------------------------------
  // TS 24.545 clauses 6.2.2 and 6.2.20: the state of the SEAL location management client.
  struct SealClient {
    seal::Configuration config;
    bool started = false, offline = false;
    std::vector<seal::Fix> stored;
    std::uint64_t next = 0;
    unsigned sent = 0, history_sent = 0, stored_total = 0;
    int last_status = 0;
  };

  SealClient seal_;

  std::optional<seal::Fix> seal_fix() const;

  void seal_status();

  void seal_start();

  void seal_tick(std::uint64_t now);

  void seal_online();

  void seal_command(const std::string &cmd);

  // ---- MCPTT location commands, timers and status (core/location.cpp) ----
  void location_command(const std::string &cmd);

  // Test aid "location position": this UE's position for location reporting.
  void location_position_command(std::istringstream &in, std::uint64_t now);
  // Test aid "location radio": this UE's radio dimension for location reporting.
  void location_radio_command(std::istringstream &in, std::uint64_t now);
  // "location request": the location information request's parts and targets (TS 24.379 clause 13.3.3.2).
  void location_request_parts(std::istringstream &in, std::vector<BodyPart> &parts, McpttInfo &info,
                              std::vector<std::string> &targets);
  // "location configure": the reporting configuration's parts and targets (TS 24.379 clause 13.3.2.2).
  void location_configure_parts(std::istringstream &in, std::vector<BodyPart> &parts,
                                std::vector<std::string> &targets);

  void location_tick(std::uint64_t now);

  Json location_status() const;

  // ---- Location procedures of the MCData client, core/location.cpp (TS 24.282 clause 17.3; area notices
  // clauses 16.2.1.4, 16.2.1.5) ----
  // The participating MCData function configures its own reporting; the UE's
  // position and radio identities are shared with the MCPTT side.
  location::Reporter mcdata_location_;
  std::string mcdata_location_server_;
  bool mcdata_location_logon_pending_ = false;
  Json mcdata_location_received_ = Json::array(), mcdata_location_sent_ = Json::array(),
       mcdata_area_notices_received_ = Json::array();

  EmergencyLocation mcdata_emergency_location() const;

  bool mcdata_location_emergency() const { return mcdata_emergency_ || mcdata_session_emergency_ || mdea_ == 3; }

  void mcdata_send_location(std::vector<BodyPart> parts, std::vector<SipHeader> headers, const std::string &target,
                            std::function<void(const SipResponse &)> done);

  void mcdata_send_location_report(location::Report report);

  std::optional<SipResponse> mcdata_location_incoming(const SipMessage &message);

  void mcdata_area_notice(const mcdata::Info &info);

  void mcdata_location_command(const std::string &cmd);

  void mcdata_location_tick(std::uint64_t now);

  Json mcdata_location_status() const;

  // ---- Incoming SIP requests in the clear and terminating calls (mcptt/incoming_calls.cpp) ----
  SipResponse incoming_clear(const SipMessage &message);

  // MESSAGE from the trusted core, by kind (run inside incoming_clear's try block).
  SipResponse incoming_clear_message(const SipMessage &message);
  // MCVideo regroup notice (TS 24.281 clause 21.2.1.3).
  SipResponse mcvideo_regroup_message(const SipMessage &message);
  // Regroup notice (TS 24.379 clause 16.2.1.3).
  SipResponse regroup_message(const SipMessage &message);
  // Private call service request or response, handed to service_message.
  SipResponse private_service_received(const SipMessage &message, const McpttInfo &info);
  // Ad hoc group emergency alert notification (TS 24.379 clause 12.1A.1.3).
  SipResponse adhoc_alert_message(const SipMessage &message, const McpttInfo &info);
  // Group alert, emergency and imminent peril notice, MESSAGE or INFO (TS 24.379 clause 12.1.1.3, 6.2.8.1.13).
  SipResponse priority_notice(const SipMessage &message);
  // INFO from the trusted core, by package and dialog.
  SipResponse incoming_clear_info(const SipMessage &message);
  // INFO in the MCVideo call's dialog (TS 24.281 clause 13.2.2.4, clause 6.2.8.1.13).
  SipResponse mcvideo_call_info(const SipMessage &message);
  // INFO in a priority private call's dialog (TS 24.379 clause 6.2.8.3.7).
  SipResponse private_priority_info(const SipMessage &message);
  // NOTIFY from the trusted core, by event package and subscription.
  SipResponse incoming_clear_notify(const SipMessage &message);
  // Group key notification (TS 24.481 clause 6.3.13.2.1, TS 33.180 clause 5.7).
  SipResponse group_key_notify(const SipMessage &message);
  // Conference event package NOTIFY of the group call (TS 24.379 clause 10.1.3.2).
  SipResponse conference_notify(const SipMessage &message);
  // Affiliation status NOTIFY (TS 24.379 clause 9.2.1.3).
  SipResponse affiliation_notify(const SipMessage &message);
  // INVITE from the trusted core: MCData, MCVideo, re-INVITE, replacement and new calls.
  SipResponse incoming_clear_invite(const SipMessage &message);
  // re-INVITE of the emergency private call (TS 24.379 clause 11.1.1.2.1.3).
  SipResponse private_call_reinvite(const SipMessage &message);
  // re-INVITE of the active group call (TS 24.379 clause 10.1.2.2.1.2).
  SipResponse group_call_reinvite(const SipMessage &message);
  // New group call (TS 24.379 clause 10.1.1.2.1.2, clause 10.1.2.2.1.6).
  SipResponse incoming_group(const SipMessage &message);
  // BYE from the trusted core: the release reasons it carries.
  SipResponse incoming_clear_bye(const SipMessage &message);

  SipResponse incoming_private(const SipMessage &message, const McpttInfo &info);

  // Take an accepted private or first-to-answer call as this client's call (TS 24.379 clause 11.1.1.2.1.2).
  void adopt_incoming_private(const SipMessage &message, const McpttInfo &info,
                              const std::optional<keying::KeyMessage> &key, bool first_to_answer);
  // Name this dialog in the answer to a call announcing a transfer (TS 24.379 clause 6.2.3.2.1).
  void name_transfer_dialog(SipResponse &response, const SipMessage &message, const McpttInfo &info);

  SipResponse incoming_ambient(const SipMessage &message, const McpttInfo &info);

  SipResponse incoming_adhoc(const SipMessage &message, const McpttInfo &info);

  // ---- Ad hoc group calls, functional aliases, private call services and private calls (mcptt/adhoc.cpp,
  // mcptt/functional_alias.cpp, mcptt/private_services.cpp, mcptt/private_call.cpp) ----
  void adhoc_call(const std::string &arguments);

  void functional_alias_command(const std::string &arguments);
  void publish_functional_alias(const std::string &action, const std::vector<std::string> &aliases, bool take_over);

  void send_alias_publish(const std::string &action, const std::vector<std::string> &aliases,
                          const std::set<std::string> &listed, const std::string &expires, bool take_over);
  std::vector<std::string> settle_alias_outcomes(Json &last, const std::map<std::string, std::string> &statuses,
                                                 bool handled, std::set<std::string> &wanted);
  void alias_status_subscribe();
  SipResponse alias_status_notify(const SipMessage &message);

  void send_service_message(const std::string &target, McpttInfo info,
                            std::function<void(const SipResponse &)> done = {});

  void report_followup(bool success);

  void service_message(const McpttInfo &info);

  // Remotely initiated group call request at the target client (TS 24.379 clause 10.1.5.2.2).
  void remote_group_call_request(const McpttInfo &info, const std::string &from);
  // Remotely initiated, transfer or forwarding private call request at the target (TS 24.379 clause 11.1.7.2.2).
  void private_call_service_request(const McpttInfo &info, const std::string &type, const std::string &from);
  // Response to this client's own private call service request (TS 24.379 clause 11.1.8.2.1).
  void service_response_received(const McpttInfo &info, const std::string &type);

  void announced_transfer(const std::string &target, const std::string &action);

  void resume_held();

  void private_service_command(const std::string &cmd);

  void adhoc_alert(const std::string &arguments);

  void adhoc_modify(const std::string &arguments);

  void private_call(const std::string &arguments, bool to_alias = false);

  // MCPTT information, priority headers and resource list of a private call request (TS 24.379 clause 11.1.1.2.1.1).
  std::vector<BodyPart> private_call_parts(const std::string &target, bool to_alias, bool emergency, bool alert,
                                           std::vector<SipHeader> &headers);
  // Responses to a private call request (TS 24.379 clause 11.1.1.2.1.1).
  void private_call_response(const std::string &target, const SipResponse &response);
  // 2xx to a private call request: announced transfer, call-back and emergency state.
  void private_call_answered(const std::string &target, const SipResponse &response);
  // 300 for a functional alias: call the selected MCPTT ID.
  void private_call_alias_redirect(const SipResponse &response);
  // 488 with a KMS Redirect Response (TS 33.180 clause 5.2.8.2.5): key the PCK again.
  void private_emergency_granted(const SipResponse &response);

  void private_emergency_refused();

  void private_call_kms_redirect(const std::string &target, const SipResponse &response);

  void first_to_answer_call(const std::string &arguments, bool to_alias = false);

  void ambient_call(const std::string &arguments);

  // ---- MCVideo client, mcvideo/service.cpp (TS 24.281), sharing this UE's SIP registration (clause 7.1).
  // Service authorisation by PUBLISH (clause 7.2.2) and affiliation (clause 8.2.1), in the
  // MCVideo namespaces, to the MCVideo server's PSI.
  bool mcvideo_enabled() const { return cfg_.contains("mcvideo"); }

  std::string mcvideo_user() const { return cfg_.at("mcvideo").at("user_id"); }

  std::string mcvideo_service_uri() const { return cfg_.at("mcvideo").at("service_uri"); }

  // The selected group: the configured one until a remote change of the selected
  // group (TS 24.281 clause 9.2.4.2.2) replaces it.
  std::string mcvideo_selected_group_;

  std::string mcvideo_group() const;

  // ---- MCVideo security, mcvideo/service.cpp (TS 24.281 clause 4.7, TS 33.180): its own key client, CSK and key
  // contexts, with the MCVideo CS-IDs, apart from the MCPTT ones.
  std::unique_ptr<KeyClient> mcvideo_keys_;
  ProtectedCall mcvideo_protection_{true};
  // TS 24.281 clause 6.6: the MCVideo CSK is also the XPK of MCVideo signalling protection.
  std::optional<keying::KeyMessage> mcvideo_csk_;
  std::string mcvideo_csk_upload_, mcvideo_key_subscription_;

  std::optional<keying::KeyMessage> mcvideo_offer_pck(const std::string &target);

  void mcvideo_bind_pck(const keying::KeyMessage &key, bool initiator);

  void mcvideo_subscribe_keys();

  std::string mcvideo_key_group_;
  std::uint64_t mcvideo_next_key_refresh_ = 0;

  SipResponse mcvideo_key_notify(const SipMessage &message);

  bool mcvideo_authorized_ = false, mcvideo_affiliated_ = false, mcvideo_status_known_ = false;
  bool mcvideo_desired_affiliation_ = true;
  std::string mcvideo_auth_etag_, mcvideo_affiliation_etag_, mcvideo_subscription_;
  std::string mcvideo_sent_pid_, mcvideo_received_pid_;
  std::set<std::string> mcvideo_groups_;
  // The groups this client asks to be affiliated to besides its configured group;
  // a PUBLISH carries the client's whole set (clause 8.2.1.2).
  std::set<std::string> mcvideo_extra_groups_;
  Json mcvideo_last_result_; // the outcome of the last authorisation or affiliation request

  void mcvideo_request(const std::string &event_name, const std::vector<BodyPart> &parts,
                       std::vector<SipHeader> headers, std::function<void(const SipResponse &)> done);

  void mcvideo_result(const std::string &action, const SipResponse &response);

  void mcvideo_authorize(const std::string &token = {});

  void mcvideo_subscribe();

  SipResponse mcvideo_affiliation_notify(const SipMessage &message);

  void mcvideo_affiliate(bool affiliate, const std::string &group = {});

  void mcvideo_command(const std::string &cmd);

  // "mcvideo-private": a private call with its options (TS 24.281 clause 10.2.2.2.1).
  void mcvideo_private_command(const std::string &argument, std::istringstream &words);
  // "mcvideo-adhoc-alert": ad hoc group emergency alert or its cancellation (TS 24.281 clause 12.1A.1.1).
  void mcvideo_adhoc_alert_command(const std::string &argument, std::istringstream &words);
  // "mcvideo-fa-bind", "mcvideo-fa-unbind": alias to group binding (TS 24.281 clause 20.4.2.1.2).
  void mcvideo_alias_binding_command(const std::string &verb, const std::string &argument, std::istringstream &words);
  // "mcvideo-regroup create|remove": regroup using a preconfigured group (TS 24.281 clause 21.2.1.1).
  void mcvideo_regroup_request_command(const std::string &argument, std::istringstream &words);
  // "mcvideo-adhoc": ad hoc group call to users or by criteria (TS 24.281 clause 22.2.2.1.1).
  void mcvideo_adhoc_command(const std::string &argument, std::istringstream &words);
  // "mcvideo-answer", "mcvideo-reject": answer a ringing call (TS 24.281 clause 10.2.2.2.2).
  void mcvideo_answer_command(const std::string &verb);
  // "mcvideo-receive", "mcvideo-stop-receiving": reception control requests (TS 24.581 clause 6.2.5).
  void mcvideo_reception_command(const std::string &verb);
  // "mcvideo-hangup": release the MCVideo call.
  void mcvideo_hangup_command();

  void mcvideo_status();

  // ---- MCVideo calls, mcvideo/call.cpp and mcvideo/session.cpp ----
  // The MCVideo group call (TS 24.281 clause 9.2.1) with transmission control (TS 24.581
  // clause 6.2) and H.264 video (TS 26.281). One MCVideo call at a time, beside MCPTT. The
  // client transmits the test pattern while it holds the permission to transmit, and
  // decodes what the media distribution function relays to it.

  //------------------------------------------------------------------------------
  // TS 24.281 clause 9.2.1 (group call), clauses 10.2.2, 12.2, 13.2 and 15.2.1 (private, video pull, video push
  // and ambient viewing calls), TS 24.581 clause 6.2: the state of the one MCVideo call.
  struct McvideoCall {
    std::string dialog, group, state = "idle", transmitter;
    std::string type = "prearranged", peer; // prearranged, chat or private; the other party of a private call
    // Functional aliases (TS 24.281 clause 20): the caller's, the alias this call was placed to or
    // received by, whether it was placed to an alias (answered by a 300), its
    // commencement mode for the call that follows the 300, and the participants' aliases.
    std::string calling_alias, called_alias, mode;
    bool to_alias = false;
    // The PCK of a protected one-to-one call (TS 33.180 clause 7.4.1), ended with the call.
    std::uint32_t pck_id = 0;
    std::map<std::string, std::string> participant_aliases;
    // A private call that is a one-to-one video push or pull (TS 24.281 clauses 13.2, 12.2): its
    // <session-type>. The pushing caller or the pulled callee transmits.
    std::string video_mode;
    // An ambient viewing call (TS 24.281 clause 15.2.1): its type ("remote-init" or
    // "local-init"), this client's role ("viewing" or "viewed-to"), whether the
    // release feature capability was received (annex D.3), and the server's
    // <release-reason> in a BYE.
    std::string ambient_type, ambient_role, release_reason;
    bool may_release = true;
    unsigned generation = 0;                   // matches a late INVITE response to this call
    std::string priority = "normal";           // emergency, imminent or normal, as the server last said (clause 4.6)
    std::optional<SipResponse> pending_answer; // a ringing private call (manual commencement)
    MediaDescription local;
    std::optional<MediaDescription> remote;
    std::unique_ptr<UdpSocket> audio, video, control;
    // Transmission control that arrives before the SDP answer: with a participating
    // server in the path, the grant of an implicit request (TS 24.581 clause 6.4) can
    // overtake the 200 (OK) the SIP core carries. Held until the answer names its source.
    std::vector<Datagram> early_control;
    bool granted = false, requesting = false;
    std::uint32_t video_ssrc = 0, frame = 0;
    std::uint32_t received_ssrc = 0; // the transmitting user's video SSRC being decoded
    // Decoding starts at an IDR picture: at a new stream, on joining late and on
    // resuming reception. Pictures before it are skipped, not undecodable.
    bool awaiting_key = true;
    unsigned skipped_before_key = 0;
    std::uint16_t sequence = 0;
    std::uint64_t next_frame = 0;
    std::unique_ptr<video::Encoder> encoder;
    std::unique_ptr<video::Decoder> decoder, reference;
    video::Depacketizer depacketizer;
    unsigned sent = 0, received = 0, undecoded = 0, revocations = 0;
    std::optional<unsigned> queue_position; // from Queue Position Info (TS 24.581 clause 9.2.12)
    // TS 24.281 clause 9.2.3: the session's focus URI and the conference event subscription to it.
    std::string session_uri, conference_subscription;
    std::map<std::string, std::string> participants; // MCVideo ID -> RFC 4575 status
    std::optional<std::uint32_t> conference_version; // RFC 4575 section 4.6: of the last notification applied
    // Reception control (TS 24.581 clause 4.1.1.2): transmissions announced by Media Transmission Notification
    // (on-network) or Media Reception Notification, and whether this client receives the current one.
    unsigned reception_notifications = 0;
    bool receiving = true;
    // The transmitting user's SSRCs from the Media Transmission Notification (TS 24.581 clause 6.2.5.3.2 step 3).
    std::uint32_t transmitter_audio_ssrc = 0, transmitter_video_ssrc = 0;
    std::uint16_t reception_reject_cause = 0;
    std::uint16_t revoke_cause = 0;
    // "<SSRC>:<RTP timestamp>" -> checksum of the picture, sent or received.
    Json sent_frames = Json::object(), received_frames = Json::object();
  };

  std::optional<McvideoCall> mcvideo_call_;
  // Live video for an agent (mcvideo.live, mcvideo/live_video.cpp; off unless configured): I420 pictures of the
  // configured size written to a FIFO are transmitted in place of the test pattern, the latest one at each frame.
  int live_video_fd_ = -1;
  std::vector<std::uint8_t> live_video_partial_;
  std::optional<video::Frame> live_video_frame_;
  unsigned live_video_frames_ = 0;
  std::uint64_t live_video_at_ = 0;
  // Received video for the client page (mcvideo.live_receive, mcvideo/live_video.cpp; off unless configured): each
  // received H.264 picture, from an IDR picture on, goes to a local UDP port for the browser to decode.
  std::unique_ptr<UdpSocket> live_receive_;
  std::string live_receive_host_;
  std::uint16_t live_receive_port_ = 0;
  unsigned live_receive_dropped_ = 0;

  // Helper: while a new call in mcvideo_call_ is set up (offer, keys, INVITE), a throw drops it again, so that a
  // failed attempt does not leave "an MCVideo call is in progress" behind.
  struct McvideoCallSetup {
    std::optional<McvideoCall> &call;
    int exceptions = std::uncaught_exceptions();

    explicit McvideoCallSetup(std::optional<McvideoCall> &held) : call(held) {}

    McvideoCallSetup(const McvideoCallSetup &) = delete;
    McvideoCallSetup &operator=(const McvideoCallSetup &) = delete;

    ~McvideoCallSetup() {
      if (std::uncaught_exceptions() > exceptions) call.reset();
    }
  };

  Json mcvideo_last_call_;
  // Conference subscriptions of ended calls: their final NOTIFY is still answered 200.
  std::set<std::string> mcvideo_retired_subscriptions_;
  // Emergency alerts (TS 24.281 clause 11.2): this user's, and those received by origin.
  bool mcvideo_alerting_ = false;
  Json mcvideo_alerts_ = Json::object(), mcvideo_alert_result_;

  std::vector<SipHeader> mcvideo_priority_headers(const McpttInfo &info) const;

  void mcvideo_note_priority(const McpttInfo &info);

  void mcvideo_change_priority(bool emergency, bool enabled);

  void mcvideo_alert(bool enabled);

  SipResponse mcvideo_message(const SipMessage &message);

  unsigned mcvideo_generation_ = 0;

  void mcvideo_invite_answered(unsigned generation, const SipResponse &response);

  McvideoCall new_mcvideo_call();

  static std::string contact_uri_of(std::string_view contact);

  std::vector<SipHeader> mcvideo_call_headers() const;

  void mcvideo_private_call(const std::string &target, std::string mode, bool no_control = false,
                            bool emergency = false, const std::string &session_type = "private", bool to_alias = false,
                            const std::string &called_alias = {});

  void mcvideo_server_call(const std::string &session_type, const std::string &url = {});

  // ---- MCVideo functional aliases, mcvideo/functional_alias.cpp (TS 24.281 clause 20). The aliases the server confirmed
  // active for this user, the one presented in calls, and the last change's result.
  std::set<std::string> mcvideo_aliases_;
  // TS 24.281 clause 20.2.1.2 step 6 a): every PUBLISH lists the whole set wanted active; the last one sent is the base
  // of the next change until the NOTIFY with its p-id-fa (or, without a status subscription, its answer) arrives; then
  // the base is mcvideo_aliases_standing_, those the last NOTIFY lists "activated" or "activating" (one listed
  // "deactivating" is given up: listing it again activates it, clause 20.2.2.2.3 step 12 a).
  std::set<std::string> mcvideo_aliases_wanted_, mcvideo_aliases_standing_;
  std::string mcvideo_alias_publish_open_;
  // TS 24.281 clause 20.2.1.2 step 6 d): the aliases this user's status NOTIFY offered as "take-over-possible", and
  // those the user asked to take over before they were offered.
  std::set<std::string> mcvideo_takeover_possible_, mcvideo_takeover_wanted_;
  std::string mcvideo_alias_in_use_;
  Json mcvideo_fa_result_ = nullptr, mcvideo_redirect_ = nullptr;
  // A call placed to an alias, until its final response: generation, alias and mode.
  Json mcvideo_alias_call_ = Json::object();

  std::string mcvideo_presented_alias() const;

  void mcvideo_functional_alias(const std::string &action, const std::vector<std::string> &aliases);

  // TS 24.281 clause 20.2.1.3: status determination subscriptions, by dialog: the alias followed, or
  // empty for this user's own aliases. mcvideo_alias_users holds, per alias, the users
  // the last per-functional alias NOTIFY named; mcvideo_fa_notified the last p-id-fa.
  std::map<std::string, std::string> mcvideo_alias_dialogs_;
  Json mcvideo_alias_users_ = Json::object(), mcvideo_fa_notified_ = nullptr;
  bool mcvideo_alias_status_known_ = false;

  void mcvideo_alias_subscribe(const std::string &alias, bool fetch);

  void mcvideo_alias_unsubscribe(const std::string &alias);

  SipResponse mcvideo_alias_notify(const SipMessage &message);

  // ---- MCVideo ad hoc group emergency alert, mcvideo/location.cpp (TS 24.281 clause 12.1A.1): the alert state of
  // annex G.20
  // (1 none, 2 confirm pending, 3 initiated), this user's alert's ad hoc group, the
  // alerts received by ad hoc group identity, and the last response.
  int mcvideo_ah_mea_ = 1;
  std::string mcvideo_adhoc_alert_group_;
  Json mcvideo_adhoc_alerts_ = Json::object(), mcvideo_adhoc_alert_result_ = nullptr;

  void mcvideo_adhoc_alert(const std::string &arguments);

  // ---- Location procedures of the MCVideo client, mcvideo/location.cpp (TS 24.281 clauses 18.3, 6.2.9). The
  // participating MCVideo function configures its own reporting; the UE's position
  // and radio identities are shared with the MCPTT and MCData sides.
  location::Reporter mcvideo_location_;
  std::string mcvideo_location_server_;
  bool mcvideo_location_logon_pending_ = false;
  Json mcvideo_location_received_ = Json::array(), mcvideo_location_sent_ = Json::array(),
       mcvideo_area_notices_received_ = Json::array(), mcvideo_location_result_ = nullptr;
  std::string mcvideo_location_request_;

  bool mcvideo_location_emergency() const;

  void mcvideo_send_location_report(location::Report report);

  std::optional<SipResponse> mcvideo_location_incoming(const SipMessage &message);

  void mcvideo_area_notice(const McpttInfo &info);

  void mcvideo_area_affiliation(const std::string &group, bool entered);

  void mcvideo_location_command(const std::string &cmd);

  void mcvideo_location_tick(std::uint64_t now);

  Json mcvideo_location_status() const;

  // ---- Remote change of the selected group, mcvideo/group_selection.cpp (TS 24.281 clause 9.2.4).
  // mcvideo_selection_result_: this user's last request and its outcome; mcvideo_selection_: the last request
  // received, while it is handled and after; mcvideo_selection_pending_: the affiliation it waits for.
  Json mcvideo_selection_result_ = nullptr, mcvideo_selection_ = nullptr, mcvideo_selection_pending_ = nullptr;
  unsigned mcvideo_selection_requests_ = 0;

  void mcvideo_select_remote(const std::string &target, const std::string &group);

  void mcvideo_selection_requested(const McpttInfo &info);

  void mcvideo_selection_affiliated(bool affiliated);

  void mcvideo_selection_answer(bool success);

  // TS 24.281 clauses 20.4.2.1.2 and 20.4.2.1.3: a SIP MESSAGE with <request-type> "fa-group-binding-req",
  // <binding-ind>, the alias in <binding-fa-uri> or <unbinding-fa-uri>, and the groups
  // in a resource-lists body. A binding names an alias this client activated. The
  // result, with any Warning, is kept in mcvideo_binding_result.
  Json mcvideo_binding_result_ = nullptr;
  unsigned mcvideo_binding_requests_ = 0;

  void mcvideo_alias_binding(bool bind, const std::string &alias, const std::vector<std::string> &groups);

  // ---- MCVideo sessions, transmission control and video media (mcvideo/session.cpp) ----
  void mcvideo_regroup_command(const std::string &action, const std::string &tgi, const std::string &preconfigured,
                               const std::vector<std::string> &groups, const std::vector<std::string> &users);

  // TS 24.281 clauses 21.2.1.3, 21.2.1.4, 21.3.1.3 and 21.3.1.4: the regroups this user is told about,
  // by temporary group identity.
  Json mcvideo_regroups_ = Json::object(), mcvideo_regroup_result_;

  void mcvideo_regroup_notified(const Regroup &regroup);

  void mcvideo_adhoc_call(const std::vector<std::string> &targets, const std::string &criteria);

  void mcvideo_ambient_call(const std::string &target, const std::string &type);

  static bool ambient_release_capability(const std::vector<SipHeader> &headers);

  // TS 24.281 clause 13.2.2.4: the <video-push-url> of the server's recording, from its SIP INFO.
  std::string mcvideo_push_url_;

  void mcvideo_group_call(bool implicit = false, const std::string &chat_group = {}, const std::string &priority = {},
                          const std::string &target_group = {});

  static bool one_to_one_video(const std::string &session_type);

  SipResponse mcvideo_invite(const SipMessage &message);

  SipResponse mcvideo_answer_invite(const SipMessage &message, const McpttInfo &info);

  // New MCVideo call from the server's invitation (TS 24.281 clause 10.2.2.2.2).
  void mcvideo_adopt_invited_call(const SipMessage &message, const McpttInfo &info);
  // Media protection of an invitation (TS 24.281 clause 4.7.1); the refusal, empty when the answer is protected.
  std::optional<SipResponse> mcvideo_accept_protection(const SipMessage &message, const McpttInfo &info,
                                                       MediaDescription &answer);

  bool mcvideo_media(std::string_view dialog, const MediaDescription &media);

  bool mcvideo_call_state(std::string_view dialog, SipCallState state);

  void mcvideo_subscribe_conference();

  SipResponse mcvideo_conference_notify(const SipMessage &message);

  void mcvideo_end_call(const std::string &reason);

  void mcvideo_start_sending(std::uint32_t ssrc);

  void mcvideo_send_control(transmission::Type type, std::vector<FloorField> fields = {});

  void mcvideo_request_transmission();

  void mcvideo_remote_transmission(const std::string &target, bool start);

  Json mcvideo_remote_result_;

  void mcvideo_end_transmission();

  void mcvideo_control_packet(const Datagram &datagram);

  void mcvideo_video_packet(const Datagram &datagram, bool multicast = false);

  // The MBMS subchannel a Map Group To Bearer message associated with a group (TS 24.380 clause 10.3.2, TS 24.581
  // clause 10.3.2): the bearer's service and TMGI, and the subchannel.
  struct MbmsMapping {
    MbmsService service = MbmsService::Mcptt;
    std::string tmgi;
    MbmsSubchannel subchannel;
    // The participating function's SSRC (the Map Group To Bearer header's): the media control messages over the
    // subchannel carry it.
    std::uint32_t ssrc = 0;
  };

  // ---- MCVideo media over an MBMS subchannel (mcvideo/multicast.cpp): TS 24.581 clauses 10.3.2 to 10.3.4 ----
  // The subchannels mapped to MCVideo groups, and the one joined for the active group call (its video port), with the
  // session, group and TMGI of its reports and the stream's last SSRC and sequence number.
  std::map<std::string, MbmsMapping> mbms_video_mappings_;
  std::unique_ptr<UdpSocket> mcvideo_multicast_socket_;
  std::string mcvideo_multicast_session_, mcvideo_multicast_group_, mcvideo_multicast_tmgi_;
  std::optional<std::pair<std::uint32_t, std::uint16_t>> mcvideo_last_rtp_;
  [[nodiscard]] bool mcvideo_mappable() const;
  void mcvideo_mbms_mapped(const std::string &tmgi, const SubchannelMessage &message);
  void mcvideo_mbms_unmapped(const SubchannelMessage &message);
  void mcvideo_multicast_join();
  void mcvideo_multicast_leave(bool report);
  void mcvideo_multicast_report(bool listening);
  void mcvideo_multicast_poll();
  [[nodiscard]] bool mcvideo_duplicate(std::span<const std::uint8_t> bytes, bool multicast);
  void mcvideo_mbms_control_packet(const Datagram &datagram);
  void mcvideo_control_message(const transmission::Packet &packet);

  void mcvideo_tick(std::uint64_t now);

  void mcvideo_poll();

  void mcvideo_call_status();

  // ---- MCData client, mcdata/service.cpp (TS 24.282), sharing this UE's SIP registration (clause 7.1).
  bool mcdata_enabled() const { return cfg_.contains("mcdata"); }

  std::string mcdata_user() const { return cfg_.at("mcdata").at("user_id"); }

  std::string mcdata_service() const { return cfg_.at("mcdata").at("service_uri"); }

  std::string mcdata_group() const { return cfg_.at("mcdata").at("group_id"); }

  bool mcdata_authorized_ = false, mcdata_affiliated_ = false;
  std::string mcdata_auth_etag_, mcdata_affiliation_etag_;
  // Affiliation status determination (clause 8.2.3): the subscription dialog, the groups the
  // last NOTIFY reported as affiliated, whether one has arrived, the last p-id sent
  // and received, and a one-off fetch (Expires 0) with its result.
  std::string mcdata_subscription_, mcdata_fetch_, mcdata_sent_pid_, mcdata_received_pid_;
  std::set<std::string> mcdata_groups_;
  bool mcdata_status_known_ = false, mcdata_desired_affiliation_ = true;
  Json mcdata_fetch_result_ = nullptr;

  // ---- MCData payload protection, mcdata/protection.cpp (TS 33.180 clause 8, TS 24.282 clause 6.6). One-to-one
  // payloads use a PCK shared with the peer MCData ID (the security context of TS 24.282
  // clause 9.2.2.2.1 step 2c); group payloads use the GMK of the group (TS 33.180 clause 8.4).
  bool mcdata_protected() const { return mcdata_enabled() && cfg_.at("mcdata").value("protect_payload", false); }

  bool mcdata_authenticates() const;

  unsigned mcdata_signed_ = 0, mcdata_verified_ = 0, mcdata_signature_failures_ = 0;

  std::string mcdata_sign(const std::string &message);

  std::string mcdata_verify(const std::string &message, const std::string &sender);

  bool mcdata_protects_signalling() const;

  std::optional<keying::KeyMessage> mcdata_csk_;
  std::string mcdata_csk_upload_;
  bool mcdata_csk_accepted_ = false;
  unsigned mcdata_signalling_sealed_ = 0, mcdata_signalling_opened_ = 0, mcdata_signalling_refused_ = 0;

  const keying::KeyMessage *mcdata_signalling_key() const;

  bool mcdata_protects_xml() const;

  unsigned mcdata_xml_encrypted_ = 0, mcdata_xml_decrypted_ = 0;

  std::optional<xmlsec::Key> mcdata_xml_key(bool uploading = false) const;

  static const xmlsec::Profile &mcdata_xml_profile();

  static bool mcdata_xml_part(const std::string &type);

  std::vector<BodyPart> mcdata_encrypt_xml(std::vector<BodyPart> parts, const std::optional<xmlsec::Key> &key);

  std::vector<BodyPart> mcdata_decrypt_xml(std::vector<BodyPart> parts);

  SipResponse mcdata_opened_response(const SipResponse &raw);

  std::vector<BodyPart> mcdata_seal_signalling(std::vector<BodyPart> parts);

  std::vector<std::pair<std::string, std::string>>
  mcdata_seal_msrp(std::vector<std::pair<std::string, std::string>> parts);

  std::vector<BodyPart> mcdata_open_signalling(std::vector<BodyPart> parts);

  std::unique_ptr<KeyClient> mcdata_keys_;

  //------------------------------------------------------------------------------
  // TS 33.180 clause 8.3: a PCK of one-to-one MCData communications, with its peer and expiry.
  struct DataKey {
    keying::KeyMessage key;
    std::string peer;
    std::uint64_t expires = 0;
  };

  std::map<std::uint32_t, DataKey> mcdata_pcks_;
  std::map<std::uint32_t, keying::KeyMessage> group_keys_;
  unsigned mcdata_protected_sent_ = 0, mcdata_protected_received_ = 0, mcdata_protection_failures_ = 0;

  keying::KeyParameterWireProfile payload_profile() const;

  void expire_data_keys();

  std::pair<keying::KeyMessage, std::string> mcdata_pck_for(const std::string &peer);

  keying::KeyMessage mcdata_gmk_for(const std::string &group) const;

  std::string mcdata_protected_payload(const std::string &message, const keying::KeyMessage &key) const;

  std::string mcdata_seal(const std::string &message, const keying::KeyMessage &key);

  void mcdata_accept_pck(const std::string &encoded, const std::string &sender);

  const keying::KeyMessage &mcdata_dppk(std::uint32_t id, const mcdata::Info &info);

  std::string mcdata_open(const std::string &body, const mcdata::Info &info);

  // A file is protected as a series of Protected Payload messages sharing one Payload
  // ID, numbered by the Payload sequence number (TS 33.180 table 8.5.4.1-1 NOTE 2),
  // since one message holds at most 65535 octets. TS 24.282 table 15.2.2-1 has no
  // type for file data: this implementation uses FD SIGNALLING PAYLOAD with bit 7.
  static constexpr std::uint8_t protected_file_type = 0x42;

  std::string mcdata_seal_file(const std::string &contents, const keying::KeyMessage &key);

  std::string mcdata_open_file(const std::string &data, const mcdata::Info &info);

  // ---- MCData service authorisation, affiliation and standalone SDS (mcdata/service.cpp) ----
  // Received SDS messages by Message ID, oldest first for bounded status output.
  std::map<std::string, Json> mcdata_received_;
  std::deque<std::string> mcdata_received_order_;
  // Timer TDU1 (delivery and read, TS 24.282 annex F.2.3) per Message ID.
  std::map<std::string, std::uint64_t> mcdata_tdu1_;
  Json mcdata_notifications_ = Json::array(), mcdata_sent_ = Json::array(), mcdata_last_result_ = nullptr;
  unsigned mcdata_discarded_ = 0;
  // File distribution: files this client offered and received, and timer TDU2
  // (FD non-mandatory download timer, annex F.2.3) per Message ID.
  Json mcdata_files_sent_ = Json::array(), mcdata_fd_last_result_ = nullptr, mcdata_file_notifications_ = Json::array();
  // The media storage function URI learned by discovery (clause 10.2.1.3) and the outcome.
  std::string mcdata_discovered_content_server_;
  Json mcdata_discovery_result_ = nullptr;
  std::map<std::string, Json> mcdata_files_;
  std::deque<std::string> mcdata_files_order_;
  std::map<std::string, std::uint64_t> mcdata_tdu2_;
  // Local storage for received messages (clause 6.2.3.1 step 4): when full, a message is
  // not delivered to the user or application and UNDELIVERED is reported.
  std::optional<std::size_t> mcdata_storage_limit_;
  Json mcdata_undelivered_ = Json::array();

  static void bounded_push(Json &list, Json entry);

  std::vector<SipHeader> mcdata_sds_headers() const;

  void mcdata_request(const std::string &event_name, std::vector<BodyPart> parts, std::vector<SipHeader> headers,
                      std::function<void(const SipResponse &)> done, const std::string &method = "PUBLISH");

  void mcdata_authorize();

  std::string mcdata_status_subscribe(const std::string &target, bool fetch);

  void mcdata_subscribe();

  SipResponse mcdata_affiliation_notify(const SipMessage &request);

  void mcdata_affiliate(bool affiliate);

  //------------------------------------------------------------------------------
  // TS 24.282 clause 9.2.2.2.1 with clause 6.2.2.1: a standalone SDS message over the signalling control plane.
  // type is "one-to-one-sds" (target: MCData ID, or a functional alias with
  // to_alias), "group-sds" (target: group) or "ad-hoc-group-sds" (target:
  // comma-separated MCData IDs or aliases, "criteria=<criteria>" or "group=<ad hoc group>").
  struct SdsRequest {
    std::string type, target, text;
    mcdata::DispositionRequest disposition = mcdata::DispositionRequest::None;
    std::optional<std::uint8_t> application;
    std::optional<mcdata::Uuid> conversation, in_reply_to;
    std::optional<unsigned> priority;
    bool to_alias = false;                                   // <call-to-functional-alias-ind>
    std::string called_alias;                                // <called-functional-alias-URI> after a 300 redirect
    mcdata::ContentType content = mcdata::ContentType::Text; // ENHANCED STATUS for clause 14.2.1.1
    bool tamper_key = false;                                 // test aid: send an altered I_MESSAGE with a new PCK
    bool tamper_signature = false;                           // test aid: send a payload whose signature is altered
  };

  // Enhanced status (TS 24.282 clause 14.2): the operational values of each group, from its group
  // document, and the statuses received from other members.
  std::map<std::string, EnhancedStatusValues> mcdata_enhanced_values_;
  Json mcdata_enhanced_status_ = Json::array();

  const EnhancedStatusValues &mcdata_group_status_values(const std::string &group, bool refresh);

  void mcdata_send_enhanced_status(const std::string &group, const std::string &id, bool force = false);

  std::vector<std::string> mcdata_adhoc_target(const std::string &spec, mcdata::Info &info) const;

  void mcdata_adhoc_answered(const SipResponse &response);

  void mcdata_send_sds(const SdsRequest &request);

  // PCK or GMK protection of an SDS DATA PAYLOAD (TS 24.282 clause 6.6.3.2); the key's identifier, empty if none.
  std::optional<std::uint32_t> mcdata_protect_sds(const SdsRequest &request, std::vector<BodyPart> &parts,
                                                  std::string &data);
  // Response to a standalone SDS, with the retry to a functional alias's selected MCData ID.
  void mcdata_sds_response(const std::string &id, const SdsRequest &retry, const SipResponse &response);

  // ---- MCData media plane, mcdata/media.cpp (TS 24.282 clause 9.2.3 with TS 24.582 clause 6.1): standalone SDS
  // whose payload travels over MSRP instead of the signalling control plane.
  std::unique_ptr<msrp::Endpoint> mcdata_media_;
  std::string mcdata_media_dialog_, mcdata_media_role_;
  Json mcdata_media_result_ = nullptr, mcdata_media_received_ = Json::array(),
       mcdata_media_notifications_ = Json::array();
  // What this client will send once the MSRP connection is bound.
  std::vector<std::pair<std::string, std::string>> mcdata_media_pending_;
  std::string mcdata_media_message_id_;
  bool mcdata_media_acknowledged_ = false, mcdata_media_session_ = false;
  // MCData emergency private communication state (TS 24.282 annex G.4.11) of the open session, and
  // whether this client asked for the emergency condition.
  int mdepc_ = 1;
  bool mcdata_session_emergency_ = false;
  // The priority states of a group SDS session (TS 24.282 annex G.4): the emergency and imminent peril group
  // communication states of this client (MDEGC annex G.4.5, MDIGC annex G.4.8) and the in-progress states of the
  // group itself as this client knows them (MDEG annex G.4.3, MDIG annex G.4.6).
  int mdegc_ = 1, mdeg_ = 1, mdigc_ = 1, mdig_ = 1;
  bool mcdata_session_imminent_ = false;
  std::string mcdata_session_group_;  // the group of an open group SDS session
  bool mcdata_session_adhoc_ = false; // that group is an ad hoc group (clause 24)

  // ---- MCData emergency and imminent peril states (mcdata/priority.cpp) ----
  static const char *mdepc_name(int state);

  static const char *mdegc_name(int state);

  static const char *mdeg_name(int state);

  static const char *mdigc_name(int state);

  static const char *mdig_name(int state);

  std::vector<SipHeader> mcdata_priority_headers(const char *level = "emergency") const;

  void mcdata_priority_response(const SipResponse &response);

  void mcdata_priority_info(const mcdata::Info &info);

  void mcdata_group_priority_received(const mcdata::Info &info, bool in_session = true);

  // ---- MCData media plane, originating side (mcdata/media.cpp) ----
  // File distribution over the media plane (TS 24.282 clause 10.2.5 with TS 24.582 clause 7.1): the
  // file itself travels in MSRP SEND requests, so it is held until the connection
  // is bound, and what arrives is kept beside the other artifacts.
  // The DPPK of the open media plane communication (TS 24.282 clause 9.2.3.2.1 step 2,
  // clause 9.2.4.2.1 step 2, clause 10.2.5.2.1 step 2) and the other party of a one-to-one one.
  std::optional<keying::KeyMessage> mcdata_media_key_;
  std::string mcdata_media_peer_;

  void mcdata_media_protect(MsrpMedia &offer, const std::string &target, bool group, bool adhoc);

  std::string mcdata_media_payload(const std::string &encoded);

  mcdata::Info mcdata_media_origin() const;

  std::string mcdata_media_file_;                   // the file this client is sending
  Json mcdata_media_files_ = Json::array();         // files received over the media plane
  Json mcdata_group_notifications_ = Json::array(); // in-progress state notifications (clause 6.3.7.1.2)
  Json mcdata_media_offered_ = nullptr;             // the file being offered to this client
  mcdata::Uuid mcdata_media_conversation_{};
  std::string mcdata_media_answer_; // repeated when a re-INVITE arrives
  Json mcdata_media_sent_ = Json::array();
  std::string mcdata_media_group_; // the calling group of a received media plane SDS

  std::vector<SipHeader> mcdata_media_headers() const;

  MsrpMedia mcdata_media_offer();

  void mcdata_media_connect(const MsrpMedia &answer);

  void mcdata_open_session(const std::string &target, bool group, const std::string &priority = "normal",
                           bool with_alert = false);

  // Priority of an SDS session request and the states it moves (TS 24.282 clause 6.2.8.3.2, 6.2.8.1.1, 6.2.8.1.9).
  void mcdata_session_priority_state(mcdata::Info &info, const std::string &target, bool group, bool emergency,
                                     bool imminent, bool with_alert);
  // Response to an SDS session request (TS 24.282 clause 9.2.4.2.3).
  void mcdata_session_answered(const SipResponse &raw);

  void mcdata_media_answered(const SipResponse &raw, const char *what);

  void mcdata_session_priority(bool emergency, bool enable);

  void mcdata_group_priority(bool emergency, bool enable);

  void mcdata_session_send(const std::string &text, mcdata::DispositionRequest disposition);

  void mcdata_send_file_media(const std::string &target, bool group, const std::string &path, bool mandatory,
                              bool completion);

  void mcdata_send_media_sds(const std::string &target, const std::string &text, bool group = false,
                             std::optional<unsigned> priority = std::nullopt);

  // ---- MCData communication release (mcdata/release.cpp) ----
  // TS 24.282 clause 13 over the media plane: what this client learned about the release of
  // its communication, as originator or as the authorised user who announced it.
  Json mcdata_release_ = Json::object();

  static std::string reason_text(const std::string &header);

  std::size_t mcdata_remaining_bytes() const;

  void mcdata_com_release_send(const std::string &dialog, const std::vector<BodyPart> &parts, const std::string &reason,
                               const std::string &action);

  void mcdata_release_command(std::istringstream &words);

  SipResponse mcdata_com_release_received(const SipMessage &request);

  // ---- MCData IP connectivity (mcdata/ipconn.cpp) ----
  // IP connectivity (TS 24.282 clause 20 with TS 24.582 clause 13). This client is also the
  // IP application of TS 24.582 clause 13.1.1: a minimal IPv4 endpoint at the configured tunnel
  // address that answers ICMP echo, so that real IP packets cross the tunnel
  // without a TUN device or extra container privileges.
  std::unique_ptr<UdpSocket> ipcon_socket_;
  std::string ipcon_dialog_, ipcon_local_sdp_;
  ipconn::TunnelMedia ipcon_peer_;
  Json ipcon_result_ = nullptr;
  Json ipcon_pings_ = Json::array();
  std::map<std::uint32_t, std::uint64_t> ipcon_outstanding_; // identifier << 16 | sequence -> sent at
  std::uint64_t ipcon_sent_ = 0, ipcon_received_ = 0, ipcon_dropped_ = 0, ipcon_echo_answered_ = 0;

  std::string ipcon_address() const;

  std::vector<SipHeader> ipcon_headers() const;

  void ipcon_bind();

  void ipcon_reset();

  void ipcon_open(const std::string &target, bool to_alias, const std::string &called_alias = {});

  SipResponse ipcon_incoming(const SipMessage &request, const mcdata::Info &info, const BodyPart &sdp);

  void ipcon_close();

  void ipcon_transmit(const std::vector<std::uint8_t> &packet);

  void ipcon_ping(const std::string &destination, unsigned count, std::size_t size);

  void ipcon_tick();

  // ---- MCData media plane, terminating side and MSRP transfer (mcdata/media_session.cpp) ----
  SipResponse mcdata_media_invite(const SipMessage &invite);

  // re-INVITE in the media plane session's dialog: priority state (TS 24.282 clause 6.2.8.4.2, 9.2.4.2.4).
  SipResponse mcdata_media_reinvite(const mcdata::Info &info);
  // PCK offered with a one-to-one communication (TS 24.282 clause 9.2.3.2.4 step 3); the refusal, empty if accepted.
  std::optional<SipResponse> mcdata_media_accept_key(const MsrpMedia &offer, const mcdata::Info &info);
  // FD over the media plane offered (TS 24.282 clause 10.2.5.2.4); the refusal, empty if received.
  std::optional<SipResponse> mcdata_media_file_offer(const SipMessage &invite, const MsrpMedia &offer,
                                                     const mcdata::Info &info, Json &offered);
  // Session kind and in-progress priority state of an invitation (TS 24.282 clause 9.2.4.2.4).
  void mcdata_media_session_state(const SipMessage &invite, const mcdata::Info &info, bool adhoc_session);
  // GMK or PCK protecting the session.
  void mcdata_media_select_key(const mcdata::Info &info, bool adhoc);
  // SDP answer and MSRP connection to the offered stream.
  void mcdata_media_answer_offer(SipResponse &response, const SipMessage &invite, const MsrpMedia &offer,
                                 const std::string &address, bool file_transfer, bool adhoc_session);

  void mcdata_media_close(const std::vector<SipHeader> &bye_headers = {});

  void mcdata_media_incoming(const msrp::Message &message);

  void mcdata_media_file_received(const std::string &transferred);

  void mcdata_media_tick();

  // ---- MCData file distribution over HTTP, mcdata/file_distribution.cpp (TS 24.282 clauses 10.2.2 to 10.2.4).
  static std::string sha256_hex(const std::string &data);

  static bool save_file(const std::filesystem::path &target, const std::string &contents);

  std::string mcdata_content_server() const;

  void mcdata_discover_content_server(const std::string &group);

  std::string mcdata_upload(const std::string &path, const std::string &group, std::string &name, std::uint64_t &size,
                            std::string &digest, const keying::KeyMessage *key = nullptr);

  bool mcdata_download(Json &file);

  void mcdata_fd_notify(Json &file, mcdata::FdNotificationType type);

  std::vector<SipHeader> mcdata_fd_headers() const;

  //------------------------------------------------------------------------------
  // TS 24.282 clause 10.2.4.2.1: a file distribution request using HTTP, as the user asks for it.
  struct FdRequest {
    std::string target, path, called_alias, url_override, store_object;
    bool group = false, mandatory = false, completion = false, to_alias = false;
    std::optional<std::uint8_t> application;
    std::optional<mcdata::Uuid> conversation;
    std::uint64_t available_for = 0; // seconds; 0 leaves the availability to the server
  };

  void mcdata_send_file(const FdRequest &request);

  void mcdata_cancel_file(const std::string &id, mcdata::TerminationType type = mcdata::TerminationType::Request,
                          std::optional<mcdata::ExtensionResponse> extension = {}, const std::string &reason = {});

  void mcdata_file_incoming(const mcdata::Info &info, const mcdata::FdSignalling &request);

  void mcdata_file_accept(const std::string &id);

  void mcdata_file_decision(const std::string &id, const std::string &action);

  // ---- MCData emergency alert, functional aliases and disposition notifications (mcdata/alert.cpp) ----
  // MCData emergency alert (TS 24.282 clause 16.2.1): MDEA state (annex G.4.1) and MCData emergency state
  // (annex G.4.2), alerts of other users by group and originator, and the last request outcome.
  int mdea_ = 1;
  bool mcdata_emergency_ = false;
  std::string mcdata_alert_group_;
  Json mcdata_alerts_received_ = Json::object(), mcdata_alert_last_result_ = nullptr,
       mcdata_alert_receipts_ = Json::array();
  // Cancellations of other users' alerts (with <originated-by>) awaiting their receipt, by group: the receipt
  // procedure of TS 24.282 clause 16.2.1.2 is for the client's own alert only.
  std::map<std::string, int> mcdata_third_party_cancels_;

  static const char *mdea_name(int state);

  void mcdata_alert(bool activate, std::string group, const std::string &originator);

  void mcdata_alert_incoming(const mcdata::Info &info, const std::vector<BodyPart> &parts);

  // TS 24.282 clause 22.2.1.2: functional alias status change. The published list is the full set
  // of aliases wanted active; an empty set is Expires 0. Its base is mcdata_aliases_standing_, those the last NOTIFY
  // lists "activated" or "activating" (one listed "deactivating" is given up: listing it again activates it, clause
  // 22.2.2.2.3 step 12 a); mcdata_aliases_active_ are those usable ("activated", or "deactivating" while still active).
  // While a change awaits its NOTIFY (mcdata_alias_publish_open_, its p-id-fa), the base of the next one is the set it
  // listed, mcdata_aliases_wanted_ (TS 24.282 clause 22.2.1.2 step 6 a).
  std::set<std::string> mcdata_aliases_wanted_, mcdata_aliases_active_, mcdata_aliases_standing_;
  std::string mcdata_alias_publish_open_;
  std::string mcdata_alias_in_use_;
  Json mcdata_fa_last_result_ = nullptr;
  // TS 24.282 clause 22.2.1.3: the subscription to this user's functional alias status (its dialog, whether it was
  // answered and the changes waiting for it), the aliases offered for take-over and those the user asked to take over.
  std::string mcdata_alias_dialog_;
  bool mcdata_alias_answered_ = false;
  int mcdata_alias_subscription_result_ = 0;
  std::vector<std::pair<std::string, std::vector<std::string>>> mcdata_pending_alias_changes_;
  std::set<std::string> mcdata_takeover_possible_, mcdata_takeover_wanted_;

  void mcdata_functional_alias(const std::string &action, const std::vector<std::string> &aliases);

  void mcdata_publish_functional_alias(const std::string &action, const std::vector<std::string> &aliases);

  void mcdata_alias_subscribe();

  SipResponse mcdata_alias_notify(const SipMessage &message);

  void mcdata_notify(Json &received, mcdata::DispositionNotification type);

  void mcdata_display(const std::string &id);

  // ---- MCData incoming requests, commands and status (mcdata/dispatch.cpp) ----
  SipResponse mcdata_incoming(const SipMessage &message);

  // In-progress emergency state or emergency alert of a group (TS 24.282 clause 6.3.7.1.2, 16.2.1.3); empty if none.
  std::optional<SipResponse> mcdata_group_state_received(const std::vector<BodyPart> &parts);
  // Media storage discovery response (TS 24.282 clause 10.2.1.3); empty if none.
  std::optional<SipResponse> mcdata_discovery_received(const std::vector<BodyPart> &parts);
  // FD request using HTTP at the terminating client (TS 24.282 clause 10.2.4.2.2).
  SipResponse mcdata_fd_request_received(const std::vector<BodyPart> &parts, const BodyPart &signalling,
                                         const mcdata::Info &info);
  // FD notifications, possibly aggregated (TS 24.282 clause 12.2.1.2).
  SipResponse mcdata_fd_notifications_received(const std::vector<const BodyPart *> &signalling_parts,
                                               const mcdata::Info &info);
  // The server's FD HTTP termination messages (TS 24.282 clause 6.3.6.1, clause 13.2.4.5.2).
  SipResponse mcdata_fd_termination_received(const SipMessage &message, const BodyPart &signalling,
                                             const mcdata::Info &info);
  // FD network notification: the file is no longer available (TS 24.282 clause 12.4.4).
  SipResponse mcdata_fd_unavailable_received(const BodyPart &signalling, const mcdata::Info &info);
  // SDS notifications, possibly aggregated (TS 24.282 clause 12.2.1.2).
  SipResponse mcdata_sds_notifications_received(const std::vector<const BodyPart *> &signalling_parts,
                                                const mcdata::Info &info);
  // Standalone SDS at the terminating client (TS 24.282 clause 9.2.2.2.2, clause 9.2.1.2).
  SipResponse mcdata_sds_received(const std::vector<BodyPart> &parts, const BodyPart &data, const BodyPart &signalling,
                                  const mcdata::Info &info);
  // PCK, signature and decryption of a received DATA PAYLOAD; the failure response, empty when open.
  std::optional<SipResponse> mcdata_open_sds_payload(const std::vector<BodyPart> &parts, const mcdata::Info &info,
                                                     std::string &data_body, std::optional<std::uint32_t> &dppk,
                                                     bool &authenticated);
  // Enhanced status payloads (TS 24.282 clause 14.2.1.2); false when the message is discarded.
  bool mcdata_render_enhanced_status(const mcdata::DataPayload &payload, const std::string &id,
                                     const mcdata::Info &info);
  // Keep a received SDS and answer its disposition request (TS 24.282 clause 9.2.1.3).
  void mcdata_deliver_sds(Json &received, const mcdata::SdsSignalling &sds, const mcdata::DataPayload &payload,
                          const std::string &id, const mcdata::Info &info);

  void mcdata_tick();

  void mcdata_command(const std::string &cmd);

  // "fd-send": FD using HTTP (TS 24.282 clause 10.2.4.2.1).
  void mcdata_fd_send_command(std::istringstream &words);
  // "sds-session": SDS session over the media plane (TS 24.282 clause 9.2.4.2.3).
  void mcdata_sds_session_command(std::istringstream &words);
  // "fd-media": FD over the media plane (TS 24.282 clause 10.2.5).
  void mcdata_fd_media_command(std::istringstream &words);
  // "sds-status": send an enhanced status (TS 24.282 clause 14.2.1.1).
  void mcdata_sds_status_command(std::istringstream &words);
  // "sds-status-values": a group's operational values, into status.json.
  void mcdata_sds_status_values_command(std::istringstream &words);
  // "ipcon": open or close IP connectivity (TS 24.282 clause 20).
  void mcdata_ipcon_command(std::istringstream &words);
  // "ipcon-ping": the built-in IP application.
  void mcdata_ipcon_ping_command(std::istringstream &words);
  // "sds-media": standalone SDS over the media plane (TS 24.282 clause 9.2.3).
  void mcdata_sds_media_command(std::istringstream &words);
  // "fd-release-intent": announce the release of a file (TS 24.282 clause 13.2.6.3.2.1).
  void mcdata_fd_release_intent_command(std::istringstream &words);
  // "fd-extension-response": answer an extension request (TS 24.282 clause 13.2.6.3.2.3).
  void mcdata_fd_extension_response_command(std::istringstream &words);
  // "fd-cancel", "fd-release", "fd-extend": FD HTTP termination requests.
  void mcdata_fd_cancel_command(const std::string &verb, std::istringstream &words);
  // "mcdata-affiliation-status": fetch, subscribe or unsubscribe (TS 24.282 clause 8.2.3).
  void mcdata_affiliation_status_command(std::istringstream &words);
  // "mcdata-storage": the received messages the client can hold.
  void mcdata_storage_command(std::istringstream &words);
  // "sds-reply": an SDS in the same conversation.
  void mcdata_sds_reply_command(std::istringstream &words);
  // "mcdata-alert": emergency alert or its cancellation (TS 24.282 clause 16.2.1.1).
  void mcdata_alert_command(std::istringstream &words);
  // "mcdata-fa": functional alias status change or use (TS 24.282 clause 22.2.1.2).
  void mcdata_fa_command(std::istringstream &words);
  // "sds", "sds-group", "sds-adhoc", "sds-fa": standalone SDS (TS 24.282 clause 9.2.1.1).
  void mcdata_sds_command(const std::string &verb, std::istringstream &words);

  void mcdata_status();

  // ---- MCPTT call state, floor participant messages and media (mcptt/call_media.cpp) ----
  void check_remote(const MediaDescription &media, bool private_call = false);

  void call_state(std::string_view id, SipCallState state, int code);

  // Clear the state of this client's ended MCPTT call (TS 24.380 clause 6.2.2).
  void clear_call_state();

  void send_floor(FloorType type);

  void release();

  void save_audio();

  // Whether a media or floor control packet comes from the server's SDP address and port (media.server_source).
  bool sent_by_server(const sockaddr_in &source, const std::string &address, std::uint16_t port) const;
  // Where to send to the server's SDP address and port: with media.server_source "port", the address the server's
  // packets on that port came from, once one has (so that replies follow the server's flow through a NAT).
  std::string server_target(const std::string &address, std::uint16_t port) const;
  // media.server_source "port": the address each server port's packets came from.
  mutable std::map<std::uint16_t, std::string> server_sources_;
  void floor_packet(const Datagram &datagram);

  void floor_message(FloorPacket packet, bool multicast);

  static bool from_server_address(const sockaddr_in &source, const std::string &address);

  void audio_packet(const Datagram &datagram, bool multicast = false);

  // ---- MBMS usage (core/mbms.cpp): TS 24.379 clause 14.3, TS 24.281 clause 16.2, TS 24.380 clause 10.3 ----
  // An announced bearer (by TMGI): its service, <announcement>, SDP, the MBMS public service identity that announced
  // it, the host group and port of its general purpose subchannel and, while the emulated MBMS interface can receive
  // it, the socket joined to it; whether "listening" was reported last, and accepted.
  struct MbmsBearer {
    MbmsService service = MbmsService::Mcptt;
    MbmsAnnouncement announcement;
    std::string sdp, psi, general_purpose_address;
    std::uint16_t general_purpose_port = 0;
    std::unique_ptr<UdpSocket> socket;
    bool reported_listening = false, accepted = false;
    // TS 24.379 clause 14.3.2 step 1 f: the MSCCK of the general purpose subchannel's "a=key-mgmt", whose SRTCP
    // protects the bearer's subchannel control messages (TS 24.380 clause 13.3.3); none when they are clear.
    std::unique_ptr<ProtectedCall> mscck;
  };
  bool mbms_enabled_ = false;
  std::string mbms_interface_ = "0.0.0.0";
  // The emulated MBMS interface: the MBMS service areas the UE is in, the TMGIs with bad radio conditions ("all":
  // every bearer).
  std::set<std::string> mbms_areas_, mbms_bad_;
  std::map<std::string, MbmsBearer> mbms_bearers_;
  // Bearers whose announcement was cancelled after "listening" was reported: service and PSI to report to.
  std::map<std::string, std::pair<MbmsService, std::string>> mbms_cancelled_;
  // TS 24.379 clause 14.3.4 (TS 24.281 clause 16.2.4): the MuSiK of each service's key download, whose SRTCP protects
  // the floor (transmission) control over the subchannels (TS 33.180 clause 9.2.2).
  std::unique_ptr<ProtectedCall> mbms_musik_, mcvideo_mbms_musik_;
  [[nodiscard]] std::vector<std::uint8_t> mbms_open_control(std::span<const std::uint8_t> bytes, const MbmsBearer &bearer);
  [[nodiscard]] static std::uint8_t mbms_mki_purpose(std::span<const std::uint8_t> bytes);
  void mbms_configure();
  [[nodiscard]] const std::string *mbms_usage_body(const SipMessage &message, MbmsService service) const;
  SipResponse mbms_message(const SipMessage &message, MbmsService service, const std::string &body);
  void mbms_update();
  void mbms_report(MbmsService service, const std::string &psi, bool listening, const std::vector<std::string> &tmgis);
  void mbms_poll();
  void mbms_subchannel_message(const std::string &tmgi, const SubchannelMessage &message);
  void mbms_command(const std::string &cmd);
  void publish_mbms();

  // ---- MCPTT media over an MBMS subchannel (mcptt/multicast.cpp): TS 24.380 clauses 10.3.2 to 10.3.4 ----
  // The subchannels the Map Group To Bearer messages on the general purpose subchannels associated with groups
  // (TS 24.380 clause 10.3.2), by group URI. While the call of a mapped group is active the client has joined its
  // subchannel (multicast_socket_) and keeps the SSRC and sequence number last received so that a packet that arrives
  // both by unicast and over the subchannel is kept once.
  std::map<std::string, MbmsMapping> mbms_mappings_;
  std::unique_ptr<UdpSocket> multicast_socket_;
  std::optional<std::pair<std::uint32_t, std::uint16_t>> last_rtp_; // SSRC, sequence number
  // The session, group and TMGI of the joined subchannel's listening status reports (TS 24.379 clause 14.3.3.2).
  std::string multicast_session_, multicast_group_, multicast_tmgi_;
  // TS 24.380 clause 10.3.3: the message sequence numbers of the floor control messages over the joined subchannel.
  FloorSequenceTracker mbms_floor_sequence_;
  void mbms_floor_packet(const Datagram &datagram);

  void multicast_join();

  void multicast_leave(bool report);

  void multicast_report(bool listening);

  void multicast_poll();

  void mbms_mapped(const std::string &tmgi, const SubchannelMessage &message);

  void mbms_unmapped(const SubchannelMessage &message);

  [[nodiscard]] bool duplicate_rtp(std::span<const std::uint8_t> packet, bool multicast);

  void kept_rtp(std::span<const std::uint8_t> packet);

  // ---- Live audio for the dispatcher (mcptt/live_audio.cpp, media.live) ----
  void open_live_audio();
  // "ptt live": transmit the microphone frames of media.live until "release".
  void start_live_transmission();
  // A received frame to the dispatcher's service, as 16-bit little-endian PCM.
  void forward_live_audio(const std::vector<std::int16_t> &pcm);
  // Microphone PCM from the dispatcher's service.
  void receive_live_audio();
  // The next 20 ms frame of the microphone, silence when none has arrived.
  void live_speech_tick(std::uint64_t now);

  // ---- Received talk bursts for an agent (mcptt/speech_capture.cpp, media.speech_dir) ----
  // Keep a received speech frame of the talker; a new talker ends the burst before.
  void capture_speech(const std::vector<std::int16_t> &pcm, std::uint32_t ssrc, const std::string &from,
                      std::uint64_t now);
  // End the burst when the floor passed on or went idle, or after a gap without floor control.
  void speech_capture_tick(std::uint64_t now);
  // Write the burst as a WAV file and report it (speech_received).
  void end_speech(const char *reason);

  // ---- Live video for an agent (mcvideo/live_video.cpp, mcvideo.live) ----
  // Open the FIFO of mcvideo.live; without mcvideo.live nothing.
  void open_live_video();
  // Read the pictures written to the FIFO; the latest complete one is transmitted.
  void receive_live_video();
  // The encoder settings: mcvideo.live's picture size, frame rate and bit rate, else the defaults.
  video::EncoderOptions mcvideo_encoder_options() const;
  // The picture to transmit next: the latest live picture, or without mcvideo.live the test pattern.
  video::Frame mcvideo_picture(const video::EncoderOptions &options, std::uint32_t index) const;
  // mcvideo.live_receive: a received picture (an access unit) to the client page's service.
  void forward_live_video(const video::AccessUnit &unit, std::uint32_t timestamp);

  // ---- Control commands (core/commands.cpp) ----
  void command(const std::string &cmd);

  void run_command(const std::string &cmd);

  // "regroup create|users|remove ..." (TS 24.379 clause 16.2.1.1, clause 16.2.1.2).
  void regroup_request_command(const std::string &cmd);
  // "priority <0..99>": the floor priority asked for.
  void floor_priority_command(const std::string &cmd);
  // "answer-mode auto|manual": the commencement mode of incoming calls.
  void answer_mode_command(const std::string &cmd);
  // "group <group> [from=<group>]": select and affiliate to a group (TS 24.379 clause 9.2.1.2).
  void select_group(const std::string &cmd);
  // "answer" or "reject" a call awaiting manual commencement (TS 24.379 clause 6.2.3.2).
  void answer_command(const std::string &cmd);
  // "call ...", "chat ...", "rejoin": MCPTT information and priority of a group call (TS 24.379 clause 10.1.1.2.1.1).
  void group_call_command(const std::string &cmd);
  // Floor and media set-up and the INVITE of a group call.
  void invite_group_call(const std::string &cmd, bool chat, McpttInfo &info, const std::vector<SipHeader> &headers);
  // "pre-establish": test aid for the server's pre-established session refusal.
  void pre_establish_command();
  // "chat-join <group>": test aid for the server's implicit affiliation checks.
  void chat_join_command(const std::string &cmd);
  // "ptt <wav>": request the floor and transmit (TS 24.380 clause 6.2.4.3.5).
  void ptt_command(const std::string &cmd);
  // "hangup": release the call.
  void hangup_command();
  // "deaffiliate": end the call and withdraw the affiliation (TS 24.379 clause 9.2.1.2).
  void deaffiliate_command();
  // "options <SIP URI>": reachability probe (RFC 3261 section 11).
  void options_command(const std::string &cmd);

  // ---- The periodic tick (core/client.cpp) ----
  void tick();

  // Floor participant timers and the end of transmission (TS 24.380 clause 11.1.1).
  void floor_participant_tick(std::uint64_t now);
  // The next 20 ms speech frame, or the end of the transmission.
  void speech_tick(std::uint64_t now);
  // One 20 ms speech frame as AMR-WB in RTP to the call's media address.
  void send_speech_frame(const std::array<std::int16_t, 320> &frame, bool marker, std::uint64_t now);
  // The status file and the received audio, every 200 ms.
  void save_status(std::uint64_t now);
  // Floor participant and priority state of the status file.
  void floor_and_priority_status();

  // ---- Start-up steps and one pass of the run loop (core/client.cpp; run() is public) ----
  // MCPTT user profile and group document at start-up (TS 24.484 clause 8.3.1A).
  void load_user_documents();
  // mcptt.group_from_profile: the configured group gives way to one of the user profile.
  void group_from_profile();
  // KMS key provisioning and the CSKs (TS 33.180 clause 5.3.3, clause 5.4).
  void provision_keys();
  void provision_identity_keys();
  // One pass of the run loop.
  void run_once();

public:
  // ---- Construction, start-up (core/client.cpp) ----
  explicit Client(Json cfg, bool disable_tls);

  void read_mcvideo_profile();
  void read_mcdata_profile();

  bool mcvideo_profile_allows(const std::string &action) const;

  // ---- IdMS sign-in and token renewal (core/tokens.cpp) ----
  // Logs in at the IdMS and takes the access token, once its identity matches this client.
  // Refresh tokens (TS 33.180 annex B.4.2.5, annex B.5): the IdMS's refresh token renews the access
  // token without the password. With oauth.refresh_token_file (an activated device),
  // the token and the authentication's nonce are kept there, owner-only, across
  // restarts, and the configuration need not hold the password.
  std::string refresh_token_, auth_nonce_;
  bool refresh_loaded_ = false;
  unsigned token_refreshes_ = 0, password_logins_ = 0, browser_logins_ = 0;
  // Refusals of the refresh grant in a row; the second is final (client_rules::refresh_refusal_final).
  unsigned refresh_refusals_ = 0;

  void load_refresh_state();

  void keep_refresh_state(const Json &tokens);

  // TS 24.482 clause 6.2.1 and TS 33.180 annexes B.4 and B.5: one token grant, run on a worker thread (grant_tokens
  // touches no client state): the IdMS settings and refresh state it starts from, and what it brings back.
  struct TokenGrant {
    Json oauth;
    std::string refresh_token, nonce;
    unsigned refusals = 0;
    std::int64_t expires = 0;
    Json tokens;
    enum class Kind { none, refresh, browser, password } kind = Kind::none;
    bool authorization_used = false, refresh_cleared = false;
    std::exception_ptr error;
  };

  static TokenGrant grant_tokens(const HttpClient &idms, TokenGrant grant);

  void take_token_grant(const TokenGrant &grant);

  // The run loop's side of the token grant: done(nullptr) once the access token is taken, done(error) otherwise.
  void request_tokens(std::function<void(std::exception_ptr)> done);

  void take_tokens(const Json &tokens);

  // The worker thread of the token grants and its hand-off to the run loop (run_once). Declared after the HTTP
  // clients: it is destroyed (and its job waited for) first.
  Handoff handoff_;
  BackgroundJob token_job_{handoff_};

  void schedule_token_renewal();

  void renew_token();

  void reauthorize();

  void authorization_lost(const std::string &what, const SipResponse &response);

  // ---- The run loop (core/client.cpp) ----
  void run();
};
} // namespace mcx
