#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""Browser check of the sign-in on IdMS's own page (prepare.py --browser-login), in Chromium,
on a running lab: the dispatcher sends the browser to IdMS, IdMS's page signs the operator in
and sends the browser back to the dispatcher's callback (TS 33.180 annex B.4.2). The browser
runs on the host network and reaches IdMS by its issuer host name, mapped to IdMS's container:

  docker run --rm --network host --add-host <issuer host>:<IdMS container address> -v playwright-py:/py \\
    -v <lab>:/lab -v $PWD/dispatcher/lab:/check -w /check mcr.microsoft.com/playwright/python:v1.55.0-noble \\
    sh -c 'PYTHONPATH=/py python3 browser_idms.py /lab [<url> <client> <seconds to wait for a token renewal>]'
"""
import json
import sys
import time
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import expect, sync_playwright

lab = Path(sys.argv[1])
url = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:18080/"
client = sys.argv[3] if len(sys.argv) > 3 else "client-c"
# With a short token lifetime on the lab's IdMS (site.json token_ttl_seconds), wait this long for a renewal.
renewal_wait = int(sys.argv[4]) if len(sys.argv) > 4 else 0
account = json.loads((lab / "generated" / f"{client}.json").read_text())
issuer = account["oauth"]["issuer"]
# The client's account: its username and password are IdMS's, in the lab's site description.
user = next(u for u in json.loads((lab / "generated" / "site.json").read_text())["users"]
            if u["mcptt_id"] == account["mcptt"]["user_id"])
# The submit button of OpenMCX's IdMS page, or of Keycloak's (#kc-login).
SUBMIT = "#kc-login, button[type=submit]"
results = []


def check(name, ok, detail=""):
    results.append(bool(ok))
    print(("PASS " if ok else "FAIL ") + name + (f": {detail}" if detail and not ok else ""), flush=True)


def engine_status():
    try:
        return json.loads((lab / "artifacts" / client / "status.json").read_text())
    except (OSError, ValueError):
        return {}


with sync_playwright() as p:
    browser = p.chromium.launch()
    # The lab's IdMS has a certificate of the lab's own CA.
    page = browser.new_page(ignore_https_errors=True)
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(url)
    expect(page.locator("#idms-login")).to_be_visible(timeout=15000)
    host = urlparse(issuer).netloc
    check("BL-01 the sign-in page offers IdMS's page, named, no password form",
          page.locator("#idms-login").is_visible() and not page.locator("#login").is_visible() and
          host in page.locator("#idms-login").inner_text())

    page.click("#idms-login button")
    page.wait_for_url(issuer + "/**", timeout=15000)
    on_idms = page.locator("input[name=password]").is_visible()
    check("BL-02 the browser is on IdMS's own sign-in page", on_idms, page.url)

    page.fill("input[name=username]", user["username"])
    page.fill("input[name=password]", "not-the-password")
    page.click(SUBMIT)
    # OpenMCX's IdMS sends the browser back refused (RFC 6749 4.1.2.1); Keycloak keeps it on its page, which says so.
    page.wait_for_load_state()
    if page.url.startswith(issuer):
        refused = page.locator("input[name=password]").is_visible() and not page.url.startswith(url)
    else:
        page.wait_for_url(url + "**", timeout=15000)
        expect(page.locator("#signin-reason")).to_contain_text("access_denied", timeout=15000)
        refused = True
    check("BL-03 a wrong password is refused", refused, page.url)

    if not page.url.startswith(issuer):
        page.click("#idms-login button")
        page.wait_for_url(issuer + "/**", timeout=15000)
    page.fill("input[name=username]", user["username"])
    page.fill("input[name=password]", user["password"])
    page.click(SUBMIT)
    page.wait_for_url(url + "**", timeout=15000)
    expect(page.locator("#registration")).to_contain_text("registered", timeout=60000)
    expect(page.locator("#identity")).to_contain_text("signed in at " + host, timeout=10000)
    check("BL-04 signed in on IdMS's page, the dispatcher registers and names the IdMS", True)
    expect(page.locator("#identity")).to_contain_text(account["mcptt"]["user_id"], timeout=10000)
    status = engine_status()
    check("BL-05 the engine exchanged the code, no password", status.get("browser_logins") == 1 and
          status.get("password_logins") == 0 and status.get("authorized") is True, json.dumps(status)[:300])
    page.screenshot(path=str(lab / "artifacts" / "dispatcher-idms-signed-in.png"), full_page=True)
    if renewal_wait:
        # TS 33.180 annex B.5: renewals use the refresh token alone; the session goes on.
        deadline = time.time() + renewal_wait
        while time.time() < deadline and engine_status().get("token_refreshes", 0) < 1:
            time.sleep(2)
        status = engine_status()
        check("BL-05b the token is renewed with the refresh token, still signed in",
              status.get("token_refreshes", 0) >= 1 and status.get("password_logins") == 0 and
              status.get("authorized") is True and page.locator("#signout").is_visible(), json.dumps(status)[:300])

    page.click("#signout")
    expect(page.locator("#idms-login")).to_be_visible(timeout=15000)
    check("BL-06 signing out ends the session", True)
    check("BL-07 no script errors on the page", not errors, "; ".join(errors))
    browser.close()

print(f"{sum(results)}/{len(results)} passed")
sys.exit(0 if all(results) else 1)
