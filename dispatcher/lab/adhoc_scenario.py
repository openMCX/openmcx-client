#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""The dispatcher's ad hoc group calls (TS 24.379 clause 17), on a running lab prepared with --private-call --adhoc
(see prepare.py, which lets the dispatcher start them, emergency ones too, and change their participants):

  DSP-46 an ad hoc group call to client-a and client-b    DSP-48 an emergency ad hoc group call
  DSP-47 a participant removed and added again

Drives the dispatcher through its web API, as the page does, and checks the other clients' status files.

  python3 lab/adhoc_scenario.py <lab> --project <project> [--url http://127.0.0.1:18080]
"""
import argparse
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
    wait(lambda: lab.state().get("registered") and lab.state().get("authorized"), 90)
    a, b = (lab.client(role).get("user_id") for role in ("client-a", "client-b"))

    def call(role):
        return lab.state().get("call_state", "idle") if role == "dispatcher" else \
            lab.client(role).get("call_state", "idle")

    def adhoc(role):
        status = lab.state() if role == "dispatcher" else lab.client(role)
        return status.get("call_state") == "active" and status.get("call_type") == "adhoc"

    def hangup(*roles):
        lab.act(action="hangup")
        return wait(lambda: all(call(r) == "idle" for r in ("dispatcher",) + roles), 25)

    # DSP-46: an ad hoc group call to client-a and client-b (TS 24.379 clause 17.2.2.1.1 step 11); the server names
    # the ad hoc group in its answer (2xx step 4) and invites both (clause 17.2.2.1.2).
    lab.act(action="adhoc", targets=[a, b])
    ok = wait(lambda: adhoc("dispatcher") and adhoc("client-a") and adhoc("client-b"), 25)
    group = lab.state().get("call_group")
    check("DSP-46 an ad hoc group call reaches client-a and client-b; the server names the group", ok and group and
          lab.client("client-a").get("call_group") == group, f"{lab.state().get('last_call_failure')} group={group}")

    # DSP-47: client-b removed from the call and added again (TS 24.379 clause 17.2.6.1.1).
    lab.act(action="adhoc-modify", remove=[b])
    ok = wait(lambda: (lab.state().get("last_modify_result") or {}).get("status") == 200 and call("client-b") == "idle" and
              adhoc("client-a"), 20)
    lab.act(action="adhoc-modify", add=[b])
    ok = ok and wait(lambda: adhoc("client-b") and adhoc("dispatcher"), 25)
    check("DSP-47 client-b removed from the ad hoc group call, then added again", ok,
          f"{lab.state().get('last_modify_result')} b={call('client-b')}")
    hangup("client-a", "client-b")

    # DSP-48: an emergency ad hoc group call (clause 17.2.2.1.1 step 15, <adhoc-emergency-ind>); client-a is told.
    lab.act(action="adhoc", kind="emergency", targets=[a])
    ok = wait(lambda: adhoc("dispatcher") and adhoc("client-a") and lab.client("client-a").get("adhoc_priority") == "emergency",
              25)
    check("DSP-48 an emergency ad hoc group call: client-a sees its emergency", ok,
          f"{lab.state().get('last_call_failure')} a={lab.client('client-a').get('adhoc_priority')}")
    hangup("client-a")

    failed = [n for n, ok in results if not ok]
    print(f"{len(results) - len(failed)}/{len(results)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
