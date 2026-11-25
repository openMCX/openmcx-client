# Emergency alerts and emergency calls

The app raises and receives the emergency alerts of all three MC services:

- the MCPTT emergency alert (TS 24.379 §12.1);
- the MCData group emergency alert (TS 24.282 §16.2);
- the MCVideo emergency alert (TS 24.281 §11.2);
- the ad hoc group emergency alert of MCPTT and MCVideo (TS 24.379 §12.1A);
- the alert that entering an emergency alert area raises (TS 24.379 §12.1.1.6, TS 24.282 §16.2.1.5);
- the affiliation change that entering or leaving a group geographic area asks for (TS 24.379 §12.1.1.4, TS 24.282
  §16.2.1.4).

## On the phone

The Call tab has one **Emergency alert** section, after the calls and before the video sections.

- **Send emergency alert** raises the alert on the selected group (the one PTT calls) through every provisioned service
  in turn: MCPTT, then MCData, then video. **Cancel alert** takes it down everywhere it is up, ad hoc alert included.
  A service that refuses is named in the result line under the button, for example
  "Done on MCPTT, Video; MCData: not allowed".
- **▾** offers:
  - one service only: **MCPTT only**, **MCData only**, **Video only**, or **Cancel … alert** while it is up there;
  - **Ad hoc alert by criteria…**: ready-made audiences from the user's groups, such as "Everyone affiliated to
    operations" (`affiliated:<group>`) or "All members of operations" (`member:<group>`). It goes through MCPTT and
    MCVideo; the server resolves the criteria and names the alert's ad hoc group.
- An alert always carries where the phone is: an Emergency location report with the current coordinate. The location
  permission is asked for on the first alert.
- **Received alerts** show once per person, whatever services they came through, as a red banner at the top:
  - who sent it and the group, or "ad hoc" with its audience;
  - the services it came through, and where the person was;
  - **Map**, and **Cancel** for a group alert (the server refuses unless this user may cancel others' alerts).

  An ongoing notification per person goes with the banner.
- **Emergency alert areas.** When the server says the phone entered an emergency alert area, the phone raises an
  alert on MCPTT and MCData itself, on the selected group, unless the user's alert is already up, and says so in a
  notification. No user request is needed (§12.1.1.6 1)a)ii)). Leaving the area is notified too; the alert stays up
  until the user, or an authorised user, cancels it (§12.1.1.6 NOTE).
- **Group geographic areas.** A notice of entering a group's geographic area affiliates the phone to the group it names
  (`<associated-group-id>`), one of leaving it de-affiliates it from that group, on MCPTT and MCData (§12.1.1.4,
  TS 24.282 §16.2.1.4); the user's other groups are kept. The notice is shown as before (LOCATION.md, "Location rules").
- While the user's own alert is up, the location reporting of that service uses its emergency information and
  triggers.

## Protocol

| Alert | Request | Receipt and notices |
|---|---|---|
| MCPTT group alert | MESSAGE to the MCPTT PSI: mcptt-info with `<mcptt-request-uri>` (the group), `<alert-ind>` and the client ID, plus the Emergency location report; a cancellation of someone else's alert names them in `<originated-by>` | the server's receipt has `<alert-ind-rcvd>`; the group's members get `<alert-ind>` with the caller, group and location |
| MCData group alert | MESSAGE to the MCData PSI with the MCData ICSI: mcdata-info `<alert-ind>` (`mcdataBoolean`), `<originated-by>`, and the location in the MCData namespace | `<alert-ind-rcvd>` in `<anyExt>` |
| MCVideo group alert | as MCPTT, in mcvideo-info (the `McvideoService`) | as MCPTT |
| Ad hoc group alert | mcptt-info (or mcvideo-info) with `<anyExt>` `<adhoc-alert-ind>` and the criteria (`<call-participants-criterias>`; MCVideo `<call-participants-criteria>`); the 200 OK names the ad hoc group in `<mcptt-calling-group-id>`; cancelled with `<adhoc-alert-ind>` false to that group | `<adhoc-alert-ind-rcvd>`; recipients get `<adhoc-alert-ind>` with the caller, group, criteria and location |
| Area notice | — | MESSAGE from the PSI with `<emergency-alert-area-ind>` (or `<group-geo-area-ind>` and `<associated-group-id>`) in `<anyExt>`; a group area notice is followed by the affiliation PUBLISH changing that group |

The OpenMCX servers accept only a coordinate in the alert's location report, so the app sends the current coordinate
alone, in each service's location-info namespace.

| Part | Where |
|---|---|
| The alert fields of the info documents | `core/…/mcptt/McpttInfo.kt`, `core/…/mcdata/McdataInfo.kt`, `core/…/mcvideo/McvideoInfo.kt` |
| MCPTT and MCData alerts, ad hoc alerts (MCPTT and MCVideo), area notices and their affiliation changes | `core/…/alert/EmergencyAlerts.kt` |
| The location report of an alert, in each namespace | `core/…/mcvideo/EmergencyLocation.kt` |
| One alert over the services; received alerts once per person | `app/…/alert/AlertCenter.kt` |
| Call tab section, banners, notifications | `app/…/ui/Screens.kt`, `app/…/Notifications.kt` |

## Emergency and imminent peril calls (MCPTT)

TS 24.379 §6.2.8, as the MCVideo calls already had them:

