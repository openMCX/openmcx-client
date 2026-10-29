//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// MCData message store client and message notification client, control of communications storage, and file upload by
// reference to a stored object (member functions of Client).
//
// Specifications: TS 24.282 V19.7.0 (Rel-19), TS 23.282 V19.10.0 (Rel-19), RFC 3428 (SIP MESSAGE), RFC 9110 (HTTP).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.282 clause 21.1: the root of this user's box at the MCData message store,
// {serverRoot}/nms/v1/{storeName}/{boxId}.
std::string Client::msgstore_root() const {
  const auto &endpoints = cfg_.at("endpoints");
  if (!endpoints.contains("msgstore")) throw std::runtime_error("no MCData message store is configured");
  return endpoints.at("msgstore").get<std::string>() + "/nms/v1/" +
         url_encode(cfg_.value("msgstore", Json::object()).value("store", "mcdata")) + "/" + url_encode(mcdata_user());
}

//------------------------------------------------------------------------------
// Helper: a resource URL of the store or a bare object/folder ID, as a URL.
std::string Client::store_url(const std::string &kind, const std::string &id) const {
  if (id.starts_with("https://")) return id;
  return msgstore_root() + "/" + kind + "/" + id;
}

//------------------------------------------------------------------------------
// Helper: an HTTP request to the message store or notification server with the MCData access token in the
// Authorization header (TS 24.282 clause 21.2), the last one kept in the status.
HttpResponse Client::store_request(const std::string &method, const std::string &url, const Json &body,
                                   const std::string &content_type, const std::string &raw) {
  std::map<std::string, std::string> headers{{"Authorization", bearer()}};
  std::string payload = raw;
  if (!body.is_null()) payload = body.dump();
  if (!payload.empty()) headers["Content-Type"] = content_type;
  auto response = http_.request(method, url, payload, headers);
  Json last = {{"method", method}, {"url", url}, {"status", response.status}};
  try {
    if (!response.body.empty() && response.body.front() == '{') last["body"] = response.json();
  } catch (const std::exception &) {
    // A body that only looks like JSON is not kept in the status; the caller reads the response itself.
  }
  store_status_["last"] = last;
  return response;
}

