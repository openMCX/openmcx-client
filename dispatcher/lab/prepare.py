#!/usr/bin/env python3
"""Turns a client of a prepared OpenMCX lab into a dispatcher position.

Lab mode (default): client-c becomes the dispatcher. Prepare the lab first with openmcx-server's
tools/prepare-prearranged-test.py (for example --private-call --mcdata), then run this on
its directory. client-c keeps its identity, certificates and configuration; its container
runs the dispatcher image, with media.live added to its configuration.

Position mode (--position, M3): in an MCX Admin lab (prepare-prearranged-test.py --admin),
client-e's slot becomes an unactivated dispatcher position: the device settings of an
activation client (no identity, no certificate) plus media.live; accounts are activated on
it from MCX Admin activation links and operators sign in on the page.

Either way the web UI is published on 127.0.0.1:<port> of the host.
"""
import argparse
import json
from pathlib import Path

LIVE = {"listen": "127.0.0.1", "port": 7100, "send_host": "127.0.0.1", "send_port": 7101}


def lab_mode(args, compose, generated) -> None:
    service = compose["services"][args.client]
    config = json.loads((generated / f"{args.client}.json").read_text())
    config["media"]["live"] = LIVE
    config.pop("received_wav", None)  # received audio goes to the browser
    lab_meta = args.lab / "lab.json"
    server_address = args.server_address or (json.loads(lab_meta.read_text()).get("host_ip") if lab_meta.exists() else None)
    if server_address:
        # With a lab whose MCPTT server offers media at the host's address (phones on the LAN), the client checks
        # against that address.
        config["server_address"] = server_address
    target = generated / f"{args.client}-dispatcher.json"
    target.write_text(json.dumps(config, indent=2) + "\n")

    # A dispatcher is an MC user with more rights (TS 23.280): give it the lab's widest
    # MCData rights and let it transmit on every MCData group it belongs to.
    site_file = generated / "site.json"
    site = json.loads(site_file.read_text())
    mcdata_id = config["mcdata"]["user_id"]
    for user in site["users"]:
        if user.get("mcdata_id") == mcdata_id:
            user["mcdata"] = dict(user.get("mcdata", {}), transmit_data=True, incoming_any=True, emergency_alert=True,
                                  alert_cancel=True, alert_cancel_any_user=True, emergency_group=True,
                                  emergency_group_cancel=True, emergency_private=True, emergency_private_cancel=True,
                                  imminent_peril=True, imminent_peril_cancel=True)
    for group in site["groups"]:
        members = group.get("mcdata", {}).get("transmit_members")
        if members is not None and mcdata_id not in members:
            members.append(mcdata_id)
    site_file.write_text(json.dumps(site, indent=2) + "\n")

    inside = f"/config/{args.client}.json"
    for volume in service["volumes"]:
        if volume.get("target") == inside:
            volume["source"] = str(target.resolve())
    service["image"] = args.image
    service["entrypoint"] = ["/usr/local/bin/dispatcher"]
    service["command"] = ["--listen", "0.0.0.0:8080", "--config", inside, "--outbox", "/run/mcx/outbox"]
    service["ports"] = [f"127.0.0.1:{args.port}:8080"]


def position_mode(args, compose, generated) -> None:
    services = compose["services"]
    if "client-e" not in services or not (generated / "activate-e" / "device.json").exists():
        raise SystemExit("--position needs an MCX Admin lab (prepare-prearranged-test.py --admin)")
    service = services["client-e"]
    device = json.loads((generated / "activate-e" / "device.json").read_text())
    device["media"]["live"] = LIVE
    device.pop("received_wav", None)
    # Groups come from the user profile of the account signed in, not from this device.
    device.get("mcptt", {}).pop("allowed_groups", None)
    device.setdefault("mcptt", {})["group_from_profile"] = True
    # Registration refreshes often enough that Revoke devices ends a session soon; after a
    # sign-out the registration lapses within this time.
    device["sip"]["register_expires"] = args.register_expires
    target = generated / "dispatcher-device.json"
    target.write_text(json.dumps(device, indent=2) + "\n")
    data = generated / "dispatcher-data"
    data.mkdir(exist_ok=True)
    data.chmod(0o777)

    command = [a for a in service["command"] if a == "--disable-tls"]
    service["volumes"] = [v for v in service["volumes"] if v.get("target") != "/config/activate"] + [
        {"type": "bind", "source": str(target.resolve()), "target": "/config/device.json", "read_only": True},
        {"type": "bind", "source": str(data.resolve()), "target": "/data"}]
    service["image"] = args.image
    service["entrypoint"] = ["/usr/local/bin/dispatcher"]
    service["command"] = ["--listen", "0.0.0.0:8080", "--device", "/config/device.json", "--data", "/data",
                          "--outbox", "/run/mcx/outbox"] + (["--engine-args", " ".join(command)] if command else [])
    service["ports"] = [f"127.0.0.1:{args.port}:8080"]
    service.pop("profiles", None)
    service["healthcheck"] = {"test": ["CMD", "python3", "-c", "import urllib.request; urllib.request.urlopen("
                                       "'http://127.0.0.1:8080/api/session', timeout=2)"],
                              "interval": "3s", "timeout": "3s", "retries": 30, "start_period": "5s"}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("lab", type=Path, help="the prepared lab directory (with compose.json)")
    parser.add_argument("--generated", type=Path, help="the fixture's generated/ directory (default: <lab>/generated;"
                        " a lab with phones may keep it elsewhere)")
    parser.add_argument("--image", default="openmcx-dispatcher:disp")
    parser.add_argument("--client", default="client-c", help="lab mode: the client that becomes the dispatcher")
    parser.add_argument("--position", action="store_true", help="position mode, in an MCX Admin lab (client-e's slot)")
    parser.add_argument("--register-expires", type=int, default=120, help="position mode: SIP registration expiry (s)")
    parser.add_argument("--port", type=int, default=18080, help="host port of the web UI (on 127.0.0.1)")
    parser.add_argument("--server-address", help="lab mode: the address the MCPTT server advertises for media, when it"
                        " is not the lab's mcptt-server (a lab with phones: the host's LAN address)")
    args = parser.parse_args()

    compose_file = args.lab / "compose.json"
    compose = json.loads(compose_file.read_text())
    generated = args.generated or args.lab / "generated"
    if args.position:
        position_mode(args, compose, generated)
        who = "client-e's slot is a dispatcher position"
    else:
        lab_mode(args, compose, generated)
        who = f"{args.client} is a dispatcher"
    compose_file.write_text(json.dumps(compose, indent=1) + "\n")
    print(f"{who}: http://127.0.0.1:{args.port}/")


if __name__ == "__main__":
    main()
