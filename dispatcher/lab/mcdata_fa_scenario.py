#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""The dispatcher's MCData functional aliases (TS 24.282 clause 22), on a running lab prepared with --mcdata (see
prepare.py, which lets the dispatcher activate every MCData alias of the lab and take one over):

  DSP-53 activation                                   DSP-55 the alias presented on an SDS
  DSP-54 take-over from another user                  DSP-56 SDS to an alias, both ways; deactivation

Drives the dispatcher through its web API, as the page does, and checks the other clients' status files.

  python3 lab/mcdata_fa_scenario.py <lab> --project <project> [--url http://127.0.0.1:18080]
"""
import argparse
import json
import sys
import uuid
from pathlib import Path

from scenario import Lab, check, results, wait


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("lab", type=Path)
    parser.add_argument("--project", required=True)
    parser.add_argument("--url", default="http://127.0.0.1:18080")
    args = parser.parse_args()
    lab = Lab(args.lab, args.project, args.url)
    wait(lambda: lab.state().get("registered") and lab.state().get("mcdata_authorized"), 90)
    a = json.loads((args.lab / "generated" / "client-a.json").read_text())["mcdata"]["user_id"]
    site = json.loads((args.lab / "generated" / "site.json").read_text())
    alias = {entry["id"].split(":", 1)[1].split(".fa@")[0]: entry["id"]
             for entry in site["functional_aliases"] if "mcdata_users" in entry}
    desk, car, commander = alias["front-desk"], alias["patrol-car-12"], alias["incident-commander"]
    me = lab.state().get("identity", {}).get("mcdata_user")

    def active(role, name):
        status = lab.state() if role == "dispatcher" else lab.client(role)
        return name in (status.get("mcdata_functional_aliases") or [])

    def outcome(name, expected):
        last = lab.state().get("mcdata_fa_last_result") or {}
        return last.get("status") == 200 and (last.get("outcomes") or {}).get(name) == expected

    def inbox(role, text):
        status = lab.state() if role == "dispatcher" else lab.client(role)
        return next((m for m in status.get("mcdata_inbox") or []
                     if text in [p.get("text") for p in m.get("payloads", [])]), None)

    word = uuid.uuid4().hex[:8]

    # DSP-53: the dispatcher activates patrol-car-12 (TS 24.282 clause 22.2.1.2); its status subscription comes
    # first and the NOTIFY says "activated".
    lab.act(action="mcdata-fa", op="activate", aliases=[car])
    ok = wait(lambda: lab.state().get("mcdata_functional_alias_subscription_result") == 200 and
              active("dispatcher", car) and outcome(car, "activated"), 20)
    check("DSP-53 the dispatcher activates the MCData alias patrol-car-12", ok, str(lab.state().get("mcdata_fa_last_result")))

    # DSP-54: client-a holds front-desk (one activation at a time); the dispatcher's activation is offered as
    # take-over-possible, and its take-over moves the alias.
    lab.tell("client-a", f"mcdata-fa activate {desk}")
    wait(lambda: active("client-a", desk), 20)
    lab.act(action="mcdata-fa", op="activate", aliases=[desk])
    ok = wait(lambda: desk in (lab.state().get("mcdata_functional_aliases_take_over_possible") or []), 20)
    lab.act(action="mcdata-fa", op="takeover", aliases=[desk])
    ok = ok and wait(lambda: active("dispatcher", desk) and not active("client-a", desk), 20)
    check("DSP-54 front-desk, held by client-a, offered for take-over and taken over", ok,
          f"{lab.state().get('mcdata_fa_last_result')} a={lab.client('client-a').get('mcdata_functional_aliases')}")

    # DSP-55: the dispatcher presents front-desk on an SDS to client-a (TS 24.282 clause 9.2.1.1,
    # <functional-alias-URI>); client-a sees it (clause 9.2.1.2 step 6c). (client-b takes SDS from client-a only.)
    lab.act(action="mcdata-fa-use", alias=desk)
    lab.act(action="sds", target=a, text=f"{word} as desk")
    ok = wait(lambda: (inbox("client-a", f"{word} as desk") or {}).get("functional_alias") == desk, 20)
    lab.act(action="mcdata-fa-use", alias="")
    check("DSP-55 an SDS from the dispatcher: client-a sees it sent as front-desk", ok,
          f"{inbox('client-a', f'{word} as desk')} {lab.state().get('mcdata_last_result')}")

    # DSP-56: client-b writes to front-desk, which the server resolves to the dispatcher; the dispatcher writes to
    # incident-commander, held by client-a; then it deactivates both of its aliases.
    lab.tell("client-b", f"sds-fa {desk} {word} to desk")
    to_me = wait(lambda: (inbox("dispatcher", f"{word} to desk") or {}).get("called_functional_alias") == desk, 20)
    lab.tell("client-a", f"mcdata-fa activate {commander}")
    wait(lambda: active("client-a", commander), 20)
    lab.act(action="sds", target=commander, to_alias=True, text=f"{word} to commander")
    to_a = wait(lambda: (inbox("client-a", f"{word} to commander") or {}).get("called_functional_alias") == commander, 20)
    lab.tell("client-a", f"mcdata-fa deactivate {commander}")
    lab.act(action="mcdata-fa", op="deactivate", aliases=[car, desk])
    gone = wait(lambda: not active("dispatcher", car) and not active("dispatcher", desk), 20)
    check("DSP-56 SDS to an alias both ways (front-desk reaches the dispatcher, incident-commander client-a); "
          "both aliases deactivated", to_me and to_a and gone,
          f"to_me={to_me} to_a={to_a} {lab.state().get('mcdata_functional_aliases')} me={me}")

    failed = [n for n, ok in results if not ok]
    print(f"{len(results) - len(failed)}/{len(results)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
