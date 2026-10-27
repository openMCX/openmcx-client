//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// MCData service authorisation, affiliation, enhanced status and standalone SDS over the signalling control plane,
// MCData client side (member functions of Client).
//
// Specifications: TS 24.282 V19.7.0 (Rel-19), TS 24.481 V19.3.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19),
// RFC 3428 (SIP MESSAGE), RFC 3903 (SIP PUBLISH), RFC 6665 (SIP SUBSCRIBE and NOTIFY).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// Helper: append an entry to a status list, keeping the last 50.
void Client::bounded_push(Json &list, Json entry) {
  list.push_back(std::move(entry));
  while (list.size() > 50) list.erase(list.begin());
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.2.4.1 step 1: the media feature tags and ICSI of a SIP MESSAGE request for SDS.
std::vector<SipHeader> Client::mcdata_sds_headers() const {
  return {{"Accept-Contact", "*;+g.3gpp.mcdata.sds;require;explicit"},
          {"Accept-Contact", "*;+g.3gpp.icsi-ref=\"" + url_encode(mcdata::sds_icsi) + "\";require;explicit"},
          {"P-Preferred-Service", mcdata::sds_icsi}};
}

//------------------------------------------------------------------------------
// Helper: send an MCData SIP request to the participating MCData function with the MCData
// ICSI (TS 24.282 clause 7.2.1A step 2) and its bodies protected (TS 24.282 clauses 6.5.2 and 6.6.3).
void Client::mcdata_request(const std::string &event_name, std::vector<BodyPart> parts, std::vector<SipHeader> headers,
                            std::function<void(const SipResponse &)> done, const std::string &method) {
  const auto boundary = "mcdata-" + random_id();
  parts = mcdata_seal_signalling(std::move(parts));
  headers.push_back({"P-Preferred-Service", mcdata::icsi});
  if (!event_name.empty()) headers.push_back({"Event", event_name});
  const auto [type, body] = message_body(parts, boundary);
  sip_.request(method, mcdata_service(), type, body, headers, [this, done](const SipResponse &response) {
    if (response.status_code >= 200) done(mcdata_opened_response(response));
  });
}

//------------------------------------------------------------------------------
// TS 24.282 clause 7.2.2: service authorisation and MCData service settings by PUBLISH; with
// mcptt.authorise_by_register, clause 7.2.1: the mcdata-info (and the CSK upload) in a REGISTER of its own, which the
// SIP core passes to the MCData server in a third-party REGISTER (clause 7.3.2).
void Client::mcdata_authorize() {
  if (!mcdata_enabled() || token_.empty() || logged_off_) return;
  const bool by_register = authorise_by_register();
  mcdata::Info info;
  info.access_token = token_;
  info.client_id = client_id();
  std::vector<SipHeader> headers{{"Expires", "4294967295"}};
  const bool refresh = !mcdata_auth_etag_.empty();
  if (refresh) headers.push_back({"SIP-If-Match", mcdata_auth_etag_});
  std::vector<BodyPart> parts{{mcdata::info_mime, mcdata::info_xml(info)}};
  if (!by_register) parts.push_back({"application/poc-settings+xml", poc_settings_xml(client_id())});
  // TS 24.282 clause 7.2.2 step 3: the CSK, once, in the first service authorisation.
  const bool uploading = !mcdata_csk_accepted_ && !mcdata_csk_upload_.empty();
  if (uploading) {
    // TS 24.282 clause 7.2.2 step 4: the access token and client ID encrypted with the CSK being uploaded.
    parts = mcdata_encrypt_xml(std::move(parts), mcdata_xml_key(true));
    parts.push_back({"application/mikey", mcdata_csk_upload_});
  }
  ++mcdata_authorization_retry_.generation;
  auto answered = [this, uploading, refresh](const SipResponse &response) {
    if (response.status_code != 200) {
      // TS 24.282 clause 7.2.2: not authorised, and so not affiliated.
      mcdata_authorized_ = mcdata_affiliated_ = false;
      failure("MCData service authorisation: " + std::to_string(response.status_code));
      // RFC 3903 section 5: after a 412 to a refresh, discard the entity-tag and publish anew; the affiliation
      // status subscription and the affiliation follow the new authorisation. A refresh refused for a reason that can
      // be transient (the SIP core answering while the server restarts, for example) is handled alike, the initial
      // publication following after a growing delay.
      const bool transient = client_rules::transient_authorization_refusal(response.status_code);
      if (refresh && (response.status_code == 412 || transient)) {
        mcdata_auth_etag_.clear();
        mcdata_affiliation_etag_.clear();
        mcdata_subscription_.clear();
        // TS 24.282 clause 22.2.2.3.6: the server ended the aliases with the authorisation, and its subscriptions.
        mcdata_alias_dialog_.clear();
        mcdata_alias_answered_ = false;
        mcdata_aliases_active_.clear();
        mcdata_aliases_standing_.clear();
        mcdata_alias_publish_open_.clear();
      }
      if (refresh && response.status_code == 412) {
        schedule([this] { mcdata_authorize(); });
      } else if (!refresh || transient) {
        // A refused publication is published again when the refusal can be transient.
        retry_authorization(mcdata_authorization_retry_, "mcdata", response, [this] {
          if (!mcdata_authorized_) mcdata_authorize();
        });
      }
      return;
    }
    mcdata_auth_etag_ = get_header(response.headers, "SIP-ETag");
    mcdata_authorization_retry_.attempts = 0;
    mcdata_authorized_ = true;
    if (uploading) {
      mcdata_csk_accepted_ = true;
      mcdata_csk_upload_.clear();
    }
    event("mcdata_service_authorized");
    mcdata_location_logon_pending_ = !mcdata_location_.configured();
    mcdata_location_.signal(location::Event::InitialLogOn, monotonic_ms());
    // TS 24.282 clause 8.2.3: learn the affiliation status (including implicit affiliations)
    // first; the affiliation PUBLISH follows the first NOTIFY.
    if (mcdata_subscription_.empty())
      schedule([this] { mcdata_subscribe(); });
    else if (mcdata_desired_affiliation_)
      schedule([this] { mcdata_affiliate(true); });
  };
  if (by_register) {
    const auto [type, body] = message_body(mcdata_seal_signalling(std::move(parts)), "mcdata-" + random_id());
    queue_register(type, body, answered);
    event("mcdata_service_authorization_by_register");
  } else {
    mcdata_request("poc-settings", parts, headers, answered);
  }
}

//------------------------------------------------------------------------------
// TS 24.282 clause 8.2.3: SUBSCRIBE to the affiliation status of an MCData user (by default this
// user) at the MCData server; Expires 0 fetches the current state once.
std::string Client::mcdata_status_subscribe(const std::string &target, bool fetch) {
  mcdata::Info info;
  info.request_uri = target;
  return sip_.subscribe(
      mcdata_service(), "presence", fetch ? 0u : 4294967295u,
      {{"P-Preferred-Service", mcdata::icsi}, {"Accept", "application/pidf+xml"}},
      [this, fetch, target](const SipResponse &response) {
        if (response.status_code < 200) return;
        if (fetch) {
          if (response.status_code >= 300)
            mcdata_fetch_result_ = {{"target", target}, {"status", response.status_code}, {"groups", nullptr}};
          return;
        }
        if (response.status_code >= 300) {
          mcdata_subscription_.clear();
          failure("MCData affiliation subscription: " + std::to_string(response.status_code));
        }
      },
      mcdata::info_mime,
      mcdata_encrypt_xml({{mcdata::info_mime, mcdata::info_xml(info)}}, mcdata_xml_key()).front().body);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 8.2.3: subscribe to this user's own affiliation status, once service authorisation succeeded.
void Client::mcdata_subscribe() {
  if (!mcdata_authorized_ || !mcdata_subscription_.empty()) return;
  mcdata_status_known_ = false;
  mcdata_subscription_ = mcdata_status_subscribe(mcdata_user(), false);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 8.2.3: NOTIFY with the per-user affiliation information (TS 24.282 clause 8.4.1).
SipResponse Client::mcdata_affiliation_notify(const SipMessage &request) {
  SipResponse response;
  response.status_code = 200;
  if (asserted_identity(request) != mcdata_service()) {
    response.status_code = 403;
    return response;
  }
  if (get_header(request.headers, "Event") != "presence") {
    response.status_code = 489;
    return response;
  }
  const bool fetch = request.dialog_id == mcdata_fetch_;
  const bool terminated = get_header(request.headers, "Subscription-State").starts_with("terminated");
  const auto body = request.parts.empty() ? request.body : request.parts.front().body;
  // TS 24.282 clause 8.4.1: one tuple per client; a subscription reads this client's (none: no group), a fetch of
  // another user's status the one tuple of the answer.
  const auto affiliation = fetch ? parse_affiliation(body) : parse_affiliation(body, client_id());

  // An empty status has no <affiliation> element to show its namespace.
  if (!affiliation.mcdata && !affiliation.groups.empty())
    throw std::runtime_error("MCData affiliation NOTIFY without the MCData namespace");
  // TS 24.282 clause 8.4.1 (TS 24.379 clause 9.2.2.2.11): the listed groups not being given up, also the base of the
  // next whole-set PUBLISH (one still "affiliating", left out, would be deaffiliated).
  std::set<std::string> groups;
  for (std::size_t index = 0; index < affiliation.groups.size(); ++index)
    if (client_rules::counts_as_affiliated(index < affiliation.statuses.size() ? affiliation.statuses[index] : ""))
      groups.insert(affiliation.groups[index]);
  if (fetch) {
    mcdata_fetch_result_ = {
        {"target", affiliation.user_id}, {"status", 200}, {"groups", groups}, {"terminated", terminated}};
    mcdata_fetch_.clear();
    return response;
  }
  if (affiliation.user_id != mcdata_user() || affiliation.client_id != client_id())
    throw std::runtime_error("MCData NOTIFY identity mismatch");
  mcdata_groups_ = groups;
  mcdata_affiliated_ = groups.contains(mcdata_group());
  if (!affiliation.publication_id.empty()) mcdata_received_pid_ = affiliation.publication_id;
  event("mcdata_affiliation_status",
        {{"groups", groups}, {"p_id", affiliation.publication_id}, {"terminated", terminated}});
  if (terminated) {
    mcdata_subscription_.clear();
    mcdata_status_known_ = false;
    return response;
  }
  if (!mcdata_status_known_) {
    mcdata_status_known_ = true;
    if (mcdata_desired_affiliation_ && !mcdata_affiliated_) schedule([this] { mcdata_affiliate(true); });
  }
  return response;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 8.2.2: affiliation status change: the configured MCData group together with the
// groups the server reports as affiliated (for example implicit affiliations).
void Client::mcdata_affiliate(bool affiliate) {
  if (!mcdata_authorized_) throw std::runtime_error("MCData affiliation requires MCData service authorisation");
  mcdata_desired_affiliation_ = affiliate;
  mcdata::Info info;
  info.request_uri = mcdata_user();
  info.client_id = client_id();

  // A user may belong to no MCData group (an account activated without one): an empty configured group is not
  // affiliated (TS 24.282 clause 8.2.2: every <affiliation> names a group), and with no group at all nothing is
  // published.
  Affiliation affiliation{mcdata_user(), client_id(), random_id(), {}, {}, true};
  if (!mcdata_group().empty()) affiliation.groups.push_back(mcdata_group());
  for (const auto &group : mcdata_groups_)
    if (!group.empty() && group != mcdata_group()) affiliation.groups.push_back(group);
  if (affiliate && affiliation.groups.empty()) return;
  mcdata_sent_pid_ = affiliation.publication_id;
  std::vector<SipHeader> headers{{"Expires", affiliate ? "4294967295" : "0"}};
  const bool refresh = !mcdata_affiliation_etag_.empty();
  if (refresh) headers.push_back({"SIP-If-Match", mcdata_affiliation_etag_});

  // TS 24.282 clause 8.2.2 steps 4 and 5: de-affiliation from every group is Expires 0 without a PIDF body.
  std::vector<BodyPart> parts{{mcdata::info_mime, mcdata::info_xml(info)}};
  if (affiliate) parts.push_back({"application/pidf+xml", affiliation_xml(affiliation, false)});
  mcdata_request("presence", parts, headers, [this, affiliate, refresh](const SipResponse &response) {
    if (response.status_code != 200) {
      failure("MCData affiliation: " + std::to_string(response.status_code));
      // RFC 3903 section 5: after a 412 to a refresh, discard the entity-tag and publish anew.
      if (refresh && response.status_code == 412) {
        mcdata_affiliation_etag_.clear();
        schedule([this, affiliate] { mcdata_affiliate(affiliate); });
      }
      return;
    }
    mcdata_affiliation_etag_ = affiliate ? std::string(get_header(response.headers, "SIP-ETag")) : std::string{};
    // The affiliation status itself comes from the NOTIFY (TS 24.282 clause 8.2.3).
    event(affiliate ? "mcdata_affiliation_requested" : "mcdata_deaffiliation_requested", {{"group", mcdata_group()}});
  });
}

//------------------------------------------------------------------------------
// TS 24.282 clause 14.2.1.1 with TS 24.481: the <mcdata-allow-enhanced-status> element and the
// <mcdata-enhanced-status-operational-values> of a group, read from the group document (cached unless refreshed).
const EnhancedStatusValues &Client::mcdata_group_status_values(const std::string &group, bool refresh) {
  if (!refresh)
    if (const auto found = mcdata_enhanced_values_.find(group); found != mcdata_enhanced_values_.end())
      return found->second;
  auto document = http_.request("GET",
                                cfg_.at("endpoints").at("gms").get<std::string>() +
                                    "/xcap-root/org.openmobilealliance.groups/global/byGroupID/" + url_encode(group),
                                {}, {{"Authorization", "Bearer " + token_}});
  if (document.status != 200)
    throw std::runtime_error("group document retrieval failed: " + std::to_string(document.status));
  return mcdata_enhanced_values_[group] = group_enhanced_status(document.body);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 14.2.1.1: the selected operational value's "id" goes to the group as a
// standalone SDS whose payload content type is ENHANCED STATUS.
// "force" skips the checks, so that a test can send an id the group does not know.
void Client::mcdata_send_enhanced_status(const std::string &group, const std::string &id, bool force) {
  const auto &values = mcdata_group_status_values(group, true);
  if (!force && !values.allowed) throw std::runtime_error("enhanced status is not allowed on this group");
  if (!force &&
      std::none_of(values.values.begin(), values.values.end(), [&id](const auto &value) { return value.first == id; }))
    throw std::runtime_error("no operational value with that id in the group document");
  SdsRequest request;
  request.type = "group-sds";
  request.target = group;
  request.text = id;
  request.content = mcdata::ContentType::EnhancedStatus;
  mcdata_send_sds(request);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.2.2.1 step 3A and clause 24.2.2.1.1 steps 5e ii, 7 and 8: an ad hoc group is named
// by a participant list ("ID,ID,..."), by criteria ("criteria=<criteria>") or by
// the identity of an ad hoc group already created ("group=<ad hoc group>").
// Returns the participants to list in an application/resource-lists+xml body.
std::vector<std::string> Client::mcdata_adhoc_target(const std::string &spec, mcdata::Info &info) const {
  require_mcdata_profile({"allow-adhoc-group-data-comn"});
  std::vector<std::string> participants;
  if (spec.starts_with("criteria="))
    info.participant_criteria = spec.substr(9);
  else if (spec.starts_with("group="))
    info.request_uri = spec.substr(6);
  else {
    std::istringstream list(spec);
    for (std::string id; std::getline(list, id, ',');)
      if (!id.empty()) participants.push_back(id);
  }
  if (participants.empty() && info.participant_criteria.empty() && info.request_uri.empty())
    throw std::runtime_error("an ad hoc group needs MCData IDs, criteria=<criteria> or group=<ad hoc group>");
  return participants;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 24.2.2.1.1, on a SIP 2xx response, step 4: the 2xx names the ad hoc group in
// <mcdata-calling-group-id>.
void Client::mcdata_adhoc_answered(const SipResponse &response) {
  for (const auto &part : response.parts) {
    if (part.content_type != mcdata::info_mime) continue;
    const auto answer = mcdata::parse_info(part.body);
    if (answer.calling_group.empty()) continue;
    mcdata_media_result_["group"] = answer.calling_group;
    if (mcdata_media_session_) mcdata_session_group_ = answer.calling_group;
    event("mcdata_adhoc_group_learned", {{"group", answer.calling_group}});
  }
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.2.2.1: the MCData client sends a one-to-one, group or ad hoc group standalone SDS over the
// signalling control plane, protected with the PCK or GMK (TS 24.282 clause 6.6.3.2) and optionally signed; a SIP 300
// (Multiple Choices) response to a functional alias target sends it again to the selected MCData ID.
void Client::mcdata_send_sds(const SdsRequest &request) {
  if (!mcdata_authorized_) throw std::runtime_error("SDS requires MCData service authorisation");
  require_mcdata_profile({"allow-transmit-data"}); // TS 24.282 clause 11.1 step 1
  mcdata::Info info;
  info.request_type = request.type;
  std::vector<std::string> participants;
  if (request.type == "group-sds") {
    info.request_uri = request.target;
    info.client_id = client_id();
  } else if (request.type == "ad-hoc-group-sds") {
    info.client_id = client_id();
    participants = mcdata_adhoc_target(request.target, info);
  } else if (request.to_alias) {
    info.call_to_functional_alias = true; // TS 24.282 clause 9.2.2.2.1 step 2b ii A
  }
  info.called_functional_alias = request.called_alias;

  // TS 24.282 clause 9.2.2.2.1 steps 2b ii B and 3b v A: present an alias only while it is active for this user.
  if (!mcdata_alias_in_use_.empty() && mcdata_aliases_active_.contains(mcdata_alias_in_use_))
    info.functional_alias = mcdata_alias_in_use_;
  info.user_requested_priority = request.priority; // TS 24.282 clause 9.2.2.2.1 steps 2b ii C and 3b v B
  mcdata::SdsSignalling signalling;
  signalling.date_time = static_cast<std::uint64_t>(std::time(nullptr));
  signalling.conversation = request.conversation.value_or(mcdata::random_uuid());
  signalling.message = mcdata::random_uuid();
  signalling.in_reply_to = request.in_reply_to;
  signalling.application_id = request.application;
  signalling.disposition = request.disposition;
  mcdata::DataPayload payload{{{request.content, request.text}}};
  std::vector<BodyPart> parts{{mcdata::info_mime, mcdata::info_xml(info)}};
  if (request.type == "one-to-one-sds")
    parts.push_back({resource_lists_mime, private_call_resource_list(request.target)});
  if (!participants.empty()) parts.push_back({resource_lists_mime, resource_list_xml(participants)});
  parts.push_back({mcdata::signalling_mime, mcdata::encode(signalling)});
  auto data = mcdata::encode(payload);
  const std::optional<std::uint32_t> dppk = mcdata_protect_sds(request, parts, data);
  data = mcdata_sign(data); // TS 33.180 clause 8.5.5, over the protected payload when both apply
  if (request.tamper_signature && mcdata_authenticates()) data.back() = static_cast<char>(data.back() ^ 1);
  parts.push_back({mcdata::payload_mime, data});
  const auto id = mcdata::uuid_text(signalling.message);
  bounded_push(mcdata_sent_, {{"message_id", id},
                              {"conversation_id", mcdata::uuid_text(signalling.conversation)},
                              {"request_type", info.request_type},
                              {"target", request.target},
                              {"text", request.content == mcdata::ContentType::Text ? request.text : std::string{}},
                              {"date_time", signalling.date_time},
                              {"status", nullptr},
                              {"called_alias", request.called_alias},
                              {"dppk_id", dppk ? Json(*dppk) : Json(nullptr)}});
  SdsRequest retry = request;
  retry.conversation = signalling.conversation;
  mcdata_request(
      "", parts, mcdata_sds_headers(),
      [this, id, retry](const SipResponse &response) { mcdata_sds_response(id, retry, response); }, "MESSAGE");
  event("mcdata_sds_sent", {{"message_id", id},
                            {"request_type", info.request_type},
                            {"target", request.target},
                            {"disposition", static_cast<int>(request.disposition)},
                            {"to_alias", request.to_alias}});
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.6.3.2 and clause 9.2.2.2.1 step 2c: protect the DATA PAYLOAD of a one-to-one SDS with a PCK
// (sent with it as a MIKEY part) or of a group SDS with the GMK; the key's identifier, empty when unprotected.
std::optional<std::uint32_t> Client::mcdata_protect_sds(const SdsRequest &request, std::vector<BodyPart> &parts,
                                                        std::string &data) {
  std::optional<std::uint32_t> dppk;
  if (mcdata_protected() && request.type == "one-to-one-sds" && !request.to_alias) {
    if (request.tamper_key)
      std::erase_if(mcdata_pcks_, [&request](const auto &entry) { return entry.second.peer == request.target; });
    auto [key, message] = mcdata_pck_for(request.target);
    if (request.tamper_key && !message.empty()) {
      auto &character = message[message.size() / 2];
      character = character == 'A' ? 'B' : 'A';
      mcdata_pcks_.erase(key.csb_id); // the recipient will not share this PCK
    }
    if (!message.empty()) parts.push_back({"application/mikey", message}); // TS 24.282 clause 9.2.2.2.1 step 2c viii
    data = mcdata_seal(data, key);
    dppk = key.csb_id;
  } else if (mcdata_protected() && request.type == "group-sds") {
    const auto key = mcdata_gmk_for(request.target);
    data = mcdata_seal(data, key);
    dppk = key.csb_id;
  }
  return dppk;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.2.2.1: the response to a standalone SDS: its status, used priority and ad hoc group, and for
// a functional alias target (300) the SDS again to the MCData ID selected for the alias.
void Client::mcdata_sds_response(const std::string &id, const SdsRequest &retry, const SipResponse &response) {
  mcdata_last_result_ = {{"message_id", id},
                         {"status", response.status_code},
                         {"warning", std::string(get_header(response.headers, "Warning"))}};
  // A 2xx may carry mcdata-info: the used priority, and for an ad hoc group SDS
  // the ad hoc group identity (project extension, see the server).
  std::string adhoc_group;
  std::optional<mcdata::Info> answer;
  if (response.content_type.starts_with(mcdata::info_mime) && !response.body.empty())
    answer = mcdata::parse_info(response.body);
  if (response.status_code < 300 && answer) {
    adhoc_group = answer->calling_group;
    mcdata_last_result_["group"] = answer->calling_group;
    mcdata_last_result_["used_priority"] = answer->used_priority ? Json(*answer->used_priority) : Json(nullptr);
  }
  for (auto &sent : mcdata_sent_)
    if (sent.at("message_id") == id) {
      sent["status"] = response.status_code;
      if (!adhoc_group.empty()) sent["group"] = adhoc_group;
    }
  if (response.status_code == 300 && retry.to_alias && answer && !answer->request_uri.empty()) {
    // TS 24.282 clause 9.2.2.2.1: send again to the MCData ID selected for the alias, naming the alias.
    mcdata_last_result_["selected"] = answer->request_uri;
    SdsRequest next = retry;
    next.target = answer->request_uri;
    next.to_alias = false;
    next.called_alias = retry.target;
    schedule([this, next] {
      try {
        mcdata_send_sds(next);
      } catch (const std::exception &error) {
        failure(error.what());
      }
    });
    return;
  }
  if (response.status_code >= 300) failure("SDS: " + std::to_string(response.status_code));
}

} // namespace mcx