//------------------------------------------------------------------------------
// Helper: keep the summary (folder, flags, mod-seq, attributes and payload parts) of a stored object.
void Client::store_remember(const Json &object) {
  const auto id = last_id(object.value("resourceURL", std::string{}));
  Json summary = {{"folder", object.value("parentFolderPath", std::string{})},
                  {"flags", Json::array()},
                  {"mod_seq", object.value("lastModSeq", 0)},
                  {"parts", object.value("payloadPart", Json::array()).size()}};
  if (object.contains("flags")) summary["flags"] = object.at("flags").value("flag", Json::array());
  for (const auto &attribute : object.value("attributes", Json::object()).value("attribute", Json::array()))
    if (!attribute.value("value", Json::array()).empty())
      summary[attribute.value("name", std::string{})] = attribute.at("value").at(0);
  for (const auto &part : object.value("payloadPart", Json::array()))
    summary["payload_hrefs"].push_back(part.value("href", std::string{}));
  store_status_["objects"][id] = summary;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 21.2.17.1 step 1: the search-based synchronization: the created objects since the last cursor, and
// the objects deleted since the last mod-seq; the known objects are then retrieved again (TS 24.282 clause 21.2.1.1).
void Client::store_sync() {
  const auto search = msgstore_root() + "/objects/operations/search";
  auto created = store_request(
      "POST", search,
      {{"selectionCriteria",
        {{"searchCriteria",
          {{"criterion",
            Json::array({{{"type", "CreatedObjects"}, {"value", store_status_.value("creation_cursor", "0")}}})}}}}}});
  if (created.status != 200) throw std::runtime_error("message store search: " + std::to_string(created.status));
  const auto list = created.json().at("objectList");
  for (const auto &object : list.value("object", Json::array())) store_remember(object);
  if (list.contains("creationCursor")) store_status_["creation_cursor"] = list.at("creationCursor");
  auto vanished = store_request(
      "POST", search,
      {{"selectionCriteria",
        {{"searchCriteria",
          {{"criterion", Json::array({{{"type", "VanishedObjects"},
                                       {"value", std::to_string(store_status_.value("mod_seq", 0))}}})}}}}}});
  if (vanished.status == 200) {
    const auto refs = vanished.json().at("objectReferenceList");
    for (const auto &ref : refs.value("objectReference", Json::array()))
      store_status_["objects"].erase(last_id(ref.value("resourceURL", std::string{})));
    store_status_["mod_seq"] = refs.value("lastModSeq", 0);
  }
  // Flags may have changed on objects already known.
  std::vector<std::string> known;
  for (const auto &item : store_status_["objects"].items()) known.push_back(item.key());
  for (const auto &id : known) {
    auto object = http_.request("GET", store_url("objects", id), {}, {{"Authorization", bearer()}});
    if (object.status == 200) store_remember(object.json().at("object"));
  }
  event("mcdata_msgstore_synchronised",
        {{"objects", store_status_["objects"].size()}, {"cursor", store_status_["creation_cursor"]}});
}

//------------------------------------------------------------------------------
// TS 24.282 clauses 21.2.19.1, 21.2.12A.1 and 21.2.22.1: a LongPolling channel at the notification server, a
// subscription of the store naming its callback, and a thread polling the channel.
void Client::store_subscribe() {
  if (store_channel_.running) throw std::runtime_error("already subscribed");
  const auto &endpoints = cfg_.at("endpoints");
  if (!endpoints.contains("notification")) throw std::runtime_error("no MCData notification server is configured");
  const auto channels = endpoints.at("notification").get<std::string>() + "/notificationchannel/v1/" +
                        url_encode(mcdata_user()) + "/channels";
  auto created = store_request("POST", channels,
                               {{"notificationChannel",
                                 {{"channelType", "LongPolling"},
                                  {"clientCorrelator", random_id()},
                                  {"applicationTag", "mcdata-msgstore"},
                                  {"channelData", {{"maxNotifications", 20}}},
                                  {"channelLifetime", 3600}}}});
  if (created.status != 201) throw std::runtime_error("notification channel: " + std::to_string(created.status));
  const auto channel = created.json().at("notificationChannel");
  store_channel_.resource = channel.at("resourceURL");
  store_channel_.channel_url = channel.at("channelData").at("channelURL");
  auto subscribed = store_request(
      "POST", msgstore_root() + "/subscriptions",
      {{"nmsSubscription",
        {{"callbackReference", {{"notifyURL", channel.at("callbackURL")}, {"callbackData", mcdata_user()}}},
         {"duration", 3600},
         {"clientCorrelator", random_id()}}}});
  if (subscribed.status != 201)
    throw std::runtime_error("message store subscription: " + std::to_string(subscribed.status));
  const auto subscription = subscribed.json().at("nmsSubscription");
  store_channel_.subscription = subscription.at("resourceURL");
  store_status_["subscription"] = subscription;
  store_status_["channel"] = channel;
  store_channel_.running = std::make_shared<std::atomic<bool>>(true);
  {
    std::lock_guard lock(store_mutex_);
    store_channel_.token = access_token();
    store_channel_.token_expires = expires_;
  }
  // TS 33.180 annex B.12: the poll thread takes the current access token at each poll (renewals included), and does
  // not poll while it has expired (it looks again every second, as after a failed poll).
  store_channel_.poller = std::thread([this, running = store_channel_.running, url = store_channel_.channel_url,
                                       ca = cfg_.at("tls").at("ca_file").get<std::string>(), map = address_map(cfg_),
                                       crls = crl_file(cfg_), proxy = http_proxy(cfg_)] {
    HttpClient client(ca, {}, map, HttpTrust::configured, crls);
    client.use_proxy(proxy); // TS 24.482 annex A.2.1.2
    while (*running) {
      std::string token;
      {
        std::lock_guard lock(store_mutex_);
        if (std::time(nullptr) <= store_channel_.token_expires) token = store_channel_.token;
      }
      if (token.empty()) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
        continue;
      }
      try {
        const auto response =
            client.request("POST", url + "?wait=8", "{}",
                           {{"Authorization", "Bearer " + token}, {"Content-Type", "application/json"}});
        if (response.status == 200) {
          const auto list = response.json().value("notificationList", Json::array());
          std::lock_guard lock(store_mutex_);
          for (const auto &notification : list) store_notifications_in_.push_back(notification);
        } else
          std::this_thread::sleep_for(std::chrono::seconds(1));
      } catch (const std::exception &) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
      }
    }
  });
  event("mcdata_msgstore_subscribed",
        {{"channel", store_channel_.resource}, {"subscription", store_channel_.subscription}});
}

