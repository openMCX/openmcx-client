#!/usr/bin/env python3
"""M3: the dispatcher position bound to MCX Admin, on a running MCX Admin lab (prepare.py --position).

  python3 ../openmcx-server/tools/prepare-prearranged-test.py --directory <lab> --project m3 --admin \
      --admin-image openmcx-admin:<tag> --token-ttl 60 --image openmcx:<tag> --client-image openmcx-client:m3
  python3 dispatcher/lab/prepare.py <lab> --position --image openmcx-dispatcher:m3
  <compose> -p m3 -f <lab>/compose.json up -d --wait
  python3 dispatcher/lab/position_scenario.py <lab> --project m3 --server ../openmcx-server

MCX Admin is driven through its API (openmcx-server's admin_helper.py), the dispatcher through
its web API only, with a browser's cookie session:
  DM-01 an unactivated position serves nothing but its sign-in page
  DM-02 an administrator creates the dispatcher account dsp1 in two groups
  DM-03 the position activates dsp1 from its link: device key, SIP credential, signed in
  DM-04 the kept account holds no password; the link cannot be used again
  DM-05 the group picker offers the profile's groups; a call on the second group
  DM-06 sign-out stops the engine; a wrong password is refused by IdMS; sign-in again
  DM-07 no session, a forged cookie or a cross-site request is refused
  DM-08 removing dsp1 from a group in MCX Admin takes it off the picker
  DM-09 suspending dsp1 during a call ends the session; sign-in is refused; restore
  DM-10 Revoke devices ends the session; activating again gives the position a new key
  DM-11 another operator (dsp2) activates on the same position; dsp1 signs in again
"""
import argparse
import http.cookiejar
import json
import secrets
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import quote

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + (f": {detail}" if detail and not ok else ""), flush=True)
    return ok


def wait(predicate, timeout=30.0, step=0.5):
    end = time.time() + timeout
    while time.time() < end:
        try:
            value = predicate()
        except (KeyError, TypeError, IndexError, ValueError):
            value = None
        if value:
            return value
        time.sleep(step)
    return None


class Browser:
    """The dispatcher page's side of the web API, with its own cookie jar."""

    def __init__(self, url):
        self.url = url
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def call(self, method, path, body=None, headers=None, timeout=120):
        h = {"X-Dispatcher": "1", "Content-Type": "application/json"}
        h.update(headers or {})
        data = json.dumps(body).encode() if body is not None else (b"{}" if method == "POST" else None)
        request = urllib.request.Request(self.url + path, data=data, method=method, headers=h)
        try:
            with self.opener.open(request, timeout=timeout) as r:
                return r.status, json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            try:
                return e.code, json.loads(e.read() or b"{}")
            except ValueError:
                return e.code, {}

    def session(self):
        return self.call("GET", "/api/session")[1]

    def state(self):
        status, body = self.call("GET", "/api/state")
        return body if status == 200 else {}

    def act(self, **body):
        return self.call("POST", "/api/command", body)[0]


