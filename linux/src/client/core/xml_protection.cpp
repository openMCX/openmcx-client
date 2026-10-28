//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// XML integrity and confidentiality protection of SIP bodies under the client-server key (CSK), MCPTT and MCVideo.
//
// Specifications: TS 24.379 V19.7.0 (Rel-19), TS 24.281 V19.6.0 (Rel-19), TS 24.282 V19.7.0 (Rel-19),
// TS 33.180 V19.5.0 (Rel-19).

#include "client.hpp"
#include "xml_gate.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// Helper: the signalling protection of a service's service configuration document, when it has been read.
std::optional<ServiceProtection> Client::protection_of(const std::string &service) const {
  const auto found = service_protection_.find(service);
  return found == service_protection_.end() ? std::nullopt : std::optional(found->second);
}

//------------------------------------------------------------------------------
// Before the service configuration document is read, whether its protection applies to a message is not known: an
// XML body the client would protect is not sent then (throws), never in the clear instead.
void Client::require_protection_known(const std::string &service, const std::vector<BodyPart> &parts) const {
  if (protection_of(service)) return;
  if (std::any_of(parts.begin(), parts.end(), [](const BodyPart &part) { return xmlsec::is_xml_mime(part.content_type); }))
    throw std::runtime_error("the " + service + " service configuration document has not been read: signalling "
                             "protection unknown");
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.6.3.3.1: "If the <integrity-protection> element in the Service Configuration document [...] is
// set to "true" or no <integrity-protection> element is present in the Service Configuration document, then sending
// integrity protected content from the MCPTT client to the MCPTT server is enabled". The requirement does not depend
// on the keys: a missing or expired CSK leaves it required, and the message is then not sent (require_xml_key()).
// False until the MCPTT service configuration document has been read (require_protection_known()).
bool Client::integrity_protection() const {
  const auto protection = protection_of("mcptt");
  return protection && protection->integrity;
}

//------------------------------------------------------------------------------
// TS 24.281 clause 6.6.3.3.1: integrity protection from the MCVideo service configuration document, as above.
bool Client::mcvideo_integrity_protection() const {
  const auto protection = protection_of("mcvideo");
  return protection && protection->integrity;
}

//------------------------------------------------------------------------------
// TS 24.281 clause 6.6.2.3.1: confidentiality protection from the MCVideo service configuration document.
bool Client::mcvideo_xml_protection() const {
  const auto protection = protection_of("mcvideo");
  return protection && protection->confidentiality;
}

//------------------------------------------------------------------------------
// Helper: the gate of required XML protection (xml_gate::require_key) for a service's CSK, now.
void Client::require_xml_key(const keying::KeyMessage *csk, const char *service) {
  xml_gate::require_key(csk, service, static_cast<std::uint64_t>(std::time(nullptr)));
}

//------------------------------------------------------------------------------
// TS 33.180 clause 9.3.5, TS 24.379 clause 6.6.3.1, TS 24.281 clause 6.6.3.1 and TS 24.282 clause 6.5.3.1: "When
// integrity protection is enabled, all XML MIME bodies transported in SIP requests and responses are integrity
// protected" (maintainer decision M11): every XML MIME body is signed, not only the types each clause lists ("can be
// integrity protected"), except the signatures body itself.
bool Client::signable_part(const std::string &type) { return type != signed_mime && xmlsec::is_xml_mime(type); }

//------------------------------------------------------------------------------
// TS 24.379 clause 6.6.2.3.1: "If the <confidentiality-protection> element in the Service Configuration document
// [...] is set to "true" or no <confidentiality-protection> element is present [...], then sending confidentiality
// protected content from the MCPTT client to the MCPTT server is enabled", independently of the keys (as
// integrity_protection()).
bool Client::xml_protection() const {
  const auto protection = protection_of("mcptt");
  return protection && protection->confidentiality;
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
  // TS 24.281 clause 7.2.1: until the server holds the CSK, only the request uploading it is protected.
  const bool uploads = std::any_of(parts.begin(), parts.end(),
                                   [](const BodyPart &part) { return part.content_type == "application/mikey"; });
  // Its first service authorisation as well: an unread document is not a document without protection.
  require_protection_known("mcvideo", parts);
  if (!xml_gate::applies(parts, mcvideo_xml_protection(), mcvideo_integrity_protection(), mcvideo_xml_part,
                         signable_part))
    return parts;
  require_xml_key(mcvideo_csk_ ? &*mcvideo_csk_ : nullptr, "MCVideo");
  if (!mcvideo_authorized_ && !uploads) return parts;
  if (mcvideo_xml_protection())
    for (auto &part : parts)
      if (mcvideo_xml_part(part.content_type) && !xmlsec::is_protected(part.body)) {
        part.body = xmlsec::encrypt(part.body, mcvideo_xml_key(), xmlsec::signalling_profile("mcvideo"));
        ++xml_encrypted_;
      }
  if (mcvideo_integrity_protection() &&
      std::none_of(parts.begin(), parts.end(), [](const BodyPart &part) { return part.content_type == signed_mime; })) {
    std::vector<xmlsec::SignedBody> bodies;
    for (auto &part : parts)
      if (signable_part(part.content_type)) {
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
// (TS 33.180 clause 9.3.4.2 and clause 9.3.5); MCVideo goes to mcvideo_protect_outgoing(), MCData (its XML already
// encrypted by the MCData service) to mcdata_sign_outgoing().
// TS 24.379 clause 7.2.1 (TS 24.281 clause 7.2.1): a REGISTER for service authorisation, with confidentiality or
// integrity protection enabled, carries the access token and the client ID encrypted with the CSK (clause 6.6.2.3.3)
// and its mcptt-info (mcvideo-info) integrity protected with the CSK (clause 6.6.3.3.3); the REGISTER is told by its
// body, its Contact naming every service of the UE. The MCData REGISTER body is encrypted by the MCData service
// (mcdata_authorize) and signed here, and a REGISTER without service authorisation has no body to protect.
std::vector<BodyPart> Client::protect_outgoing(const SipTransmission &transmission, std::vector<BodyPart> parts) {
  if (unprotected_subscription(transmission, parts)) return parts;
  const auto carries = [&parts](const std::string &type) {
    return std::any_of(parts.begin(), parts.end(), [&type](const BodyPart &part) { return part.content_type == type; });
  };
  // TS 24.282 clause 6.5.3.3.1: the MCData service encrypts its own XML; its signatures are added here, last.
  if (transmission.method == "REGISTER" ? carries(mcdata::info_mime) : mcdata_transmission(transmission)) {
    // TS 24.282 clauses 6.5.2.3.1 and 6.5.3.3.1: required MCData protection without the MCData CSK (or past its
    // Expiry time): not sent.
    require_protection_known("mcdata", parts);
    if (xml_gate::applies(parts, mcdata_protects_xml(), mcdata_integrity_protection(), mcdata_xml_part, signable_part))
      require_xml_key(mcdata_csk_ ? &*mcdata_csk_ : nullptr, "MCData");
    return mcdata_sign_outgoing(std::move(parts));
  }
  if (transmission.method == "REGISTER") {
    if (carries(mcvideo_service.info_mime)) return mcvideo_protect_outgoing(std::move(parts));
    if (!carries(mcptt_mime)) return parts;
  } else if (mcvideo_transmission(transmission)) {
    return mcvideo_protect_outgoing(std::move(parts));
  }

  // An XML body whose protection is not known is not sent, the first service authorisation included: an unread
  // service configuration document is not one without protection (the configuration management subscription that
  // goes before it is unprotected_subscription()'s). TS 24.379 clause 7.2.1: until the server holds the CSK, only the
  // request uploading it is protected.
  require_protection_known("mcptt", parts);
  const bool uploads = carries("application/mikey");
  if (!xml_gate::applies(parts, xml_protection(), integrity_protection(), mcptt_xml_part, signable_part)) return parts;
  require_xml_key(csk_ ? &*csk_ : nullptr, "MCPTT");
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
// TS 24.484 clause 6.3.13.2.2 step d and TS 24.481 clause 6.3.13.2.1 step d (which the GKTP subscription of TS 24.481
// clause 7.7 follows): the CMC and the GMC protect the mcptt-info and resource-lists bodies of their subscription
// "if identity hiding is required", including the CSK in an application/mikey body; this client does not apply
// identity hiding, so the requests of these subscriptions are sent without XML protection, before and after the
// service authorisation alike (the service configuration document does not decide them). A message belongs to one of
// them by its dialog (the configuration, group document and group key subscriptions held), or, for the initial
// SUBSCRIBE whose dialog is not known yet, by the documents its resource list subscribes to
// (xml_gate::subscription_procedure). The GMK of a GKTP document stays protected by its MIKEY-SAKKE I_MESSAGE.
bool Client::unprotected_subscription(const SipTransmission &transmission, const std::vector<BodyPart> &parts) const {
  const auto &dialog = transmission.call_id;
  if (!dialog.empty() &&
      (dialog == config_subscription_ || dialog == key_subscription_ || dialog == mcvideo_key_subscription_ ||
       group_doc_subscriptions_.contains(dialog) || key_subscriptions_.contains(dialog) ||
       std::any_of(extra_key_subscriptions_.begin(), extra_key_subscriptions_.end(),
                   [&dialog](const auto &entry) { return entry.second == dialog; })))
    return true;
  return !transmission.response && transmission.method == "SUBSCRIBE" &&
         get_header(transmission.headers, "Event") == "xcap-diff" && xml_gate::subscription_procedure(parts);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 6.6.1.4 and TS 24.281 clause 6.6.1.4: before any other procedure, verify the signatures of a
// received SIP message (clause 6.6.3.4.2; failure raises "139 ...") and decrypt its XML elements
// (clause 6.6.2.4.2) under the MCPTT or MCVideo CSK.
std::vector<BodyPart> Client::protect_incoming(const SipTransmission &, std::vector<BodyPart> parts) {
  if (!csk_ && !mcvideo_csk_ && !mcdata_csk_) return parts;
  // The XPK a body names: this client's MCPTT CSK or its MCVideo CSK (TS 24.281 clause 6.6.2.2).
  const auto key_for = [this](std::optional<std::uint32_t> id) -> std::optional<xmlsec::Key> {
    if (id && csk_ && *id == csk_->csb_id) return xml_key();
    if (id && mcvideo_csk_ && *id == mcvideo_csk_->csb_id) return mcvideo_xml_key();
    return std::nullopt;
  };
  // TS 24.282 clause 6.5.3.2: signatures of the MCData server are under the MCData CSK (also while it is uploaded:
  // the server's first request can overtake its 200 (OK) to the service authorisation).
  const auto signing_key_for = [this, &key_for](std::optional<std::uint32_t> id) -> std::optional<xmlsec::Key> {
    if (const auto data = mcdata_integrity_key(true); id && data && *id == data->id) {
      require_mcdata_csk_in_use();
      return data;
    }
    return key_for(id);
  };
  // TS 24.379 clause 6.6.1.4 step 1 (TS 24.282 clause 6.5.1.4): the signatures under this client's CSK are
  // verified, then removed.
  if (const auto signatures = std::find_if(parts.begin(), parts.end(),
                                           [](const BodyPart &part) { return part.content_type == signed_mime; });
      signatures != parts.end())
    if (const auto key = signing_key_for(xmlsec::signature_key_id(signatures->body))) {
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