//------------------------------------------------------------------------------
// TS 24.282 clauses 21.2.13A.1 and 21.2.20.1: delete the subscription to notifications and the notification channel,
// and stop polling.
void Client::store_unsubscribe() {
  if (!store_channel_.running) return;
  *store_channel_.running = false;
  // TS 24.282 clause 21.2.20.1 NOTE: the channel and the subscription go together (clause 21.2.13A.1).
  store_request("DELETE", store_channel_.subscription);
  store_request("DELETE", store_channel_.resource);
  if (store_channel_.poller.joinable()) store_channel_.poller.join();
  store_channel_ = StoreChannel{};
  store_status_.erase("channel");
  store_status_.erase("subscription");
}

//------------------------------------------------------------------------------
// TS 24.282 clause 21.2.22.1, on the HTTP response, step 1: the notifications keep the local copy in step.
void Client::store_tick() {
  std::vector<Json> arrived;
  {
    std::lock_guard lock(store_mutex_);
    arrived.swap(store_notifications_in_);
  }
  for (const auto &notification : arrived) {
    const auto list = notification.value("nmsEventList", Json::object());
    for (const auto &change : list.value("nmsEvent", Json::array())) {
      store_status_["events"] = store_status_.value("events", 0) + 1;
      if (change.contains("changedObject")) {
        const auto id = last_id(change.at("changedObject").value("resourceURL", std::string{}));
        auto &summary = store_status_["objects"][id];
        summary["flags"] = change.at("changedObject").value("flags", Json::object()).value("flag", Json::array());
        summary["mod_seq"] = change.at("changedObject").value("lastModSeq", 0);
        summary["notified"] = true;
      } else if (change.contains("deletedObject")) {
        store_status_["objects"].erase(last_id(change.at("deletedObject").value("resourceURL", std::string{})));
      }
    }
    Json kept = {{"index", list.value("index", 0)},
                 {"restart_token", list.value("restartToken", std::string{})},
                 {"events", list.value("nmsEvent", Json::array()).size()},
                 {"callback_data", list.value("callbackData", std::string{})}};
    store_status_["notifications"].push_back(kept);
    while (store_status_["notifications"].size() > 50) store_status_["notifications"].erase(0);
  }
}

