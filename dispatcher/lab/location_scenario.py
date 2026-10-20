#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""The dispatcher's location requests and reporting changes (TS 24.379 clause 13.3), on a running lab prepared with
--private-call --mcdata --location (see prepare.py, which gives the dispatcher the right to ask for others' location
and to change their reporting, and lets client-a share its location with client-b only). client-b shares its
location with everyone.

  DSP-57 client-b located, with a fresh report        DSP-59 client-b told to report every 2 s
  DSP-58 client-a, who does not share, not located

Drives the dispatcher through its web API, as the page does, and checks the other clients' status files.

  python3 lab/location_scenario.py <lab> --project <project> [--url http://127.0.0.1:18080]
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

    def reports(who):
        return [r for r in (lab.state().get("location") or {}).get("received") or [] if r.get("reporting_uri") == who]

    def answered(action):
        result = lab.state().get("location_last_result") or {}
        return result.get("action") == action and result.get("status") == 200

    # DSP-57: the location of client-b asked with refresh (TS 24.379 clause 13.3.3.2); the participating function asks
    # client-b (clause 13.2.3.1) and passes its report on, with the request's ID as ReportID.
    lab.act(action="location-request", targets=[b], refresh=True)
    request = None
    ok = wait(lambda: answered("request") and any(r.get("coordinate") for r in reports(b)), 20)
    if ok:
        request = lab.state().get("location_last_request")
        ok = any(r.get("id") == request for r in reports(b))
    check("DSP-57 client-b located: its fresh report reaches the dispatcher, under the request's ID", ok,
          f"{lab.state().get('location_last_result')} reports={reports(b)[-1:]} request={request}")

    # DSP-58: client-a does not share its location with the dispatcher: the request is accepted, and no report of
    # client-a comes (clause 13.2.3.2, a target that does not share is left out).
    lab.act(action="location-request", targets=[a], refresh=True)
    ok = wait(lambda: answered("request"), 15) and not wait(lambda: reports(a), 6)
    check("DSP-58 client-a, who does not share its location, is not located", ok, str(reports(a)))

    # DSP-59: client-b told to report its position every 2 s (clause 13.3.2.2); client-b applies the configuration.
    lab.act(action="location-configure", targets=[b], periodic=2)

    # The client's status names the configuration's triggers: one now, the periodic one.
    def configured():
        configuration = (lab.client("client-b").get("location") or {}).get("configuration") or {}
        return len(configuration.get("triggers") or []) == 1 and configuration.get("minimum_interval") == 1

    def sent():
        return (lab.client("client-b").get("location") or {}).get("sent") or []

    def sent_after(last):
        """The reports sent after the one given (the list keeps the last 32)."""
        reports = sent()
        return reports[reports.index(last) + 1:] if last in reports else reports

    ok = wait(lambda: answered("configure") and configured(), 20)
    last = (sent() or [None])[-1]
    ok = ok and wait(lambda: len(sent_after(last)) >= 3, 12)
    check("DSP-59 client-b told to report every 2 s: it applies the configuration and reports", ok,
          f"{lab.state().get('location_last_result')} {(lab.client('client-b').get('location') or {}).get('configuration')}")

    failed = [n for n, ok in results if not ok]
    print(f"{len(results) - len(failed)}/{len(results)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
