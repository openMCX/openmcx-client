#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""The dispatcher's functional aliases (TS 24.379 clause 9A), on a running lab prepared with --private-call
--functional-alias (see prepare.py, which lets the dispatcher activate every alias of the lab and take one over):

  DSP-25 activation                                  DSP-28 a private call to an alias the dispatcher holds
  DSP-26 take-over from another user                 DSP-29 deactivation
  DSP-27 the alias presented on private and group    DSP-30 a private and a first-to-answer call to an alias
         calls

Drives the dispatcher through its web API, as the page does, and checks the other clients' status files.

  python3 lab/fa_scenario.py <lab> --project <project> [--url http://127.0.0.1:18080]
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
    me = lab.state().get("identity", {}).get("user")
    a, b, d = (lab.client(role).get("user_id") for role in ("client-a", "client-b", "client-d"))
    site = json.loads((args.lab / "generated" / "site.json").read_text())
    alias = {entry["id"].split(":", 1)[1].split(".alias@")[0]: entry["id"] for entry in site["functional_aliases"]}
    duty, car, commander = alias["duty-officer"], alias["patrol-car-12"], alias["incident-commander"]

    def call(role):
        return lab.state().get("call_state", "idle") if role == "dispatcher" else \
            lab.client(role).get("call_state", "idle")

    def private_between(role, peer):
        status = lab.state() if role == "dispatcher" else lab.client(role)
        return status.get("call_state") == "active" and status.get("call_type") == "private" and \
            status.get("private_peer") == peer

    def idle(*roles):
        return wait(lambda: all(call(r) == "idle" for r in ("dispatcher",) + roles), 20)

    def active(role, name):
        status = lab.state() if role == "dispatcher" else lab.client(role)
        return name in (status.get("active_functional_aliases") or [])

    def outcome(name, expected):
        last = lab.state().get("last_fa_result") or {}
        return last.get("status") == 200 and (last.get("outcomes") or {}).get(name) == expected

    # DSP-25: the dispatcher activates patrol-car-12 (TS 24.379 clause 9A.2.1.2); its status subscription
    # (clause 9A.2.1.1) comes first and the NOTIFY says "activated" (clause 9A.2.1.3).
    lab.act(action="fa", op="activate", aliases=[car])
    ok = wait(lambda: lab.state().get("functional_alias_subscription_result") == 200 and active("dispatcher", car) and
              outcome(car, "activated"), 20)
    check("DSP-25 the dispatcher activates patrol-car-12", ok, str(lab.state().get("last_fa_result")))

    # DSP-26: client-a holds duty-officer (one activation at a time); the dispatcher's activation is offered as
    # take-over-possible, and its take-over (clause 9A.2.1.2 step 6 d)) moves the alias; client-a is told.
    lab.tell("client-a", f"fa activate {duty}")
    wait(lambda: active("client-a", duty), 20)
    lab.act(action="fa", op="activate", aliases=[duty])
    ok = wait(lambda: duty in (lab.state().get("takeover_possible_functional_aliases") or []), 20)
    check("DSP-26a duty-officer, held by client-a, offered to the dispatcher for take-over", ok,
          str(lab.state().get("last_fa_result")))
    lab.act(action="fa", op="takeover", aliases=[duty])
    ok = wait(lambda: active("dispatcher", duty) and not active("client-a", duty), 20)
    check("DSP-26b the dispatcher takes duty-officer over; client-a no longer holds it", ok,
          f"{lab.state().get('active_functional_aliases')} a={lab.client('client-a').get('active_functional_aliases')}")

    # DSP-27: the dispatcher presents duty-officer (<functional-alias-URI>, TS 24.379 clause 11.1.1.2.1.1 and
    # clause 10.1.1.2.1.1); the called users see it (clause 11.1.1.3.1.1, clause 10.1.1.3.1.1).
    lab.act(action="fa-use", alias=duty)
    lab.act(action="private", target=b)
    ok = wait(lambda: private_between("dispatcher", b) and lab.client("client-b").get("calling_functional_alias") == duty,
              20)
    check("DSP-27a private call: client-b sees the dispatcher as duty-officer", ok,
          str(lab.client("client-b").get("calling_functional_alias")))
    lab.act(action="hangup")
    idle("client-b")
    lab.act(action="call")
    ok = wait(lambda: call("dispatcher") == "active" and
              lab.client("client-a").get("calling_functional_alias") == duty, 20)
    check("DSP-27b group call: client-a sees the dispatcher as duty-officer", ok,
          str(lab.client("client-a").get("calling_functional_alias")))
    lab.act(action="hangup")
    idle("client-a", "client-b")
    lab.act(action="fa-use", alias="")
    check("DSP-27c the dispatcher stops presenting the alias", wait(lambda: lab.state().get("functional_alias") == "", 5),
          str(lab.state().get("functional_alias")))

    # DSP-28: client-a calls duty-officer (clause 11.1.1.2.1.1 step 14c); the server resolves it to the dispatcher,
    # which is told the alias called (clause 11.1.1.4.1) and answers.
    lab.tell("client-a", f"private-fa {duty} manual")
    ok = wait(lambda: call("dispatcher") == "ringing" and lab.state().get("called_functional_alias") == duty, 20)
    lab.act(action="answer")
    ok = ok and wait(lambda: private_between("client-a", me), 20)
    check("DSP-28 a call to duty-officer reaches the dispatcher, told the alias called", ok,
          f"{call('dispatcher')} {lab.state().get('called_functional_alias')}")
    lab.tell("client-a", "hangup")
    idle("client-a")

    # DSP-29: the dispatcher deactivates patrol-car-12 and duty-officer (clause 9A.2.1.2 step 5, Expires 0).
    lab.act(action="fa", op="deactivate", aliases=[car, duty])
    ok = wait(lambda: not active("dispatcher", car) and not active("dispatcher", duty), 20)
    check("DSP-29 the dispatcher deactivates both aliases", ok, str(lab.state().get("active_functional_aliases")))

    # DSP-30: calls from the dispatcher to an alias: a private call to incident-commander, held by client-a, and a
    # first-to-answer call to patrol-car-12, held by client-d (clause 11.1.1.2.1.1 steps 14c and 15).
    lab.tell("client-a", f"fa activate {commander}")
    lab.tell("client-d", f"fa activate {car}")
    wait(lambda: active("client-a", commander) and active("client-d", car), 20)
    lab.act(action="private", target=commander, to_alias=True)
    ok = wait(lambda: private_between("dispatcher", a) and
              lab.client("client-a").get("called_functional_alias") == commander, 20)
    check("DSP-30a the dispatcher's call to incident-commander reaches client-a", ok,
          f"{lab.state().get('private_peer')} {lab.state().get('functional_alias_redirect')}")
    lab.act(action="hangup")
    idle("client-a")
    lab.act(action="first-to-answer", alias=car)
    ringing = wait(lambda: call("client-d") == "ringing", 20)
    lab.tell("client-d", "answer")
    ok = ringing and wait(lambda: private_between("dispatcher", d), 20)
    check("DSP-30b the dispatcher's first-to-answer call to patrol-car-12 reaches client-d", ok,
          f"{call('dispatcher')} {lab.state().get('private_peer')} d={call('client-d')}")
    lab.act(action="hangup")
    idle("client-d")
    lab.tell("client-a", f"fa deactivate {commander}")
    lab.tell("client-d", f"fa deactivate {car}")

    failed = [n for n, ok in results if not ok]
    print(f"{len(results) - len(failed)}/{len(results)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
