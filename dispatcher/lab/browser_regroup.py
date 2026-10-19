#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""Browser check of the dispatcher's Regroup panel on a running lab prepared with --regroup (see prepare.py and
regroup_scenario.py), run like browser_check.py:
  docker run --rm --network host -v <lab>:/lab -v $PWD:/check -w /check \
    mcr.microsoft.com/playwright/python:v1.55.0-noble python3 browser_regroup.py /lab
"""
import json
import sys
from pathlib import Path

from playwright.sync_api import expect, sync_playwright

lab = Path(sys.argv[1])
url = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:18080/"
results = []


def check(name, ok, detail=""):
    results.append(bool(ok))
    print(("PASS " if ok else "FAIL ") + name + (f": {detail}" if detail and not ok else ""), flush=True)


site = json.loads((lab / "generated" / "site.json").read_text())
north = next(g["id"] for g in site["groups"] if "-north@" in g["id"])
south, temp = north.replace("-north@", "-south@"), north.replace("-north@", "-temp-8@")
preconf = next(g["id"] for g in site["groups"] if g.get("preconfigured_use_only") and g.get("type") != "chat")

with sync_playwright() as p:
    browser = p.chromium.launch()
    page = browser.new_page(viewport={"width": 1280, "height": 900})
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(url)
    expect(page.locator("#registration")).to_contain_text("registered", timeout=30000)
    # A group regroup needs its preconfigured group; a temporary group has none, but may be chat or protected.
    page.fill("#regroup input[name=tgi]", north.replace("-north@", "-regroup-9@"))
    page.fill("#regroup input[name=members]", north + " " + south)
    page.click("#regroup button")
    check("BRW-14 a regroup without its preconfigured group is not sent",
          page.locator("#regroup input[name=preconfigured]").evaluate("e => !e.disabled") and
          page.locator("#regroup-list .item").count() == 0)
    page.select_option("#regroup select[name=kind]", "temporary")
    check("BRW-15 a temporary group takes no preconfigured group",
          page.locator("#regroup input[name=preconfigured]").is_disabled() and
          page.locator("#regroup input[name=chat]").is_enabled())
    # A temporary group formed from the page, offered in the group picker, then deleted.
    page.fill("#regroup input[name=tgi]", temp)
    page.click("#regroup button")
    entry = page.locator("#regroup-list .item", has_text=temp)
    expect(entry).to_be_visible(timeout=25000)
    expect(page.locator("#group-select option", has_text=temp + " (regroup)")).to_have_count(1)
    entry.locator("button", has_text="Delete").click()
    expect(entry).to_have_count(0, timeout=25000)
    expect(page.locator("#group-select option", has_text=temp)).to_have_count(0)
    check("BRW-16 a temporary group formed from the page, offered in the group picker, deleted", True)
    # And a group regroup with its preconfigured group, removed from its list entry.
    rg = north.replace("-north@", "-regroup-9@")
    page.select_option("#regroup select[name=kind]", "create")
    page.fill("#regroup input[name=tgi]", rg)
    page.fill("#regroup input[name=preconfigured]", preconf)
    page.click("#regroup button")
    entry = page.locator("#regroup-list .item", has_text=rg)
    expect(entry).to_contain_text("as " + preconf, timeout=25000)
    entry.locator("button", has_text="Remove").click()
    expect(entry).to_have_count(0, timeout=25000)
    check("BRW-17 a group regroup created from the page and removed", True)
    page.screenshot(path=str(lab / "artifacts" / "dispatcher-regroup.png"), full_page=True)
    check("BRW-05 no script errors", not errors, "; ".join(errors))
    browser.close()

print(f"{sum(results)}/{len(results)} passed")
sys.exit(0 if all(results) else 1)
