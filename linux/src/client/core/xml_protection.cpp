// XML integrity and confidentiality protection of SIP bodies under the client-server key (CSK), MCPTT and MCVideo.
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 24.281 V19.6.0 (Rel-19), TS 33.180 V19.4.0 (Rel-19).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.379 clause 6.6.3.1: whether integrity protection of XML bodies is configured (and a CSK is held).
bool Client::integrity_protection() const {
  return keys_ && csk_ && cfg_.contains("security") && cfg_.at("security").value("integrity_protection", false);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.6.3.1: the XML MIME bodies that are integrity protected, all thirteen of the list (this client
// sends no MBMS or MBS usage info and no floor request body today).
bool Client::signable_part(const std::string &type) {
  static const std::set<std::string> types{mcptt_mime,
                                           "application/poc-settings+xml",
                                           resource_lists_mime,
                                           "application/vnd.3gpp.mcptt-mbms-usage-info+xml",
                                           "application/vnd.3gpp.mcptt-mbs-usage-info+xml",
                                           "application/vnd.3gpp.mcptt-location-info+xml",
                                           "application/vnd.3gpp.mcptt-affiliation-command+xml",
                                           "application/vnd.3gpp.mcptt-floor-request+xml",
                                           "application/conference-info+xml",
                                           "application/pidf+xml",
                                           "application/xcap-diff+xml",
                                           "application/simple-filter+xml",
                                           "application/vnd.3gpp.mcptt-regroup+xml"};
  return types.contains(type);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.6.2.1: whether confidentiality protection of XML content is configured (and a CSK is held).
bool Client::xml_protection() const {
  return keys_ && csk_ && cfg_.contains("security") && cfg_.at("security").value("confidentiality_protection", false);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 4.8: the MCPTT XML MIME bodies that carry sensitive application data to encrypt.
bool Client::mcptt_xml_part(const std::string &type) {
  return type == mcptt_mime || type == resource_lists_mime || type == "application/pidf+xml" ||
         type == mcptt_location_mime || type == "application/conference-info+xml";
}

//------------------------------------------------------------------------------
// TS 24.379 clause 4.8 and clause 6.6.2.3.3: the XML elements and the URI attributes that are encrypted.
const xmlsec::Profile &Client::mcptt_xml_profile() {
  static const xmlsec::Profile profile{{"mcptt-access-token",
                                        "mcptt-request-uri",
                                        "mcptt-calling-user-id",
                                        "mcptt-called-party-id",
                                        "mcptt-calling-group-id",
                                        "mcptt-client-id",
                                        "originated-by",
                                        "associated-group-id",
                                        "location-of-functional-alias-URI",
                                        "emergency-ind",
                                        "alert-ind",
                                        "imminentperil-ind",
                                        "CurrentServingEcgi",
                                        "NeighbouringEcgi",
                                        "MbmsSaId",
                                        "MbsfnArea",
                                        "CL_5GMbsfsaArea",
                                        "CurrentServingNcgi",
                                        "CL_NeighbouringNcgi",
                                        "CL_BearingAndSpeed",
                                        "longitude",
                                        "latitude",
                                        "altitude",
                                        "horizontalaccuracy",
                                        "verticalaccuracy"},
                                       {{"entry", "uri"},
                                        {"presence", "entity"},
                                        {"affiliation", "group"},
                                        {"functionalAlias", "functionalAliasID"},
                                        {"user", "entity"}}};
  return profile;
}

//------------------------------------------------------------------------------
// Helper: whether a SIP transmission belongs to MCData (protected by the MCData service, not here). An MCData request
// carries the MCData ICSI; an answer to the MCData server names it.
bool Client::mcdata_transmission(const SipTransmission &transmission) const {
  if (mcdata_enabled() && (transmission.request_uri == mcdata_service() || transmission.to_uri == mcdata_service() ||
                           (transmission.response && transmission.from_uri == mcdata_service())))
    return true;
  return std::any_of(transmission.headers.begin(), transmission.headers.end(),
                     [](const SipHeader &header) { return header.value.find("icsi.mcdata") != std::string::npos; });
}

//------------------------------------------------------------------------------
// Helper: whether a SIP transmission is an MCVideo request or response: its XML is not protected under the MCPTT
// CSK, which the MCVideo server does not hold, but under the MCVideo CSK (mcvideo_protect_outgoing()).
bool Client::mcvideo_transmission(const SipTransmission &transmission) const {
  if (mcvideo_enabled() &&
      (transmission.request_uri == mcvideo_service_uri() || transmission.to_uri == mcvideo_service_uri() ||
       (transmission.response && transmission.from_uri == mcvideo_service_uri())))
    return true;
  // The service a request asks for (P-Preferred-Service), not the feature tags every
  // Contact of this UE lists.
  return std::any_of(transmission.headers.begin(), transmission.headers.end(), [](const SipHeader &header) {
    return (header.name == "P-Preferred-Service" || header.name == "P-Asserted-Service") &&
           header.value == mcvideo_service.icsi;
  });
}

//------------------------------------------------------------------------------
// TS 24.281 clause 6.6.2.1: the MCVideo XML MIME bodies that carry sensitive application data to encrypt.
bool Client::mcvideo_xml_part(const std::string &type) {
  return type == mcvideo_service.info_mime || type == resource_lists_mime || type == "application/pidf+xml" ||
         type == mcvideo_service.location_mime || type == "application/conference-info+xml";
}

//------------------------------------------------------------------------------
// TS 24.281 clause 6.6.2.3.1 and clause 6.6.3.3.1: the MCVideo client encrypts the XML elements and signs the XML
// bodies of an outgoing MCVideo request under the MCVideo CSK.
std::vector<BodyPart> Client::mcvideo_protect_outgoing(std::vector<BodyPart> parts) {
  if (!mcvideo_csk_) return parts;
  // TS 24.281 clause 7.2.1: until the server holds the CSK, only the request uploading it is protected.
  const bool uploads = std::any_of(parts.begin(), parts.end(),
                                   [](const BodyPart &part) { return part.content_type == "application/mikey"; });
  if (!mcvideo_authorized_ && !uploads) return parts;
  if (xml_protection())
    for (auto &part : parts)
      if (mcvideo_xml_part(part.content_type) && !xmlsec::is_protected(part.body)) {
        part.body = xmlsec::encrypt(part.body, mcvideo_xml_key(), xmlsec::signalling_profile("mcvideo"));
        ++xml_encrypted_;
      }
  if (integrity_protection() &&
      std::none_of(parts.begin(), parts.end(), [](const BodyPart &part) { return part.content_type == signed_mime; })) {
    std::vector<xmlsec::SignedBody> bodies;
    for (auto &part : parts)
      if (signable_part(part.content_type) || mcvideo_xml_part(part.content_type)) {
        if (part.content_id.empty()) part.content_id = random_id().substr(0, 16) + "@mcx";
        bodies.push_back({part.content_id, part.body});
      }
    // TS 24.281 clause 6.6.3.3.3: the signatures in the application/vnd.3gpp.mcptt-signed+xml of TS 24.379.
    if (!bodies.empty()) {
      parts.push_back({signed_mime, xmlsec::sign(bodies, mcvideo_xml_key(), "urn:3gpp:ns:mcpttSignedDoc:1.0")});
      ++xml_signed_;
    }
  }
  return parts;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.6.1.2 and clause 6.6.1.3: just before sending, the MCPTT client encrypts the XML elements
// (clause 6.6.2.3.1) and signs the XML bodies (clause 6.6.3.3.1) of a SIP request or response under the CSK
// (TS 33.180 clause 9.3.4.2 and clause 9.3.5); MCVideo goes to mcvideo_protect_outgoing(), MCData is left alone.
std::vector<BodyPart> Client::protect_outgoing(const SipTransmission &transmission, std::vector<BodyPart> parts) {
  if ((!xml_protection() && !integrity_protection()) || transmission.method == "REGISTER" ||
      mcdata_transmission(transmission))
    return parts;
  if (mcvideo_transmission(transmission)) return mcvideo_protect_outgoing(std::move(parts));
  // TS 24.379 clause 7.2.1: until the server holds the CSK, only the request uploading it is protected.
  const bool uploads = std::any_of(parts.begin(), parts.end(),
                                   [](const BodyPart &part) { return part.content_type == "application/mikey"; });
  if (!authorized_ && !uploads) return parts;
  if (xml_protection())
    for (auto &part : parts)
      if (mcptt_xml_part(part.content_type) && !xmlsec::is_protected(part.body)) {
        part.body = xmlsec::encrypt(part.body, xml_key(), mcptt_xml_profile());
        ++xml_encrypted_;
      }
  if (integrity_protection() &&
      std::none_of(parts.begin(), parts.end(), [](const BodyPart &part) { return part.content_type == signed_mime; })) {
    std::vector<xmlsec::SignedBody> bodies;
    for (auto &part : parts)
      if (signable_part(part.content_type)) {
        if (part.content_id.empty()) part.content_id = random_id().substr(0, 16) + "@mcx";
        bodies.push_back({part.content_id, part.body});
      }
    if (!bodies.empty()) {
      parts.push_back({signed_mime, xmlsec::sign(bodies, xml_key(), "urn:3gpp:ns:mcpttSignedDoc:1.0")});
      ++xml_signed_;
    }
  }
  return parts;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.6.1.4 and TS 24.281 clause 6.6.1.4: before any other procedure, verify the signatures of a
// received SIP message (clause 6.6.3.4.2; failure raises "139 ...") and decrypt its XML elements
// (clause 6.6.2.4.2) under the MCPTT or MCVideo CSK.
std::vector<BodyPart> Client::protect_incoming(const SipTransmission &, std::vector<BodyPart> parts) {
  if (!csk_ && !mcvideo_csk_) return parts;
  // The XPK a body names: this client's MCPTT CSK or its MCVideo CSK (TS 24.281 clause 6.6.2.2).
  const auto key_for = [this](std::optional<std::uint32_t> id) -> std::optional<xmlsec::Key> {
    if (id && csk_ && *id == csk_->csb_id) return xml_key();
    if (id && mcvideo_csk_ && *id == mcvideo_csk_->csb_id) return mcvideo_xml_key();
    return std::nullopt;
  };
  // TS 24.379 clause 6.6.1.4 step 1: the signatures under this client's CSK are verified, then removed.
  if (const auto signatures = std::find_if(parts.begin(), parts.end(),
                                           [](const BodyPart &part) { return part.content_type == signed_mime; });
      signatures != parts.end())
    if (const auto key = key_for(xmlsec::signature_key_id(signatures->body))) {
      std::vector<xmlsec::SignedBody> bodies;
      for (const auto &part : parts)
        if (part.content_type != signed_mime && !part.content_id.empty())
          bodies.push_back({part.content_id, part.body});
      try {
        (void)xmlsec::verify(signatures->body, bodies, *key);
      } catch (const std::exception &error) {
        throw std::runtime_error(std::string("139 ") + error.what());
      }
      ++xml_verified_;
      parts.erase(signatures);
    }
  for (auto &part : parts) {
    if ((!mcptt_xml_part(part.content_type) && !mcvideo_xml_part(part.content_type)) ||
        !xmlsec::is_protected(part.body))
      continue;
    // Content under the MCData CSK is opened by the MCData service.
    const auto key = key_for(xmlsec::key_id(part.body));
    if (!key) continue;
    part.body = xmlsec::decrypt(part.body, *key);
    ++xml_decrypted_;
  }
  return parts;
}

} // namespace mcx
