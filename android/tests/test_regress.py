#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""tools/regress.py without labs: the unit table against tests/scenarios.py, selection, outcomes and the summary."""
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import regress  # noqa: E402


class Table(unittest.TestCase):
    def test_every_scenario_has_one_home_or_is_excluded(self):
        scenarios = regress.scenario_keys()
        self.assertIn("S85", scenarios)
        self.assertIn("V5", scenarios)
        self.assertEqual(regress.coverage(regress.UNITS, scenarios), [])
        homed = [s for u in regress.UNITS for s in u["home"]]
        self.assertEqual(sorted(homed + list(regress.EXCLUDED)), sorted(scenarios))

    def test_coverage_reports_gaps_doubles_and_unknowns(self):
        units = [regress.unit("a", [], [], ["S1", "S2"]), regress.unit("b", [], [], ["S2", "S9"], also=["S7"])]
        problems = regress.coverage(units, {"S1", "S2", "S3"}, excluded={"S4": "x"})
        text = "\n".join(problems)
        self.assertIn("S2 is homed in 2 units", text)
        self.assertIn("S3 is homed in 0 units", text)
        self.assertIn("S9 is not a scenario", text)
        self.assertIn("also-run S7 is not in its run", text)
        self.assertIn("excluded S4 is not a scenario", text)

    def test_unit_names_are_unique_and_lab_safe(self):
        names = [u["name"] for u in regress.UNITS]
        self.assertEqual(len(names), len(set(names)))
        for name in names:
            self.assertRegex(name, r"^[a-z0-9-]{1,24}$")

    def test_service_tags(self):
        for u in regress.UNITS:
            self.assertTrue(u["tags"] & {"mcptt", "mcdata", "mcvideo"}, u["name"])

    def test_peer_units_name_the_reference_client(self):
        for u in regress.UNITS:
            self.assertEqual(u["peer"], "--reference-client" in u["options"])
            self.assertEqual(u["peer"], "peer" in u["tags"], u["name"])


class Selection(unittest.TestCase):
    def test_tags_names_and_exact(self):
        self.assertEqual({u["name"] for u in regress.select("protected")}, {"protected"})
        mcvideo = {u["name"] for u in regress.select("mcvideo")}
        self.assertEqual(mcvideo, {u["name"] for u in regress.UNITS if "mcvideo" in u["tags"]})
        # "mcdata" is a tag and a unit: =mcdata selects the unit alone.
        self.assertGreater(len(regress.select("mcdata")), 1)
        self.assertEqual([u["name"] for u in regress.select("=mcdata")], ["mcdata"])
        self.assertEqual({u["name"] for u in regress.select("=base, regroup")}, {"base", "regroup"})
        self.assertEqual(len(regress.select("")), len(regress.UNITS))

    def test_unknown_names_exit(self):
        with self.assertRaises(SystemExit):
            regress.select("nosuch")
        with self.assertRaises(SystemExit):
            regress.select("=mcptt")  # a tag, not a unit

    def test_narrow_keeps_s1_first(self):
        units = regress.narrow(regress.select("=location"), "v1")
        self.assertEqual(units[0]["run"], ["S1", "V1"])
        units = regress.narrow(regress.select("=base"), "S1,S2,S3")
        self.assertEqual(units[0]["run"], ["S1", "S2", "S3"])
        # The regroup unit has no S1 to keep.
        self.assertEqual(regress.narrow(regress.select("=regroup"), "S31")[0]["run"], ["S31"])
        # A unit without the scenario is dropped.
        self.assertEqual([u["name"] for u in regress.narrow(regress.select("=base,=regroup"), "S2")], ["base"])
        with self.assertRaises(SystemExit):
            regress.narrow(regress.select("=base"), "S30")

    def test_list_needs_no_lab(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(regress.main(["--list", "--select", "=base"]), 0)
        self.assertIn("run:  S1 S2 S3 S4 S5 S6 S11 S12", out.getvalue())


class Outcomes(unittest.TestCase):
    def outcome(self, log, checks):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "s.log").write_text(log)
            if checks is not None:
                (Path(d) / "r.json").write_text(json.dumps(checks))
            return regress.scenario_outcome(Path(d) / "s.log", Path(d) / "r.json")

    def test_pass_fail_skip(self):
        ok = [{"scenario": "S2", "check": "a", "ok": True}, {"scenario": "S2", "check": "b", "ok": True}]
        self.assertEqual(self.outcome("S2 s2\n  [PASS] a\n", ok)["status"], "pass")
        bad = ok + [{"scenario": "S2", "check": "S2 completed", "ok": False, "error": "timed out waiting"}]
        o = self.outcome("", bad)
        self.assertEqual((o["status"], o["checks"], o["passed"]), ("fail", 3, 2))
        self.assertIn("S2 completed: timed out waiting", o["error"])
        o = self.outcome("S18 s18\n  [SKIP] S18 needs a lab made with --floor-queue\n", [])
        self.assertEqual(o["status"], "skip")
        self.assertEqual(o["skips"], ["S18 needs a lab made with --floor-queue"])
        # Partly skipped but with checks: a pass, the skip noted.
        o = self.outcome("  [SKIP] S22 needs monitor\n", ok)
        self.assertEqual((o["status"], o["skips"]), ("pass", ["S22 needs monitor"]))

    def test_nothing_reported_is_a_failure(self):
        o = self.outcome("Traceback (most recent call last):\nRuntimeError: no lab\n", None)
        self.assertEqual(o["status"], "fail")
        self.assertIn("Traceback", o["error"])


