#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""lab/androidlab.py: phone roles and the file distribution settings of a phone lab."""
import argparse
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lab"))
import androidlab  # noqa: E402


def args(**kw):
    base = dict(phone=None, phone_ip=None, phone_device=None)
    base.update(kw)
    return argparse.Namespace(**base)


class PhoneRoles(unittest.TestCase):
    def test_two_phone_roles(self):
        phones = androidlab.phone_devices(args(phone_device=["client-a:SER1:10.0.0.2", "client-b:SER2:10.0.0.3"]))
        self.assertEqual(phones, {"client-a": {"serial": "SER1", "ip": "10.0.0.2"}, "client-b": {"serial": "SER2", "ip": "10.0.0.3"}})

    def test_legacy_single_phone_arguments(self):
        self.assertEqual(androidlab.phone_devices(args(phone="client-a", phone_ip="10.0.0.2")),
                         {"client-a": {"serial": None, "ip": "10.0.0.2"}})

    def test_duplicates_and_bad_roles_are_refused(self):
        for bad in (["client-a:S:1.2.3.4", "client-a:T:1.2.3.5"], ["client-a:S:1.2.3.4", "client-b:S:1.2.3.5"],
                    ["client-z:S:1.2.3.4"], ["client-a:S"]):
            with self.assertRaises(SystemExit):
                androidlab.phone_devices(args(phone_device=bad))
        with self.assertRaises(SystemExit):
            androidlab.phone_devices(args(phone="client-a"))

    def test_old_lab_metadata_is_read_as_one_phone(self):
        with tempfile.TemporaryDirectory() as d:
            old = androidlab.RUNS
            androidlab.RUNS = Path(d)
            try:
                (Path(d) / "p1").mkdir()
                (Path(d) / "p1" / "lab.json").write_text(json.dumps({"clients": [], "phone": {"role": "client-a", "ip": "1.2.3.4", "serial": "S"}}))
                self.assertEqual(androidlab.meta("p1")["phones"], {"client-a": {"serial": "S", "ip": "1.2.3.4"}})
            finally:
                androidlab.RUNS = old


class FileDistributionSettings(unittest.TestCase):
    def test_lab_fd_policy_is_consistent(self):
        with tempfile.TemporaryDirectory() as d:
            fixture = Path(d)
            generated = fixture / "generated"
            generated.mkdir()
            a, b, c = "sip:alice.mcdata@mcx.test", "sip:bob.mcdata@mcx.test", "sip:c.mcdata@mcx.test"
            (generated / "site.json").write_text(json.dumps({
                "mcdata_service": {"max_data_size_fd_bytes": 4000},
                "users": [{"mcdata_id": a, "mcdata": {"transmit_data": True, "targets": [c], "max_data_1to1": 300}},
                          {"mcdata_id": b, "mcdata": {"transmit_data": True, "incoming_from": [a]}},
                          {"mcdata_id": c, "mcdata": {"transmit_data": True, "max_data_1to1": 5}}],
                "groups": [{"id": "sip:operations@mcx.test", "mcdata": {"allow_fd": True, "max_fd_bytes": 2000, "transmit_members": [a]}}]}))
            (generated / "mcdata-server.json").write_text(json.dumps({"mcdata": {"fd": {"default_file_availability_ms": 20000,
                                                                                         "max_file_availability_ms": 600000}}}))
            (generated / "client-a.json").write_text(json.dumps({"endpoints": {"content": "https://http-proxy:8443/content"}}))
            compose = {"services": {"http-proxy": {"command": ["--config", "x"]}, "content-server": {"command": ["--role", "content"]}}}
            fd = androidlab.file_distribution(fixture, compose, [a, b], 100_000_000, 900, 900_000)
            site = json.loads((generated / "site.json").read_text())
            self.assertEqual(site["mcdata_service"]["max_data_size_fd_bytes"], 100_000_000)
            users = {u["mcdata_id"]: u["mcdata"] for u in site["users"]}
            for phone in (a, b):
                self.assertEqual(users[phone]["max_data_1to1"], 100_000_000)
                self.assertTrue(users[phone]["incoming_any"])
                self.assertNotIn("targets", users[phone]); self.assertNotIn("incoming_from", users[phone])
            self.assertEqual(users[c]["max_data_1to1"], 5)  # not a phone: unchanged
            group = site["groups"][0]["mcdata"]
            self.assertEqual((group["max_fd_bytes"], group["fd_supported"]), (100_000_000, True))
            self.assertEqual(set(group["transmit_members"]), {a, b})
            server = json.loads((generated / "mcdata-server.json").read_text())["mcdata"]["fd"]
            self.assertEqual((server["default_file_availability_ms"], server["max_file_availability_ms"]), (900_000, 900_000))
            self.assertEqual(compose["services"]["http-proxy"]["command"][-4:],
                             ["--content-request-limit-bytes", "100065536", "--file-transfer-timeout-ms", "900000"])
            self.assertEqual(compose["services"]["content-server"]["command"][-2:], ["--file-transfer-timeout-ms", "900000"])
            self.assertEqual(fd["content_server"], "https://http-proxy:8443/content")
            self.assertEqual((fd["service_max_bytes"], fd["user_max_bytes"]), (100_000_000, 100_000_000))


if __name__ == "__main__":
    unittest.main()
