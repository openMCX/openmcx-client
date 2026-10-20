#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

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


def waited(predicate, seconds=15):
    """Whether predicate holds within the seconds."""
    deadline = time.time() + seconds
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(0.3)
    return False


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
    check("BRW-02 coming-soon options are shown disabled", page.locator("#soon button:disabled").count() == 6)

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
    # During the call: raised to emergency, then back to normal; client-a follows.
    page.click("#raise-emergency")
    expect(page.locator("#priority-off")).to_be_enabled(timeout=15000)
    raised = waited(lambda: client("client-a").get("priority") == "emergency")
    page.click("#priority-off")
    expect(page.locator("#priority-off")).to_be_disabled(timeout=15000)
    check("BRW-06 the call raised to emergency from the page and back to normal", raised and
          page.locator("#raise-emergency").is_enabled(), str(client("client-a").get("priority")))
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
    # The dispatcher's own emergency alert, listed by user ID, then cancelled.
    me = json.loads((lab / "generated" / "client-c.json").read_text())["mcptt"]["user_id"]
    page.click("button[data-action=alert][data-on=true]")
    expect(page.locator("#alerts")).to_contain_text("Emergency alert: " + me, timeout=15000)
    page.click("button[data-action=alert][data-on=false]")
    expect(page.locator("#alerts")).not_to_contain_text(me, timeout=15000)
    check("BRW-07 the alert list names the user, and the alert is cancelled", True)
    check("BRW-08 the call actions are offered: broadcast, the priority buttons idle, no floor control",
          page.locator("button[data-action=call][data-kind=broadcast]").is_visible() and
          page.locator("#raise-emergency").is_disabled() and page.locator("#private input[name=no_floor]").count() == 1)
    # A call-back asked of client-b from the private call field, listed, then cancelled from the list.
    bob = json.loads((lab / "generated" / "client-b.json").read_text())["mcptt"]["user_id"]
    page.fill("#private input[name=target]", bob)
    page.select_option("#private select[name=urgency]", "high")
    page.click("#callback-request")
    expect(page.locator("#callbacks")).to_contain_text("Call-back asked of " + bob, timeout=15000)
    page.locator("#callbacks button", has_text="Cancel").click()
    expect(page.locator("#callbacks")).not_to_contain_text(bob, timeout=15000)
    check("BRW-09 call-back requested from the page, listed and cancelled", True)
    page.check("#chat")
    check("BRW-10 the chat group option leaves no broadcast call", page.locator("#broadcast").is_disabled())
    page.uncheck("#chat")
    # Call services: idle, the transfer and forward buttons wait for a private call; first-to-answer and ambient
    # listening start from the page.
    check("BRW-11 call services offered, transfer and forward disabled while idle",
          page.locator("#transfer-now").is_disabled() and page.locator("#forward").is_disabled() and
          page.locator("#fta button").is_enabled() and page.locator("#ambient button").is_enabled())
    alice = json.loads((lab / "generated" / "client-a.json").read_text())["mcptt"]["user_id"]
    page.fill("#ambient input[name=target]", alice)
    page.click("#ambient button")
    expect(page.locator("#call-state")).to_contain_text("ambient listening: listening to " + alice, timeout=20000)
    page.click("button[data-action=hangup]")
    expect(page.locator("#call-state")).not_to_contain_text("ambient", timeout=15000)
    check("BRW-12 ambient listening started from the page, shown, and released", True)
    # Functional aliases (a lab with --functional-alias): one activated from the page, used on calls, deactivated.
    site = json.loads((lab / "generated" / "site.json").read_text())
    car = next((a["id"] for a in site.get("functional_aliases", []) if "patrol-car-12" in a["id"]), None)
    if car:
        page.fill("#fa input[name=alias]", car)
        page.click("#fa-activate")
        entry = page.locator("#fa-list .item", has_text=car).filter(has_text="activated").first
        expect(entry).to_be_visible(timeout=20000)
        entry.locator("button", has_text="Use on calls").click()
        expect(page.locator("#fa-using")).to_contain_text("Calls present " + car, timeout=15000)
        entry.locator("button", has_text="Stop using").click()
        expect(page.locator("#fa-using")).to_have_text("", timeout=15000)
        entry.locator("button", has_text="Deactivate").click()
        expect(page.locator("#fa-list .item", has_text=car).filter(has_text="presented")).to_have_count(0)
        expect(page.locator("#fa-list")).to_contain_text(car + ": deactivated", timeout=20000)
        page.check("#private input[name=to_alias]")
        check("BRW-13 functional alias activated, used on calls and deactivated from the page; no emergency call "
              "to an alias", page.locator("#private input[name=emergency]").is_disabled())
        page.uncheck("#private input[name=to_alias]")
    # The message store (a lab with --msgstore): synchronised, a folder created and deleted from the page.
    dispatcher = json.loads((lab / "generated" / "client-c-dispatcher.json").read_text())
    if "msgstore" in dispatcher.get("endpoints", {}):
        page.click("#store-sync")
        expect(page.locator("#store-last")).to_contain_text("objects/operations/search", timeout=20000)
        name = "brw-" + uuid.uuid4().hex[:6]
        page.fill("#store-new-folder input[name=name]", name)
        page.click("#store-new-folder button")
        page.click("#store-folders")
        entry = page.locator("#store-folder-list .item", has_text=name)
        expect(entry).to_be_visible(timeout=20000)
        listed = page.locator("#store-folder option", has_text=name).count() == 1
        entry.locator("button", has_text="Delete").click()
        page.click("#store-folders")
        expect(entry).to_have_count(0, timeout=20000)
        check("BRW-18 message store synchronised; a folder created, offered and deleted from the page", listed)
    # Ad hoc group calls (a lab with --adhoc): one to client-a from the page, its participants changeable during it.
    if any("adhoc_call" in user for user in site["users"]):
        check("BRW-19a ad hoc participant changes wait for the call", page.locator("#adhoc-add").is_disabled())
        page.fill("#adhoc input[name=targets]", alice)
        page.click("#adhoc button")
        expect(page.locator("#adhoc-state")).to_contain_text("Ad hoc group sip:", timeout=20000)
        expect(page.locator("#adhoc-add")).to_be_enabled(timeout=10000)
        page.click("button[data-action=hangup]")
        expect(page.locator("#call-state")).to_contain_text("idle", timeout=15000)
        check("BRW-19b an ad hoc group call to client-a from the page, named by the server, hung up", True)
    # An SDS session with client-a and enhanced status on the lab's MCData group (TS 24.282 clauses 9.2.4, 14.2).
    alice_data = json.loads((lab / "generated" / "client-a.json").read_text())["mcdata"]["user_id"]
    check("BRW-20a nothing to send before a session is open", page.locator("#session-send button", has_text="Send").is_disabled())
    page.fill("#session input[name=target]", alice_data)
    page.click("#session button")
    expect(page.locator("#session-state")).to_contain_text("open", timeout=20000)
    word = "brw-" + uuid.uuid4().hex[:6]
    page.fill("#session-send input[name=text]", word)
    page.locator("#session-send button", has_text="Send").click()
    got = waited(lambda: any(word in [x.get("text") for x in m.get("payloads", [])]
                             for m in client("client-a").get("mcdata_media_received") or []))
    page.click("#session-close")
    expect(page.locator("#session-send button", has_text="Send")).to_be_disabled(timeout=15000)
    check("BRW-20b an SDS session with client-a opened, used and closed from the page", got)
    group = site["groups"][0]["id"]
    page.fill("#status input[name=group]", group)
    page.click("#status button")
    value = page.locator("#status-values .item", has_text=group).locator("button").first
    expect(value).to_be_visible(timeout=15000)
    sent = value.text_content()
    value.click()
    check("BRW-21 enhanced status: the group's values offered, one sent to client-a",
          waited(lambda: any(e.get("value") == sent for e in client("client-a").get("mcdata_enhanced_status") or [])), sent)
    page.screenshot(path=str(lab / "artifacts" / "dispatcher.png"), full_page=True)
    check("BRW-05 no script errors", not errors, "; ".join(errors))
    browser.close()

print(f"{sum(results)}/{len(results)} passed")
sys.exit(0 if all(results) else 1)
