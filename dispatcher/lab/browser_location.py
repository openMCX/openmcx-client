#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""Browser check of the dispatcher's Location panel on a running lab prepared with --location (see prepare.py and
location_scenario.py), run like browser_check.py:
  docker run --rm --network host -v <lab>:/lab -v $PWD:/check -w /check \\
    mcr.microsoft.com/playwright/python:v1.55.0-noble python3 browser_location.py /lab
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


with sync_playwright() as p:
    browser = p.chromium.launch()
    page = browser.new_page(viewport={"width": 1280, "height": 900})
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(url)
    expect(page.locator("#registration")).to_contain_text("registered", timeout=30000)
    # client-b shares its location with everyone (see location_scenario.py).
    bob = json.loads((lab / "generated" / "client-b.json").read_text())["mcptt"]["user_id"]
    page.fill("#location input[name=targets]", bob)
    page.check("#location input[name=refresh]")
    page.click("#location button:text-is('Locate')")
    entry = page.locator("#location-list .item", has_text=bob)
    expect(entry).to_contain_text(".", timeout=20000)
    link = entry.locator("a", has_text="Map")
    href = link.get_attribute("href") or ""
    check("BRW-23 client-b located from the page, its position listed with a map link opening apart",
          href.startswith("https://www.openstreetmap.org/?mlat=") and link.get_attribute("target") == "_blank", href)
    expect(page.locator("#location-result")).to_contain_text("Location request: 200")
    page.screenshot(path=str(lab / "artifacts" / "dispatcher-location.png"), full_page=True)
    check("BRW-05 no script errors", not errors, "; ".join(errors))
    browser.close()

print(f"{sum(results)}/{len(results)} passed")
sys.exit(0 if all(results) else 1)
