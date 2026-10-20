#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""The dispatcher's MCData IP connectivity (TS 24.282 clause 20, the GRE-in-UDP tunnel of TS 24.582 clause 13), on a
running lab prepared with --mcdata --functional-alias (each client's tunnel address is its mcdata.ipconn.address):

  DSP-60 a session with client-a, both ends open        DSP-62 client-a pings the dispatcher; released
  DSP-61 the dispatcher pings client-a in the tunnel    DSP-63 client-a's session to the dispatcher
                                                        DSP-64 a session to a functional alias

Drives the dispatcher through its web API, as the page does, and checks client-a's status file.

  python3 lab/ipcon_scenario.py <lab> --project <project> [--url http://127.0.0.1:18080] [--client client-c]
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
    parser.add_argument("--client", default="client-c")
    args = parser.parse_args()
    lab = Lab(args.lab, args.project, args.url)
    wait(lambda: lab.state().get("registered") and lab.state().get("mcdata_authorized"), 90)
    generated = args.lab / "generated"
    a_config, my_config = (json.loads((generated / f"{role}.json").read_text()) for role in ("client-a", args.client))
    a, a_tunnel, my_tunnel = (a_config["mcdata"]["user_id"], a_config["mcdata"]["ipconn"]["address"],
                              my_config["mcdata"]["ipconn"]["address"])
    me = my_config["mcdata"]["user_id"]
    site = json.loads((generated / "site.json").read_text())
    commander = next(entry["id"] for entry in site["functional_aliases"]
                     if "mcdata_users" in entry and "incident-commander" in entry["id"])

    def session(role):
        return (lab.state() if role == "dispatcher" else lab.client(role)).get("mcdata_ipconn") or {}

    def counters(role):
        return (lab.state() if role == "dispatcher" else lab.client(role)).get("mcdata_ipconn_counters") or {}

    def replies(role):
        return [p for p in (lab.state() if role == "dispatcher" else lab.client(role)).get("mcdata_ipconn_pings") or []]

    def both(state):
        return session("dispatcher").get("state") == state and session("client-a").get("state") == state

    def released():
        return wait(lambda: session("dispatcher").get("state") != "open" and session("client-a").get("state") != "open", 20)

    # DSP-60: the dispatcher opens an IP connectivity session with client-a (TS 24.282 clause 20.2.1); each end
    # knows the other's tunnel endpoint (TS 24.582 clause 13.1.2).
    lab.act(action="ipcon", target=a)
    ok = wait(lambda: both("open"), 20)
    check("DSP-60 an IP connectivity session with client-a: open at both ends", ok,
          f"{session('dispatcher')} a={session('client-a')}")

    # DSP-61: the dispatcher's IP application pings client-a's tunnel address; client-a's answers its echoes.
    before, answered = len(replies("dispatcher")), counters("client-a").get("echo_answered", 0)
    lab.act(action="ipcon-ping", address=a_tunnel, count=3, size=64)
    ok = wait(lambda: len([p for p in replies("dispatcher")[before:] if p.get("from") == a_tunnel]) >= 3 and
              counters("client-a").get("echo_answered", 0) >= answered + 3, 15)
    check("DSP-61 three echoes to client-a's tunnel address answered", ok,
          f"{replies('dispatcher')[before:]} {counters('client-a')}")

    # DSP-62: client-a pings the dispatcher's tunnel address, the dispatcher answers; the dispatcher then releases
    # the session (TS 24.282 clause 20.4.1).
    before, answered = len(replies("client-a")), counters("dispatcher").get("echo_answered", 0)
    lab.tell("client-a", f"ipcon-ping {my_tunnel} 2 32")
    ok = wait(lambda: len([p for p in replies("client-a")[before:] if p.get("from") == my_tunnel]) >= 2 and
              counters("dispatcher").get("echo_answered", 0) >= answered + 2, 15)
    lab.act(action="ipcon", close=True)
    ok = ok and released() and session("dispatcher").get("state") == "closed"
    check("DSP-62 client-a's echoes answered by the dispatcher; the session released", ok,
          f"{session('dispatcher')} a={session('client-a')} {counters('dispatcher')}")

    # DSP-63: client-a opens a session to the dispatcher (TS 24.282 clause 20.2.2), which accepts it and names the
    # caller; client-a releases it.
    lab.tell("client-a", f"ipcon {me}")
    ok = wait(lambda: both("open") and session("dispatcher").get("from") == a and
              session("dispatcher").get("originating") is False, 20)
    lab.tell("client-a", "ipcon close")
    ok = ok and released()
    check("DSP-63 client-a's IP connectivity session to the dispatcher accepted, then released by client-a", ok,
          f"{session('dispatcher')} a={session('client-a')}")

    # DSP-64: a session to incident-commander, activated by client-a: the server names client-a (TS 24.282 clause
    # 20.4.2 step 5a1) and the dispatcher reaches it.
    lab.tell("client-a", f"mcdata-fa activate {commander}")
    wait(lambda: commander in (lab.client("client-a").get("mcdata_functional_aliases") or []), 20)
    lab.act(action="ipcon", alias=commander)
    ok = wait(lambda: both("open") and session("dispatcher").get("selected") == a and
              session("dispatcher").get("called_alias") == commander, 20)
    lab.act(action="ipcon", close=True)
    ok = ok and released()
    lab.tell("client-a", f"mcdata-fa deactivate {commander}")
    check("DSP-64 a session to incident-commander reaches client-a, who activated it", ok,
          f"{session('dispatcher')} a={session('client-a')}")

    failed = [n for n, ok in results if not ok]
    print(f"{len(results) - len(failed)}/{len(results)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
