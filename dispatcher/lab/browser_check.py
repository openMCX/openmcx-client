#!/usr/bin/env python3
"""Browser check of the dispatcher UI on a running lab (see prepare.py), in Chromium with
its fake microphone. Run it in the Playwright image with the host network, for example:
  docker run --rm --network host -v <lab>:/lab -v $PWD:/check -w /check \
    mcr.microsoft.com/playwright/python:v1.55.0-noble python3 browser_check.py /lab
"""
import json
import sys
import time
import uuid
from pathlib import Path

from playwright.sync_api import expect, sync_playwright

lab = Path(sys.argv[1])
url = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:18080/"
results = []


def check(name, ok, detail=""):
    results.append(bool(ok))
    print(("PASS " if ok else "FAIL ") + name + (f": {detail}" if detail and not ok else ""), flush=True)


def client(name):
    try:
        return json.loads((lab / "artifacts" / name / "status.json").read_text())
    except (OSError, ValueError):
        return {}


with sync_playwright() as p:
    browser = p.chromium.launch(args=["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream",
                                      "--autoplay-policy=no-user-gesture-required"])
    page = browser.new_page(viewport={"width": 1280, "height": 900}, permissions=["microphone"])
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(url)
    expect(page.locator("#registration")).to_contain_text("registered", timeout=30000)
    check("BRW-01 page shows the dispatcher registered", True)
    check("BRW-02 coming-soon options are shown disabled", page.locator("#soon button:disabled").count() == 7)

    page.click("button[data-action=call][data-kind=normal]")
    expect(page.locator("#call-state")).to_contain_text("active", timeout=20000)
    expect(page.locator("#ptt")).to_be_enabled()
    before = client("client-a").get("received_packets", 0)
    box = page.locator("#ptt").bounding_box()
    page.mouse.move(box["x"] + box["width"] / 2, box["y"] + box["height"] / 2)
    page.mouse.down()
    expect(page.locator("#floor-state")).to_contain_text("HasPermission", timeout=10000)
    time.sleep(3)
    page.screenshot(path=str(lab / "artifacts" / "dispatcher-talking.png"), full_page=True)
    page.mouse.up()
    got = client("client-a").get("received_packets", 0) - before
    check("BRW-03 hold to talk sends the browser microphone to client-a", got >= 80, f"packets={got}")
    expect(page.locator("#floor-state")).not_to_contain_text("HasPermission", timeout=10000)
    page.click("button[data-action=hangup]")
    expect(page.locator("#call-state")).to_contain_text("idle", timeout=15000)

    target = json.loads((lab / "generated" / "client-a.json").read_text())["mcdata"]["user_id"]
    text = "browser " + uuid.uuid4().hex[:8]
    page.fill("#sds input[name=target]", target)
    page.fill("#sds input[name=text]", text)
    page.click("#sds button")
    deadline = time.time() + 20
    delivered = False
    while time.time() < deadline and not delivered:
        delivered = any(any(q.get("text") == text for q in m.get("payloads", [])) for m in client("client-a").get("mcdata_inbox", []))
        time.sleep(0.3)
    check("BRW-04 SDS sent from the page reaches client-a", delivered)
    page.screenshot(path=str(lab / "artifacts" / "dispatcher.png"), full_page=True)
    check("BRW-05 no script errors", not errors, "; ".join(errors))
    browser.close()

print(f"{sum(results)}/{len(results)} passed")
sys.exit(0 if all(results) else 1)
