#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""The dispatcher's call actions beyond scenario.py, on a running lab prepared with --private-call --priority
--broadcast --chat (see prepare.py, which gives the dispatcher the lab's widest MCPTT rights):

  DSP-09 broadcast group call                        DSP-12 another user's emergency alert cancelled
  DSP-10 emergency and imminent peril raised and     DSP-13 chat group call joined, emergency raised
         brought back during a group call            DSP-14 call-back asked of a user, who calls back
  DSP-11 private call without floor control          DSP-15 call-back request cancelled
                                                     DSP-16 call-back asked of the dispatcher, fulfilled

Drives the dispatcher through its web API, as the page does, and checks the other clients' status files.

  python3 lab/actions_scenario.py <lab> --project <project> [--url http://127.0.0.1:18080]
"""
import argparse
import json
import sys
from pathlib import Path

from scenario import Lab, check, results, wait


def idle(lab, *clients):
    """The dispatcher and the clients back to idle."""
    return wait(lambda: lab.state().get("call_state", "idle") == "idle" and
                all(lab.client(c).get("call_state", "idle") == "idle" for c in clients), 20)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("lab", type=Path)
    parser.add_argument("--project", required=True)
    parser.add_argument("--url", default="http://127.0.0.1:18080")
    args = parser.parse_args()
    lab = Lab(args.lab, args.project, args.url)
    wait(lambda: lab.state().get("registered") and lab.state().get("affiliated"), 90)
    me = lab.state().get("identity", {}).get("user")
    a, b = lab.client("client-a").get("user_id"), lab.client("client-b").get("user_id")

    # DSP-09: a broadcast group call (TS 24.379 clause 10.1.2.2.1.1 with the broadcast indication).
    lab.act(action="call", kind="broadcast")
    ok = wait(lambda: lab.state().get("call_state") == "active" and lab.state().get("call_type") == "broadcast" and
              lab.client("client-a").get("call_state") == "active" and
              lab.client("client-a").get("call_type") == "broadcast", 20)
    check("DSP-09 broadcast group call from the dispatcher, client-a in it as a broadcast call", ok,
          f"{lab.state().get('call_type')}/{lab.client('client-a').get('call_type')}")
    lab.act(action="hangup")
    idle(lab, "client-a", "client-b")

    # DSP-10: a normal group call raised to emergency, back, to imminent peril, back (TS 24.379 clause 10.1.1.2.1.3 to
    # 10.1.1.2.1.5); the members follow the call's priority.
    lab.act(action="call")
    wait(lambda: lab.state().get("call_state") == "active" and lab.client("client-a").get("call_state") == "active", 20)
    for level, on, expected in (("emergency", True, "emergency"), ("emergency", False, "normal"),
                                ("imminent", True, "imminent"), ("imminent", False, "normal")):
        lab.act(action="priority", level=level, on=on)
        ok = wait(lambda expected=expected: lab.state().get("priority") == expected and
                  lab.client("client-a").get("priority") == expected and not lab.state().get("priority_pending"), 15)
        check(f"DSP-10 {level} {'raised' if on else 'cancelled'} during the call: dispatcher and client-a {expected}",
              ok, f"{lab.state().get('priority')}/{lab.client('client-a').get('priority')}")
    lab.act(action="hangup")
    idle(lab, "client-a", "client-b")

    # DSP-11: a private call without floor control (TS 24.379 clause 11.1.2.2).
    lab.act(action="private", target=b, no_floor=True)
    ok = wait(lambda: lab.state().get("call_state") == "active" and lab.state().get("floor_control") is False and
              lab.client("client-b").get("call_state") == "active" and
              lab.client("client-b").get("floor_control") is False, 20)
    check("DSP-11 private call without floor control from the dispatcher to client-b", ok,
          f"{lab.state().get('floor_control')}/{lab.client('client-b').get('floor_control')}")
    lab.act(action="hangup")
    idle(lab, "client-b")

    # DSP-12: client-a raises an emergency alert; the dispatcher cancels it (TS 24.379 clause 12.1.1.2).
    lab.tell("client-a", "alert on")
    ok = wait(lambda: a in lab.state().get("alerts", []), 20)
    check("DSP-12a client-a's emergency alert reaches the dispatcher", ok, str(lab.state().get("alerts")))
    lab.act(action="alert", on=False, user=a)
    ok = wait(lambda: a not in lab.state().get("alerts", []) and a not in lab.client("client-a").get("alerts", []), 20)
    check("DSP-12b the dispatcher cancels client-a's alert, for the dispatcher and client-a", ok,
          f"{lab.state().get('alerts')}/{lab.client('client-a').get('alerts')}")
    check("DSP-12c the dispatcher raised no alert of its own", me not in lab.state().get("alerts", []))

    # DSP-13: the dispatcher selects the chat group and joins its call (TS 24.379 clause 10.1.2.2.1.1), client-a
    # joins too; the dispatcher raises the chat call to emergency and back (clause 10.1.2.2.1.4).
    allowed = json.loads((args.lab / "generated" / "client-c.json").read_text())["mcptt"]["allowed_groups"]
    chat = next(g for g in allowed if "-chat@" in g)
    home = lab.state().get("identity", {}).get("group")
    lab.act(action="group", id=chat)
    wait(lambda: lab.state().get("identity", {}).get("group") == chat, 15)
    lab.act(action="call", chat=True)
    lab.tell("client-a", "group " + chat)
    wait(lambda: lab.client("client-a").get("affiliated"), 10)
    lab.tell("client-a", "chat")
    ok = wait(lambda: lab.state().get("call_state") == "active" and lab.state().get("call_type") == "chat" and
              lab.client("client-a").get("call_state") == "active" and lab.client("client-a").get("call_type") == "chat",
              20)
    check("DSP-13a chat group call joined by the dispatcher and client-a", ok,
          f"{lab.state().get('call_state')}/{lab.state().get('call_type')}")
    lab.act(action="priority", level="emergency", on=True)
    raised = wait(lambda: lab.state().get("priority") == "emergency" and not lab.state().get("priority_pending"), 15)
    lab.act(action="priority", level="emergency", on=False)
    back = wait(lambda: lab.state().get("priority") == "normal" and not lab.state().get("priority_pending"), 15)
    check("DSP-13b the chat call raised to emergency and back", raised and back, str(lab.state().get("priority")))
    lab.act(action="hangup")
    lab.tell("client-a", "hangup")
    idle(lab, "client-a")
    lab.tell("client-a", "group " + home)
    lab.act(action="group", id=home)
    wait(lambda: lab.state().get("identity", {}).get("group") == home and lab.state().get("affiliated"), 15)

    # DSP-14: the dispatcher asks client-b to call it back, high urgency (TS 24.379 clause 11.1.5.2.1); client-b
    # calls back (clause 11.1.5.2.3) and the dispatcher answers.
    lab.act(action="callback", target=b, urgency="high")
    ok = wait(lambda: lab.client("client-b").get("callback_requests_received", {}).get(me, {}).get("urgency") == "high"
              and lab.state().get("callback_requests_sent", {}).get(b) == "PCCB-I3: confirmed", 20)
    check("DSP-14a call-back request reaches client-b with its urgency, confirmed to the dispatcher", ok,
          f"{lab.state().get('callback_requests_sent')}")
    lab.tell("client-b", "callback-fulfil " + me)
    ringing = wait(lambda: lab.state().get("call_state") == "ringing", 20)
    lab.act(action="answer")
    ok = wait(lambda: lab.state().get("call_state") == "active" and lab.client("client-b").get("call_state") == "active"
              and b not in lab.state().get("callback_requests_sent", {}), 15)
    check("DSP-14b client-b calls back, the dispatcher answers and its request is done", ringing and ok,
          f"{lab.state().get('call_state')} {lab.state().get('callback_requests_sent')}")
    lab.act(action="hangup")
    idle(lab, "client-b")

    # DSP-15: a request cancelled (TS 24.379 clause 11.1.5.2.1, cancel-request).
    lab.act(action="callback", target=b)
    wait(lambda: me in lab.client("client-b").get("callback_requests_received", {}), 20)
    lab.act(action="callback-cancel", target=b)
    ok = wait(lambda: me not in lab.client("client-b").get("callback_requests_received", {}) and
              b not in lab.state().get("callback_requests_sent", {}), 20)
    check("DSP-15 call-back request cancelled, for client-b and the dispatcher", ok,
          f"{lab.state().get('callback_requests_sent')}")

    # DSP-16: client-a asks the dispatcher to call it back; the dispatcher does, client-a answers.
    lab.tell("client-a", f"callback {me} low")
    ok = wait(lambda: lab.state().get("callback_requests_received", {}).get(a, {}).get("urgency") == "low", 20)
    check("DSP-16a client-a's call-back request listed by the dispatcher", ok,
          str(lab.state().get("callback_requests_received")))
    lab.act(action="callback-fulfil", target=a)
    wait(lambda: lab.client("client-a").get("call_state") == "ringing", 20)
    lab.tell("client-a", "answer")
    ok = wait(lambda: lab.state().get("call_state") == "active" and lab.client("client-a").get("call_state") == "active"
              and a not in lab.state().get("callback_requests_received", {}), 15)
    check("DSP-16b the dispatcher calls client-a back, the request is done", ok,
          f"{lab.state().get('call_state')} {lab.state().get('callback_requests_received')}")
    lab.act(action="hangup")
    idle(lab, "client-a")

    failed = [n for n, ok in results if not ok]
    print(f"{len(results) - len(failed)}/{len(results)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
