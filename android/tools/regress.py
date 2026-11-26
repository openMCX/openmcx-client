#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

# Lab tooling: runs the Android end-to-end scenarios (tests/scenarios.py) of the unit table below on
# labs of lab/androidlab.py. It implements no 3GPP procedure; the scenarios name the clauses they check.
"""Run the Android client's lab regression: build the APK, create a lab per unit, run its
scenarios, bring the lab down, and write a summary.

Each unit is one lab (lab/androidlab.py create with the unit's options, then up) and the scenarios
that run on it, one tests/scenarios.py call each. A unit's first scenario is S1 where the unit
has it: S1 waits for every client to be registered and authorised, which the others take for
granted. Every scenario of tests/scenarios.py is the home of exactly one unit or listed in
EXCLUDED; a unit may also run scenarios homed elsewhere, under its own configuration (a
protected lab re-runs the clear scenarios, for example), reported as that unit's.

A scenario that fails is run once more on a fresh lab of its unit (after S1 where the unit has
it): passing then makes it a flake, failing again a failure. Labs are named <prefix>-<unit>
with a prefix unique to the run, and only those are touched; each is brought down after its last
scenario, also on Ctrl-C or an error. --jobs labs run at once; a lab is started only when the
host has --min-available GB of memory available (an Android lab takes about 3.5 GB), and labs
are started one after the other. The summary is written to <out>/SUMMARY.md and summary.json;
the exit status is 0 only when no scenario failed.

Examples:
  tools/regress.py --list
  tools/regress.py --list --select mcvideo
  tools/regress.py --server-image openmcx-all:pre-implicit-2e66c56 --select =base --scenarios S1,S2,S3
  tools/regress.py --server-image openmcx-all:pre-implicit-2e66c56 --peer-image openmcx-client:linux-fix-d63-a54faca \\
      --all --jobs 2
  tools/regress.py --server-image IMG --apk app-debug.apk --select protected,location --out /tmp/rg

--select takes unit names and tags, comma separated, or =name for that unit alone (as the server's
tools/regress.py); --scenarios narrows the selected units to those scenarios.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, wait, FIRST_COMPLETED
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[1]  # android/
LAB = ROOT / "lab" / "androidlab.py"
SCENARIOS = ROOT / "tests" / "scenarios.py"
RUNS = ROOT / "lab" / "run"
DEFAULT_APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
SIP_IMAGE = "openmcx-kamailio:6.1.4-14"
SERVER_TREE = "/home/ab/openmcx/openmcx-server"

# The lab options of the regressions in docs/TEST_REPORT.md ("Final regression on the server fixes",
# "Regression on merged main"), where each set of scenarios last passed in full.
MCVIDEO_PRIORITY = ["--mcvideo-priority", "client-a=7:4", "--mcvideo-priority", "client-b=5"]
PM = ["--priority", "--functional-alias", "--mcdata-pre-established", "--pre-established",
      "--reference-client", "client-d", "--fixture-arg=--msgstore"]
LOCATION = ["--location", "--functional-alias", "--location-rules", "--priority", "--mcvideo"] + MCVIDEO_PRIORITY
PROTECTED = ["--protected", "--xml-protection", "--integrity-protection", "--priority", "--mcvideo"] + MCVIDEO_PRIORITY


def unit(name, tags, options, run, also=()):
    """One unit: a lab made with options, the scenarios run on it in order (run), and of those the ones whose home
    is another unit (also). A unit whose run starts with S1 keeps S1 first whatever --scenarios selects."""
    also = list(also)
    return dict(name=name, tags=set(tags) | {name}, options=list(options), run=list(run), also=also,
                home=[s for s in run if s not in also], warmup=run[:1] == ["S1"],
                peer="--reference-client" in options)


def keys(prefix, *numbers):
    """Scenario keys: keys("S", 1, (3, 5)) is S1, S3, S4, S5."""
    out = []
    for n in numbers:
        out += [f"{prefix}{i}" for i in (range(n[0], n[1] + 1) if isinstance(n, tuple) else [n])]
    return out


UNITS = [
    # Start-up, calls, SDS, the product's hooks and private calls without floor control (TEST_REPORT.md "Scenarios").
    unit("base", ["smoke", "mcptt", "mcdata", "default"], ["--monitor-group"], keys("S", (1, 6), 11, 12)),
    # Refresh and recovery, lifecycle, impaired network and latency: the mcx-nettools:1 image (lab/nettools) and
    # sch_netem on the host.
    unit("network", ["mcptt", "netem", "default"], ["--monitor-group"], keys("S", 1, (7, 10)), also=["S1"]),
    # The reg event package, implicit floor, service settings, monitoring, group dynamic data and ad hoc criteria.
    unit("registration", ["mcptt", "mcdata", "default"], ["--monitor-group"], keys("S", 1, (20, 22), 24, 28),
         also=["S1"]),
    # Negotiated-mode remote affiliation (TS 24.379 clause 9.2.1.4) needs the fixture's rights; with them S23 has
    # 17 checks (12 without). S19's third-party deregistration runs here too.
    unit("remote-affiliation", ["mcptt", "mcvideo", "affiliation"],
         ["--mcvideo", "--priority", "--monitor-group", "--fixture-arg=--remote-affiliation"],
         ["S1", "S16", "S19", "S23", "V6"], also=["S1", "S16"]),
    # MCData media plane, IP connectivity, enhanced status, message store and functional aliases; S67 talks to the
    # Linux reference client as client-d (--peer-image).
    unit("mcdata", ["mcdata", "media-plane", "peer"], PM, ["S1", "S6"] + keys("S", (60, 67), (80, 82), 84),
         also=["S1", "S6"]),
    # Pre-established sessions, MCData (S70-S74) and MCPTT (S75-S79, S85).
    unit("pre-established", ["mcptt", "mcdata", "pre-established", "peer"], PM, ["S1"] + keys("S", (70, 79), 85),
         also=["S1"]),
    # Emergency calls, first-to-answer, chat, broadcast and floor priority.
    unit("call-types", ["mcptt", "priority"], ["--priority", "--chat", "--broadcast"],
         keys("S", 1, 16, (25, 27), 29), also=["S1"]),
    # Regroups and temporary groups. The regroup fixture affiliates nobody to operations, so S1 and S18 fail here by
    # design: no S1, and S18 is in floor-queue.
    unit("regroup", ["mcptt", "regroup"], ["--regroup", "--floor-queue"], ["S30", "S31"]),
    unit("floor-queue", ["mcptt"], ["--floor-queue"], ["S1", "S18"], also=["S1"]),
    # Location, location-based functional aliases, rules based affiliation, areas, SEAL location, and the MCVideo
    # signalling scenarios (V1-V4; V6, homed in remote-affiliation, as in every regression).
    unit("location", ["mcptt", "mcdata", "mcvideo", "location"], LOCATION,
         ["S1"] + keys("V", (1, 4), 6) + keys("S", 13, 14, 17, (40, 45)), also=["S1", "V6"]),
    # Private call services: ambient listening, call-back, remote calls, transfer, forwarding.
    unit("private-services", ["mcptt", "private-services"], ["--private-services", "--priority"],
         keys("S", 1, (32, 39)), also=["S1"]),
    # End-to-end protection with XML confidentiality and integrity: V5 (the KMS) and S83 (protected MSRP) need it;
    # the clear scenarios run again under protection.
    unit("protected", ["mcptt", "mcdata", "mcvideo", "protected"], PROTECTED,
         keys("S", (1, 6), 12, 16) + keys("V", (1, 6)) + keys("S", (60, 66), 80, 81, 83, 84),
         also=keys("S", (1, 6), 12, 16) + keys("V", 1, 2, 3, 4, 6) + keys("S", (60, 66), 80, 81, 84)),
]

EXCLUDED = {
    "S15": "the server address edited in Settings needs phone mode (a phone profile from lab/run/<lab>/phone and the "
           "servers on the host's published ports, MCPTT media on 4000-4199 and 5000-5199); never run in a regression",
}

print_lock = threading.Lock()
STOP = threading.Event()
PROCESSES: set = set()
PROCESSES_LOCK = threading.Lock()
LABS: dict = {}  # lab name -> project, of the labs this run created and has not brought down
START_LOCK = threading.Lock()


def say(text):
    with print_lock:
        print(text, flush=True)


def scenario_key(key):
    return (key[0], int(key[1:]))


# ------------------------------------------------------------------------------
def select(names, units=UNITS):
    """The units a --select list names: unit names or tags, "=name" for that unit alone; exits on an unknown name."""
    if not names:
        return list(units)
    wanted = {name.strip() for name in names.split(",") if name.strip()}
    exact = {name[1:] for name in wanted if name.startswith("=")}
    wanted -= {"=" + name for name in exact}
    chosen = [u for u in units if u["tags"] & wanted or u["name"] in exact]
    unknown = (wanted - set().union(*(u["tags"] for u in units))) | (exact - {u["name"] for u in units})
    if unknown:
        sys.exit(f"unknown unit or tag: {', '.join(sorted(unknown))} (see --list)")
    return chosen


def narrow(units, scenarios):
    """The units with only the given scenarios (S1 kept first where the unit warms up with it); units left with
    nothing but S1 when S1 was not asked for are dropped. Exits on a scenario none of the units runs."""
    if not scenarios:
        return units
    wanted = {s.strip().upper() for s in scenarios.split(",") if s.strip()}
    unknown = wanted - {s for u in units for s in u["run"]}
    if unknown:
        sys.exit(f"no selected unit runs {', '.join(sorted(unknown, key=scenario_key))} (see --list)")
    out = []
    for u in units:
        run = [s for s in u["run"] if s in wanted]
        if not run:
            continue
        if u["warmup"] and run[0] != "S1":
            run = ["S1"] + run
        out.append(dict(u, run=run))
    return out


def coverage(units, scenarios, excluded=EXCLUDED):
    """Problems with the table: scenarios homed in no unit or in several, unknown names, also-runs not in the run."""
    problems = []
    homes = {}
    for u in units:
        for s in u["run"]:
            if s not in scenarios:
                problems.append(f"{u['name']}: {s} is not a scenario of tests/scenarios.py")
        for s in u["also"]:
            if s not in u["run"]:
                problems.append(f"{u['name']}: also-run {s} is not in its run")
        if len(set(u["run"])) != len(u["run"]):
            problems.append(f"{u['name']}: a scenario is run twice")
        for s in u["home"]:
            homes.setdefault(s, []).append(u["name"])
    for s in sorted(scenarios, key=scenario_key):
        where = homes.get(s, [])
        if s in excluded and where:
            problems.append(f"{s} is excluded but homed in {', '.join(where)}")
        elif s not in excluded and len(where) != 1:
            problems.append(f"{s} is homed in {len(where)} units ({', '.join(where) or 'none'})")
    for s in excluded:
        if s not in scenarios:
            problems.append(f"excluded {s} is not a scenario of tests/scenarios.py")
    return problems


def listing(units):
    lines = []
    for u in units:
        lines.append(f"{u['name']:20} {' '.join(u['options']) or '-'}")
        lines.append(f"{'':20} run:  {' '.join(u['run'])}")
        if u["also"]:
            lines.append(f"{'':20} also: {' '.join(s for s in u['also'] if s in u['run'])} (homed elsewhere)")
        lines.append(f"{'':20} tags: {', '.join(sorted(u['tags'] - {u['name']}))}")
    if EXCLUDED:
        lines.append("excluded:")
        for s, why in EXCLUDED.items():
            lines.append(f"  {s}: {why}")
    return "\n".join(lines)


# ------------------------------------------------------------------------------
def run(command, log, timeout=None, always=False):
    """Run command in android/ with its output appended to log; returns the exit status or "timeout". After Ctrl-C
    only the always commands (bringing labs down) run."""
    if STOP.is_set() and not always:
        return "stopped"
    log.parent.mkdir(parents=True, exist_ok=True)
    with open(log, "a") as out:
        out.write(f"\n$ {' '.join(map(str, command))}\n")
        out.flush()
        process = subprocess.Popen([str(c) for c in command], cwd=ROOT, stdout=out, stderr=subprocess.STDOUT,
                                   start_new_session=True, env=dict(os.environ, **ENV))
        with PROCESSES_LOCK:
            PROCESSES.add(process)
        try:
            return process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            kill(process)
            out.write(f"\n[regress] timed out after {timeout} s\n")
            return "timeout"
        finally:
            with PROCESSES_LOCK:
                PROCESSES.discard(process)


ENV: dict = {}


def kill(process):
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=20)
    except (ProcessLookupError, subprocess.TimeoutExpired):
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    except PermissionError:
        process.kill()


def available_gb():
    for line in Path("/proc/meminfo").read_text().splitlines():
        if line.startswith("MemAvailable:"):
            return int(line.split()[1]) / 1024 / 1024
    return 0.0


def wait_for_memory(minimum, what):
    said = 0.0
    while not STOP.is_set():
        free = available_gb()
        if free >= minimum:
            return True
        if time.monotonic() - said > 120:
            say(f"[wait]  {what}: {free:.1f} GB available, waiting for {minimum} GB")
            said = time.monotonic()
        STOP.wait(15)
    return False


# ------------------------------------------------------------------------------
def scenario_outcome(log, results_json):
    """A scenario's outcome from its log and the lab's android-scenarios.json: status pass, fail or skip, checks."""
    try:
        checks = json.loads(Path(results_json).read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        checks = []
    try:
        text = Path(log).read_text(errors="replace")
    except FileNotFoundError:
        text = ""
    skips = [line.strip()[len("[SKIP] "):] for line in text.splitlines() if line.strip().startswith("[SKIP]")]
    failed = [c for c in checks if not c.get("ok")]
    if failed:
        status = "fail"
    elif checks:
        status = "pass"
    elif skips:
        status = "skip"
    else:
        status = "fail"  # no check and no skip: it did not run (a crash before the scenario, or a timeout)
    error = ""
    if failed:
        first = failed[0]
        error = first.get("check", "") + (f": {first['error']}" if first.get("error") else "")
    elif status == "fail":
        found = re.search(r"((?:Traceback|Error|error|timed out)[^\n]{0,200})", text)
        error = found.group(1) if found else "no checks reported"
    return dict(status=status, checks=len(checks), passed=len(checks) - len(failed),
                failed_checks=[c.get("check", "") for c in failed][:20], skips=skips, error=error[:300])


def lab_down(name, out, keep):
    """Bring a lab of this run down (androidlab.py down), whatever state it is in."""
    project = LABS.get(name)
    if project is None:
        return
    lab = RUNS / name
    if (lab / "lab.json").exists():
        if (lab / "compose.json").exists():
            run([sys.executable, LAB, "logs", name], out / "compose.log", timeout=300, always=True)
        run([sys.executable, LAB, "down", name] + (["--keep"] if keep else []), out / "lab.log", timeout=900,
            always=True)
    sweep(project, out / "lab.log")
    if not keep and lab.exists() and not (lab / "lab.json").exists():
        # A create that failed before lab.json: only the fixture's own files, none written by a server.
        shutil.rmtree(lab, ignore_errors=True)
    LABS.pop(name, None)


def sweep(project, log):
    """Remove whatever Docker objects of project are left (a create or down that did not finish)."""
    label = f"label=com.docker.compose.project={project}"
    left = []
    for kind, listing_args in (("container", ["ps", "-aq"]), ("network", ["network", "ls", "-q"]),
                               ("volume", ["volume", "ls", "-q"])):
        ids = subprocess.run(["docker", *listing_args, "--filter", label], capture_output=True, text=True).stdout.split()
        if ids:
            left.append(f"{len(ids)} {kind}(s)")
            remove = {"container": ["rm", "-f"], "network": ["network", "rm"], "volume": ["volume", "rm", "-f"]}[kind]
            subprocess.run(["docker", *remove, *ids], capture_output=True)
    if left:
        with open(log, "a") as out:
            out.write(f"[regress] swept {project}: {', '.join(left)}\n")


def run_unit(u, args, out, lab_name, scenarios, attempt):
    """Create and start one lab for unit u, run scenarios on it, bring it down. Returns the per-scenario attempts
    and, if the lab could not be made, an error."""
    out.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    results, lab_error = {}, ""
    project = f"mcxandroid-{lab_name}"
    try:
        with START_LOCK:
            if not wait_for_memory(args.min_available, lab_name):
                raise RuntimeError("stopped")
            say(f"[start] {lab_name} ({' '.join(u['options']) or 'default'}): {' '.join(scenarios)}")
            LABS[lab_name] = project
            command = [sys.executable, LAB, "create", "--openmcx", args.server_tree, "--name", lab_name,
                       "--server-image", args.server_image] + u["options"]
            if u["peer"]:
                command += ["--client-image", args.peer_image]
            code = run(command, out / "lab.log", timeout=900)
            if code != 0:
                raise RuntimeError(f"androidlab.py create: {code}, see {out / 'lab.log'}")
            set_sip_image(RUNS / lab_name / "compose.json", args.sip_image)
            code = run([sys.executable, LAB, "up", lab_name], out / "lab.log", timeout=1500)
            if code != 0:
                raise RuntimeError(f"androidlab.py up: {code}, see {out / 'lab.log'}")
        say(f"[up]    {lab_name} in {(time.monotonic() - started) / 60:.1f} min")
        results_json = RUNS / lab_name / "android-scenarios.json"
        for key in scenarios:
            if STOP.is_set():
                break
            log = out / f"{key}.log"
            results_json.unlink(missing_ok=True)
            begun = time.monotonic()
            code = run([sys.executable, SCENARIOS, lab_name, key], log, timeout=args.timeout)
            outcome = scenario_outcome(log, results_json)
            if code == "timeout":
                outcome.update(status="fail", error=f"timed out after {args.timeout} s; " + outcome["error"])
            elif code == "stopped":
                break
            if results_json.exists():
                shutil.copyfile(results_json, out / f"{key}.json")
            outcome.update(lab=lab_name, attempt=attempt, seconds=round(time.monotonic() - begun), log=str(log))
            results[key] = outcome
            say(f"        {lab_name} {key}: {outcome['status'].upper()} {outcome['passed']}/{outcome['checks']}"
                + (f"  {outcome['error'][:100]}" if outcome["status"] == "fail" else ""))
    except Exception as error:  # the lab could not be made or started
        lab_error = str(error)[:300]
        say(f"[error] {lab_name}: {lab_error}")
    finally:
        lab_down(lab_name, out, args.keep)
    minutes = round((time.monotonic() - started) / 60, 2)
    say(f"[done]  {lab_name}: {sum(r['status'] == 'pass' for r in results.values())}/{len(scenarios)} passed "
        f"in {minutes:.1f} min")
    return dict(results=results, error=lab_error, minutes=minutes, lab=lab_name)


def set_sip_image(path, image):
    """The lab's SIP core (and P-CSCF) image: the fixture's compose names the server checkout's own tag."""
    compose = json.loads(path.read_text())
    for service in compose.get("services", {}).values():
        if str(service.get("image", "")).startswith("openmcx-kamailio:"):
            service["image"] = image
    path.write_text(json.dumps(compose, indent=2) + "\n")


