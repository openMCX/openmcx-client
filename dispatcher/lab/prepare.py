#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""Turns a client of a prepared OpenMCX lab into a dispatcher position.

Lab mode (default): client-c becomes the dispatcher. Prepare the lab first with openmcx-server's
tools/prepare-prearranged-test.py (for example --private-call --mcdata), then run this on
its directory. client-c keeps its identity, certificates and configuration; its container
runs the dispatcher image, with media.live added to its configuration.

Position mode (--position, M3): in an MCX Admin lab (prepare-prearranged-test.py --admin),
client-e's slot becomes an unactivated dispatcher position: the device settings of an
activation client (no identity, no certificate) plus media.live; accounts are activated on
it from MCX Admin activation links and operators sign in on the page.

With --browser-login (lab mode), the operator signs in on IdMS's own page: the configuration
keeps no password (oauth.login "browser"), its redirect URI is the dispatcher's callback on
127.0.0.1:<port>, and the lab's IdMS (OpenMCX's, or Keycloak's client in a --keycloak lab) registers that
redirect URI.

Either way the web UI is published on 127.0.0.1:<port> of the host.
"""
import argparse
import json
from pathlib import Path

LIVE = {"listen": "127.0.0.1", "port": 7100, "send_host": "127.0.0.1", "send_port": 7101}
SESSION_HEALTHCHECK = {"test": ["CMD", "python3", "-c", ("import urllib.request; urllib.request.urlopen("
                                "'http://127.0.0.1:8080/api/session', timeout=2)")],
                       "interval": "3s", "timeout": "3s", "retries": 30, "start_period": "5s"}


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
    site_file = generated / "site.json"
    site = json.loads(site_file.read_text())
    if args.browser_login:
        callback = f"http://127.0.0.1:{args.port}/api/login/callback"
        for key in ("username", "password", "refresh_token_file", "forget_password"):
            config["oauth"].pop(key, None)
        config["oauth"].update(login="browser", redirect_uri=callback)
        if callback not in site["oauth_redirect_uris"]:
            site["oauth_redirect_uris"].append(callback)
        # A Keycloak lab (prepare-prearranged-test.py --keycloak): Keycloak's client registers it too.
        realm_file = generated / "keycloak" / "openmcx.json"
        if realm_file.exists():
            realm = json.loads(realm_file.read_text())
            for client in realm["clients"]:
                if client["clientId"] == config["oauth"]["client_id"] and callback not in client["redirectUris"]:
                    client["redirectUris"].append(callback)
            realm_file.write_text(json.dumps(realm, indent=2) + "\n")
    target = generated / f"{args.client}-dispatcher.json"
    target.write_text(json.dumps(config, indent=2) + "\n")

    # A dispatcher is an MC user with more rights (TS 23.280): give it the lab's widest
    # MCData rights and let it transmit on every MCData group it belongs to.
    mcdata_id = config["mcdata"]["user_id"]
    for user in site["users"]:
        if user.get("mcdata_id") == mcdata_id:
            user["mcdata"] = dict(user.get("mcdata", {}), transmit_data=True, incoming_any=True, emergency_alert=True,
                                  alert_cancel=True, alert_cancel_any_user=True, emergency_group=True,
                                  emergency_group_cancel=True, emergency_private=True, emergency_private_cancel=True,
                                  imminent_peril=True, imminent_peril_cancel=True,
                                  # Ad hoc group communications (TS 24.282 clause 24): started and joined.
                                  adhoc=True, adhoc_participation=True,
                                  # The storage of its communications in a message store (--msgstore, TS 24.282
                                  # clause 21.3): private ones and those of every group.
                                  store_comms=True, store_private_comms=True,
                                  store_group_comms=[group["id"] for group in site["groups"]])
    for group in site["groups"]:
        members = group.get("mcdata", {}).get("transmit_members")
        if members is not None and mcdata_id not in members:
            members.append(mcdata_id)
    # And the widest MCPTT rights (TS 24.484 user profile, TS 24.481 group document): emergency and imminent peril
    # calls, their cancellation and emergency alerts (also another user's) on every group, private calls to anyone,
    # emergency ones too, private calls from anyone, call-backs, first-to-answer calls, ambient listening (both
    # kinds), remotely initiated private and group calls, transfer and manual forwarding, and broadcast group calls
    # (the MCPTT server's broadcast_members, a lab policy).
    # client-a may request call-backs too, so that the scenario can ask the dispatcher for one.
    mcptt_id = config["mcptt"]["user_id"]
    peer = json.loads((generated / "client-a.json").read_text())["mcptt"]["user_id"]
    for user in site["users"]:
        if user.get("mcptt_id") == peer:
            user.setdefault("private_call", {}).update(callback=True, callback_cancel=True)
        if user.get("mcptt_id") == mcptt_id:
            user.setdefault("group_call", {}).update(emergency=True, emergency_cancel=True, imminent=True,
                                                     imminent_cancel=True, alert=True, alert_cancel=True,
                                                     remote_init=True)
            user.setdefault("private_call", {}).update(allow=True, automatic_commencement=True,
                                                       manual_commencement=True, any_target=True, emergency=True,
                                                       emergency_any=True, emergency_cancel=True, callback=True,
                                                       callback_cancel=True, incoming_any=True, first_to_answer=True,
                                                       ambient_remote=True, ambient_local=True, remote_init=True,
                                                       transfer=True, forward_manual=True)
    # In a lab with functional aliases (--functional-alias), the dispatcher may activate each of them (TS 24.379
    # clause 9A.2.2.2.3: the alias's user list) and take one over from its holder
    # (<allow-takeover-functional-alias-other-user>).
    for alias in site.get("functional_aliases", []):
        if mcptt_id not in alias.setdefault("users", []):
            alias["users"].append(mcptt_id)
    if site.get("functional_aliases"):
        for user in site["users"]:
            if user.get("mcptt_id") == mcptt_id:
                user["functional_alias_takeover"] = True
    # In a lab with ad hoc group calls (--adhoc), the dispatcher may start them, emergency and imminent peril ones
    # too, and change their participants (TS 24.484 <allow-adhoc-group-call>,
    # <allow-to-modify-adhoc-group-call-participants-info>).
    for user in site["users"]:
        if user.get("mcptt_id") == mcptt_id and "adhoc_call" in user:
            user["adhoc_call"].update(allow=True, participation=True, emergency=True, imminent=True, modify=True)
    # In a lab with regroups (--regroup), the dispatcher may create and remove regroups (TS 24.484 <allow-regroup>)
    # and form temporary groups (TS 24.481 clause 6.3.14.3.1, a group management right).
    if any(group.get("preconfigured_use_only") for group in site["groups"]):
        for user in site["users"]:
            if user.get("mcptt_id") == mcptt_id:
                user["regroup"] = True
                user.setdefault("group_management", {})["temporary"] = True
    for group in site["groups"]:
        policy = group.get("priority_policy")
        for key in ("emergency_users", "emergency_cancel_users", "imminent_users", "imminent_cancel_users",
                    "alert_users", "alert_cancel_users"):
            if policy is not None and mcptt_id not in policy.setdefault(key, []):
                policy[key].append(mcptt_id)
    site_file.write_text(json.dumps(site, indent=2) + "\n")
    for server_file in generated.glob("mcptt-*.json"):
        server = json.loads(server_file.read_text())
        members = server.get("mcptt", {}).get("call_policy", {}).get("broadcast_members")
        if members is not None and mcptt_id not in members:
            members.append(mcptt_id)
        # A transferred user keeps its call with the dispatcher while it calls the target (TS 24.379 clause
        # 11.1.8.2.2), so it is briefly in two private calls; the limit (N10, TS 24.484 <MaxCall>) must allow that.
        private = server.get("mcptt", {}).get("private_call")
        if private is not None and private.get("max_calls", 1) < 2:
            private["max_calls"] = 2
        server_file.write_text(json.dumps(server, indent=2) + "\n")

    inside = f"/config/{args.client}.json"
    for volume in service["volumes"]:
        if volume.get("target") == inside:
            volume["source"] = str(target.resolve())
    service["image"] = args.image
    service["entrypoint"] = ["/usr/local/bin/dispatcher"]
    service["command"] = ["--listen", "0.0.0.0:8080", "--config", inside, "--outbox", "/run/mcx/outbox"]
    service["ports"] = [f"127.0.0.1:{args.port}:8080"]
    if args.browser_login:
        # Nothing registers before an operator signs in: healthy is the page answering.
        service["healthcheck"] = SESSION_HEALTHCHECK


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
    service["healthcheck"] = SESSION_HEALTHCHECK


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("lab", type=Path, help="the prepared lab directory (with compose.json)")
    parser.add_argument("--generated", type=Path, help="the fixture's generated/ directory (default: <lab>/generated;"
                        " a lab with phones may keep it elsewhere)")
    parser.add_argument("--image", default="openmcx-dispatcher:disp")
    parser.add_argument("--client", default="client-c", help="lab mode: the client that becomes the dispatcher")
    parser.add_argument("--position", action="store_true", help="position mode, in an MCX Admin lab (client-e's slot)")
    parser.add_argument("--browser-login", action="store_true", help="lab mode: the operator signs in on IdMS's page")
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