//------------------------------------------------------------------------------
// TS 24.282 clause 21.2: the user command mcdata-msgstore <operation> ..., one message store client or message
// notification client procedure per operation.
void Client::msgstore_command(std::istringstream &words) {
  if (!mcdata_authorized_) throw std::runtime_error("MCData service authorisation required");
  std::string op;
  words >> op;
  std::vector<std::string> args;
  for (std::string word; words >> word;) args.push_back(word);
  const auto need = [&](std::size_t count) {
    if (args.size() < count) throw std::runtime_error("mcdata-msgstore " + op + ": missing arguments");
  };
  const auto refs = [&](std::size_t from, const char *kind, const char *key) {
    Json list = Json::array();
    for (std::size_t index = from; index < args.size(); ++index)
      list.push_back({{"resourceURL", store_url(kind, args[index])}});
    return Json{{key, list}};
  };
  if (op == "sync") return store_sync();
  if (op == "get") { // TS 24.282 clause 21.2.1.1
    need(1);
    const auto response = store_request("GET", store_url("objects", args[0]));
    if (response.status == 200) store_remember(response.json().at("object"));
    return;
  }
  if (op == "payload") { // the payload, kept as its size, hash and text
    need(1);
    return msgstore_payload(args);
  }
  if (op == "search") return msgstore_search(args); // TS 24.282 clause 21.2.2.1: name=value attributes, flag=<flag>
  if (op == "flag" || op == "unflag") {             // TS 24.282 clause 21.2.3.1 (one object)
    need(2);
    store_request(op == "flag" ? "PUT" : "DELETE", store_url("objects", args[0]) + "/flags/" + url_encode(args[1]));
    return;
  }
  if (op == "bulk-flag") { // TS 24.282 clause 21.2.3.1 (bulk): bulk-flag <flag> <id>...
    need(2);
    auto objects = refs(1, "objects", "objectReference");
    store_request("POST", msgstore_root() + "/objects/operations/bulkUpdate",
                  {{"bulkUpdate", {{"objects", objects}, {"flags", {{"flag", {args[0]}}}}, {"operation", "AddFlag"}}}});
    return;
  }
  if (op == "delete") { // TS 24.282 clause 21.2.4.1: one object, or several in bulk
    need(1);
    if (args.size() == 1)
      store_request("DELETE", store_url("objects", args[0]));
    else
      store_request("POST", msgstore_root() + "/objects/operations/bulkDelete",
                    {{"objectReferenceList", refs(0, "objects", "objectReference")}});
    return;
  }
  if (op == "folders") return msgstore_folders(); // TS 24.282 clause 21.2.23.1: the hierarchy
  if (op == "folder-search") {                    // TS 24.282 clause 21.2.11.1: folder-search <name>
    need(1);
    return msgstore_folder_search(args);
  }
  if (op == "folder") { // TS 24.282 clause 21.2.18.1: folder <id|root>
    need(1);
    return msgstore_folder(args);
  }
  if (op == "folder-create") { // TS 24.282 clause 21.2.8.1: folder-create <name> [parent id]
    need(1);
    return msgstore_folder_create(args);
  }
  if (op == "folder-delete") { // TS 24.282 clause 21.2.7.1
    need(1);
    store_request("DELETE", store_url("folders", args[0]));
    return;
  }
  if (op == "move" || op == "copy" || op == "move-folder" ||
      op == "copy-folder") { // TS 24.282 clauses 21.2.6.1 and 21.2.10.1
    need(2);
    const bool folders = op.ends_with("-folder");
    Json ref = {{"targetFolder", store_url("folders", args[0])}};
    ref[folders ? "sourceFolders" : "sourceObjects"] =
        refs(1, folders ? "folders" : "objects", folders ? "folderReference" : "objectReference");
    store_request("POST",
                  msgstore_root() + "/objects/operations/" + (op.starts_with("move") ? "moveToFolder" : "copyToFolder"),
                  {{"targetSourceRef", ref}});
    return;
  }
  if (op == "upload") { // TS 24.282 clause 21.2.15.1: upload <path> [folder id]
    need(1);
    return msgstore_upload(args);
  }
  if (op == "retrieve") { // TS 24.282 clause 21.2.24.1
    need(1);
    return msgstore_retrieve(args);
  }
  if (op == "subscribe") return store_subscribe();
  if (op == "unsubscribe") return store_unsubscribe();
  if (op == "restart") { // TS 24.282 clause 21.2.14A.1: restart the notification stream from an index
    need(1);
    return msgstore_restart(args);
  }
  throw std::runtime_error(
      "mcdata-msgstore sync|get|payload|search|flag|unflag|bulk-flag|delete|folders|folder|folder-search|"
      "folder-create|folder-delete|move|copy|move-folder|copy-folder|upload|retrieve|subscribe|unsubscribe|restart");
}

