# Location reporting

The app takes part in the MC location procedures: TS 24.379 clause 13 for MCPTT, TS 24.282 clause 17 for MCData and
TS 24.281 clause 18 for MCVideo. The position and cell it reports are the phone's own: the platform's location (fused,
GPS or network provider) and the cell it is registered on, from TelephonyManager. The OpenMCX reference client only
simulates them.

## How it works

- **Configuration.** After service authorisation, each participating function (MCPTT, MCData, MCVideo server) sends
  a `<Configuration>` in a MESSAGE: what to report (coordinate, serving ECGI or NCGI, and so on), the minimum report
  interval, and the triggers. The app applies Full and Update configurations. It refuses one that is not from the
  service's PSI with 403.
- **Triggers** (Annex F.3.3), evaluated twice a second by a port of OpenMCX's reference `Reporter`:
  - cell, tracking area, PLMN and RAT changes, and entering or leaving a given cell;
  - periodic reports and travelled distance;
  - the signalling events: initial log-on, a non-emergency group or private call starting, configuration received;
  - entering or leaving a geographical area (polygons and ellipsoid arcs, with border widths);
  - the adaptive trigger.

  In an MCVideo emergency (an emergency or imminent-peril video call, or the user's emergency alert), the emergency
  information and triggers apply.
- **Reports** go as a single-bodied MESSAGE to the function that sent the configuration, with the fired TriggerIds, a
  `locTimestamp` and what was requested.
- **Requests** from the participating function are answered at once, with their RequestId.
- **Locating another user:** in the ▾ menu next to a person, **Where are they? (location)** sends an MCPTT
  location request (resource list plus `<Request>` with refresh). The answer, a report with `<mcptt-reporting-uri>`,
  appears under **Locations** on the Call tab with **Map**.
- **Identities** are written as Annex F.3 has them:
  - ECGI: MCC and MNC as six digits, then the 28-bit cell identity in binary;
  - NCGI: the same with the 36-bit NR cell identity;
  - tracking area: six digits and 16 bits; 5G tracking area: six hexadecimal digits;
  - a two-digit MNC is followed by a 0, as OpenMCX writes `001010` for 001/01.

| Part | Where |
|---|---|
| location-info XML, TS 23.032 coding, area geometry | `core/…/location/LocationInfo.kt` |
| Triggers and the minimumReportInterval timer | `core/…/location/Reporter.kt` |
| MESSAGE handling per service; reports and requests | `core/…/location/LocationClient.kt` |
| The phone's position and registered cell | `app/…/location/DeviceLocation.kt` |

Unit tests port the server's `tests/test_location.cpp`: the coordinate coding and area known answers, round trips of
every trigger kind in the three namespaces, and its whole trigger scenario, step by step with the same times.

## Tests on the phones

Lab `loc1`: `androidlab.py create … --location` (the server's location fixture). In it,
client-a (alice) may request and configure others' locations, and client-b (bob) shares his with everyone.

`tests/scenarios.py loc1 S13`: **18/18**.
- Both phones are configured by all three servers and report their position at log-on, and every report is accepted.
- Alice's request for bob is accepted. The server asks bob's phone, which answers at once, and alice's copy of bob's
  position is bob's report.
- Bob's request for alice is refused with 403 and warning 193.
- A group call's start is reported by both phones.

S2, S12 and short data still pass on the same lab. The scenario records no coordinates, only whether a report carried
one and distances between reports.

Not tested yet:
- **Cell identities:** neither test phone has a SIM card, so there is no registered cell. The cell and tracking-area
  identities and their triggers are implemented but have only been exercised in unit tests.
- **Area notices:** acted on since phase 6, below (emergency alert areas: ALERTS.md).

## Location rules (phase 6)

The same position (the fused fix, or the `location-position` override) also drives the location rules of the user
profiles and group documents (`client/LocationRuleProcedures.kt`, `location/LocationRules.kt`), ported from the Linux
client (`mcptt/location_rules.cpp`, `mcdata/location_rules.cpp`):

- **Where the rules come from.** The MCPTT user profile read at login; the MCData and MCVideo user profiles, read when
  the service is authorised (TS 24.484 §10.3.1A, §9.3.1A); the group documents' `<permitted-geographic-area>` and
  `<mandatory-geographic-area>` (TS 24.481 §7.2.4.2). Areas are polygons or ellipsoid arcs in TS 23.032 codes.
- **When.** On every position. An area is entered or left by a crossing between two positions: the first position
  after the rules are read only places the client. Leaving the service or logging off forgets the crossings.
- **Location based functional aliases** (TS 24.379 §9A.2.1.4, TS 24.282 §22.2.1.4, TS 24.281 §20.2.1.4): entering an
  area of `<LocationCriteriaForActivation>` (or leaving one of its exit areas) activates the alias; the deactivation
  criteria deactivate it. Each goes through the status change PUBLISH of §9A.2.1.2. While a criterion holds an alias
  and `<manual-deactivation-not-allowed-if-location-criteria-met>` is true, the user cannot deactivate it.
- **Rules based affiliation** (TS 24.379 §9.2.1.7.2, TS 24.282 §8.2.6.2): a `<RulesForAffiliation>` or
  `<RulesForDeaffiliation>` rule whose location criterion is met (an area crossed) and whose functional alias criterion
  is met (one of its aliases active, or none listed) affiliates to or de-affiliates from its group, by the PUBLISH of
  §9.2.1.2 / §8.2.2 that changes that group only. A rule with an alias criterion is also evaluated when one of its
  aliases becomes active, against the last position. An area with `<Speed>` or `<Heading>` is never met (no speed or
  heading is known). With `<manual-deaffiliation-not-allowed-if-affiliation-rules-are-met>`, the user cannot
  de-affiliate from a group a rule affiliated.
- **Group geographic areas** (TS 24.379 §9.2.1.7.3, MCPTT): outside a group's mandatory area the client de-affiliates,
  and affiliates again when back inside; outside the permitted or mandatory area an affiliation (the user's or a rule's)
  is refused.
- **Group geographic area notices** (TS 24.379 §12.1.1.4, TS 24.282 §16.2.1.4): the participating function's MESSAGE
  with `<group-geo-area-ind>` in `<anyExt>` and `<associated-group-id>` affiliates to (entered) or de-affiliates from
  (left) that group, the user's other groups kept.
- **On the phone:** an alias activated by location reads "Active · by location" in Settings; a group affiliated by a
  rule, or left for being outside its area, says so in the Call tab's group list, where a base group left stays listed
  as not affiliated.

## SEAL location reporting (TS 24.545)

`client/SealLocationClient.kt`, `location/SealLocation.kt`, as the Linux client's `core/seal_location.cpp`: besides the
MC services' location procedures, the client reports to the SEAL location management server (the provisioning's
`lms`, behind the HTTP proxy):

- §6.2.2.2.1: `GET <lms>/seal-lm/v1/users/<MCPTT ID>/configuration`, the periodic trigger and minimum interval, and the
  report and history URIs of the OpenMCX `<anyExt>`;
- §6.2.2.2.2: a `<report>` with the trigger and the current coordinate, POSTed on each period;
- §6.2.20.2: while offline (a test aid) the positions are stored; back online they go in one
  `<history-loc-result-report>`.

The access token then carries `3gpp:mc:location_management_service` (TS 33.180 table B.4.2.2-1); without it the
LMS refuses the configuration with 403. Started and stopped by `seal start|stop` (no UI).

## Debug commands

| Command | What it does |
|---|---|
| `location-position <lat> <lon>`, `location-position clear` | stand in for the phone's position: the reports and the rules follow it |
| `location-rules` | per service: the rules read, the aliases activated by location, the groups a rule affiliated or the area left, the group areas known, the last notes, the affiliations and active aliases |
| `affiliate <group> [mcptt\|mcdata\|mcvideo]`, `deaffiliate <group> [...]` | the user's affiliation status change (§9.2.1.2, §8.2.2), with the §9.2.1.7.3 and lock checks |
| `fa\|mcdata-fa\|mcvideo-fa deactivate <alias>` | refused while a location criterion holds and locks the alias |
| `seal start\|stop\|offline\|online\|status` | the SEAL location management client |
| `androidlab.py mock-location <lab> <client> <lat> <lon>` (host) | a fused fix through Android's test provider (redroid has no fix of its own) |

## Tests of the rules

- **Unit tests:** `LocationRulesTest` (the profile and group document cases of the server's `test_protocol.cpp`, each
  rule, the crossings, the locks, conflicting rules, MCData), `LocationRuleProceduresTest` (on a fake SIP core: the
  group geographic area notice, and a profile's rules driven by positions down to the PUBLISHes), `SealLocationTest`.
- **Lab:** `androidlab.py create … --location --functional-alias --location-rules`: incident-commander activated in
  one area, client-a's group left in another (MCPTT and MCData rules), and the group's mandatory area around them all
  (`androidlab.location_rules`). With `--location` each redroid client also gets a fused fix at the fixture's position.
- **Scenarios** S40 (aliases), S41 (affiliation rules), S42 (mandatory area), S43 (group area notices), S44 (emergency
  alert area), S45 (SEAL): 28 of 28 on lab `p6-a` (TEST_REPORT.md).
