// MCData file distribution using HTTP, MCData client and media storage client side: upload, download, FD request,
// FD notifications and FD termination (member functions of Client).
//
// Specifications: TS 24.282 V19.7.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19), TS 24.482 V19.1.0 (Rel-19),
// RFC 3428 (SIP MESSAGE), RFC 5547 (file selector), RFC 9110 (HTTP).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// Helper: write a received file; false if it could not be written whole.
bool Client::save_file(const std::filesystem::path &target, const std::string &contents) {
  std::ofstream out(target, std::ios::binary | std::ios::trunc);
  out.write(contents.data(), static_cast<std::streamsize>(contents.size()));
  out.close();
  return static_cast<bool>(out);
}

//------------------------------------------------------------------------------
// Helper: the hash of the RFC 5547 file selector (SHA-256), as lower-case hex.
std::string Client::sha256_hex(const std::string &data) {
  unsigned char digest[EVP_MAX_MD_SIZE];
  unsigned length = 0;
  if (!EVP_Digest(data.data(), data.size(), digest, &length, EVP_sha256(), nullptr) || length != 32)
    throw std::runtime_error("SHA-256 failed");
  std::string out;
  for (unsigned index = 0; index < length; ++index) {
    static constexpr char hex[] = "0123456789abcdef";
    out += hex[digest[index] >> 4];
    out += hex[digest[index] & 0x0f];
  }
  return out;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 10.2.2.1: the <MCDataContentServerURI> of the configuration, or the absolute URI
// learned from an "msf-disc-res" when the client had none (TS 24.282 clause 10.2.1.3).
std::string Client::mcdata_content_server() const {
  const auto &endpoints = cfg_.at("endpoints");
  if (endpoints.contains("content")) return endpoints.at("content").get<std::string>();
  if (!mcdata_discovered_content_server_.empty()) return mcdata_discovered_content_server_;
  throw std::runtime_error("no MCData content server is configured or discovered");
}

//------------------------------------------------------------------------------
// TS 24.282 clause 10.2.1.3: ask for the absolute URI of the media storage function (SIP MESSAGE, "msf-disc-req").
void Client::mcdata_discover_content_server(const std::string &group) {
  if (!mcdata_authorized_) throw std::runtime_error("discovery requires MCData service authorisation");
  mcdata::Info info;
  info.request_type = "msf-disc-req";
  info.client_id = client_id();
  info.calling_group = group;
  mcdata_discovery_result_ = nullptr;
  mcdata_request(
      "", {{mcdata::info_mime, mcdata::info_xml(info)}}, mcdata_fd_headers(),
      [this, group](const SipResponse &response) {
        mcdata_discovery_result_ = {{"group", group},
                                    {"status", response.status_code},
                                    {"warning", std::string(get_header(response.headers, "Warning"))},
                                    {"uri", nullptr}};
        if (response.status_code >= 300) failure("media storage discovery: " + std::to_string(response.status_code));
      },
      "MESSAGE");
  event("mcdata_media_storage_discovery_requested", {{"group", group}});
}

//------------------------------------------------------------------------------
// TS 24.282 clause 10.2.2.1: upload the file with an HTTP POST of a multipart/mixed body, and keep
// the URL of the Location header field of the 201 (Created) response.
std::string Client::mcdata_upload(const std::string &path, const std::string &group, std::string &name,
                                  std::uint64_t &size, std::string &digest, const keying::KeyMessage *key) {
  std::ifstream file(path, std::ios::binary);
  if (!file) throw std::runtime_error("cannot read " + path);
  std::string contents((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
  // TS 24.282 clause 10.2.2.1 step 7: a one-to-one file is protected before it is uploaded.
  // The metadata then describes the stored object, as the media storage function holds it.
  if (key) contents = mcdata_seal_file(contents, *key);
  name = std::filesystem::path(path).filename().string();
  size = contents.size();
  digest = sha256_hex(contents);
  mcdata::Info info;
  info.request_type = group.empty() ? "one-to-one-fd" : "group-fd";
  info.request_uri = group;
  info.calling_user = mcdata_user();
  const auto boundary = "mcdata-" + random_id();
  std::string body =
      "--" + boundary + "\r\nContent-Type: " + mcdata::info_mime + "\r\n\r\n" + mcdata::info_xml(info) + "\r\n--" +
      boundary + "\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=\"" + name +
      "\"\r\nContent-Length: " + std::to_string(size) + "\r\n\r\n" + contents + "\r\n--" + boundary + "--\r\n";
  const auto response =
      http_.request("POST", mcdata_content_server() + "/mcdata/files", body,
                    {{"Content-Type", "multipart/mixed; boundary=" + boundary}, {"Authorization", "Bearer " + token_}});
  if (response.status != 201) {
    // The media storage function refused the file (TS 24.282 clause 10.2.2.2 step 1): 403 by
    // transmission control, 413 above <max-data-size-fd-bytes>.
    mcdata_fd_last_result_ = {{"upload_status", response.status}, {"name", name}, {"bytes", size}};
    event("mcdata_file_upload_refused", {{"status", response.status}, {"name", name}, {"bytes", size}});
    throw std::runtime_error("file upload: " + std::to_string(response.status));
  }
  const auto location = response.headers.find("location");
  if (location == response.headers.end() || location->second.empty())
    throw std::runtime_error("file upload without a Location");
  return location->second;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 10.2.3.1: download the file and keep it beside the other artifacts; the hash of the file selector
// is verified and a protected one-to-one file opened with its PCK.
bool Client::mcdata_download(Json &file) {
  const auto url = file.at("file_url").get<std::string>();
  // A file stored by another system's media storage function is fetched through
  // this system's own, which forwards the request (TS 24.282 clause 10.2.3.1 NOTE 2; TS 24.482
  // annex A is not supplied).
  const auto own = cfg_.at("endpoints").value("content", std::string{});
  const auto source = own.empty() || url.starts_with(own) ? url : own + "/mcdata/remote-files?url=" + url_encode(url);
  const auto response = http_.request("GET", source, {}, {{"Authorization", "Bearer " + token_}});
  file["download_status"] = response.status;
  if (response.status != 200) {
    event("mcdata_file_download_failed", {{"file_url", url}, {"status", response.status}});
    return false;
  }
  // The hash selector (RFC 5547) describes the stored object; a protected one-to-one
  // file is then opened with the PCK shared with its sender (TS 24.282 clause 10.2.3.1).
  const auto expected = file.value("sha256", std::string{});
  const bool verified = expected.empty() || expected == sha256_hex(response.body);
  std::string contents = response.body;
  if (mcdata_protected() && file.value("group", std::string{}).empty()) {
    mcdata::Info origin;
    origin.calling_user = file.value("from", std::string{});
    try {
      contents = mcdata_open_file(response.body, origin);
      file["protected"] = true;
    } catch (const std::exception &error) {
      ++mcdata_protection_failures_;
      event("mcdata_file_undecryptable", {{"file_url", url}, {"reason", error.what()}});
      file["state"] = "undecryptable";
      return false;
    }
  }
  const auto directory = std::filesystem::path(cfg_.at("status_file").get<std::string>()).parent_path() / "downloads";
  std::filesystem::create_directories(directory);
  const auto name = file.value("name", std::string("file"));
  const auto target = directory / std::filesystem::path(name).filename();
  const bool saved = save_file(target, contents);
  file["path"] = target.string();
  file["bytes"] = response.body.size();
  file["plain_bytes"] = contents.size();
  file["plain_sha256"] = sha256_hex(contents);
  file["hash_verified"] = expected.empty() ? Json(nullptr) : Json(verified);
  file["state"] = !saved ? "unsaved" : verified ? "downloaded" : "hash-mismatch";
  event("mcdata_file_downloaded", {{"file_url", url},
                                   {"bytes", response.body.size()},
                                   {"hash_verified", file["hash_verified"]},
                                   {"saved", saved}});
  return verified && saved;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.2.3.2 with clause 12.2.1.1: an FD NOTIFICATION to the originator of the FD request.
void Client::mcdata_fd_notify(Json &file, mcdata::FdNotificationType type) {
  mcdata::Info info;
  info.calling_group = file.value("group", std::string{});
  mcdata::FdNotification notice;
  notice.type = type;
  notice.date_time = static_cast<std::uint64_t>(std::time(nullptr));
  notice.conversation = mcdata::parse_uuid(file.at("conversation_id").get<std::string>());
  notice.message = mcdata::parse_uuid(file.at("message_id").get<std::string>());
  notice.sender = mcdata_user();
  if (!file.at("application_id").is_null()) notice.application_id = file.at("application_id").get<std::uint8_t>();
  mcdata_request(
      "",
      {{mcdata::info_mime, mcdata::info_xml(info)},
       {resource_lists_mime, private_call_resource_list(file.at("from").get<std::string>())},
       {mcdata::signalling_mime, mcdata::encode(notice)}},
      mcdata_fd_headers(),
      [this](const SipResponse &response) {
        if (response.status_code >= 300) failure("FD notification: " + std::to_string(response.status_code));
      },
      "MESSAGE");
  file["notified"].push_back(static_cast<int>(type));
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.2.4.1 step 2: the media feature tags and ICSI of a SIP MESSAGE request for file distribution.
std::vector<SipHeader> Client::mcdata_fd_headers() const {
  return {{"Accept-Contact", "*;+g.3gpp.mcdata.fd;require;explicit"},
          {"Accept-Contact", "*;+g.3gpp.icsi-ref=\"" + url_encode(mcdata::fd_icsi) + "\";require;explicit"},
          {"P-Preferred-Service", mcdata::fd_icsi}};
}

//------------------------------------------------------------------------------
// TS 24.282 clauses 10.2.4.1 and 10.2.4.2.1: upload the file, then send the FD request naming its URL; a SIP 300
// (Multiple Choices) response to a functional alias target sends it again to the selected MCData ID.
void Client::mcdata_send_file(const FdRequest &request) {
  if (!mcdata_authorized_) throw std::runtime_error("file distribution requires MCData service authorisation");
  require_mcdata_profile({"allow-transmit-data"}); // TS 24.282 clause 11.1 step 1
  std::string name, digest;
  std::uint64_t size = 0;
  std::string url = request.url_override;
  // TS 33.180 clause 8.3 with TS 24.282 clause 10.2.2.1 steps 7 and 8: a one-to-one file is protected
  // with the PCK shared with the recipient; a group file is uploaded in clear.
  std::optional<keying::KeyMessage> key;
  std::string key_message;
  if (mcdata_protected() && !request.group && !request.to_alias && url.empty() && request.store_object.empty()) {
    auto [pck, message] = mcdata_pck_for(request.target);
    key = pck;
    key_message = message;
  }
  if (url.empty() && !request.store_object.empty()) {
    url = mcdata_upload_by_reference(request.store_object, request.group ? request.target : std::string{}, name, size,
                                     digest);
  } else if (url.empty()) {
    url = mcdata_upload(request.path, request.group ? request.target : std::string{}, name, size, digest,
                        key ? &*key : nullptr);
  } else if (!request.path.empty() && std::filesystem::exists(request.path)) {
    // The file is already stored: its metadata still describes what is offered.
    name = std::filesystem::path(request.path).filename().string();
    size = std::filesystem::file_size(request.path);
  }
  mcdata::Info info;
  info.request_type = request.group ? "group-fd" : "one-to-one-fd";
  info.client_id = client_id();
  if (request.group) info.request_uri = request.target;
  if (request.to_alias) info.call_to_functional_alias = true;
  info.called_functional_alias = request.called_alias;
  if (!mcdata_alias_in_use_.empty() && mcdata_aliases_active_.contains(mcdata_alias_in_use_))
    info.functional_alias = mcdata_alias_in_use_;
  mcdata::FdSignalling signalling;
  signalling.date_time = static_cast<std::uint64_t>(std::time(nullptr));
  signalling.conversation = request.conversation.value_or(mcdata::random_uuid());
  signalling.message = mcdata::random_uuid();
  signalling.application_id = request.application;
  signalling.mandatory_download = request.mandatory;
  signalling.completion_requested = request.completion;
  signalling.file_url = url;
  signalling.metadata = mcdata::file_metadata(
      name.empty() ? std::filesystem::path(request.path).filename().string() : name, size, "application/octet-stream",
      "", request.available_for ? static_cast<std::uint64_t>(std::time(nullptr)) + request.available_for : 0, digest);
  std::vector<BodyPart> parts{{mcdata::info_mime, mcdata::info_xml(info)}};
  if (!request.group) parts.push_back({resource_lists_mime, private_call_resource_list(request.target)});
  parts.push_back({mcdata::signalling_mime, mcdata::encode(signalling)});
  if (!key_message.empty()) parts.push_back({"application/mikey", key_message});
  const auto id = mcdata::uuid_text(signalling.message);
  bounded_push(mcdata_files_sent_, {{"message_id", id},
                                    {"conversation_id", mcdata::uuid_text(signalling.conversation)},
                                    {"request_type", info.request_type},
                                    {"target", request.target},
                                    {"file_url", url},
                                    {"name", name},
                                    {"bytes", size},
                                    {"status", nullptr},
                                    {"mandatory", request.mandatory},
                                    {"dppk_id", key ? Json(key->csb_id) : Json(nullptr)}});
  FdRequest retry = request;
  retry.conversation = signalling.conversation;
  retry.url_override = url;
  mcdata_request(
      "", parts, mcdata_fd_headers(),
      [this, id, retry](const SipResponse &response) {
        mcdata_fd_last_result_ = {{"message_id", id},
                                  {"status", response.status_code},
                                  {"warning", std::string(get_header(response.headers, "Warning"))}};
        for (auto &sent : mcdata_files_sent_)
          if (sent.at("message_id") == id) sent["status"] = response.status_code;
        if (response.status_code == 300 && retry.to_alias && response.content_type.starts_with(mcdata::info_mime)) {
          // TS 24.282 clause 10.2.4.2.1: send again to the MCData ID selected for the functional alias.
          const auto answer = mcdata::parse_info(response.body);
          if (!answer.request_uri.empty()) {
            mcdata_fd_last_result_["selected"] = answer.request_uri;
            FdRequest next = retry;
            next.target = answer.request_uri;
            next.to_alias = false;
            next.called_alias = retry.target;
            schedule([this, next] {
              try {
                mcdata_send_file(next);
              } catch (const std::exception &error) {
                failure(error.what());
              }
            });
            return;
          }
        }
        if (response.status_code >= 300) failure("FD request: " + std::to_string(response.status_code));
      },
      "MESSAGE");
  event("mcdata_file_sent", {{"message_id", id},
                             {"request_type", info.request_type},
                             {"target", request.target},
                             {"file_url", url},
                             {"bytes", size},
                             {"mandatory", request.mandatory}});
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.2.2.4 with clauses 13.2.1.2, 13.2.4.5.2.2 and 13.2.6.3.2.1: an FD HTTP TERMINATION message
// that releases a distribution this client started, releases another user's distribution, or asks to extend one.
void Client::mcdata_cancel_file(const std::string &id, mcdata::TerminationType type,
                                std::optional<mcdata::ExtensionResponse> extension, const std::string &reason) {
  const auto sent = std::find_if(mcdata_files_sent_.begin(), mcdata_files_sent_.end(),
                                 [&id](const Json &entry) { return entry.at("message_id") == id; });
  const auto received = mcdata_files_.find(id);
  const bool own = sent != mcdata_files_sent_.end();
  if (!own && received == mcdata_files_.end()) throw std::runtime_error("no file distribution with that message ID");
  const Json &record = own ? *sent : received->second;
  mcdata::FdHttpTermination request;
  request.conversation = mcdata::parse_uuid(record.at("conversation_id").get<std::string>());
  request.message = mcdata::parse_uuid(id);
  request.type = type;
  request.extension = extension;
  request.file_url = record.value("file_url", std::string{});
  mcdata::Info info;
  info.client_id = client_id();
  if (own) {
    info.request_type = record.value("request_type", std::string{});
    if (info.request_type == "group-fd") info.request_uri = record.value("target", std::string{});
  } else {
    info.request_uri = record.value("from", std::string{}); // TS 24.282 clause 13.2.1.2 step 5a
  }
  const std::string action = type == mcdata::TerminationType::ExtensionRequest        ? "extend"
                             : type == mcdata::TerminationType::AuthorisedUserRequest ? "release"
                             : type == mcdata::TerminationType::IntentToRelease       ? "release-intent"
                             : type == mcdata::TerminationType::ExtensionResponse     ? "extension-response"
                                                                                      : "terminate";
  auto headers = mcdata_fd_headers();
  if (!reason.empty())
    headers.push_back({"Reason", "SIP;cause=200;text=\"" + reason + "\""}); // TS 24.282 clause 13.2.6.3.2.1 step 3
  mcdata_request(
      "", {{mcdata::info_mime, mcdata::info_xml(info)}, {mcdata::signalling_mime, mcdata::encode(request)}}, headers,
      [this, id, action](const SipResponse &response) {
        mcdata_fd_last_result_ = {{"message_id", id},
                                  {"action", action},
                                  {"status", response.status_code},
                                  {"warning", std::string(get_header(response.headers, "Warning"))}};
        if (response.status_code >= 300) failure("FD " + action + ": " + std::to_string(response.status_code));
      },
      "MESSAGE");
  if (own && type == mcdata::TerminationType::Request) (*sent)["state"] = "terminating";
  event("mcdata_file_termination_requested", {{"message_id", id}, {"action", action}, {"file_url", request.file_url}});
}

//------------------------------------------------------------------------------
// TS 24.282 clause 10.2.1.2: an incoming FD SIGNALLING PAYLOAD, mandatory or non-mandatory download.
void Client::mcdata_file_incoming(const mcdata::Info &info, const mcdata::FdSignalling &request) {
  const auto id = mcdata::uuid_text(request.message);
  if (mcdata_files_.contains(id)) return; // retransmitted request
  Json file = {{"message_id", id},
               {"conversation_id", mcdata::uuid_text(request.conversation)},
               {"from", info.calling_user},
               {"group", info.calling_group},
               {"file_url", request.file_url},
               {"name", mcdata::metadata_name(request.metadata.value_or(std::string{}))},
               {"size", mcdata::metadata_size(request.metadata.value_or(std::string{})).value_or(0)},
               {"sha256", mcdata::metadata_sha256(request.metadata.value_or(std::string{}))},
               {"mandatory", request.mandatory_download},
               {"completion_requested", request.completion_requested},
               {"application_id", request.application_id ? Json(*request.application_id) : Json(nullptr)},
               {"state", "offered"},
               {"notified", Json::array()},
               {"download_status", nullptr}};
  // TS 24.282 clause 10.2.1.2.2 step 5 and clause 10.2.1.2.3 step 2: an unknown application ID is discarded.
  const auto applications = cfg_.at("mcdata").value("applications", std::vector<unsigned>{});
  if (request.application_id &&
      std::find(applications.begin(), applications.end(), *request.application_id) == applications.end()) {
    ++mcdata_discarded_;
    event("mcdata_file_discarded", {{"message_id", id}, {"application_id", *request.application_id}});
    return;
  }
  mcdata_files_[id] = file;
  mcdata_files_order_.push_back(id);
  while (mcdata_files_order_.size() > 20) {
    mcdata_files_.erase(mcdata_files_order_.front());
    mcdata_files_order_.pop_front();
  }
  event("mcdata_file_offered", {{"message_id", id},
                                {"from", info.calling_user},
                                {"group", info.calling_group},
                                {"mandatory", request.mandatory_download}});
  if (request.mandatory_download) {
    // TS 24.282 clause 10.2.1.2.2 steps 7 to 9: accept, download, and report completion when asked.
    schedule([this, id] { mcdata_file_accept(id); });
    return;
  }
  // TS 24.282 clause 10.2.1.2.3 step 3: wait for the user with timer TDU2 running.
  // TS 24.282 table F.2.3-1: TDU2 (FD non-mandatory download), default 60 s, configurable (mcdata.tdu2_ms).
  mcdata_tdu2_[id] = monotonic_ms() + cfg_.at("mcdata").value("tdu2_ms", 60000u);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 10.2.1.2.2 steps 7 to 9 and clause 10.2.1.2.3 step 8: accept, download and report.
void Client::mcdata_file_accept(const std::string &id) {
  const auto found = mcdata_files_.find(id);
  if (found == mcdata_files_.end()) throw std::runtime_error("no file transfer with that ID");
  auto &file = found->second;
  if (file.value("state", "") == "downloaded") return;
  mcdata_tdu2_.erase(id);
  file["state"] = "accepted";
  mcdata_fd_notify(file, mcdata::FdNotificationType::Accepted);
  if (!mcdata_download(file)) {
    file["state"] = "download-failed";
    return;
  }
  if (file.value("completion_requested", false)) mcdata_fd_notify(file, mcdata::FdNotificationType::Completed);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 10.2.1.2.3 steps 5 to 8: the user accepts, defers or rejects a non-mandatory FD request.
void Client::mcdata_file_decision(const std::string &id, const std::string &action) {
  const auto found = mcdata_files_.find(id);
  if (found == mcdata_files_.end()) throw std::runtime_error("no file transfer with that ID");
  auto &file = found->second;
  if (action == "accept") return mcdata_file_accept(id);
  const bool deferring = action == "defer";
  // TS 24.282 clause 10.2.1.2.3 steps 5 to 7: a deferral is only possible while TDU2 runs.
  if (deferring && !mcdata_tdu2_.contains(id)) {
    event("mcdata_file_deferral_ignored", {{"message_id", id}});
    return;
  }
  mcdata_tdu2_.erase(id);
  file["state"] = deferring ? "deferred" : "rejected";
  mcdata_fd_notify(file, deferring ? mcdata::FdNotificationType::Deferred : mcdata::FdNotificationType::Rejected);
}

} // namespace mcx