- **Starting:** an emergency or imminent peril prearranged group call (▾ next to a group), and an emergency private
  call (▾ next to a person). The INVITE's mcptt-info carries `<emergency-ind>` true with `<alert-ind>` false (the
  server requires the alert indication with an emergency call; an alert is sent separately, above), or
  `<imminentperil-ind>` true; the request carries the provisioned `Resource-Priority` (`mcptt.resource_priority`).
- **In the call:** **Emergency** / **Cancel emergency**, and in a group call **Imminent peril** / **End imminent peril**,
  send a re-INVITE in the dialog with the new indication and Resource-Priority.
- **Following the server:** the state the server announces (the 200 OK, an incoming INVITE, its re-INVITEs and INFO)
  sets the call's state, shown as a red EMERGENCY or amber IMMINENT PERIL strip.
- **Location:** while an MCPTT call is in emergency or imminent peril, MCPTT location reporting uses the emergency
  information and triggers.

| Part | Where |
|---|---|
| `imminentperil-ind`, `resource_priority` | `core/…/mcptt/McpttInfo.kt`, `core/…/client/Config.kt` |
| Calls, re-INVITE, the announced state | `core/…/mcptt/McpttService.kt` (`groupCall(priority)`, `privateCall(emergency)`, `setPriority`) |
| Call tab ▾ items, call screen strip and buttons | `app/…/ui/Screens.kt` |

The lab's `--priority` now also passes the fixture's `--private-call`, which gives the private call policy: alice may
make emergency private calls to anyone, bob to alice only.

Phone scenario **S16**: an emergency group call (the state at both phones, speech, alice's location reporting in
emergency), the emergency cancelled in the call; an imminent peril group call in which bob raises and cancels an
emergency; an emergency private call. Result **18/18**, run with S1
and S2 (38/38), then S14 again (17/17).

## Debug commands

| Command | What it does |
|---|---|
| `alert on\|off [mcptt\|mcdata\|video]` | the Call tab's button, or one service |
| `adhoc-alert <criteria> [mcptt\|video]`, `adhoc-alert cancel` | an ad hoc alert, e.g. `affiliated:sip:operations@mcx.test` |
| `alert-cancel-of <person>` | cancel someone's alert (e.g. `bob`) |
| `alerts` | own alerts, received alerts per person, area notices (with the affiliation a group area notice made, and the time), the last result |
| `location-position <lat> <lon>`, `location-position clear` | stand in for the phone's position (area tests) |
| `group [group] emergency\|imminent`, `private <MCPTT ID> auto emergency` | an emergency or imminent peril call |
| `priority emergency\|imminent on\|off` | raise or cancel the state of the call in progress |

## Tests

- **Lab:** `lab/androidlab.py create … --mcvideo --location --priority`. `--priority` gives the groups the fixture's
  priority policy:
  - alice and bob may raise alerts; only alice may cancel them, her own and others';
  - bob's MCData alerts are allowed on another group only, so the server refuses them on operations.
- **Phone scenario S14** (`tests/scenarios.py <lab> S14`), on two phones:
  - alice's alert reaches bob once, through all three services;
  - bob's attempt to cancel it is refused, and alice cancels it everywhere;
  - bob's alert goes through MCPTT and video, with MCData named as refused; alice cancels it for him;
  - an MCData-only alert;
  - an ad hoc alert to everyone affiliated to operations, and its withdrawal;
  - alice moved into the lab's emergency alert area: the notice, the alert it raises on MCPTT and MCData, bob seeing
    it at the area's position, and the notice of leaving.

  Result: **17/17**. The report records no position of either phone.
- **Phone scenario S44** (phase 6, lab `--location --functional-alias --location-rules --priority`): alice moved into
  the emergency alert area; the server's notice; her MCPTT alert on the selected group raised by the phone with no
  user request; bob receives it at the area's position; the notice of leaving leaves it up. **4/4**, with S14 **17/17**
  on the same lab. S43 covers the group geographic area notices (LOCATION.md).
- **TS 37.579-2 6.6.4 on the real server** (`suite-android/results/android-mcptt-real-r1/6.6.4`, Fail "only after the
  user request"). What the app does matches the clause, as S14 and S44 show; the run could not show it. Step 1's
  stimulus `ss.send_message(kind='emergency_alert_area', entered=True)` has no real-server SS action ("not available
  in the openmcx adapter; handed to the operator"): no notice was sent (the step is Inconclusive, "the SS stimulus is
  not in the trace"). Step 2A then moved the DUT to the PIXIT `initial` position and asked for the alert 20 ms later,
  so the only alert in the trace was the user's. On the real server the notice follows a location report inside a
  configured emergency alert area (TS 24.379 §6.3.2.4). The suite's SS adapter has to stand in for step 1 by moving the
  DUT into that area (`location-position <area>`, the fixture's `hazard` at 48.05 N 2.05 E) and waiting for the
  notice, and for step 4 by moving it out. Then the automatic alert comes before step 2A. That is a suite change; the
  app needs none.
- **Unit tests:** `AlertInfoTest` covers the fields in each info document and the location in each namespace;
  `LocationRuleProceduresTest.groupGeographicAreaNotice` the group area notice's PUBLISHes.
- **Compose test:** `ScreensTest.emergencyAlertsAreSentAndShown` covers the section, the ▾ choices, the ad hoc audiences
  and the banner shown once per person.
