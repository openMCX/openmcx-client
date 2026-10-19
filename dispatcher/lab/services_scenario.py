#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""The dispatcher's call services, on a running lab prepared with --private-call --mcdata (see prepare.py, which
gives the dispatcher the lab's widest MCPTT and MCData rights):

  DSP-17 first-to-answer call                       DSP-21 announced transfer
  DSP-18 ambient listening, remote and local        DSP-22 manual forwarding of a ringing call
  DSP-19 remotely initiated private and group call  DSP-23 conference participants of a group call
  DSP-20 unannounced transfer                       DSP-24 MCData emergency alert, own and another user's

Drives the dispatcher through its web API, as the page does, and checks the other clients' status files.

  python3 lab/services_scenario.py <lab> --project <project> [--url http://127.0.0.1:18080]
"""
import argparse
import json
import sys
from pathlib import Path

from scenario import Lab, check, results, wait


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("lab", type=Path)
    parser.add_argument("--project", required=True)
    parser.add_argument("--url", default="http://127.0.0.1:18080")
    args = parser.parse_args()
    lab = Lab(args.lab, args.project, args.url)
    wait(lambda: lab.state().get("registered") and lab.state().get("affiliated"), 90)
    identity = lab.state().get("identity", {})
    me, me_data, group = identity.get("user"), identity.get("mcdata_user"), identity.get("group")
    a, b = lab.client("client-a").get("user_id"), lab.client("client-b").get("user_id")

    def call(role):
        return lab.state().get("call_state", "idle") if role == "dispatcher" else \
            lab.client(role).get("call_state", "idle")

    def private_between(role, peer):
        status = lab.state() if role == "dispatcher" else lab.client(role)
        return status.get("call_state") == "active" and status.get("call_type") == "private" and \
            status.get("private_peer") == peer

    def idle(*roles):
        return wait(lambda: all(call(r) == "idle" for r in ("dispatcher",) + roles), 20)

    def outcome(kind):
        return lab.state().get("service_outcomes", {}).get(kind) == "success"

    # DSP-17: a first-to-answer call to client-a and client-b (TS 24.379 clause 11.1.1.2.1.1 steps 10 and 15);
    # client-b answers first and has the call, client-a's invitation ends.
    lab.act(action="first-to-answer", targets=[a, b])
    ringing = wait(lambda: call("client-a") == "ringing" and call("client-b") == "ringing", 20)
    lab.tell("client-b", "answer")
    ok = wait(lambda: private_between("dispatcher", b) and lab.state().get("first_to_answer") and
              call("client-a") == "idle", 20)
    check("DSP-17 first-to-answer call: both invitees ring, client-b answers and has the call", ringing and ok,
          f"{lab.state().get('call_state')} {lab.state().get('private_peer')} a={call('client-a')}")
    lab.act(action="hangup")
    idle("client-a", "client-b")

    # DSP-18: ambient listening (TS 24.379 clause 11.1.6.2.1.1): the dispatcher listens to client-a (remote-init),
    # then lets client-b listen to it (local-init); each released by the side the server allows.
    for kind, peer, own_role in (("remote", "client-a", "listening"), ("local", "client-b", "listened-to")):
        target = a if peer == "client-a" else b
        lab.act(action="ambient", kind=kind, target=target)
        # The listened-to user's call is not shown as a call (TS 24.379 clause 11.1.6): its ambient state says it.
        ok = wait(lambda own_role=own_role, peer=peer, kind=kind: (lab.state().get("ambient") or {}).get("role") ==
                  own_role and (own_role == "listening" or (lab.state().get("ambient") or {}).get("connected")) and
                  (lab.client(peer).get("ambient") or {}).get("type") == kind + "-init" and
                  (call("dispatcher") == "active" or own_role == "listened-to"), 20)
        check(f"DSP-18 ambient listening {kind}-init with {peer}: the dispatcher is {own_role}", ok,
              f"{lab.state().get('ambient')} / {lab.client(peer).get('ambient')}")
        if (lab.state().get("ambient") or {}).get("release_allowed"):
            lab.act(action="hangup")
        else:
            lab.tell(peer, "hangup")
        released = wait(lambda peer=peer: not lab.state().get("ambient") and not lab.client(peer).get("ambient"), 20)
        check(f"DSP-18 ambient listening {kind}-init released", released, str(lab.state().get("last_ambient")))
        idle(peer)

    # DSP-19: client-a asked to call client-b (TS 24.379 clause 11.1.7.2.1), then to call the group (clause 10.1.5.2.1);
    # the dispatcher is told of the outcome.
    lab.act(action="remote-call", remote=a, target=b, mode="auto")
    ok = wait(lambda: private_between("client-a", b) and outcome("remotely-initiated-private-call-response"), 20)
    check("DSP-19a client-a places the private call the dispatcher asked for, and reports success", ok,
          str(lab.state().get("service_outcomes")))
    lab.tell("client-a", "hangup")
    idle("client-a", "client-b")
    lab.act(action="remote-group-call", remote=a, target=group)
    ok = wait(lambda: call("client-a") == "active" and lab.client("client-a").get("call_type") == "prearranged" and
              outcome("remotely-initiated-group-call-response"), 20)
    check("DSP-19b client-a places the group call the dispatcher asked for, and reports success", ok,
          str(lab.state().get("service_outcomes")))
    lab.tell("client-a", "hangup")
    lab.act(action="hangup")
    idle("client-a", "client-b")

    # DSP-20: an unannounced transfer (TS 24.379 clause 11.1.8.2.1): the dispatcher's call with client-a goes to
    # client-b; the dispatcher leaves it.
    lab.act(action="private", target=a)
    wait(lambda: private_between("dispatcher", a), 20)
    lab.act(action="transfer", target=b)
    ok = wait(lambda: private_between("client-a", b) and call("dispatcher") == "idle" and
              outcome("transfer-private-call-response"), 20)
    check("DSP-20 the dispatcher's call with client-a transferred to client-b", ok,
          f"{call('dispatcher')} {lab.state().get('service_outcomes')}")
    lab.tell("client-a", "hangup")
    idle("client-a", "client-b")

    # DSP-21: an announced transfer (TS 24.379 clause 11.1.8.2.3): the call with client-a held, client-b called,
    # then the transfer completed; client-a's call replaces the dispatcher's with client-b.
    lab.act(action="private", target=a)
    wait(lambda: private_between("dispatcher", a), 20)
    lab.act(action="transfer", target=b, step="announced")
    ok = wait(lambda: (lab.state().get("announced_transfer") or {}).get("state") == "announcing" and
              call("client-b") == "active", 20)
    check("DSP-21a announced transfer: client-a held, the dispatcher talks to client-b", ok,
          str(lab.state().get("announced_transfer")))
    lab.act(action="transfer", target=b, step="complete")
    ok = wait(lambda: private_between("client-a", b) and call("dispatcher") == "idle" and
              (lab.state().get("announced_transfer") or {}).get("state") == "completed", 20)
    check("DSP-21b announced transfer completed: client-a and client-b in the call, the dispatcher out", ok,
          f"{call('dispatcher')} {lab.state().get('announced_transfer')}")
    lab.tell("client-a", "hangup")
    idle("client-a", "client-b")

    # DSP-22: client-a calls the dispatcher (manual answer); the dispatcher forwards the ringing call to client-b
    # (TS 24.379 clause 11.1.9.2.1); client-a is told 480 warning 175 and reaches client-b.
    lab.tell("client-a", f"private {me} manual")
    wait(lambda: call("dispatcher") == "ringing", 20)
    lab.act(action="forward", target=b)
    failure = lambda: lab.client("client-a").get("last_call_failure") or {}
    ok = wait(lambda: private_between("client-a", b) and outcome("forward-private-call-response"), 25)
    check("DSP-22 the ringing call forwarded to client-b: client-a reaches it", ok,
          f"{failure()} {lab.state().get('service_outcomes')}")
    lab.tell("client-a", "hangup")
    idle("client-a", "client-b")

    # DSP-23: in a group call, the dispatcher's conference event subscription (TS 24.379 clause 10.1.3.2) lists who
    # is connected.
    lab.act(action="call")
    ok = wait(lambda: call("dispatcher") == "active" and
              {me, a} <= set(lab.state().get("conference_participants", [])), 20)
    check("DSP-23 group call: the conference participants include the dispatcher and client-a", ok,
          str(lab.state().get("conference_participants")))
    lab.act(action="hangup")
    idle("client-a", "client-b")

    # DSP-24: the MCData emergency alert (TS 24.282 clause 16.2.1): the dispatcher's own, sent and cancelled, then
    # client-a's, cancelled by the dispatcher (clause 16.2.1.2, an authorised user).
    def received(role, origin):
        status = lab.state() if role == "dispatcher" else lab.client(role)
        return any(origin in users for users in (status.get("mcdata_alerts_received") or {}).values())

    a_data = json.loads((args.lab / "generated" / "client-a.json").read_text())["mcdata"]["user_id"]
    lab.act(action="mcdata-alert", on=True)
    ok = wait(lambda: lab.state().get("mcdata_alert_state", "").startswith("MDEA 3") and received("client-a", me_data),
              20)
    check("DSP-24a the dispatcher's MCData alert initiated and received by client-a", ok,
          str(lab.state().get("mcdata_alert_state")))
    lab.act(action="mcdata-alert", on=False)
    ok = wait(lambda: lab.state().get("mcdata_alert_state", "").startswith("MDEA 1") and
              not received("client-a", me_data), 20)
    check("DSP-24b the dispatcher's MCData alert cancelled", ok, str(lab.state().get("mcdata_alert_state")))
    # With its own alert running, the dispatcher cancels client-a's: its own stays (clause 16.2.1.2, the receipt
    # procedure is for the sender's own alert only).
    lab.act(action="mcdata-alert", on=True)
    wait(lambda: lab.state().get("mcdata_alert_state", "").startswith("MDEA 3"), 20)
    lab.tell("client-a", "mcdata-alert")
    ok = wait(lambda: received("dispatcher", a_data), 20)
    check("DSP-24c client-a's MCData alert listed by the dispatcher", ok, str(lab.state().get("mcdata_alerts_received")))
    alert_group = next((g for g, users in (lab.state().get("mcdata_alerts_received") or {}).items() if a_data in users),
                       "")
    lab.act(action="mcdata-alert", on=False, user=a_data, group=alert_group)
    ok = wait(lambda: not received("dispatcher", a_data) and
              lab.client("client-a").get("mcdata_alert_state", "").startswith("MDEA 1"), 20)
    check("DSP-24d the dispatcher cancels client-a's MCData alert, for both", ok,
          f"{lab.state().get('mcdata_alerts_received')} {lab.client('client-a').get('mcdata_alert_state')}")
    check("DSP-24e the dispatcher's own MCData alert is still initiated", wait(
        lambda: lab.state().get("mcdata_alert_state", "").startswith("MDEA 3"), 3), str(lab.state().get("mcdata_alert_state")))
    lab.act(action="mcdata-alert", on=False)
    wait(lambda: lab.state().get("mcdata_alert_state", "").startswith("MDEA 1"), 20)

    failed = [n for n, ok in results if not ok]
    print(f"{len(results) - len(failed)}/{len(results)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