# ------------------------------------------------------------------------------
def failures(u, first):
    """The scenarios of unit u that failed in its first run, or did not run (its lab could not be made)."""
    return [k for k in u["run"] if k not in first["results"] or first["results"][k]["status"] == "fail"]


def merge(u, first, rerun):
    """The unit's per-scenario results from its first run and the re-run of its failures."""
    rows = []
    for key in u["run"]:
        attempts = [a["results"][key] for a in (first, rerun) if a and key in a["results"]]
        if not attempts:
            error = (first or {}).get("error") or "not run"
            rows.append(dict(unit=u["name"], scenario=key, home=key in u["home"], status="fail", checks=0, passed=0,
                             error=error, attempts=[]))
            continue
        last = attempts[-1]
        status = last["status"]
        if len(attempts) > 1 and attempts[0]["status"] == "fail" and status in ("pass", "skip"):
            status = "flake"
        rows.append(dict(unit=u["name"], scenario=key, home=key in u["home"], status=status, checks=last["checks"],
                         passed=last["passed"], error=attempts[0]["error"] if status in ("fail", "flake") else "",
                         skips=last.get("skips", []), attempts=attempts))
    return rows


def totals(rows):
    count = {s: sum(r["status"] == s for r in rows) for s in ("pass", "fail", "flake", "skip")}
    count.update(scenarios=len(rows), checks=sum(r["checks"] for r in rows), checks_passed=sum(r["passed"] for r in rows))
    return count


