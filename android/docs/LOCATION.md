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
- **Area notices:** the notices of §6.3.2.4 (group geographic areas, emergency alert areas) are not acted on yet.
