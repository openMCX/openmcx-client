// MCData payload, signalling and XML protection, MCData client side: PCK, GMK and CSK protected payloads, payload
// authentication and XML confidentiality (member functions of Client).
//
// Specifications: TS 24.282 V19.7.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 33.180 clause 8.5.5 with TS 24.282 clause 6.6.3.2: DATA PAYLOADs signed with this user's
// KMS-provisioned ECCSI key as its MCData ID, and signatures verified on receipt.
bool Client::mcdata_authenticates() const {
  return mcdata_enabled() && cfg_.at("mcdata").value("authenticate_payload", false);
}

//------------------------------------------------------------------------------
// TS 33.180 clause 8.5.5 with TS 24.282 clause 6.6.3.2: sign a payload with this user's KMS-provisioned key and set
// bit 8 of its message type (TS 33.180 clause 8.5.1); unchanged when payload authentication is off.
std::string Client::mcdata_sign(const std::string &message) {
  if (!mcdata_authenticates()) return message;
  if (!mcdata_keys_) throw std::runtime_error("MCData key management client unavailable");
  const auto bytes =
      mcdata_keys_->authenticate(static_cast<std::uint8_t>(message.at(0)),
                                 std::span(reinterpret_cast<const std::uint8_t *>(message.data()), message.size()));
  ++mcdata_signed_;
  return {bytes.begin(), bytes.end()};
}

//------------------------------------------------------------------------------
// TS 33.180 clauses 8.5.1 and 8.5.5: a signed payload (bit 8) from sender: verified, then its original is returned.
std::string Client::mcdata_verify(const std::string &message, const std::string &sender) {
  if (!mcdata_keys_) throw std::runtime_error("no key material to verify a signed payload");
  const auto opened = mcdata_keys_->open_authenticated(
      sender, std::span(reinterpret_cast<const std::uint8_t *>(message.data()), message.size()));
  if ((opened.original.empty() ? 0 : opened.original[0] | 0x80) != opened.message_type)
    throw std::runtime_error("signed payload type mismatch");
  ++mcdata_verified_;
  return {opened.original.begin(), opened.original.end()};
}

