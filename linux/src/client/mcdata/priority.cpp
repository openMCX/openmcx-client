//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// MCData emergency and imminent peril group communication states of the MCData client (member functions of Client).
//
// Specifications: TS 24.282 V19.7.0 (Rel-19).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.282 table G.4.11-1: the name of an MCData emergency private communication (MDEPC) state.
const char *Client::mdepc_name(int state) {
  return state == 2   ? "MDEPC 2: emergency-pc-requested"
         : state == 3 ? "MDEPC 3: emergency-pc-granted"
                      : "MDEPC 1: emergency-pc-capable";
}

//------------------------------------------------------------------------------
// TS 24.282 table G.4.5-1: the name of an MCData emergency group communication (MDEGC) state.
const char *Client::mdegc_name(int state) {
  return state == 2   ? "MDEGC 2: emergency-communication-requested"
         : state == 3 ? "MDEGC 3: emergency-communication-granted"
                      : "MDEGC 1: emergency-gc-capable";
}

//------------------------------------------------------------------------------
// TS 24.282 table G.4.4-1: the name of an MCData emergency group (MDEG) state.
const char *Client::mdeg_name(int state) {
  return state == 2   ? "MDEG 2: in-progress"
         : state == 3 ? "MDEG 3: cancel-pending"
         : state == 4 ? "MDEG 4: confirm-pending"
                      : "MDEG 1: no-emergency";
}

//------------------------------------------------------------------------------
// TS 24.282 table G.4.8-1: the name of an MCData imminent peril group communication (MDIGC) state.
const char *Client::mdigc_name(int state) {
  return state == 2   ? "MDIGC 2: imminent-peril-communication-requested"
         : state == 3 ? "MDIGC 3: imminent-peril-communication-granted"
                      : "MDIGC 1: imminent-peril-gc-capable";
}

//------------------------------------------------------------------------------
// TS 24.282 table G.4.7-1: the name of an MCData imminent peril group (MDIG) state.
const char *Client::mdig_name(int state) {
  return state == 2   ? "MDIG 2: in-progress"
         : state == 3 ? "MDIG 3: cancel-pending"
         : state == 4 ? "MDIG 4: confirm-pending"
                      : "MDIG 1: no-imminent-peril";
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.2.8.1.15: the Resource-Priority value of a priority communication, from the MCData service
// configuration document.
std::vector<SipHeader> Client::mcdata_priority_headers(const char *level) const {
  const auto resources = service_priorities("mcdata");
  const auto value = resources.value(level, std::string{});
  if (value.empty()) return {};
  return {{"Resource-Priority", value}};
}

//------------------------------------------------------------------------------
// TS 24.282 clauses 6.2.8.1.4 and 6.2.8.1.5: the response to a request for a priority group
// communication grants it (SIP 2xx), or returns the states it moved from (SIP 4xx, 5xx or 6xx).
void Client::mcdata_priority_response(const SipResponse &response) {
  const bool pending_info = info_pending(response);
  if (response.status_code >= 300) {
    if (mdegc_ != 1) {
      mdegc_ = 1;
      if (mdeg_ == 4) mdeg_ = 1;
      if (mdea_ == 2) mdea_ = 1; // TS 24.282 clause 6.2.8.1.5 step 1c
    }
    if (mdigc_ != 1) {
      mdigc_ = 1;
      if (mdig_ == 4) mdig_ = 1;
    }
    return;
  }
  if (mdegc_ != 1) { // TS 24.282 clause 6.2.8.1.4 step 1
    mdeg_ = 2;
    mdegc_ = 3;
    mdigc_ = 1;
    mdig_ = 1;
    mcdata_session_emergency_ = true;
    mcdata_session_imminent_ = false;
    // TS 24.282 clause 6.2.8.1.4 step 1b: the alert is initiated unless a SIP INFO request is pending.
    if (mdea_ == 2 && !pending_info) mdea_ = 3;
  } else if (mdigc_ != 1 && !pending_info) { // TS 24.282 clause 6.2.8.1.4 step 2
    mdigc_ = 3;
    mdig_ = 2;
    mcdata_session_imminent_ = true;
  }
  // With warning 149 the imminent peril request waits for the SIP INFO request
  // of TS 24.282 clause 6.3.7.1.10, handled by mcdata_priority_info().
}

//------------------------------------------------------------------------------
// TS 24.282 clause 6.2.8.1.13: a SIP INFO request in the dialog corrects the priority of the
// communication, for instance an imminent peril request on a group that is
// already in an in-progress emergency state.
void Client::mcdata_priority_info(const mcdata::Info &info) {
  // Step 2: an emergency communication granted while its alert waits for confirmation (MDEA 2).
  if (mdegc_ == 3 && mdea_ == 2 && info.alert) mdea_ = *info.alert ? 3 : 1;
  // Step 3: an imminent peril request on a group in the in-progress emergency state is carried at the emergency
  // level instead.
  if ((mdigc_ == 2 || mdigc_ == 3) && info.imminent_peril == false && info.emergency == true) {
    mdig_ = 1;
    mdigc_ = 1;
    mdeg_ = 2;
    mcdata_session_imminent_ = false;
    mcdata_session_emergency_ = true;
  }
  // Step 4: this client's requests carry no <originated-by>, so a pending alert cancellation (MDEA 4) is settled.
  if (mdea_ == 4 && info.alert) mdea_ = *info.alert ? 3 : 1;
  status_["mcdata_priority_corrections"] = status_.value("mcdata_priority_corrections", 0u) + 1;
  event("mcdata_session_priority_corrected", {{"emergency", mdeg_ == 2}, {"imminent", mdig_ == 2}});
}

//------------------------------------------------------------------------------
// TS 24.282 clause 9.2.4.2.4 steps 4A and 4B: the in-progress states carried by a received
// INVITE or re-INVITE of a group session.
void Client::mcdata_group_priority_received(const mcdata::Info &info, bool in_session) {
  if (info.emergency == true) {
    mdeg_ = 2;
    mdig_ = 1;
    mdigc_ = 1;
    if (in_session) {
      mcdata_session_emergency_ = true;
      mcdata_session_imminent_ = false;
    }
  } else if (info.emergency == false) {
    mdeg_ = 1;
    mdegc_ = 1;
    if (in_session) mcdata_session_emergency_ = false;
  }
  if (info.imminent_peril == true) {
    mdig_ = 2;
    if (in_session) mcdata_session_imminent_ = true;
  } else if (info.imminent_peril == false && info.emergency != true) {
    mdig_ = 1;
    mdigc_ = 1;
    if (in_session) mcdata_session_imminent_ = false;
  }
}

} // namespace mcx
