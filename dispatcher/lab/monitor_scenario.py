#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""The dispatcher monitoring several groups (TS 24.379 clause 9.2.1.2: affiliated to groups besides the selected one),
on a running lab prepared with --private-call --mcdata:

  DSP-65 two more groups affiliated besides the selected one   DSP-67 an emergency alert on a monitored group
  DSP-66 a call on a monitored group reaches the dispatcher    DSP-68 a monitored group selected; monitoring stopped
  DSP-69 an alert on the selected group cancelled during a call on a monitored group

Drives the dispatcher through its web API, as the page does, and checks the other clients' status files.

  python3 lab/monitor_scenario.py <lab> --project <project> [--url http://127.0.0.1:18080]
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
    selected = lab.state().get("identity", {}).get("group", "")
    domain = selected.split("@", 1)[1]
    # operations-parallel: the dispatcher and client-d; operations-chat: client-a too.
    parallel, chat = f"sip:operations-parallel@{domain}", f"sip:operations-chat@{domain}"
    alice = json.loads((args.lab / "generated" / "client-a.json").read_text())["mcptt"]["user_id"]

    def affiliation(group):
        return (lab.state().get("affiliations") or {}).get(group)

    def idle(role):
        return (lab.state() if role == "dispatcher" else lab.client(role)).get("call_state", "idle") == "idle"

    # DSP-65: the dispatcher monitors operations-parallel and operations-chat; the server affiliates it to both and
    # to the selected group still.
    lab.act(action="monitor", groups=[parallel, chat])
    ok = wait(lambda: affiliation(parallel) == "affiliated" and affiliation(chat) == "affiliated" and
              affiliation(selected) == "affiliated" and lab.state().get("affiliated"), 20)
    check("DSP-65 operations-parallel and operations-chat affiliated besides the selected group", bool(ok),
          str(lab.state().get("affiliations")))

    # DSP-66: client-d calls operations-parallel; the call reaches the dispatcher, named by its group, and the
    # selected group's priority is the same after it.
    lab.tell("client-d", f"group {parallel}")
    wait(lambda: lab.client("client-d").get("group_id") == parallel and lab.client("client-d").get("affiliated"), 20)
    lab.tell("client-d", "call")
    ok = wait(lambda: lab.state().get("call_state") == "active" and lab.state().get("call_group") == parallel, 20)
    in_a = lab.client("client-a").get("call_state", "idle") != "idle"
    lab.tell("client-d", "hangup")
    after = wait(lambda: idle("dispatcher") and idle("client-d"), 20)
    check("DSP-66 client-d's call on operations-parallel reaches the dispatcher (not client-a); idle after it",
          bool(ok) and not in_a and bool(after) and lab.state().get("priority") == "normal",
          f"call_group={lab.state().get('call_group')} state={lab.state().get('call_state')} a_in_call={in_a}")

    # DSP-67: with operations-parallel selected and operations monitored, client-a's emergency alert on operations is
    # listed for that group, not for the selected one; its cancellation clears it. Then back as before.
    lab.act(action="group", id=parallel)
    wait(lambda: lab.state().get("identity", {}).get("group") == parallel and lab.state().get("affiliated"), 20)
    lab.act(action="monitor", groups=[selected])
    wait(lambda: affiliation(selected) == "affiliated", 20)
    lab.tell("client-a", "alert on")

    def alerted():
        return alice in ((lab.state().get("monitored") or {}).get(selected) or {}).get("alerts", [])

    raised = wait(alerted, 20)
    apart = alice not in (lab.state().get("alerts") or [])
    lab.tell("client-a", "alert off")
    cleared = wait(lambda: not alerted(), 20)
    lab.act(action="group", id=selected)
    wait(lambda: lab.state().get("identity", {}).get("group") == selected and lab.state().get("affiliated"), 20)
    lab.act(action="monitor", groups=[parallel, chat])
    wait(lambda: affiliation(parallel) == "affiliated" and affiliation(chat) == "affiliated", 20)
    check("DSP-67 client-a's alert on monitored operations listed for that group only, then cancelled",
          bool(raised) and apart and bool(cleared),
          f"raised={raised} apart={apart} cleared={cleared} {lab.state().get('monitored')} "
          f"a={lab.client('client-a').get('last_error')}")

    # DSP-69: client-a's alert on the selected group, cancelled by the dispatcher during a call on monitored
    # operations-parallel (TS 24.379 clause 12.1.1.2): the cancellation names the selected group, and its receipt
    # completes the request, so none stays pending after it.
    lab.tell("client-a", "alert on")
    raised = wait(lambda: alice in (lab.state().get("alerts") or []), 20)
    lab.tell("client-d", "call")
    in_call = wait(lambda: lab.state().get("call_state") == "active" and lab.state().get("call_group") == parallel, 20)
    shown = lab.state().get("alerts_group")
    lab.act(action="alert", on=False, user=alice, group=selected)
    done = wait(lambda: alice not in (lab.client("client-a").get("alerts") or []) and
                not lab.state().get("alert_pending"), 20)
    lab.tell("client-d", "hangup")
    idle_after = wait(lambda: idle("dispatcher") and idle("client-d"), 20)
    check("DSP-69 client-a's alert cancelled during a call on a monitored group; no request left pending",
          bool(raised) and bool(in_call) and bool(done) and bool(idle_after) and shown == parallel and
          alice not in (lab.state().get("alerts") or []),
          f"raised={raised} in_call={in_call} done={done} pending={lab.state().get('alert_pending')!r} "
          f"a={lab.client('client-a').get('alerts')} dispatcher={lab.state().get('alerts')}")

    # DSP-68: operations-parallel selected becomes the selected group (no longer monitored); with the selected
    # group back and monitoring stopped, only the selected group is affiliated and client-d's call does not reach
    # the dispatcher.
    lab.act(action="group", id=parallel)
    moved = wait(lambda: lab.state().get("identity", {}).get("group") == parallel and
                 parallel not in (lab.state().get("monitored_groups") or []) and lab.state().get("affiliated"), 20)
    lab.act(action="group", id=selected)
    wait(lambda: lab.state().get("identity", {}).get("group") == selected and lab.state().get("affiliated"), 20)
    lab.act(action="monitor", groups=[])
    stopped = wait(lambda: not lab.state().get("monitored_groups") and affiliation(parallel) != "affiliated" and
                   affiliation(chat) != "affiliated" and affiliation(selected) == "affiliated", 20)
    lab.tell("client-d", "call")
    wait(lambda: lab.client("client-d").get("call_state") == "active", 20)
    reached = wait(lambda: not idle("dispatcher"), 5)
    lab.tell("client-d", "hangup")
    wait(lambda: idle("client-d"), 20)
    lab.tell("client-d", f"group {selected}")
    wait(lambda: lab.client("client-d").get("group_id") == selected and lab.client("client-d").get("affiliated"), 20)
    check("DSP-68 a monitored group selected leaves the monitored list; monitoring stopped, client-d's call stays away",
          bool(moved) and bool(stopped) and not reached, f"{lab.state().get('affiliations')} reached={reached}")

    failed = [n for n, ok in results if not ok]
    print(f"{len(results) - len(failed)}/{len(results)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
