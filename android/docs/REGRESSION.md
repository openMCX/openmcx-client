# Android regression runner

`tools/regress.py` runs the end-to-end scenarios of `tests/scenarios.py` on fresh `lab/androidlab.py` labs and
writes one summary, as the server's `tools/regress.py` does for its matrix. Python 3 standard library only.

```bash
tools/regress.py --list                                    # units, options and scenarios; no lab
tools/regress.py --list --select mcvideo                   # what a selection would run
tools/regress.py --server-image openmcx-all:<tag> --select =base --scenarios S1,S2,S3
tools/regress.py --server-image openmcx-all:<tag> --peer-image openmcx-client:<tag> --all --jobs 2
```

- **Units** (`UNITS` in the tool): a name, tags (service `mcptt`, `mcdata`, `mcvideo`; features such as `protected`,
  `location`, `pre-established`, `peer`), the `androidlab.py create` options and the scenarios run on that lab, in
  order. The options are those under which each scenario last passed (TEST_REPORT.md, "Final regression on the server
  fixes", "Regression on merged main"). Each scenario is the home of exactly one unit, or is in `EXCLUDED` with the
  reason (S15: phone mode). A unit may also run scenarios homed elsewhere (`also`), under its configuration: S1 first
  in every unit but `regroup` (it waits for registration and authorisation), the clear scenarios on the protected
  lab. `tests/test_regress.py` checks the table against `tests/scenarios.py`.
- **Selection:** `--select` takes unit names and tags, comma separated, `=name` for one unit alone (`mcdata` is a tag
  and a unit); `--all` every unit; `--scenarios S2,V1` narrows the selected units to those scenarios (S1 stays first).
- **Images:** `--server-image` (required), `--sip-image` (default `openmcx-kamailio:6.1.4-14`, written into each lab's
  compose file), `--peer-image` (the Linux `mcx-client` image, client-d of the units tagged `peer`), `--server-tree`
  (the openmcx-server checkout whose fixture generator makes the labs, default `/home/ab/openmcx/openmcx-server`).
- **APK:** built from this tree with `tools/gradle-docker.sh :app:assembleDebug` unless `--apk` names one (passed to
  `androidlab.py` as `MCX_ANDROID_APK`). The summary records its sha256, the tree's commit and whether it was dirty.
- **Labs:** named `rg<stamp>-<unit>` (re-runs `-r`), so a run touches only its own. `--jobs` labs at once (default 1);
  labs start one after the other, each when `MemAvailable` is at least `--min-available` GB (default 8; a lab takes
  about 3.5 GB). Every lab is brought down after its last scenario, also on Ctrl-C, SIGTERM or an error, and anything
  left of its Compose project is removed. `--keep` keeps `lab/run/<lab>` (fixture, provisioning).
- **Re-runs:** a failed scenario runs once more on a fresh lab of its unit (after S1): passing then is a **flake**,
  failing again a **failure**. `--no-rerun` skips this. A scenario with no checks that printed `[SKIP]` is skipped.
- **Output** (`--out`, default `lab/run/regress-<stamp>`): `SUMMARY.md` and `summary.json` (versions: APK, client and
  server trees, server, SIP, peer and redroid images; per unit and per scenario pass, fail, flake or skip with check
  counts and attempts; totals; duration), and per unit `lab.log`, `compose.log`, `<scenario>.log` and `.json`. The
  exit status is 0 only when no scenario failed.

The server's `tools/regress.py --android CLIENT_TREE` runs this after its own labs, with its image and SIP core.

Host prerequisites as for any lab ([README](../README.md#run-against-the-openmcx-servers)): `binder_linux`,
`sch_netem` and the `mcx-nettools:1` image (unit `network`).