def websocket_status(url, cookie=None):
    """The HTTP status of an audio socket handshake (101 when accepted)."""
    host, port = url.split("//")[1].split(":")
    with socket.create_connection((host, int(port)), timeout=5) as s:
        extra = f"Cookie: dispatcher_session={cookie}\r\n" if cookie else ""
        s.sendall((f"GET /api/audio HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                   f"Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\nOrigin: {url}\r\n{extra}\r\n").encode())
        return int(s.recv(1024).split(b" ", 2)[1])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("lab", type=Path)
    parser.add_argument("--project", required=True)
    parser.add_argument("--server", type=Path, required=True, help="an openmcx-server checkout (its admin_helper.py and compose)")
    parser.add_argument("--url", default="http://127.0.0.1:18080")
    args = parser.parse_args()
    lab, server = args.lab.resolve(), args.server.resolve()
    generated = lab / "generated"
    compose = json.loads((lab / "compose.json").read_text())
    cli = [str(server / ".cache/deps/docker-compose-linux-x86_64"), "-p", args.project, "-f", str(lab / "compose.json")]
    network, image = compose["networks"]["mcx"]["name"], compose["services"]["mcxadmin"]["image"]
    alice = json.loads((generated / "client-a.json").read_text())

    def helper(*words):
        run = subprocess.run(["docker", "run", "--rm", "--network", network,
                              "-v", f'{generated / "tls/ca.crt"}:/ca.crt:ro',
                              "-v", f'{server / "tests/integration/admin_helper.py"}:/admin_helper.py:ro',
                              "--entrypoint", "python3", image, "/admin_helper.py", *words],
                             capture_output=True, text=True, timeout=60)
        if run.returncode:
            raise AssertionError(f"helper {words[0]}: {run.stderr.strip()[-500:]}")
        return json.loads(run.stdout.strip().splitlines()[-1])

    def client_status(role):
        try:
            return json.loads((lab / "artifacts" / role / "status.json").read_text())
        except (OSError, ValueError):
            return {}

    def client_command(role, line):
        subprocess.run(cli + ["exec", "-T", role, "sh", "-c", 'printf "%s\\n" "$0" > /run/mcx/control', line], check=True)

    def in_position(command):
        return subprocess.run(cli + ["exec", "-T", "client-e", "sh", "-c", command], capture_output=True, text=True).stdout

    page = Browser(args.url)
    o = alice["oauth"]
    admin = {"token": None, "at": 0.0}

    def token():
        # The lab's tokens live 60 s (--token-ttl), alice's too: sign her in again as needed.
        if time.time() - admin["at"] > 40:
            admin["token"] = helper("login", o["username"], o["password"], o["client_id"], o["redirect_uri"])["token"]
            admin["at"] = time.time()
        return admin["token"]

    def api(method, path, etag=None, body=None):
        words = ["api", token(), method, path] + ([etag or ""] if etag is not None or body is not None else [])
        return helper(*(words + ([json.dumps(body)] if body is not None else [])))

    def config_etag():
        return api("GET", "/v1/config")["etag"]

    def set_members(group_id, add=(), remove=()):
        listed = api("GET", "/v1/groups")
        group = next(g for g in listed["body"]["groups"] if g["id"] == group_id)
        group["members"] = [m for m in group["members"] if m not in remove] + [m for m in add if m not in group["members"]]
        return api("PUT", "/v1/groups/" + quote(group_id, safe=""), listed["etag"], group)["status"] == 200

    def new_account(login, groups):
        current = api("GET", "/v1/config")
        domain = current["body"]["system"]["domain"]
        user = dict(next(u for u in current["body"]["users"] if u["login"] == "bob"))
        user.update(login=login, display_name=login.upper(), status="active", mc_id=f"sip:{login}.mc@{domain}",
                    mcptt_id=f"sip:{login}.mcptt@{domain}", sip_uri=f"sip:{login}@{domain}",
                    client_ids=[f"urn:uuid:5d3c9a1e-6f0b-4c1e-9a55-{secrets.token_hex(6)}"], groups=[])
        for key in ("mcdata_id", "mcvideo_id", "email"):
            user.pop(key, None)
        ok = api("PUT", f"/v1/users/{login}", current["etag"], user)["status"] == 200
        for g in groups:
            ok = ok and set_members(g, add=[user["mcptt_id"]])
        link = api("POST", f"/v1/users/{login}/password-link")["body"]["url"]
        password = secrets.token_urlsafe(18)
        ok = ok and helper("set-password", link, password)["status"] == 200
        return ok, user, password

    def activation_link(login):
        return api("POST", f"/v1/users/{login}/activation")["body"]["url"]

    # DM-01
    s = page.session()
    check("DM-01 an unactivated position: not signed in, no account", s.get("signed_in") is False and s.get("accounts") == [], s)
    check("DM-01 its state and commands need a session", page.call("GET", "/api/state")[0] == 401 and page.act(action="call") == 401)

    # DM-02
    groups = [g["id"] for g in api("GET", "/v1/groups")["body"]["groups"]]
    ops = next(g for g in groups if g.endswith("/operations") or g.startswith("sip:operations@"))
    second = next((g for g in groups if "parallel" in g), next(g for g in groups if g != ops))
    ok, dsp1, password1 = new_account("dsp1", [ops, second])
    check("DM-02 alice creates dsp1 in two groups, dsp1 sets a password", ok)

    # DM-03
    link = activation_link("dsp1")
    status, body = page.call("POST", "/api/activate", {"url": link, "password": password1})
    st = wait(lambda: (x := page.state()).get("registered") and x.get("affiliated") and x, 30)
    check("DM-03 the position activates dsp1 from its link and signs in", status == 200 and st and
          st["identity"]["user"] == dsp1["mcptt_id"] and page.session().get("operator") == "dsp1", f"{status} {body} {st}")

    # DM-04
    kept = in_position("cat /data/accounts/*/config.json")
    files = in_position("ls /data/accounts/*/")
    try:
        oauth = json.loads(kept)["oauth"]
    except (ValueError, KeyError):
        oauth = {}
    check("DM-04 the kept account has its device key and certificate and no password",
          oauth.get("username") == "dsp1" and "password" not in oauth and password1 not in kept
          and "device.key" in files and "device.crt" in files, files)
    again = page.call("POST", "/api/activate", {"url": link, "password": password1})[0]
    check("DM-04 no second activation while an operator is signed in", again == 409, again)

    # DM-05
    def select(group_id):
        # The engine drops its affiliation when the group changes and affiliates again.
        page.act(action="group", id=group_id)
        time.sleep(1.5)
        return wait(lambda: (x := page.state())["identity"]["group"] == group_id and x.get("affiliated"), 20)

    profile = wait(lambda: set(page.state().get("profile_groups", [])) >= {ops, second} and page.state()["profile_groups"], 20)
    first = page.state()["identity"]["group"]  # activation's group: the first of the account's groups
    other = second if first == ops else ops
    moved = select(other)
    page.act(action="call")
    called = wait(lambda: page.state().get("call_state") == "active", 20)
    page.act(action="hangup")
    wait(lambda: page.state().get("call_state") == "idle", 15)
    back = select(first)
    check("DM-05 the picker offers the profile's groups; a call on the other group; back again",
          profile and moved and called and back, f"{profile} {first} {moved} {called} {back} {page.state().get('last_call_failure')}")

    # DM-06
    page.call("POST", "/api/logout")
    out = page.session()
    engine_gone = wait(lambda: "mcx-client" not in in_position("cat /proc/[0-9]*/comm 2>/dev/null"), 10)
    reused = page.call("POST", "/api/activate", {"url": link, "password": password1})[0]
    check("DM-06 the used activation link is refused", reused == 400, reused)
    status_wrong, body_wrong = page.call("POST", "/api/login", {"username": "dsp1", "password": "not-" + password1})
    status_ok, _ = page.call("POST", "/api/login", {"username": "dsp1", "password": password1})
    back_in = wait(lambda: page.state().get("affiliated"), 30)
    check("DM-06 sign-out stops the engine; IdMS refuses a wrong password; the right one signs in",
          out.get("signed_in") is False and engine_gone and status_wrong == 401 and status_ok == 200 and back_in,
          f"{out} {engine_gone} {status_wrong} {body_wrong} {status_ok}")

    # DM-07
    stranger = Browser(args.url)
    cookie = next(c.value for c in page.jar if c.name == "dispatcher_session")
    forged = Browser(args.url)
    forged.jar.set_cookie(http.cookiejar.Cookie(0, "dispatcher_session", "0" * 64, None, False, "127.0.0.1", False, False,
                                                "/", True, False, None, True, None, None, {}))
    refused = [stranger.act(action="call"), forged.act(action="call"),
               page.call("POST", "/api/command", {"action": "call"}, headers={"X-Dispatcher": ""})[0],
               websocket_status(args.url), websocket_status(args.url, cookie)]
    busy = stranger.call("POST", "/api/login", {"username": "dsp1", "password": password1})[0]
    check("DM-07 no session, a forged cookie, a cross-site POST and a socket without the session are refused",
          refused == [401, 401, 403, 403, 101] and busy == 409, f"{refused} {busy}")

    # DM-08
    check("DM-08 the operator works on the second group", select(second))
    check("DM-08 alice removes dsp1 from the group in use", set_members(second, remove=[dsp1["mcptt_id"]]))
    gone = wait(lambda: second not in page.state().get("profile_groups", [second]), 40)
    page.act(action="call")
    time.sleep(3)
    st = page.state()
    check("DM-08 the profile update takes the group off the picker; a call on it is refused",
          gone and st.get("call_state") != "active", f"{st.get('profile_groups')} {st.get('call_state')} {st.get('last_call_failure')}")
    page.act(action="hangup")
    page.call("POST", "/api/logout")
    page.call("POST", "/api/login", {"username": "dsp1", "password": password1})
    landed = wait(lambda: (x := page.state())["identity"]["group"] == ops and x.get("affiliated"), 30)
    page.act(action="group", id=second)
    time.sleep(2)
    check("DM-08 signing in again lands on a group the profile lists; the removed one cannot be selected",
          landed and page.state()["identity"]["group"] == ops, page.state().get("identity"))

    # DM-09
    page.act(action="call")
    in_call = wait(lambda: page.state().get("call_state") == "active", 20)
    suspended = api("POST", "/v1/users/dsp1/suspend", config_etag())["status"] == 200
    t0 = time.time()
    ended = wait(lambda: (x := page.session()).get("signed_in") is False and "IdMS" in x.get("reason", "") and x, 90)
    took = time.time() - t0
    refused = page.call("POST", "/api/login", {"username": "dsp1", "password": password1})[0]
    check("DM-09 suspending dsp1 during a call ends the session with the reason; sign-in is refused",
          in_call and suspended and ended and refused == 401, f"{in_call} {suspended} {ended} {refused}")
    print(f"  session ended {took:.0f} s after the suspension (token lifetime 60 s)")
    restored = api("POST", "/v1/users/dsp1/restore", config_etag())["status"] == 200
    status = wait(lambda: page.call("POST", "/api/login", {"username": "dsp1", "password": password1})[0] == 200, 60, 5)
    check("DM-09 restoring dsp1 lets the operator sign in again", restored and status and wait(lambda: page.state().get("affiliated"), 30))

    # DM-10
    old_crt = in_position("cat /data/accounts/*/device.crt")
    revoked = api("POST", "/v1/users/dsp1/revoke-devices")["status"] == 200
    t0 = time.time()
    # IdMS revokes the refresh tokens with the devices, and the SIP core refuses the next
    # registration: whichever comes first ends the session.
    ended = wait(lambda: (x := page.session()).get("signed_in") is False and ("SIP core" in x.get("reason", "")
                                                                           or "IdMS" in x.get("reason", "")) and x, 200, 2)
    took = time.time() - t0
    refused = page.call("POST", "/api/login", {"username": "dsp1", "password": password1})[0]
    check("DM-10 Revoke devices ends the session at the next registration; sign-in no longer works",
          revoked and ended and refused != 200, f"{revoked} {ended} {refused}")
    print(f"  session ended {took:.0f} s after Revoke devices (token lifetime 60 s, registration expiry 120 s): {ended and ended.get('reason')}")
    status, body = page.call("POST", "/api/activate", {"url": activation_link("dsp1"), "password": password1})
    new_crt = in_position("cat /data/accounts/*/device.crt")
    check("DM-10 activating again gives the position a new certificate, and dsp1 is back",
          status == 200 and new_crt and new_crt != old_crt and wait(lambda: page.state().get("affiliated"), 30), f"{status} {body}")

    # DM-11
    page.call("POST", "/api/logout")
    ok, dsp2, password2 = new_account("dsp2", [ops])
    status, body = page.call("POST", "/api/activate", {"url": activation_link("dsp2"), "password": password2})
    as_dsp2 = wait(lambda: page.state().get("identity", {}).get("user") == dsp2["mcptt_id"] and page.state().get("affiliated"), 30)
    accounts = sorted(page.session().get("accounts", []))
    page.call("POST", "/api/logout")
    status1, _ = page.call("POST", "/api/login", {"username": "dsp1", "password": password1})
    as_dsp1 = wait(lambda: page.state().get("identity", {}).get("user") == dsp1["mcptt_id"], 30)
    check("DM-11 a second operator activates on the position; the first signs in again without a link",
          ok and status == 200 and as_dsp2 and accounts == ["dsp1", "dsp2"] and status1 == 200 and as_dsp1,
          f"{status} {body} {accounts} {status1}")
    page.call("POST", "/api/logout")

    failed = [n for n, ok in results if not ok]
    print(f"{len(results) - len(failed)}/{len(results)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
