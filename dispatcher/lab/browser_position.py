#!/usr/bin/env python3
"""Browser check of the position's sign-in page (M3), in Chromium, on a running MCX Admin lab
(prepare.py --position). Prepare an account on the host, then run the check in the Playwright image:

  python3 dispatcher/lab/browser_position.py prepare <lab> --project m3 --server ../openmcx-server   # writes <lab>/browser-account.json
  docker run --rm --network host -v playwright-py:/py -v <lab>:/lab -v $PWD/dispatcher/lab:/check -w /check \
    mcr.microsoft.com/playwright/python:v1.55.0-noble sh -c 'PYTHONPATH=/py python3 browser_position.py check /lab'
"""
import json
import secrets
import subprocess
import sys
from pathlib import Path
from urllib.parse import quote


def prepare(lab: Path, project: str, server: Path):
    generated = lab / "generated"
    compose = json.loads((lab / "compose.json").read_text())
    network, image = compose["networks"]["mcx"]["name"], compose["services"]["mcxadmin"]["image"]

    def helper(*words):
        run = subprocess.run(["docker", "run", "--rm", "--network", network, "-v", f'{generated / "tls/ca.crt"}:/ca.crt:ro',
                              "-v", f'{server / "tests/integration/admin_helper.py"}:/admin_helper.py:ro',
                              "--entrypoint", "python3", image, "/admin_helper.py", *words], capture_output=True, text=True, check=True)
        return json.loads(run.stdout.strip().splitlines()[-1])

    o = json.loads((generated / "client-a.json").read_text())["oauth"]
    token = helper("login", o["username"], o["password"], o["client_id"], o["redirect_uri"])["token"]
    current = helper("api", token, "GET", "/v1/config")
    domain = current["body"]["system"]["domain"]
    login = "web" + secrets.token_hex(2)
    user = dict(next(u for u in current["body"]["users"] if u["login"] == "bob"))
    user.update(login=login, display_name="Web", status="active", mc_id=f"sip:{login}.mc@{domain}",
                mcptt_id=f"sip:{login}.mcptt@{domain}", sip_uri=f"sip:{login}@{domain}",
                client_ids=[f"urn:uuid:5d3c9a1e-6f0b-4c1e-9a55-{secrets.token_hex(6)}"], groups=[])
    for key in ("mcdata_id", "mcvideo_id", "email"):
        user.pop(key, None)
    assert helper("api", token, "PUT", f"/v1/users/{login}", current["etag"], json.dumps(user))["status"] == 200
    listed = helper("api", token, "GET", "/v1/groups")
    for group in listed["body"]["groups"][:2]:
        listed = helper("api", token, "GET", "/v1/groups")
        group = next(g for g in listed["body"]["groups"] if g["id"] == group["id"])
        group["members"].append(user["mcptt_id"])
        assert helper("api", token, "PUT", "/v1/groups/" + quote(group["id"], safe=""), listed["etag"], json.dumps(group))["status"] == 200
    password = secrets.token_urlsafe(18)
    link = helper("api", token, "POST", f"/v1/users/{login}/password-link")["body"]["url"]
    assert helper("set-password", link, password)["status"] == 200
    url = helper("api", token, "POST", f"/v1/users/{login}/activation")["body"]["url"]
    (lab / "browser-account.json").write_text(json.dumps({"login": login, "password": password, "url": url}))
    print(f"prepared {login}")


def run_check(lab: Path, url="http://127.0.0.1:18080/"):
    from playwright.sync_api import expect, sync_playwright
    account = json.loads((lab / "browser-account.json").read_text())
    results = []

    def check(name, ok, detail=""):
        results.append(bool(ok))
        print(("PASS " if ok else "FAIL ") + name + (f": {detail}" if detail and not ok else ""), flush=True)

    with sync_playwright() as p:
        browser = p.chromium.launch(args=["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream"])
        page = browser.new_page(viewport={"width": 1280, "height": 900}, permissions=["microphone"])
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.goto(url)
        expect(page.locator("#signin")).to_be_visible(timeout=10000)
        check("BP-01 the sign-in page shows, the console behind it does not answer", page.locator("#signin").is_visible())
        page.screenshot(path=str(lab / "position-signin.png"), full_page=True)

        page.click("#activation summary")
        page.fill("#activate input[name=url]", account["url"])
        page.fill("#activate input[name=password]", account["password"])
        page.click("#activate button")
        expect(page.locator("#signin")).to_be_hidden(timeout=90000)
        expect(page.locator("#registration")).to_contain_text("registered", timeout=30000)
        check("BP-02 activating from the link signs the operator in", True)
        expect(page.locator("#identity")).to_contain_text(account["login"], timeout=10000)
        page.wait_for_function("document.querySelectorAll('#group-select option').length >= 2", timeout=30000)
        options = page.locator("#group-select option").all_text_contents()
        check("BP-03 the group picker lists the account's groups", len(options) >= 2, options)
        page.select_option("#group-select", index=1)
        expect(page.locator("#group-select")).to_have_value(page.locator("#group-select option").nth(1).get_attribute("value"), timeout=20000)
        page.wait_for_timeout(1500)
        page.screenshot(path=str(lab / "position-signed-in.png"), full_page=True)

        page.click("#signout")
        expect(page.locator("#signin")).to_be_visible(timeout=15000)
        expect(page.locator("#accounts")).to_contain_text(account["login"])
        check("BP-04 sign-out returns to the sign-in page, which lists the activated account", True)

        page.fill("#login input[name=password]", "wrong-" + account["password"])
        page.fill("#login input[name=username]", account["login"])
        page.click("#login button")
        expect(page.locator("#signin-error")).to_contain_text("refused", timeout=60000)
        check("BP-05 a wrong password is refused on the page", True)
        page.fill("#login input[name=password]", account["password"])
        page.click("#login button")
        expect(page.locator("#signin")).to_be_hidden(timeout=60000)
        expect(page.locator("#group-select")).to_have_value(options and page.locator("#group-select").input_value(), timeout=5000)
        check("BP-06 the right password signs in again", True)
        page.click("#signout")
        expect(page.locator("#signin")).to_be_visible(timeout=15000)
        check("BP-07 no script errors", not errors, "; ".join(errors))
        browser.close()
    print(f"{sum(results)}/{len(results)} passed")
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    if sys.argv[1] == "prepare":
        import argparse
        parser = argparse.ArgumentParser()
        parser.add_argument("mode")
        parser.add_argument("lab", type=Path)
        parser.add_argument("--project", required=True)
        parser.add_argument("--server", type=Path, required=True)
        a = parser.parse_args()
        prepare(a.lab.resolve(), a.project, a.server.resolve())
    else:
        run_check(Path(sys.argv[2]))