//------------------------------------------------------------------------------
// TS 24.282 clauses 6.5.2, 6.5.3 and 6.6.3 with TS 33.180 clause 9.5: signalling protection with the
// MCData server under a CSK this client uploads at service authorisation (TS 24.282 clause 7.2.2 step 3).
bool Client::mcdata_protects_signalling() const {
  return mcdata_enabled() && cfg_.at("mcdata").value("protect_signalling", false);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.6.1: the CSK protecting signalling with the participating MCData function, once it accepted it.
const keying::KeyMessage *Client::mcdata_signalling_key() const {
  return mcdata_csk_accepted_ && mcdata_csk_ ? &*mcdata_csk_ : nullptr;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.5.2 with TS 33.180 clause 9.3: confidentiality of the sensitive XML content of
// TS 24.282 clause 4.6, under the CSK (XPK) and this client's MCData ID.
bool Client::mcdata_protects_xml() const {
  return mcdata_enabled() && cfg_.at("mcdata").value("confidentiality_protection", false);
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.5.2.2: the XPK (the CSK and CSK-ID) of XML confidentiality protection; while uploading the CSK
// it is used before the server has accepted it.
std::optional<xmlsec::Key> Client::mcdata_xml_key(bool uploading) const {
  if (!mcdata_protects_xml() || !mcdata_csk_ || !(mcdata_csk_accepted_ || uploading)) return std::nullopt;
  xmlsec::Key key;
  key.xpk = mcdata_csk_->key;
  key.id = mcdata_csk_->csb_id;
  key.user = mcdata_user();
  return key;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 4.6: the XML elements and URI attributes that confidentiality protection encrypts.
const xmlsec::Profile &Client::mcdata_xml_profile() {
  static const xmlsec::Profile profile{
      {"mcdata-access-token", "mcdata-request-uri", "mcdata-calling-user-id", "mcdata-called-party-id",
       "mcdata-calling-group-id", "alert-ind", "originated-by", "mcdata-client-id", "functional-alias-URI",
       "called-functional-alias-URI",
       // TS 24.282 annex D.4: the location reported, when confidentiality protection is required.
       "CurrentServingEcgi", "NeighbouringEcgi", "MbmsSaId", "MbsfnArea", "CL_5GMbsfsaArea", "CurrentServingNcgi",
       "CL_NeighbouringNcgi", "CL_BearingAndSpeed", "longitude", "latitude", "altitude", "horizontalaccuracy",
       "verticalaccuracy"},
      {{"entry", "uri"}, {"presence", "entity"}, {"affiliation", "group"}, {"functionalAlias", "functionalAliasID"}}};
  return profile;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 4.6: the MIME bodies whose XML content is confidentiality protected.
bool Client::mcdata_xml_part(const std::string &type) {
  return type == mcdata::info_mime || type == resource_lists_mime || type == "application/pidf+xml" ||
         type == mcdata::location_mime;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.5.2.3.1: encrypt the protected XML content of the MIME bodies under the XPK.
std::vector<BodyPart> Client::mcdata_encrypt_xml(std::vector<BodyPart> parts, const std::optional<xmlsec::Key> &key) {
  if (!key) return parts;
  for (auto &part : parts)
    if (mcdata_xml_part(part.content_type)) {
      part.body = xmlsec::encrypt(part.body, *key, mcdata_xml_profile());
      ++mcdata_xml_encrypted_;
    }
  return parts;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.5.2.4: decrypt the confidentiality protected XML content of received MIME bodies.
std::vector<BodyPart> Client::mcdata_decrypt_xml(std::vector<BodyPart> parts) {
  const auto key = mcdata_xml_key();
  for (auto &part : parts) {
    if (!mcdata_xml_part(part.content_type) || !xmlsec::is_protected(part.body)) continue;
    if (!key) throw std::runtime_error("encrypted XML without an XPK");
    part.body = xmlsec::decrypt(part.body, *key);
    ++mcdata_xml_decrypted_;
  }
  return parts;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.5.2.4: a SIP response with its protected XML content decrypted; an undecryptable body is
// reported and left as it is.
SipResponse Client::mcdata_opened_response(const SipResponse &raw) {
  auto response = raw;
  try {
    if (!response.parts.empty()) response.parts = mcdata_decrypt_xml(response.parts);
    // A single XML body is also kept in body, which is what most handlers read.
    if (mcdata_xml_part(response.content_type.substr(0, response.content_type.find(';'))) &&
        xmlsec::is_protected(response.body)) {
      response.body =
          mcdata_decrypt_xml({{response.content_type.substr(0, response.content_type.find(';')), response.body}})
              .front()
              .body;
    }
  } catch (const std::exception &error) {
    event("mcdata_xml_undecryptable", {{"status", response.status_code}, {"reason", error.what()}});
  }
  return response;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.6.3.2 with TS 33.180 clauses 8.5.4 and 9.5.3: seal the MCData Data signalling messages
// under the CSK (bit 7 of the message type set), then encrypt the XML content (TS 24.282 clause 6.5.2.3.1).
std::vector<BodyPart> Client::mcdata_seal_signalling(std::vector<BodyPart> parts) {
  const auto *key = mcdata_signalling_key();
  if (!key) return mcdata_encrypt_xml(std::move(parts), mcdata_xml_key());
  for (auto &part : parts) {
    if (part.content_type != mcdata::signalling_mime || part.body.empty()) continue;
    part.body = mcdata_protected_payload(part.body, *key);
    ++mcdata_signalling_sealed_;
  }
  return mcdata_encrypt_xml(std::move(parts), mcdata_xml_key());
}

//------------------------------------------------------------------------------
// TS 33.180 clause 9.5.3 over the media plane: the signalling bodies of MSRP SEND requests
// between this client and the MCData server are sealed with the CSK too.
std::vector<std::pair<std::string, std::string>>
Client::mcdata_seal_msrp(std::vector<std::pair<std::string, std::string>> parts) {
  if (!mcdata_signalling_key()) return parts;
  std::vector<BodyPart> body;
  for (auto &[type, value] : parts) body.push_back({type, value});
  body = mcdata_seal_signalling(std::move(body));
  parts.clear();
  for (auto &part : body) parts.emplace_back(part.content_type, part.body);
  return parts;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.6.3.2 with TS 33.180 clause 8.5.4: open the MCData Data signalling messages protected under the
// CSK; clear signalling while protection is in use is refused.
std::vector<BodyPart> Client::mcdata_open_signalling(std::vector<BodyPart> parts) {
  const auto *key = mcdata_signalling_key();
  if (!key) throw std::runtime_error("no CSK for MCData signalling");
  for (auto &part : parts) {
    if (part.content_type != mcdata::signalling_mime) continue;
    if (part.body.empty() || !(static_cast<std::uint8_t>(part.body[0]) & 0x40))
      throw std::runtime_error("clear signalling while signalling protection is in use");
    const std::span bytes(reinterpret_cast<const std::uint8_t *>(part.body.data()), part.body.size());
    if (keying::payload_key_id(bytes) != key->csb_id) throw std::runtime_error("signalling under another key");
    const auto opened = keying::open_payload(bytes, key->key, payload_profile());
    if (opened.plain.empty() || (opened.message_type & 0x3f) != (opened.plain[0] & 0x3f) || (opened.plain[0] & 0xc0))
      throw std::runtime_error("protected signalling type mismatch");
    part.body.assign(opened.plain.begin(), opened.plain.end());
    ++mcdata_signalling_opened_;
  }
  return parts;
}

//------------------------------------------------------------------------------
// Helper: the configured wire profile (message type and IEIs) of the key parameters of protected payloads.
keying::KeyParameterWireProfile Client::payload_profile() const {
  const auto &wire = cfg_.at("security").at("key_parameter_profile");
  return {wire.at("message_type").get<std::uint8_t>(), wire.at("algorithm").get<std::uint8_t>(),
          wire.at("key_parameters_iei").get<std::uint8_t>(), wire.at("encrypted_payload_iei").get<std::uint8_t>()};
}

//------------------------------------------------------------------------------
// Helper: drop the PCK contexts whose lifetime has ended.
void Client::expire_data_keys() {
  const auto now = static_cast<std::uint64_t>(std::time(nullptr));
  std::erase_if(mcdata_pcks_, [now](const auto &entry) { return entry.second.expires <= now; });
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.2.2.1 step 2c: an existing unexpired context with the peer, or a new PCK
// whose I_MESSAGE the request carries in an application/mikey body.
std::pair<keying::KeyMessage, std::string> Client::mcdata_pck_for(const std::string &peer) {
  expire_data_keys();
  for (const auto &[id, context] : mcdata_pcks_)
    if (context.peer == peer) return {context.key, {}};
  if (!mcdata_keys_) throw std::runtime_error("MCData key management client unavailable");
  auto [key, message] = mcdata_keys_->make_pck(peer);
  if (mcdata_pcks_.size() >= 256) throw std::runtime_error("MCData PCK context capacity reached");
  mcdata_pcks_[key.csb_id] = {
      key, peer, static_cast<std::uint64_t>(std::time(nullptr)) + cfg_.at("mcdata").value("pck_lifetime_s", 3600u)};
  return {key, message};
}

//------------------------------------------------------------------------------
// TS 33.180 clause 8.4 with TS 24.282 clause 6.6.3.2: the current GMK whose MC group IDs include the group.
keying::KeyMessage Client::mcdata_gmk_for(const std::string &group) const {
  const keying::KeyMessage *best = nullptr;
  const auto now = static_cast<std::uint64_t>(std::time(nullptr));
  for (const auto &[id, key] : group_keys_) {
    try {
      keying::require_active(key, group, now);
    } catch (const std::exception &) {
      continue;
    }
    if (!best || key.properties->activation_unix_seconds > best->properties->activation_unix_seconds) best = &key;
  }
  if (!best) throw std::runtime_error("no active group key for " + group);
  return *best;
}

//------------------------------------------------------------------------------
// TS 33.180 clause 8.5.1: the Protected Payload message of an MCData message under a key (a DPPK or the CSK); it
// inherits the message type with bit 7 set.
std::string Client::mcdata_protected_payload(const std::string &message, const keying::KeyMessage &key) const {
  keying::ProtectedPayload sealed;
  sealed.message_type = static_cast<std::uint8_t>(message.at(0)) | 0x40;
  sealed.unix_seconds = static_cast<std::uint64_t>(std::time(nullptr));
  sealed.dppk_id = key.csb_id;
  sealed.plain.assign(message.begin(), message.end());
  const auto bytes = keying::seal_payload(sealed, key.key, payload_profile());
  return {bytes.begin(), bytes.end()};
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.6.3.1: the whole DATA PAYLOAD message is protected.
std::string Client::mcdata_seal(const std::string &message, const keying::KeyMessage &key) {
  auto sealed = mcdata_protected_payload(message, key);
  ++mcdata_protected_sent_;
  return sealed;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.2.2.2 step 3: authenticate an I_MESSAGE from the sender and keep its PCK.
// A redelivered or retransmitted message repeats an I_MESSAGE already accepted.
void Client::mcdata_accept_pck(const std::string &encoded, const std::string &sender) {
  if (!mcdata_keys_) throw std::runtime_error("MCData key management client unavailable");
  expire_data_keys();
  if (const auto known = mcdata_pcks_.find(KeyClient::key_id(encoded));
      known != mcdata_pcks_.end() && known->second.peer == sender)
    return;
  auto [key, initiator] = mcdata_keys_->accept_pck(sender, encoded);
  if (mcdata_pcks_.size() >= 256) throw std::runtime_error("MCData PCK context capacity reached");
  mcdata_pcks_[key.csb_id] = {key, initiator,
                              static_cast<std::uint64_t>(std::time(nullptr)) +
                                  cfg_.at("mcdata").value("pck_lifetime_s", 3600u)};
  event("mcdata_pck_accepted", {{"from", sender}, {"pck_id", key.csb_id}});
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.6.3.2 with TS 33.180 clause 8.5.2.1: the DPPK a received Protected Payload message names:
// the PCK shared with the sender (PCK-ID, four most significant bits "0001", TS 24.282 clause 9.2.2.2.1 step 2c iii),
// or the GMK of the group the message was sent to.
const keying::KeyMessage &Client::mcdata_dppk(std::uint32_t id, const mcdata::Info &info) {
  const keying::KeyMessage *key = nullptr;
  if (id >> 28 == 1) {
    expire_data_keys();
    const auto found = mcdata_pcks_.find(id);
    if (found == mcdata_pcks_.end() || found->second.peer != info.calling_user || !info.calling_group.empty())
      throw std::runtime_error("no PCK shared with the sender");
    key = &found->second.key;
  } else if (id >> 28 == 0) {
    const auto found = group_keys_.find(id);
    if (found == group_keys_.end() || info.calling_group.empty()) throw std::runtime_error("unknown group key");
    keying::require_active(found->second, info.calling_group, static_cast<std::uint64_t>(std::time(nullptr)));
    key = &found->second;
  } else
    throw std::runtime_error("DPPK-ID is neither a PCK-ID nor a GMK-ID");
  return *key;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.6.3.2 with TS 33.180 clause 8.5.4: decrypt a Protected Payload message with its DPPK; it must
// carry a DATA PAYLOAD. Returns the clear DATA PAYLOAD message.
std::string Client::mcdata_open(const std::string &body, const mcdata::Info &info) {
  const std::span bytes(reinterpret_cast<const std::uint8_t *>(body.data()), body.size());
  const auto *key = &mcdata_dppk(keying::payload_key_id(bytes), info);
  auto opened = keying::open_payload(bytes, key->key, payload_profile());
  if (opened.message_type != (static_cast<std::uint8_t>(mcdata::MessageType::DataPayload) | 0x40))
    throw std::runtime_error("protected message is not a DATA PAYLOAD");
  ++mcdata_protected_received_;
  return {opened.plain.begin(), opened.plain.end()};
}

//------------------------------------------------------------------------------
// TS 33.180 clauses 8.5.1 and 8.5.4: the protected binary data representing a file, as a series of protected payloads.
std::string Client::mcdata_seal_file(const std::string &contents, const keying::KeyMessage &key) {
  const auto sealed =
      keying::seal_series(std::span(reinterpret_cast<const std::uint8_t *>(contents.data()), contents.size()),
                          protected_file_type, key.key, key.csb_id, payload_profile());
  ++mcdata_protected_sent_;
  return {sealed.begin(), sealed.end()};
}

//------------------------------------------------------------------------------
// TS 33.180 clauses 8.5.1 and 8.5.4: the clear file from the protected binary data representing it.
std::string Client::mcdata_open_file(const std::string &data, const mcdata::Info &info) {
  const std::span bytes(reinterpret_cast<const std::uint8_t *>(data.data()), data.size());
  const auto plain = keying::open_series(bytes, protected_file_type,
                                         mcdata_dppk(keying::payload_key_id(bytes), info).key, payload_profile());
  ++mcdata_protected_received_;
  return {plain.begin(), plain.end()};
}

} // namespace mcx