//------------------------------------------------------------------------------
// Helper: mcdata-msgstore payload: the payload of an object (or one payload part), kept as its size, hash and text.
void Client::msgstore_payload(const std::vector<std::string> &args) {
  const auto url = store_url("objects", args[0]) + (args.size() > 1 ? "/payloadParts/" + args[1] : "/payload");
  const auto response = http_.request("GET", url, {}, {{"Authorization", bearer()}});
  store_status_["payload"] = {
      {"object", args[0]},
      {"status", response.status},
      {"bytes", response.body.size()},
      {"sha256", sha256_hex(response.body)},
      {"content_type", response.headers.contains("content-type") ? response.headers.at("content-type") : ""}};
}

//------------------------------------------------------------------------------
// TS 24.282 clause 21.2.2.1: search the stored objects by name=value attributes and flag=<flag>.
void Client::msgstore_search(const std::vector<std::string> &args) {
  Json criteria = Json::array();
  for (const auto &argument : args) {
    const auto eq = argument.find('=');
    if (eq == std::string::npos) throw std::runtime_error("search criterion is name=value or flag=<flag>");
    if (argument.substr(0, eq) == "flag")
      criteria.push_back({{"type", "Flag"}, {"value", argument.substr(eq + 1)}});
    else
      criteria.push_back({{"type", "Attribute"}, {"name", argument.substr(0, eq)}, {"value", argument.substr(eq + 1)}});
  }
  const auto response =
      store_request("POST", msgstore_root() + "/objects/operations/search",
                    {{"selectionCriteria", {{"searchCriteria", {{"criterion", criteria}, {"operator", "And"}}}}}});
  Json found = Json::array();
  if (response.status == 200)
    for (const auto &entry : response.json().at("objectList").value("object", Json::array())) {
      store_remember(entry);
      found.push_back(last_id(entry.value("resourceURL", std::string{})));
    }
  store_status_["search"] = found;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 21.2.23.1: the folder hierarchy.
void Client::msgstore_folders() {
  const auto response =
      store_request("POST", msgstore_root() + "/folders/operations/search", {{"selectionCriteria", Json::object()}});
  if (response.status == 200) {
    Json folders = Json::object();
    for (const auto &folder : response.json().at("folderReferenceList").value("folderReference", Json::array()))
      folders[folder.value("path", std::string{})] = last_id(folder.value("resourceURL", std::string{}));
    store_status_["folders"] = folders;
  }
}

//------------------------------------------------------------------------------
// TS 24.282 clause 21.2.11.1: folder-search <name>.
void Client::msgstore_folder_search(const std::vector<std::string> &args) {
  store_request("POST", msgstore_root() + "/folders/operations/search",
                {{"selectionCriteria",
                  {{"searchCriteria", {{"criterion", Json::array({{{"type", "Name"}, {"value", args[0]}}})}}}}}});
}

//------------------------------------------------------------------------------
// TS 24.282 clause 21.2.18.1: folder <id|root>, the objects of a folder.
void Client::msgstore_folder(const std::vector<std::string> &args) {
  const auto response = store_request("GET", store_url("folders", args[0]));
  if (response.status == 200) {
    Json objects = Json::array();
    for (const auto &entry :
         response.json().at("folder").value("objects", Json::object()).value("objectReference", Json::array()))
      objects.push_back(last_id(entry.value("resourceURL", std::string{})));
    store_status_["folder"] = {
        {"id", args[0]}, {"path", response.json().at("folder").value("path", std::string{})}, {"objects", objects}};
  }
}

//------------------------------------------------------------------------------
// TS 24.282 clause 21.2.8.1: folder-create <name> [parent id].
void Client::msgstore_folder_create(const std::vector<std::string> &args) {
  Json folder = {{"name", args[0]}};
  if (args.size() > 1) folder["parentFolder"] = store_url("folders", args[1]);
  store_request("POST", msgstore_root() + "/folders", {{"folder", folder}});
}

//------------------------------------------------------------------------------
// TS 24.282 clause 21.2.15.1: upload <path> [folder id], a file stored as a new object.
void Client::msgstore_upload(const std::vector<std::string> &args) {
  std::ifstream file(args[0], std::ios::binary);
  if (!file) throw std::runtime_error("cannot read " + args[0]);
  std::string contents((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
  Json object = {{"attributes",
                  {{"attribute",
                    Json::array({{{"name", "Subject"}, {"value", {std::filesystem::path(args[0]).filename().string()}}},
                                 {{"name", "Direction"}, {"value", {"Out"}}}})}}}};
  if (args.size() > 1) object["parentFolder"] = store_url("folders", args[1]);
  const auto boundary = "nms-" + random_id();
  const auto body =
      "--" + boundary +
      "\r\nContent-Disposition: form-data; name=\"root-fields\"\r\nContent-Type: application/json\r\n\r\n" +
      Json{{"object", object}}.dump() + "\r\n--" + boundary +
      "\r\nContent-Disposition: form-data; name=\"attachments\"; filename=\"" +
      std::filesystem::path(args[0]).filename().string() + "\"\r\nContent-Type: application/octet-stream\r\n\r\n" +
      contents + "\r\n--" + boundary + "--\r\n";
  store_request("POST", msgstore_root() + "/objects", nullptr, "multipart/form-data; boundary=" + boundary, body);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 21.2.24.1: retrieve an object (its content fetched by the message store).
void Client::msgstore_retrieve(const std::vector<std::string> &args) {
  const auto response = store_request("POST", store_url("objects", args[0]) + "/retrieve", {{"empty", Json::object()}});
  if (response.status == 200) store_remember(response.json().at("object"));
}

//------------------------------------------------------------------------------
// TS 24.282 clause 21.2.14A.1: restart the notification stream from an index.
void Client::msgstore_restart(const std::vector<std::string> &args) {
  if (store_channel_.subscription.empty()) throw std::runtime_error("not subscribed");
  store_request("POST", store_channel_.subscription,
                {{"nmsSubscriptionUpdate",
                  {{"restartToken", store_status_.at("subscription").value("restartToken", std::string{})},
                   {"index", std::stoll(args[0])},
                   {"duration", 3600}}}});
}

//------------------------------------------------------------------------------
// TS 24.282 clauses 21.3.2.2 and 21.3.2.3: mcdata-msgstore-ctrl enable|disable [all-private] [all-group]
// [private=<ID>,...] [group=<ID>,...]; without <allow-store-comms-in-msgstore> in the MCData user profile the user
// is told and nothing is sent.
void Client::msgstore_control_command(std::istringstream &words) {
  if (!mcdata_authorized_) throw std::runtime_error("MCData service authorisation required");
  std::string action;
  words >> action;
  if (action != "enable" && action != "disable") throw std::runtime_error("mcdata-msgstore-ctrl enable|disable ...");
  require_mcdata_profile({"allow-store-comms-in-msgstore"});
  const bool enable = action == "enable";
  mcdata::Info info;
  info.request_type = "store-comms-in-msgstore-ctrl-req";
  info.client_id = client_id();
  mcdata::MsgStoreControl control;
  const auto split = [](const std::string &value) {
    std::vector<std::string> out;
    for (std::size_t start = 0; start <= value.size();) {
      const auto end = value.find(',', start);
      if (const auto id = value.substr(start, end == std::string::npos ? std::string::npos : end - start); !id.empty())
        out.push_back(id);
      if (end == std::string::npos) break;
      start = end + 1;
    }
    return out;
  };
  for (std::string word; words >> word;) {
    if (word == "all-private")
      info.store_all_private = enable;
    else if (word == "all-group")
      info.store_all_group = enable;
    else if (word.starts_with("private=")) {
      info.store_specific_private = action;
      (enable ? control.enable_private : control.disable_private) = split(word.substr(8));
    } else if (word.starts_with("group=")) {
      info.store_specific_group = action;
      (enable ? control.enable_groups : control.disable_groups) = split(word.substr(6));
    } else if (word == "none") { // test aid: a request naming nothing (warning 235)
    } else
      throw std::runtime_error("unknown mcdata-msgstore-ctrl argument " + word);
  }
  if (!mcdata_alias_in_use_.empty() && mcdata_aliases_active_.contains(mcdata_alias_in_use_))
    info.functional_alias = mcdata_alias_in_use_;
  std::vector<BodyPart> parts{{mcdata::info_mime, mcdata::info_xml(info)}};
  if (!info.store_specific_private.empty() || !info.store_specific_group.empty())
    parts.push_back({mcdata::msgstore_ctrl_mime, mcdata::msgstore_control_xml(control)});
  mcdata_request(
      {}, parts,
      {{"Accept-Contact", "*;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mcdata\";require;explicit"}},
      [this, action](const SipResponse &response) {
        store_status_["control"] = {{"action", action},
                                    {"status", response.status_code},
                                    {"warning", std::string(get_header(response.headers, "Warning"))},
                                    {"asserted", std::string(get_header(response.headers, "P-Asserted-Identity"))}};
      },
      "MESSAGE");
  event("mcdata_msgstore_control_sent", {{"action", action}});
}

//------------------------------------------------------------------------------
// TS 24.282 clause 10.2.2.1 steps 7 and 8 (network-stored file) with NOTE 3: a file already in the user's message store is
// uploaded to the media storage function by reference to the object's payload.
std::string Client::mcdata_upload_by_reference(const std::string &object, const std::string &group, std::string &name,
                                               std::uint64_t &size, std::string &digest) {
  const auto payload = store_url("objects", object) + "/payload";
  const auto stored = http_.request("GET", payload, {}, {{"Authorization", bearer()}});
  if (stored.status != 200) throw std::runtime_error("message store payload: " + std::to_string(stored.status));
  size = stored.body.size();
  digest = sha256_hex(stored.body);
  name = object;
  mcdata::Info info;
  info.request_type = group.empty() ? "one-to-one-fd" : "group-fd";
  info.request_uri = group;
  info.calling_user = mcdata_user();
  const auto boundary = "mcdata-" + random_id();
  const auto body = "--" + boundary + "\r\nContent-Type: " + mcdata::info_mime + "\r\n\r\n" + mcdata::info_xml(info) +
                    "\r\n--" + boundary + "\r\nContent-Type: message/external-body; access-type=URL; URL=\"" + payload +
                    "\"\r\n\r\n\r\n--" + boundary + "--\r\n";
  const auto response =
      http_.request("POST", mcdata_content_server() + "/mcdata/files", body,
                    {{"Content-Type", "multipart/mixed; boundary=" + boundary}, {"Authorization", bearer()}});
  if (response.status != 201) {
    mcdata_fd_last_result_ = {{"upload_status", response.status}, {"reference", payload}};
    throw std::runtime_error("file upload by reference: " + std::to_string(response.status));
  }
  const auto location = response.headers.find("location");
  if (location == response.headers.end()) throw std::runtime_error("file upload without a Location");
  event("mcdata_file_uploaded_by_reference", {{"object", object}, {"location", location->second}, {"bytes", size}});
  return location->second;
}

} // namespace mcx