def write_summary(out, summary):
    (out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    v, t = summary["versions"], summary["totals"]
    lines = [f"# Android regression {summary['date']}", "",
             f"**{t['pass']} passed, {t['fail']} failed, {t['flake']} flaky, {t['skip']} skipped** of "
             f"{t['scenarios']} scenario runs; checks {t['checks_passed']} of {t['checks']}; "
             f"{summary['minutes']:.1f} min, {summary['jobs']} job(s).", "",
             "| Version | |", "|---|---|",
             f"| APK | sha256 `{v['apk']['sha256']}`, {v['apk']['source']} |",
             f"| Client tree | `{v['client_tree']['commit']}`{' (dirty)' if v['client_tree']['dirty'] else ''} |",
             f"| Server image | `{v['server_image']['name']}` (`{v['server_image']['id']}`) |",
             f"| SIP image | `{v['sip_image']['name']}` (`{v['sip_image']['id']}`) |",
             f"| Peer image | " + (f"`{v['peer_image']['name']}` (`{v['peer_image']['id']}`)" if v["peer_image"] else "none")
             + " |",
             f"| Server tree | `{v['server_tree']['path']}` at `{v['server_tree']['commit']}`"
             f"{' (dirty)' if v['server_tree']['dirty'] else ''} |",
             f"| Redroid | `{v['redroid']}` |", "",
             "| Unit | Options | Scenarios | Pass | Fail | Flake | Skip | Checks | Minutes |", "|---|---|---|---|---|---|---|---|---|"]
    for u in summary["units"]:
        c = u["totals"]
        lines.append(f"| {u['name']} | `{' '.join(u['options']) or '-'}` | {' '.join(u['run'])} | {c['pass']} | {c['fail']} | "
                     f"{c['flake']} | {c['skip']} | {c['checks_passed']}/{c['checks']} | {u['minutes']:.1f} |"
                     + (f" lab: {u['error']}" if u.get("error") else ""))
    lines += ["", "| Unit | Scenario | Result | Checks | Attempts | Failure | Log |", "|---|---|---|---|---|---|---|"]
    for r in summary["results"]:
        attempts = " ".join(f"{a['status']}" for a in r["attempts"]) or "-"
        log = r["attempts"][-1]["log"] if r["attempts"] else ""
        lines.append(f"| {r['unit']} | {r['scenario']}{'' if r['home'] else ' (also)'} | {r['status'].upper()} | "
                     f"{r['passed']}/{r['checks']} | {attempts} | {r['error'].replace('|', '/')[:160]} | {log} |")
    flakes = [r for r in summary["results"] if r["status"] == "flake"]
    failures = [r for r in summary["results"] if r["status"] == "fail"]
    if flakes:
        lines += ["", "Flakes (failed, then passed on a fresh lab):"]
        lines += [f"- {r['unit']} {r['scenario']}: {r['error'][:200]}" for r in flakes]
    if failures:
        lines += ["", "Failures:"]
        lines += [f"- {r['unit']} {r['scenario']}: {r['error'][:200]}" for r in failures]
    if summary.get("excluded"):
        lines += ["", "Excluded:"] + [f"- {s}: {why}" for s, why in summary["excluded"].items()]
    if summary.get("interrupted"):
        lines += ["", "**Interrupted**: the run was stopped before every unit finished."]
    (out / "SUMMARY.md").write_text("\n".join(lines) + "\n")
    return lines


# ------------------------------------------------------------------------------
def image_id(image):
    found = subprocess.run(["docker", "image", "inspect", image, "--format", "{{.Id}}"], capture_output=True, text=True)
    return found.stdout.strip().removeprefix("sha256:")[:12] if found.returncode == 0 else None


def tree_state(path):
    commit = subprocess.run(["git", "-C", str(path), "rev-parse", "--short=12", "HEAD"], capture_output=True, text=True)
    status = subprocess.run(["git", "-C", str(path), "status", "--porcelain", "--untracked-files=no"],
                            capture_output=True, text=True)
    return dict(path=str(path), commit=commit.stdout.strip() or "unknown", dirty=bool(status.stdout.strip()),
                changes=status.stdout.splitlines()[:50])


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--server-tree", default=SERVER_TREE, help=f"the openmcx-server checkout whose fixture generator makes the labs (default {SERVER_TREE})")
    parser.add_argument("--server-image", help="the openmcx-all application image of the labs")
    parser.add_argument("--sip-image", default=SIP_IMAGE, help=f"the SIP core image (default {SIP_IMAGE})")
    parser.add_argument("--peer-image", help="the Linux mcx-client image, client-d of the units tagged peer")
    parser.add_argument("--apk", type=Path, help="a debug APK to install (default: build this tree's with tools/gradle-docker.sh :app:assembleDebug)")
    parser.add_argument("--select", help="comma-separated unit names or tags, =name for one unit alone")
    parser.add_argument("--all", action="store_true", help="every unit")
    parser.add_argument("--scenarios", help="only these scenarios of the selected units (comma separated; S1 stays first where the unit has it)")
    parser.add_argument("--jobs", type=int, default=1, help="labs at once (default 1; an Android lab takes about 3.5 GB)")
    parser.add_argument("--min-available", type=float, default=8.0, help="GB of available memory a lab waits for before it starts (default 8)")
    parser.add_argument("--timeout", type=int, default=1500, help="seconds allowed per scenario (default 1500)")
    parser.add_argument("--list", action="store_true", help="list the (selected) units and their scenarios; no labs")
    parser.add_argument("--keep", action="store_true", help="keep each lab's lab/run/NAME directory (fixture, provisioning) after it is brought down")
    parser.add_argument("--out", type=Path, help="directory for the logs and the summary (default lab/run/regress-<stamp>)")
    parser.add_argument("--no-rerun", action="store_true", help="do not re-run failed scenarios on a fresh lab")
    args = parser.parse_args(argv)
    problems = coverage(UNITS, scenario_keys())
    if problems:
        sys.exit("the unit table does not match tests/scenarios.py:\n  " + "\n  ".join(problems))
    if args.list:
        units = narrow(select(args.select), args.scenarios) if args.select or args.scenarios else UNITS
        print(listing(units))
        return 0
    if not args.server_image:
        parser.error("--server-image is required")
    if not args.all and not args.select:
        parser.error("choose --all or --select")
    units = narrow(UNITS if args.all else select(args.select), args.scenarios)
    if not units:
        parser.error("nothing selected")
    if any(u["peer"] for u in units) and not args.peer_image:
        parser.error(f"units {', '.join(u['name'] for u in units if u['peer'])} need --peer-image (the Linux mcx-client image)")
    for image in [args.server_image, args.sip_image] + ([args.peer_image] if args.peer_image else []):
        if not image_id(image):
            parser.error(f"image {image} not found")
    if not (Path(args.server_tree) / "tools/prepare-prearranged-test.py").exists():
        parser.error(f"{args.server_tree} is not an openmcx-server checkout")
    stamp = datetime.datetime.now().strftime("%m%d%H%M%S")
    prefix = f"rg{stamp}"
    out = (args.out or RUNS / f"regress-{stamp}").resolve()
    out.mkdir(parents=True, exist_ok=True)
    began = time.monotonic()
    client_tree = tree_state(ROOT)
    if args.apk:
        apk = args.apk.resolve()
        if not apk.exists():
            parser.error(f"{apk} not found")
        source = f"given ({apk})"
    else:
        say(f"[build] tools/gradle-docker.sh :app:assembleDebug ({client_tree['commit']}"
            f"{', dirty' if client_tree['dirty'] else ''}), log {out / 'build.log'}")
        if run(["bash", ROOT / "tools/gradle-docker.sh", ":app:assembleDebug"], out / "build.log", timeout=3600) != 0:
            sys.exit(f"the APK build failed, see {out / 'build.log'}")
        apk = DEFAULT_APK
        source = f"built from {client_tree['commit']}{' (dirty)' if client_tree['dirty'] else ''}"
    ENV["MCX_ANDROID_APK"] = str(apk)
    sys.path.insert(0, str(ROOT / "lab"))
    import androidlab  # noqa: E402  (its redroid image, for the summary)
    versions = dict(
        apk=dict(path=str(apk), sha256=sha256(apk), source=source),
        client_tree=client_tree,
        server_image=dict(name=args.server_image, id=image_id(args.server_image)),
        sip_image=dict(name=args.sip_image, id=image_id(args.sip_image)),
        peer_image=dict(name=args.peer_image, id=image_id(args.peer_image)) if args.peer_image else None,
        server_tree=tree_state(args.server_tree),
        redroid=androidlab.REDROID,
    )
    say(f"[run]   {len(units)} unit(s), {sum(len(u['run']) for u in units)} scenario runs, {args.jobs} job(s), "
        f"labs {prefix}-*, APK {versions['apk']['sha256'][:12]}, output {out}")

    def interrupt(signum, frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupt)
    firsts, reruns, interrupted = {}, {}, False
    # The units with the most scenarios first.
    plan = sorted(units, key=lambda u: -len(u["run"]))
    pool = ThreadPoolExecutor(max_workers=max(1, args.jobs))
    try:
        futures = {pool.submit(run_unit, u, args, out / u["name"], f"{prefix}-{u['name']}", u["run"], 1): u
                   for u in plan}
        pending = set(futures)
        while pending:
            done, pending = wait(pending, timeout=1, return_when=FIRST_COMPLETED)
            for f in done:
                firsts[futures[f]["name"]] = f.result()
        if not args.no_rerun:
            again = []
            for u in plan:
                failed = failures(u, firsts[u["name"]])
                if failed:
                    again.append((u, failed, (["S1"] if u["warmup"] and "S1" not in failed else []) + failed))
            if again:
                say("[rerun] " + "; ".join(u["name"] + ": " + " ".join(failed) for u, failed, _ in again))
            futures = {pool.submit(run_unit, u, args, out / u["name"] / "rerun", f"{prefix}-{u['name']}-r", r, 2):
                       (u, failed) for u, failed, r in again}
            pending = set(futures)
            while pending:
                done, pending = wait(pending, timeout=1, return_when=FIRST_COMPLETED)
                for f in done:
                    u, failed = futures[f]
                    result = f.result()
                    # S1 run only to warm up the re-run lab is not a result of its own.
                    if "S1" not in failed:
                        result["results"].pop("S1", None)
                    reruns[u["name"]] = result
    except KeyboardInterrupt:
        interrupted = True
        say("[stop]  interrupted: stopping the scenarios and bringing the labs down")
        STOP.set()
        with PROCESSES_LOCK:
            running = list(PROCESSES)
        for process in running:
            kill(process)
    finally:
        STOP.set() if interrupted else None
        pool.shutdown(wait=True, cancel_futures=True)
        for name in list(LABS):
            lab_down(name, out / "cleanup", args.keep)
    rows = []
    unit_rows = []
    for u in units:
        first, rerun = firsts.get(u["name"]), reruns.get(u["name"])
        mine = merge(u, first, rerun) if first else []
        rows += mine
        unit_rows.append(dict(name=u["name"], options=u["options"], run=u["run"], home=[s for s in u["run"] if s in u["home"]],
                              lab=first["lab"] if first else None, error=(first or {}).get("error", ""),
                              rerun_error=(rerun or {}).get("error", ""),
                              minutes=round((first or {}).get("minutes", 0) + (rerun or {}).get("minutes", 0), 2),
                              totals=totals(mine)))
    summary = dict(date=datetime.date.today().isoformat(), prefix=prefix, jobs=args.jobs,
                   minutes=round((time.monotonic() - began) / 60, 2), interrupted=interrupted, versions=versions,
                   selection=dict(select=args.select, all=args.all, scenarios=args.scenarios, rerun=not args.no_rerun),
                   totals=totals(rows), units=unit_rows, results=rows,
                   excluded={s: why for s, why in EXCLUDED.items()})
    lines = write_summary(out, summary)
    say("\n".join(lines[2:3]))
    for r in rows:
        if r["status"] in ("fail", "flake"):
            say(f"  {r['status'].upper()} {r['unit']} {r['scenario']}: {r['error'][:120]}")
    left = subprocess.run(["docker", "ps", "-a", "--format", "{{.Names}}"], capture_output=True, text=True).stdout.split()
    left = [c for c in left if c.startswith(f"mcxandroid-{prefix}-")]
    if left:
        say(f"warning: containers left behind: {' '.join(left)}")
    say(f"summary: {out / 'SUMMARY.md'}")
    return 1 if interrupted or summary["totals"]["fail"] else 0


def scenario_keys():
    """The scenario keys tests/scenarios.py defines (importing it needs no lab)."""
    sys.path.insert(0, str(ROOT / "lab"))
    sys.path.insert(0, str(ROOT / "tests"))
    import scenarios  # noqa: E402
    return set(scenarios.Lab.SCENARIOS)


if __name__ == "__main__":
    sys.exit(main())
