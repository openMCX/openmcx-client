#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""The dispatcher's MCData message store (TS 24.282 clause 21), on a running lab prepared with --mcdata --msgstore
(see prepare.py, which lets the dispatcher store its private and group communications):

  DSP-36 storage of its communications enabled         DSP-41 a search by flag
  DSP-37 SDS stored, synchronised and read             DSP-42 a file from the browser stored
  DSP-38 flags set and removed                         DSP-43 notifications of changes
  DSP-39 a folder created, an object moved into it     DSP-44 objects and the folder deleted
  DSP-40 an object copied into the folder              DSP-45 storage disabled

Drives the dispatcher through its web API, as the page does.

  python3 lab/store_scenario.py <lab> --project <project> [--url http://127.0.0.1:18080]
"""
import argparse
import json
import sys
import uuid
from pathlib import Path

from scenario import Lab, check, results, wait

SEEN, FLAGGED = "\\Seen", "\\Flagged"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("lab", type=Path)
    parser.add_argument("--project", required=True)
    parser.add_argument("--url", default="http://127.0.0.1:18080")
    args = parser.parse_args()
    lab = Lab(args.lab, args.project, args.url)
    wait(lambda: lab.state().get("registered") and lab.state().get("mcdata_authorized"), 90)
    me = lab.state().get("identity", {}).get("mcdata_user")
    a_data = json.loads((args.lab / "generated" / "client-a.json").read_text())["mcdata"]["user_id"]

    def store():
        return lab.state().get("msgstore") or {}

    def last(method, status, path=""):
        request = store().get("last") or {}
        return request.get("method") == method and request.get("status") == status and path in request.get("url", "")

    def objects():
        return store().get("objects") or {}

    def text_of(oid):
        """The text of a stored SDS (its Text attribute), its payload read through the dispatcher too."""
        lab.act(action="msgstore", op="payload", id=oid)
        read = wait(lambda: (store().get("payload") or {}).get("object") == oid and
                    (store().get("payload") or {}).get("status") == 200 and store()["payload"].get("bytes", 0) > 0, 10)
        return objects().get(oid, {}).get("Text", "") if read else ""

    def synchronised(predicate, timeout=30):
        """Synchronises (TS 24.282 clause 21.2.17.1) until the predicate holds."""
        def again():
            lab.act(action="msgstore", op="sync")
            return wait(predicate, 3)
        return wait(again, timeout, step=0.1)

    # DSP-36: the dispatcher asks its participating function to store all its private and group communications
    # (TS 24.282 clause 21.3.2.2); the engine first checks <allow-store-comms-in-msgstore> in its profile.
    lab.act(action="msgstore-ctrl", op="enable", all_private=True, all_group=True)
    ok = wait(lambda: (store().get("control") or {}).get("status") == 200, 20)
    check("DSP-36 the dispatcher enables the storage of its communications: 200", ok, str(store().get("control")))

    # DSP-37: an SDS from client-a and one from the dispatcher are deposited (clause 21.2.5A); the dispatcher
    # synchronises and reads them.
    word = "stored-" + uuid.uuid4().hex[:8]
    lab.tell("client-a", f"sds {me} {word}-in")
    lab.act(action="sds", target=a_data, text=word + "-out")

    def stored(suffix):
        return next((oid for oid, o in objects().items() if o.get("Text") == word + suffix), None)

    synchronised(lambda: stored("-in") and stored("-out"))
    incoming, outgoing = stored("-in"), stored("-out")
    texts = {oid: text_of(oid) for oid in (incoming, outgoing) if oid}
    check("DSP-37 the SDS exchanged with client-a stored, synchronised, their payloads read",
          incoming and outgoing and texts.get(incoming) == word + "-in", json.dumps(texts)[:400])
    if not incoming:
        print(f"{sum(ok for _, ok in results)}/{len(results)} passed")
        sys.exit(1)

    # DSP-38: flags set (clause 21.2.3.1) and read back (clause 21.2.1.1), one removed again.
    for flag in (SEEN, FLAGGED):
        lab.act(action="msgstore", op="flag", ids=[incoming], flag=flag)
        wait(lambda: last("PUT", 204) or last("PUT", 200), 10)
    lab.act(action="msgstore", op="get", id=incoming)
    ok = wait(lambda: {SEEN, FLAGGED} <= set(objects().get(incoming, {}).get("flags", [])), 10)
    lab.act(action="msgstore", op="unflag", ids=[incoming], flag=SEEN)
    wait(lambda: last("DELETE", 204) or last("DELETE", 200), 10)
    lab.act(action="msgstore", op="get", id=incoming)
    # The store keeps flags of its own, such as \Recent.
    ok = ok and wait(lambda: FLAGGED in objects().get(incoming, {}).get("flags", []) and
                     SEEN not in objects().get(incoming, {}).get("flags", []), 10)
    check("DSP-38 \\Seen and \\Flagged set on the stored SDS, \\Seen removed again", ok,
          str(objects().get(incoming)))

    # DSP-39: a folder created (clause 21.2.8.1), listed (clause 21.2.23.1), and the SDS moved into it (clause
    # 21.2.6.1).
    name = "dsp-" + uuid.uuid4().hex[:6]
    lab.act(action="msgstore", op="folder-create", name=name)
    wait(lambda: last("POST", 201, "/folders"), 10)
    lab.act(action="msgstore", op="folders")
    ok = wait(lambda: any(path.rstrip("/").endswith(name) for path in store().get("folders") or {}), 10)
    folder = next((fid for path, fid in (store().get("folders") or {}).items() if path.rstrip("/").endswith(name)), "")
    lab.act(action="msgstore", op="move", folder=folder, ids=[incoming])
    wait(lambda: last("POST", 200, "moveToFolder") or last("POST", 204, "moveToFolder"), 10)
    lab.act(action="msgstore", op="folder", id=folder)
    ok = ok and wait(lambda: incoming in ((store().get("folder") or {}).get("objects") or []), 10)
    check("DSP-39 a folder created and listed; the SDS moved into it", ok,
          f"{store().get('folders')} {store().get('folder')}")

    # DSP-40: the other SDS copied into the folder (clause 21.2.10.1).
    lab.act(action="msgstore", op="copy", folder=folder, ids=[outgoing])
    wait(lambda: last("POST", 200, "copyToFolder") or last("POST", 201, "copyToFolder"), 10)
    lab.act(action="msgstore", op="folder", id=folder)
    ok = wait(lambda: len((store().get("folder") or {}).get("objects") or []) == 2, 10)
    check("DSP-40 the other SDS copied into the folder: two objects there", ok, str(store().get("folder")))

    # DSP-41: a search by flag (clause 21.2.2.1) finds the flagged SDS only.
    lab.act(action="msgstore", op="search", criteria=["flag=" + FLAGGED])
    ok = wait(lambda: store().get("search") == [incoming], 10)
    check("DSP-41 a search for \\Flagged finds the flagged SDS", ok, str(store().get("search")))

    # DSP-42: a file from the browser stored as a new object (clause 21.2.15.1).
    file_name = f"note-{uuid.uuid4().hex[:6]}.txt"
    boundary = uuid.uuid4().hex
    form = b"".join([
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{file_name}\"\r\n"
        f"Content-Type: text/plain\r\n\r\n".encode(), b"stored from the dispatcher\n", f"\r\n--{boundary}--\r\n".encode()])
    status, raw = lab.api("POST", "/api/msgstore/upload", raw=form,
                          headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    ok = status == 202 and synchronised(lambda: any(o.get("Subject") == file_name for o in objects().values()))
    uploaded = next((oid for oid, o in objects().items() if o.get("Subject") == file_name), "")
    check("DSP-42 a file from the browser stored as an object", ok, f"{status} {raw[:200]!r}")

    # DSP-43: the notification channel and the store's subscription (clauses 21.2.19.1 and 21.2.12A.1): a new SDS
    # is notified without a synchronisation.
    lab.act(action="msgstore", op="subscribe")
    subscribed = wait(lambda: (store().get("subscription") or {}).get("resourceURL"), 15)
    events = store().get("events", 0)
    lab.tell("client-a", f"sds {me} {word}-notified")
    ok = subscribed and wait(lambda: store().get("events", 0) > events, 30)
    check("DSP-43 subscribed to the store's changes; a new SDS is notified", ok,
          f"{store().get('subscription')} events={store().get('events')}")
    lab.act(action="msgstore", op="unsubscribe")
    wait(lambda: not (store().get("subscription") or {}).get("resourceURL"), 15)

    # DSP-44: the objects deleted in bulk (clause 21.2.4.1) and the folder deleted (clause 21.2.7.1).
    gone = [oid for oid in (incoming, outgoing, uploaded) if oid]
    lab.act(action="msgstore", op="delete", ids=gone)
    wait(lambda: last("POST", 200, "bulkDelete") or last("POST", 204, "bulkDelete"), 10)
    ok = synchronised(lambda: not set(gone) & set(objects()))
    lab.act(action="msgstore", op="folder-delete", id=folder)
    wait(lambda: last("DELETE", 204, "/folders/") or last("DELETE", 200, "/folders/"), 10)
    lab.act(action="msgstore", op="folders")
    ok = ok and wait(lambda: not any(path.rstrip("/").endswith(name) for path in store().get("folders") or {}), 10)
    check("DSP-44 the objects deleted in bulk, then the folder", ok, f"{store().get('last')}")

    # DSP-45: storage disabled again (clause 21.3.2.3).
    lab.act(action="msgstore-ctrl", op="disable", all_private=True, all_group=True)
    ok = wait(lambda: (store().get("control") or {}).get("action") == "disable" and
              (store().get("control") or {}).get("status") == 200, 20)
    check("DSP-45 storage of its communications disabled: 200", ok, str(store().get("control")))

    failed = [n for n, ok in results if not ok]
    print(f"{len(results) - len(failed)}/{len(results)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