def attempt(status, checks=3, passed=None, error=""):
    return dict(status=status, checks=checks, passed=checks if passed is None else passed, error=error, skips=[],
                failed_checks=[], lab="lab", attempt=1, seconds=1, log="x.log")


class Merge(unittest.TestCase):
    u = regress.unit("u", ["mcptt"], [], ["S1", "S2", "S3", "S4"], also=["S1"])

    def test_flake_fail_pass_and_failures(self):
        first = dict(results={"S1": attempt("pass"), "S2": attempt("fail", passed=1, error="x"),
                              "S3": attempt("fail", error="y"), "S4": attempt("skip", 0)}, error="", minutes=1)
        self.assertEqual(regress.failures(self.u, first), ["S2", "S3"])
        rerun = dict(results={"S2": attempt("pass"), "S3": attempt("fail", error="y again")}, error="", minutes=1)
        rows = {r["scenario"]: r for r in regress.merge(self.u, first, rerun)}
        self.assertEqual({k: r["status"] for k, r in rows.items()},
                         {"S1": "pass", "S2": "flake", "S3": "fail", "S4": "skip"})
        self.assertEqual(rows["S2"]["error"], "x")
        self.assertEqual(len(rows["S3"]["attempts"]), 2)
        self.assertFalse(rows["S1"]["home"])
        self.assertEqual(regress.totals(list(rows.values()))["flake"], 1)

    def test_no_rerun_keeps_the_failure(self):
        first = dict(results={"S1": attempt("pass"), "S2": attempt("fail")}, error="", minutes=1)
        rows = {r["scenario"]: r for r in regress.merge(self.u, first, None)}
        self.assertEqual(rows["S2"]["status"], "fail")
        self.assertEqual(rows["S3"]["status"], "fail")  # never ran

    def test_a_lab_that_could_not_be_made(self):
        first = dict(results={}, error="androidlab.py up: 1", minutes=1)
        self.assertEqual(regress.failures(self.u, first), ["S1", "S2", "S3", "S4"])
        rows = regress.merge(self.u, first, None)
        self.assertTrue(all(r["status"] == "fail" and r["error"] == "androidlab.py up: 1" for r in rows))


class Summary(unittest.TestCase):
    def test_files_and_totals(self):
        u = regress.unit("u", ["mcptt"], ["--priority"], ["S1", "S2"])
        first = dict(results={"S1": attempt("pass", 15), "S2": attempt("fail", 10, 9, "x | y")}, error="", minutes=2)
        rerun = dict(results={"S2": attempt("pass", 10)}, error="", minutes=1)
        rows = regress.merge(u, first, rerun)
        image = dict(name="img", id="abc")
        summary = dict(date="2026-11-27", prefix="rg1", jobs=1, minutes=3.0, interrupted=False,
                       versions=dict(apk=dict(sha256="f" * 64, source="built from 123", path="a.apk"),
                                     client_tree=dict(commit="123", dirty=True), server_image=image, sip_image=image,
                                     peer_image=None, server_tree=dict(path="/s", commit="456", dirty=False),
                                     redroid="redroid"),
                       totals=regress.totals(rows),
                       units=[dict(name="u", options=u["options"], run=u["run"], minutes=3.0, error="",
                                   totals=regress.totals(rows))],
                       results=rows, excluded={"S15": "phone mode"})
        with tempfile.TemporaryDirectory() as d:
            regress.write_summary(Path(d), summary)
            text = (Path(d) / "SUMMARY.md").read_text()
            saved = json.loads((Path(d) / "summary.json").read_text())
        self.assertIn("**1 passed, 0 failed, 1 flaky, 0 skipped** of 2 scenario runs; checks 25 of 25", text)
        self.assertIn("sha256 `" + "f" * 64, text)
        self.assertIn("`123` (dirty)", text)
        self.assertIn("| u | S2 | FLAKE | 10/10 | fail pass | x / y |", text)
        self.assertIn("- S15: phone mode", text)
        self.assertEqual(saved["totals"]["flake"], 1)


if __name__ == "__main__":
    unittest.main()
