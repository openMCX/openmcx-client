#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""Create and run an Android test lab: the OpenMCX servers of an openmcx-server checkout with
redroid containers as the MC UEs, each running the Android client.

    lab/androidlab.py create --name a1 [--debug-sip]
    lab/androidlab.py up a1                 # servers and Android containers, then install and provision
    lab/androidlab.py install a1            # reinstall the debug APK and reprovision every client
    lab/androidlab.py exec a1 client-a status
    lab/androidlab.py create --name p1 --phone client-a --phone-ip 192.0.2.23   # phone mode (docs/PHONE.md)
    lab/androidlab.py create --name fd1 --phones-only --fd-max-bytes 100000000 \
        --phone-device client-a:SERIAL:192.0.2.23 --phone-device client-b:SERIAL:192.0.2.24   # two phones, large files
    lab/androidlab.py qr p1 client-a        # serve client-a's profile once and show its QR code (Settings > Scan QR code)
    lab/androidlab.py qr p1 client-a --apply    # the same, handing the link to the phone's app without the camera
    lab/androidlab.py create --name m1 --phones-only --admin --admin-image openmcx-admin:0.1 --phone-device ...   # MCX Admin manages the accounts
    lab/androidlab.py admin-activate m1 client-a --user p1   # a new user in MCX Admin, activated on the phone from its code
    lab/androidlab.py admin-activate m1 --user p2 --qr       # the same for a phone the lab does not drive: shows its QR code
    lab/androidlab.py down a1

The server side comes from OpenMCX's own fixture generator (tools/prepare-prearranged-test.py),
unchanged: its SIP core reaches these clients, which have no listener, over the connections
they registered on (RFC 5626 flows; docs/SIP_CORE_FLOW_REUSE.md). Each client is a redroid container on the lab network, under the reference
client's hostname, so the provisioned TLS certificates still match. It is driven over adb
through the debug-build control endpoint.

The host needs the binder driver: sudo modprobe binder_linux devices=binder,hwbinder,vndbinder
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]  # android/
RUNS = ROOT / "lab" / "run"
CLIENTS = ["client-a", "client-b", "client-c"]
REDROID = "redroid/redroid:15.0.0_64only-latest"
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
PACKAGE = "org.openmcx.client"
FILES = f"/data/user/0/{PACKAGE}/files"
CONTROL = "mcxclient-control"  # the debug build's control socket (@mcxclient-control)
ADB = Path(shutil.which("adb") or Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk")) / "platform-tools/adb")


def speech_wav(path: Path, seed: int, seconds: float = 6.0) -> None:
    """A deterministic speech-like test signal (16 kHz mono 16-bit): a voiced source with a
    moving pitch through two moving formant resonators, in syllable-length bursts."""
    import math, random, struct, wave
    rate = 16000
    rnd = random.Random(seed)
    samples, phase = [], 0.0
    y1 = y2 = z1 = z2 = 0.0
    for n in range(int(seconds * rate)):
        t = n / rate
        syllable = (t * 4.0) % 1.0
        envelope = math.sin(math.pi * syllable) ** 2 if syllable < 0.8 else 0.0
        pitch = 110 + 40 * math.sin(2 * math.pi * 0.7 * t + seed)
        phase += pitch / rate
        source = (phase % 1.0) - 0.5 + 0.05 * (rnd.random() - 0.5)
        out = []
        for f, bw, state in ((500 + 300 * math.sin(2 * math.pi * 1.3 * t + seed), 80, 0), (1500 + 700 * math.sin(2 * math.pi * 0.9 * t), 120, 1)):
            r = math.exp(-math.pi * bw / rate)
            a1, a2 = 2 * r * math.cos(2 * math.pi * f / rate), -r * r
            if state == 0:
                y = source + a1 * y1 + a2 * y2; y2, y1 = y1, y; out.append(y)
            else:
                z = source + a1 * z1 + a2 * z2; z2, z1 = z1, z; out.append(z)
        v = int(max(-32767, min(32767, 900 * envelope * (out[0] + 0.5 * out[1]))))
        samples.append(v)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
        w.writeframes(struct.pack(f"<{len(samples)}h", *samples))


def compose_bin(openmcx: Path) -> list[str]:
    local = openmcx / ".cache/deps/docker-compose-linux-x86_64"
    return [str(local)] if local.exists() else ["docker", "compose"]


def provisioning(ref: dict, site: dict, role: str, debug: bool, fd: dict | None = None) -> dict:
    """The Android client's provisioning bundle from the reference client's provisioned configuration."""
    sip, mcptt, mcdata, oauth = ref["sip"], ref["mcptt"], ref.get("mcdata"), ref["oauth"]
    bundle = {
        "name": role,
        "domain": site["domain"],
        "client_id": mcptt["client_id"],
        "sip": {"public_user_id": sip["identity"], "private_user_id": sip["username"], "password": sip["password"],
                "proxy": "sip-core", "proxy_port": 5060 if debug else 5061, "transport": "TCP" if debug else "TLS",
                "register_expires": 3600},
        "tls": {"ca": "ca.crt", "cert": "client.crt", "key": "client.key"},
        "idms": {"issuer": oauth["issuer"], "client_id": oauth["client_id"], "redirect_uri": oauth["redirect_uri"],
                 "username": oauth["username"], "password": oauth["password"]},
        "cms": ref["endpoints"]["cms"],
        "gms": ref["endpoints"]["gms"],
        "mcptt": {"user_id": mcptt["user_id"], "psi": mcptt["service_uri"], "groups": [mcptt["group_id"]],
                  "answer_mode": "automatic", "session_expires": 1800, "manual_answer_timeout": 20,
                  # The functional aliases this user may activate (TS 24.379 clause 9A), from the site's definitions.
                  "functional_aliases": [fa["id"] for fa in site.get("functional_aliases", [])
                                         if mcptt["user_id"] in fa.get("users", [])]},
        "media": {"port_range": [20000, 20400], "amr_wb_mode": 2},
    }
    if mcdata:
        bundle["mcdata"] = {"user_id": mcdata["user_id"], "psi": mcdata["service_uri"], "groups": [mcdata["group_id"]],
                            "max_payload_size_sds_cplane_bytes": 1000, "tdu1_seconds": 10,
                            # MCData functional aliases (TS 24.282 clause 22): those whose MCData user list names this user.
                            "functional_aliases": [fa["id"] for fa in site.get("functional_aliases", [])
                                                   if mcdata["user_id"] in fa.get("mcdata_users", [])]}
        if fd:
            bundle["mcdata"]["fd"] = fd
    mcvideo = ref.get("mcvideo")
    if mcvideo:
        # Chat groups (TS 24.281 §9.2.2) the user belongs to: joined on demand from the Call tab.
        chats = [g["id"] for g in site.get("groups", []) if g.get("type") == "chat"
                 and mcvideo["user_id"] in (g.get("mcvideo") or {}).get("members", [])]
        bundle["mcvideo"] = {"user_id": mcvideo["user_id"], "psi": mcvideo["service_uri"], "groups": [mcvideo["group_id"]],
                             "chat_groups": chats, "answer_mode": "automatic",
                             # MCVideo functional aliases (TS 24.281 clause 20): those whose <mcvideo-user-list> names this user.
                             "functional_aliases": [fa["id"] for fa in site.get("functional_aliases", [])
                                                    if mcvideo["user_id"] in fa.get("mcvideo_users", [])]}
    security = ref.get("security")
    if security:
        # TS 33.180: the KMS, the Key Parameters wire profile, and what is protected (the fixture's --protected).
        bundle["security"] = {k: security[k] for k in ("kms_uri", "gms_uri", "key_parameter_profile", "protect_media", "protect_floor") if k in security}
    return bundle


def host_ip() -> str:
    """The host's address on its default route: what a phone on the same LAN reaches."""
    out = subprocess.run(["ip", "-4", "route", "get", "1.1.1.1"], capture_output=True, text=True, check=True).stdout
    return out.split(" src ")[1].split()[0]


# Phone mode: what the host publishes (host port -> container port), and the MCPTT server's media ports.
PHONE_SIP_PORT, PHONE_IDMS_PORT, PHONE_PROXY_PORT, PHONE_KMS_PORT = 5061, 8443, 8444, 8445
MEDIA_PORTS = ("4000-4199", "5000-5199")  # the MCPTT server's RTP and floor ports (4000+2k, 5000+2k per leg)
# The MCVideo server's audio, transmission control and video bases, moved off the MCPTT server's for phones.
MCVIDEO_BASES = {"rtp_port": 7000, "floor_port": 7200, "video_port": 7400}


def mcptt_server_name(services: dict) -> str:
    """The MCPTT server's service name: mcptt-server (mcx-server in older server checkouts)."""
    return "mcptt-server" if "mcptt-server" in services else "mcx-server"


def file_distribution(fixture: Path, compose: dict, users: list[str], max_bytes: int, availability_s: int,
                      timeout_ms: int) -> dict:
    """Large-file settings for the phone lab, consistent across the servers and the phones: the
    service limit, the phone users' one-to-one limit and the first group's limit are all
    [max_bytes]; the phone users may send files to anyone and receive them from anyone; files stay
    [availability_s] seconds; the proxy passes [max_bytes] plus 64 KiB of multipart framing, and
    one transfer may take [timeout_ms]. Returns the phones' provisioned fd settings."""
    generated = fixture / "generated"
    site_file = generated / "site.json"
    site = json.loads(site_file.read_text())
    site.setdefault("mcdata_service", {})["max_data_size_fd_bytes"] = max_bytes
    for user in site["users"]:
        if user.get("mcdata_id") in users:
            policy = user.setdefault("mcdata", {})
            policy.update(transmit_data=True, incoming_any=True, max_data_1to1=max_bytes)
            policy.pop("targets", None)
            policy.pop("incoming_from", None)
    group = site["groups"][0]
    settings = group.setdefault("mcdata", {})
    settings.update(allow_fd=True, fd_supported=True, max_fd_bytes=max_bytes)
    if "transmit_members" in settings:
        settings["transmit_members"] = sorted(set(settings["transmit_members"]) | set(users))
    site_file.write_text(json.dumps(site, indent=2) + "\n")
    server_file = generated / "mcdata-server.json"
    server = json.loads(server_file.read_text())
    fd = server["mcdata"].setdefault("fd", {})
    fd["default_file_availability_ms"] = availability_s * 1000
    fd["max_file_availability_ms"] = max(fd.get("max_file_availability_ms", 0), availability_s * 1000)
    server_file.write_text(json.dumps(server, indent=2) + "\n")
    services = compose["services"]
    services["http-proxy"]["command"] = [*services["http-proxy"]["command"],
                                         "--content-request-limit-bytes", str(max_bytes + 65536),
                                         "--file-transfer-timeout-ms", str(timeout_ms)]
    services["content-server"]["command"] = [*services["content-server"]["command"], "--file-transfer-timeout-ms", str(timeout_ms)]
    content = json.loads((generated / "client-a.json").read_text())["endpoints"]["content"]
    return {"content_server": content, "service_max_bytes": max_bytes, "user_max_bytes": max_bytes,
            "tdu2_seconds": 60, "transfer_timeout_ms": 60000}


def phone_devices(args) -> dict[str, dict]:
    """Role to {serial, ip}: the repeatable --phone-device, or the single --phone/--phone-ip."""
    phones: dict[str, dict] = {}
    for spec in args.phone_device or []:
        parts = spec.split(":")
        if len(parts) != 3 or parts[0] not in CLIENTS or not parts[1] or not parts[2]:
            sys.exit(f"--phone-device {spec}: expected ROLE:SERIAL:IP with ROLE one of {', '.join(CLIENTS)}")
        if parts[0] in phones or any(p["serial"] == parts[1] for p in phones.values()):
            sys.exit(f"--phone-device {spec}: role or serial given twice")
        phones[parts[0]] = {"serial": parts[1], "ip": parts[2]}
    if args.phone:
        if phones:
            sys.exit("use either --phone with --phone-ip or --phone-device")
        if not args.phone_ip:
            sys.exit("--phone needs --phone-ip: the phone's address on the LAN (the MCPTT server checks SDP addresses against it)")
        phones[args.phone] = {"serial": None, "ip": args.phone_ip}
    return phones


def create(args) -> None:
    openmcx = Path(args.openmcx).resolve()
    lab = RUNS / args.name
    if lab.exists():
        sys.exit(f"{lab} exists; remove it or choose another --name")
    phones = phone_devices(args)
    if args.phones_only and not phones:
        sys.exit("--phones-only needs at least one --phone-device")
    RUNS.mkdir(parents=True, exist_ok=True)
    fixture = lab / "fixture"
    command = [sys.executable, str(openmcx / "tools/prepare-prearranged-test.py"), "--directory", str(fixture),
               "--project", f"mcxandroid-{args.name}", "--adhoc", "--mcdata", "--image", args.server_image]
    if args.debug_sip:
        command.append("--debug-sip")
    if args.mcvideo:
        command.append("--mcvideo")
    if args.location:
        # Location reporting (TS 24.379 clause 13): the server configures every client's reporting at log-on,
        # client-a may request and configure others' location, client-b shares its location with everyone.
        command.append("--location")
    if args.functional_alias:
        # Functional aliases (TS 24.379 clause 9A): "duty-officer" for client-a and client-b (one activation, client-b may
        # take it over), "patrol-car-12" for client-c and client-d, "incident-commander" for client-a.
        # With the private call policy, as the regression's functional-alias lab: calls to an alias are private calls.
        command += ["--functional-alias"] + ([] if args.priority else ["--private-call"])
    if args.priority:
        # Emergency, imminent peril and emergency alerts (TS 24.379 §6.2.8, §12.1): client-a and client-b may raise
        # them, client-a may cancel an alert (its own or another user's). With the private call policy: emergency
        # private calls, client-a to anyone, client-b to client-a only.
        command += ["--priority", "--private-call"]
    if args.floor_queue:
        # Queued floor requests (TS 24.380 §6.2.4.9, §6.3.5.4.4): the server queues a request while the floor is taken
        # and publishes <floor-control-queue> in the MCPTT service configuration, from which the app offers mc_queueing.
        command.append("--floor-queue")
    if args.protected:
        # End-to-end protection (TS 33.180): media, transmission control and key management, with the
        # interoperability profile's Key Parameters IEI.
        command += ["--protected", "--key-parameters-iei", "0x79"]
    if args.reference_client:
        if not args.client_image:
            sys.exit("--reference-client needs --client-image (the mcx-client image, built with openmcx-client/linux/tools/build-image)")
        if set(args.reference_client) & set(phones):
            sys.exit("a reference client cannot also be a phone")
    if args.client_image:
        command += ["--client-image", args.client_image]
    if args.admin:
        # MCX Admin manages the accounts (openmcx-server tools/prepare-prearranged-test.py --admin); phones activate from its codes.
        if not args.admin_image:
            sys.exit("--admin needs --admin-image (built from the openmcx-admin repository)")
        command += ["--admin", "--admin-image", args.admin_image]
    subprocess.run(command, check=True)
    generated = fixture / "generated"
    compose = json.loads((fixture / "compose.json").read_text())
    site = json.loads((generated / "site.json").read_text())
    # A user allowed to call anyone (the private call policy's any_target) gets no <PrivateCallList>, so the app
    # would offer nobody: list the other callable users too. Their calls are authorised as before.
    callable_ids = [u["mcptt_id"] for u in site["users"] if (u.get("private_call") or {}).get("callable", True)]
    for user in site["users"]:
        policy = user.get("private_call") or {}
        if policy.get("any_target") and not policy.get("targets"):
            policy["targets"] = [i for i in callable_ids if i != user["mcptt_id"]]
    (generated / "site.json").write_text(json.dumps(site, indent=2) + "\n")
    template = compose["services"]["client-a"]
    for role in ["client-a", "client-b", "client-c", "client-d"]:
        # A reference client stays: the C++ mcx-client of the fixture, as a third party beside the phones.
        if role not in (args.reference_client or []):
            compose["services"].pop(role, None)
    hostip = args.host_ip or (host_ip() if phones else None)
    fd = None
    if args.fd_max_bytes:
        users = [json.loads((generated / f"{r}.json").read_text())["mcdata"]["user_id"] for r in phones]
        fd = file_distribution(fixture, compose, users, args.fd_max_bytes, args.fd_availability_seconds, args.file_transfer_timeout_ms)
        site = json.loads((generated / "site.json").read_text())
    for role in CLIENTS:
        ref = json.loads((generated / f"{role}.json").read_text())
        bundle = lab / "provisioning" / role
        bundle.mkdir(parents=True)
        config = provisioning(ref, site, role, args.debug_sip, fd if role in phones else None)
        if role in phones:
            sip_port = 5060 if args.debug_sip else 5061
            config["endpoints"] = {f"sip-core:{sip_port}": f"{hostip}:{args.sip_port if not args.debug_sip else 5060}",
                                   "idms:8443": f"{hostip}:{args.idms_port}",
                                   "http-proxy:8443": f"{hostip}:{args.proxy_port}"}
            if args.protected:
                config["endpoints"]["kms:8443"] = f"{hostip}:{args.kms_port}"
        (bundle / "bundle.json").write_text(json.dumps(config, indent=2) + "\n")
        for source, target in (("ca.crt", "ca.crt"), (f"{role}.crt", "client.crt"), (f"{role}.key", "client.key")):
            shutil.copyfile(generated / "tls" / source, bundle / target)
        if role in phones:
            phone = lab / "phone"
            phone.mkdir(exist_ok=True)
            prov = phone / f"{role}.mcxprov"
            prov.write_text(json.dumps({"format": "mcx-provisioning/1", "bundle": config,
                                        "ca": (bundle / "ca.crt").read_text(), "cert": (bundle / "client.crt").read_text(),
                                        "key": (bundle / "client.key").read_text()}, indent=2) + "\n")
            os.chmod(prov, 0o600)
            continue
        if args.phones_only or role in (args.reference_client or []):
            continue
        compose["services"][role] = {
            "image": args.redroid,
            "hostname": role,
            "privileged": True,
            "networks": template["networks"],
            "depends_on": template["depends_on"],
            # Docker's embedded resolver, so the lab's service names resolve.
            "command": ["androidboot.redroid_gpu_mode=guest", "androidboot.redroid_net_ndns=1",
                        "androidboot.redroid_net_dns1=127.0.0.11", "androidboot.use_memfd=true"],
        }
    if phones:
        # The phone reaches the servers through the host: publish the SIP core, the IdMS, the HTTP
        # proxy (8443 is taken by the IdMS, so on 8444) and the MCPTT server's media ports.
        services = compose["services"]
        sip_port = 5060 if args.debug_sip else 5061
        services["sip-core"]["ports"] = [f"{args.sip_port if not args.debug_sip else 5060}:{sip_port}"]
        services["idms"]["ports"] = [f"{args.idms_port}:8443"]
        services["http-proxy"]["ports"] = [f"{args.proxy_port}:8443"]
        if args.protected:
            services["kms"]["ports"] = [f"{args.kms_port}:8443"]
        mcptt_server = mcptt_server_name(services)
        services[mcptt_server]["ports"] = [f"{r}:{r}/udp" for r in MEDIA_PORTS]
        if args.admin:
            # Activation (MCX Admin slice F2): the phone fetches its package from MCX Admin, signs in, and gets
            # its SIP credential and certificate from the Credential Service, all through the host.
            services["mcxadmin"]["ports"] = [f"{args.admin_port}:8443"]
            services["credentials"]["ports"] = [f"{args.credentials_port}:8443"]
            admin_cfg_path = generated / "mcxadmin.json"
            admin_cfg = json.loads(admin_cfg_path.read_text())
            admin_cfg["activation"]["base_url"] = f"https://{hostip}:{args.admin_port}"
            admin_cfg["activation"]["address_map"] = {
                f"sip-core:{sip_port}": f"{hostip}:{args.sip_port if not args.debug_sip else 5060}",
                "idms:8443": f"{hostip}:{args.idms_port}", "http-proxy:8443": f"{hostip}:{args.proxy_port}",
                "credentials:8443": f"{hostip}:{args.credentials_port}", "mcxadmin:8443": f"{hostip}:{args.admin_port}",
                **({"kms:8443": f"{hostip}:{args.kms_port}"} if args.protected else {})}
            # The console, and IdMS's password links, for a browser on this computer: at the host's address.
            console = f"https://{hostip}:{args.admin_port}/ui/callback"
            admin_cfg["console"].update(authorize_url=f"https://{hostip}:{args.idms_port}/authorize", redirect_uri=console)
            site_path = generated / "site.json"
            site_doc = json.loads(site_path.read_text())
            site_doc["oauth_redirect_uris"].append(console)
            site_doc["admin_accounts"]["link_base_url"] = f"https://{hostip}:{args.idms_port}"
            site_path.write_text(json.dumps(site_doc, indent=2) + "\n")
            if args.mcvideo:
                # Activation codes carry MCVideo too (the fixture lists MCPTT and MCData only).
                video_uri = json.loads((generated / "client-a.json").read_text())["mcvideo"]["service_uri"]
                admin_cfg["activation"]["services"]["mcvideo"] = video_uri
            admin_cfg_path.write_text(json.dumps(admin_cfg, indent=2) + "\n")
        # The MCPTT server puts its media address in SDP and accepts only the SDP address it
        # resolves for each user: advertise the host, and map the phone's user to the phone.
        server = generated / f"{mcptt_server}.json"
        cfg = json.loads(server.read_text())
        cfg["media"]["advertise"] = hostip
        for role, phone in phones.items():
            phone_user = json.loads((generated / f"{role}.json").read_text())["mcptt"]["user_id"]
            cfg["peers"][phone_user] = phone["ip"]
        server.write_text(json.dumps(cfg, indent=2) + "\n")
        video_server = generated / "mcvideo-server.json"
        if args.mcvideo and video_server.exists():
            # The same for MCVideo, on its own published ports (TS 24.281: audio, video and transmission control).
            vcfg = json.loads(video_server.read_text())
            vcfg["media"].update(advertise=hostip, **MCVIDEO_BASES)
            if args.admin:
                # As the fixture does for the MCPTT server in admin mode: accept the SDP address the SIP core
                # checked, for users created in MCX Admin, whose phones the configuration does not name.
                vcfg["sip"]["core_checks_media_address"] = True
            # A person answers a ringing video call: the server waits 30 s (the MCPTT default), not the fixture's 5 s.
            vcfg.setdefault("mcvideo", {}).setdefault("private_call", {})["setup_timeout_ms"] = 30000
            for role, phone in phones.items():
                video_user = json.loads((generated / f"{role}.json").read_text())["mcvideo"]["user_id"]
                vcfg["peers"][video_user] = phone["ip"]
            video_server.write_text(json.dumps(vcfg, indent=2) + "\n")
            services["mcvideo-server"]["ports"] = [f"{p}-{p + 199}:{p}-{p + 199}/udp" for p in MCVIDEO_BASES.values()]
    (lab / "compose.json").write_text(json.dumps(compose, indent=2) + "\n")
    speech = lab / "speech"
    speech.mkdir()
    for seed, name in enumerate(("alpha", "bravo", "charlie")):
        speech_wav(speech / f"{name}.wav", seed)
    meta_doc = {"openmcx": str(openmcx), "project": f"mcxandroid-{args.name}", "debug_sip": args.debug_sip,
                "server_image": args.server_image, "mcvideo": args.mcvideo, "protected": args.protected, "location": args.location,
                "priority": args.priority, "floor_queue": args.floor_queue,
                "reference_clients": args.reference_client or [], "client_image": args.client_image,
                "admin": args.admin, "admin_image": args.admin_image,
                "mcptt_server": mcptt_server_name(compose["services"]),
                "clients": [] if args.phones_only else [c for c in CLIENTS if c not in phones]}
    if phones:
        meta_doc["phones"] = phones
        meta_doc["host_ip"] = hostip
        meta_doc["ports"] = {"sip": args.sip_port, "idms": args.idms_port, "proxy": args.proxy_port}
    if fd:
        meta_doc["fd"] = {**fd, "availability_seconds": args.fd_availability_seconds,
                          "file_transfer_timeout_ms": args.file_transfer_timeout_ms}
    (lab / "lab.json").write_text(json.dumps(meta_doc, indent=2) + "\n")
    print(lab)
    for role, phone in phones.items():
        print(f"phone mode: {role} is the phone at {phone['ip']}; servers published on {hostip}\n"
              f"  provisioning file: {lab / 'phone' / (role + '.mcxprov')}\n"
              f"  after 'up': lab/androidlab.py install-phone {args.name} --role {role}"
              f"{'' if phone['serial'] else ' --serial <adb serial>'}, or import the file in the app")


def meta(name: str) -> dict:
    m = json.loads((RUNS / name / "lab.json").read_text())
    # Labs made before two-phone support record one phone.
    if "phone" in m and "phones" not in m:
        m["phones"] = {m["phone"]["role"]: {"serial": m["phone"].get("serial"), "ip": m["phone"]["ip"]}}
    return m


def save_meta(name: str, m: dict) -> None:
    m.pop("phone", None)
    (RUNS / name / "lab.json").write_text(json.dumps(m, indent=2) + "\n")


def compose(name: str, *extra: str, capture: bool = False) -> subprocess.CompletedProcess:
    m = meta(name)
    return subprocess.run([*compose_bin(Path(m["openmcx"])), "-p", m["project"], "-f", str(RUNS / name / "compose.json"),
                           *extra], check=True, text=True, capture_output=capture)


def address(name: str, client: str) -> str:
    container = f"{meta(name)['project']}-{client}-1"
    out = subprocess.run(["docker", "inspect", "-f", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}",
                          container], check=True, capture_output=True, text=True)
    return out.stdout.strip()


class Device:
    """One client: a redroid container reached with adb over the lab network, or, in phone
    mode, the phone, by the adb serial recorded with install-phone (USB or wireless debugging)."""

    def __init__(self, lab: str, client: str) -> None:
        self.client = client
        phone = (meta(lab).get("phones") or {}).get(client)
        if phone is not None:
            if not phone.get("serial"):
                raise RuntimeError(f"{client} is the phone: run install-phone first")
            self.serial = phone["serial"]
            self.redroid = False
        else:
            self.serial = f"{address(lab, client)}:5555"
            self.redroid = True

    def adb(self, *args: str, check: bool = True) -> str:
        out = subprocess.run([str(ADB), "-s", self.serial, *args], capture_output=True, text=True)
        if check and out.returncode:
            raise RuntimeError(f"{self.client}: adb {' '.join(args)}: {out.stderr.strip() or out.stdout.strip()}")
        return out.stdout

    def connect(self, timeout: float = 120) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.redroid or ":" in self.serial:
                subprocess.run([str(ADB), "connect", self.serial], capture_output=True, text=True)
            if self.adb("shell", "getprop", "sys.boot_completed", check=False).strip() == "1":
                return
            time.sleep(2)
        raise RuntimeError(f"{self.client}: Android did not boot within {timeout:.0f} s")

    def install(self, bundle: Path, speech: Path) -> None:
        if not self.adb("install", "-r", "-g", str(APK), check=False).strip().endswith("Success"):
            # MIUI refuses -g without "USB debugging (Security settings)": install, then grant.
            self.adb("install", "-r", str(APK))
            for perm in ("RECORD_AUDIO", "POST_NOTIFICATIONS"):
                self.adb("shell", "pm", "grant", PACKAGE, f"android.permission.{perm}", check=False)
        self.adb("shell", "am", "force-stop", PACKAGE)
        staging = "/data/local/tmp/mcx"
        self.adb("shell", "rm", "-rf", staging, f"{staging}-speech")
        self.adb("push", str(bundle), staging)
        self.adb("push", str(speech), f"{staging}-speech")
        # Readable by the app whether adbd runs as shell or as root.
        self.adb("shell", "chmod", "-R", "a+rX", staging, f"{staging}-speech")
        self.adb("shell", "run-as", PACKAGE, "sh", "-c",
                 f"'rm -rf files/provisioning files/speech files/received files/sip-trace.log && mkdir -p files && "
                 f"cp -r {staging} files/provisioning && cp -r {staging}-speech files/speech'")
        self.adb("shell", "rm", "-rf", staging, f"{staging}-speech")
        # The lab clients must stay reachable; on a phone the app asks the user for this exemption.
        self.adb("shell", "dumpsys", "deviceidle", "whitelist", f"+{PACKAGE}", check=False)
        self.adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.app.MainActivity")

    def control(self, cmd: str, *args: str, timeout: float = 30) -> dict:
        """One request to the debug control endpoint, through an adb port forward."""
        port = int(self.adb("forward", "tcp:0", f"localabstract:{CONTROL}").strip())
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=timeout) as s:
                s.sendall((json.dumps({"cmd": cmd, "args": list(args)}) + "\n").encode())
                data = b""
                while not data.endswith(b"\n"):
                    chunk = s.recv(65536)
                    if not chunk:
                        break
                    data += chunk
        finally:
            self.adb("forward", "--remove", f"tcp:{port}", check=False)
        if not data:
            raise RuntimeError(f"{self.client}: no response from the control endpoint")
        return json.loads(data)


def media_hairpin(name: str) -> None:
    """Phone mode: the MCPTT server advertises the host's address, but a redroid client reaching
    the host's published ports from inside the lab network goes through Docker's userland proxy,
    which replaces its source address, and the server drops media whose source is not the SDP
    address. Send such traffic straight to the server instead (DNAT in the client's namespace)."""
    m = meta(name)
    containers = m["clients"] + m.get("reference_clients", [])
    if not m.get("phones") or not containers:
        return
    server = address(name, m.get("mcptt_server", "mcx-server"))
    # MCVideo media goes to its own server on its own ports (MCVIDEO_BASES), when the lab has it.
    targets = [(r, server) for r in MEDIA_PORTS]
    if m.get("mcvideo"):
        video_server = address(name, "mcvideo-server")
        targets += [(f"{p}-{p + 199}", video_server) for p in MCVIDEO_BASES.values()]
    for client in containers:
        container = f"{m['project']}-{client}-1"
        # Both directions: out to the host address goes to the server, and what the server sends
        # appears to come from the host address. With only the first, a server packet that arrives
        # before the client's first one leaves a conntrack entry whose reply tuple clashes with the
        # DNATed packet, and netfilter changes the client's source port, which the server rejects.
        # Own chains, so Docker's rules (its DNS redirect lives in nat OUTPUT) are never touched.
        rules = []
        for r, target in targets:
            ports = r.replace("-", ":")
            rules.append(f"iptables-nft -t nat -A MCXMEDIA -d {m['host_ip']} -p udp --dport {ports} -j DNAT --to-destination {target}")
            rules.append(f"iptables-nft -t nat -A MCXMEDIA_IN -s {target} -p udp --sport {ports} -j SNAT --to-source {m['host_ip']}")
        script = ("for c in MCXMEDIA MCXMEDIA_IN; do iptables-nft -t nat -N $c 2>/dev/null; iptables-nft -t nat -F $c; done && "
                  "(iptables-nft -t nat -C OUTPUT -j MCXMEDIA 2>/dev/null || iptables-nft -t nat -A OUTPUT -j MCXMEDIA) && "
                  "(iptables-nft -t nat -C INPUT -j MCXMEDIA_IN 2>/dev/null || iptables-nft -t nat -A INPUT -j MCXMEDIA_IN) && "
                  "conntrack -F 2>/dev/null; " + " && ".join(rules))
        out = subprocess.run(["docker", "run", "--rm", "--net", f"container:{container}", "--cap-add", "NET_ADMIN",
                              "mcx-nettools:1", "sh", "-c", script], capture_output=True, text=True)
        if out.returncode:
            sys.exit(f"{client}: media hairpin failed (build lab/nettools?): {out.stderr.strip()}")
        print(f"{client}: media to {m['host_ip']} goes straight to the media servers")


def install_phone(name: str, serial: str | None, role: str | None = None) -> None:
    """Install the debug APK and the phone's provisioning on a phone over adb, and record its serial.
    Only the provisioning, test speech and received audio are replaced; other app data stays."""
    m = meta(name)
    phones = m.get("phones") or sys.exit(f"{name} is not a phone-mode lab (create --phone or --phone-device)")
    if role is None:
        if len(phones) != 1:
            sys.exit(f"{name} has phones {', '.join(phones)}: choose one with --role")
        role = next(iter(phones))
    phone = phones.get(role) or sys.exit(f"{role} is not a phone in {name}")
    phone["serial"] = serial or phone.get("serial") or sys.exit(f"{role}: give its --serial")
    save_meta(name, m)
    device = Device(name, role)
    device.connect(timeout=10)
    device.install(RUNS / name / "provisioning" / role, RUNS / name / "speech")
    print(f"{role}: installed on the phone {phone['serial']}")


def qr(name: str, role: str, port: int, apply: bool, timeout: float) -> None:
    """Serve a phone's provisioning file once over HTTPS and show the QR code that names it.

    A profile with its CA, certificate and key is larger than a QR code holds, so the code
    carries a one-time link. The phone does not trust the lab CA yet: the link pins this
    server's own certificate (#pin=, its SHA-256), made for this run (QrProfile in core)."""
    import base64, hashlib, secrets
    m = meta(name)
    prov = RUNS / name / "phone" / f"{role}.mcxprov"
    if not prov.exists():
        sys.exit(f"{prov} not found: {role} is not a phone of {name}")
    hostip = m.get("host_ip") or host_ip()
    work = RUNS / name / "qr"
    work.mkdir(exist_ok=True)
    cert, key = work / "server.crt", work / "server.key"
    subprocess.run(["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes",
                    "-keyout", str(key), "-out", str(cert), "-days", "1", "-subj", "/CN=openMCX profile",
                    "-addext", f"subjectAltName=IP:{hostip}"], check=True, capture_output=True)
    der = subprocess.run(["openssl", "x509", "-in", str(cert), "-outform", "DER"], check=True, capture_output=True).stdout
    pin = base64.urlsafe_b64encode(hashlib.sha256(der).digest()).decode().rstrip("=")
    token = secrets.token_urlsafe(18)
    url = f"https://{hostip}:{port}/p/{token}#pin={pin}"
    # Served from a container on a published port, as every lab service is reached (a host
    # firewall passes Docker's published ports, not always a host process's).
    (work / "serve.py").write_text(
        "import http.server, os, ssl, sys, threading\n"
        "body = open('/profile', 'rb').read(); path = '/p/' + os.environ['TOKEN']\n"
        "claimed, done, lock = [False], threading.Event(), threading.Lock()\n"
        "class Once(http.server.BaseHTTPRequestHandler):\n"
        "    def do_GET(self):\n"
        "        with lock:\n"
        "            first = self.path == path and not claimed[0]\n"
        "            claimed[0] = claimed[0] or first\n"
        "        if not first:\n"
        "            self.send_error(404); return\n"
        "        self.send_response(200); self.send_header('Content-Type', 'application/json')\n"
        "        self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)\n"
        "        self.wfile.flush(); done.set()\n"
        "    def log_message(self, *a): pass\n"
        "server = http.server.ThreadingHTTPServer(('0.0.0.0', 8443), Once)\n"
        "ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.load_cert_chain('/w/server.crt', '/w/server.key')\n"
        "server.socket = ctx.wrap_socket(server.socket, server_side=True)\n"
        "threading.Thread(target=server.serve_forever, daemon=True).start()\n"
        "sys.exit(0 if done.wait(float(os.environ['TIMEOUT'])) else 3)\n")
    for f in (work / "serve.py", cert, key):
        f.chmod(0o644)
    container = f"mcxqr-{name}-{role}"
    subprocess.run(["docker", "rm", "-f", container], capture_output=True)
    subprocess.run(["docker", "run", "-d", "--name", container, "-p", f"{port}:8443", "-e", f"TOKEN={token}",
                    "-e", f"TIMEOUT={timeout}", "-v", f"{work}:/w:ro", "-v", f"{prov}:/profile:ro",
                    "python:3.12-slim", "python", "/w/serve.py"], check=True, capture_output=True)
    time.sleep(1)
    png = work / f"{role}.png"
    shown = subprocess.run(["docker", "run", "--rm", "-v", f"{work}:/w", "python:3.12-slim", "sh", "-c",
                            "pip -q --disable-pip-version-check install segno >/dev/null 2>&1 && python -c "
                            "'import segno,sys; q=segno.make(sys.argv[1], error=\"m\"); q.save(\"/w/" + png.name + "\", scale=8, border=4); "
                            "q.terminal(compact=True)' " + json.dumps(url)], capture_output=True, text=True)
    if shown.returncode == 0:
        print(shown.stdout)
        print(f"QR code image: {png}")
    else:
        print(f"(no QR image: {shown.stderr.strip()[-200:]})")
    print(f"{role}: scan in Settings > Provisioning > Scan QR code, or open {url}")
    if apply:
        result = Device(name, role).control("import-qr", url, timeout=60)
        print(f"{role}: {json.dumps(result)}")
    status = subprocess.run(["docker", "wait", container], capture_output=True, text=True).stdout.strip()
    subprocess.run(["docker", "rm", "-f", container], capture_output=True)
    if status == "0":
        print(f"{role}: the profile was fetched; the link no longer works")
    else:
        print(f"{role}: not fetched within {timeout:.0f} s; the link is closed")


def admin_activate(name: str, role: str | None, user: str, qr_code: bool = False) -> None:
    """In an --admin lab: create [user] in MCX Admin (from bob's record, in operations), set its password
    from IdMS's one-time link, issue an activation code, and activate the phone of [role] from it with the
    password (the debug control's activate: what scanning the code and typing the password do). With
    [qr_code], for a phone the lab does not drive: show the code as a QR code, with the password to type."""
    import secrets, uuid as uuidlib
    m = meta(name)
    if not m.get("admin"):
        sys.exit(f"{name} is not an --admin lab")
    gen = RUNS / name / "fixture" / "generated"
    helper = Path(m["openmcx"]) / "tests/integration/admin_helper.py"

    def h(*words: str) -> dict:
        out = subprocess.run(["docker", "run", "--rm", "--network", f"{m['project']}_mcx", "-v", f"{gen / 'tls/ca.crt'}:/ca.crt:ro",
                              "-v", f"{helper}:/admin_helper.py:ro", "--entrypoint", "python3", m["admin_image"], "/admin_helper.py", *words],
                             capture_output=True, text=True, check=True)
        return json.loads(out.stdout.strip().splitlines()[-1])

    alice = json.loads((gen / "client-a.json").read_text())["oauth"]
    token = h("login", alice["username"], alice["password"], alice["client_id"], alice["redirect_uri"])["token"]
    if not token:
        sys.exit("the administrator (alice) did not sign in")
    config = h("api", token, "GET", "/v1/config")
    domain = config["body"]["system"]["domain"]
    if not any(u["login"] == user for u in config["body"]["users"]):
        template = next(u for u in config["body"]["users"] if u["login"] == "bob")
        record = {**template, "login": user, "display_name": user, "status": "active", "mc_id": f"sip:{user}.mc@{domain}",
                  "mcptt_id": f"sip:{user}.mcptt@{domain}", "sip_uri": f"sip:{user}@{domain}",
                  "client_ids": [f"urn:uuid:{uuidlib.uuid4()}"], "groups": template.get("groups") or []}
        record.pop("email", None)
        for service in ("mcdata", "mcvideo"):
            if template.get(f"{service}_id"):
                record[f"{service}_id"] = f"sip:{user}.{service}@{domain}"
        saved = h("api", token, "PUT", f"/v1/users/{user}", config["etag"], json.dumps(record))
        if saved["status"] != 200:
            sys.exit(f"{user} not created: {saved}")
        print(f"{user}: created in MCX Admin, in {', '.join(record['groups']) or 'no group'}")
    link = h("api", token, "POST", f"/v1/users/{user}/password-link")["body"]["url"]
    # The link names the IdMS at the host's address for a browser; the helper reaches it by name on the lab network.
    link = "https://idms:8443/" + link.split("/", 3)[3]
    password = secrets.token_urlsafe(12)
    if h("set-password", link, password)["status"] != 200:
        sys.exit(f"{user}: the password was not set")
    code = h("api", token, "POST", f"/v1/users/{user}/activation")["body"]
    print(f"{user}: activation code {code['url']}")
    if qr_code:
        work = RUNS / name / "qr"
        work.mkdir(exist_ok=True)
        png = work / f"{user}.png"
        png.write_bytes(base64.b64decode(code["qr_png"]))
        shown = subprocess.run(["docker", "run", "--rm", "python:3.12-slim", "sh", "-c",
                                "pip -q --disable-pip-version-check install segno >/dev/null 2>&1 && python -c "
                                "'import segno,sys; segno.make(sys.argv[1], error=\"m\").terminal(compact=True)' "
                                + json.dumps(code["url"])], capture_output=True, text=True)
        print(shown.stdout)
        print(f"QR code image: {png}")
        print(f"{user}: scan the code in the app (Settings > Scan QR code), then sign in with the password {password}")
        print(f"{user}: the code works once and expires after an hour")
        return
    result = Device(name, role).control("activate", code["url"], password, timeout=120)
    print(f"{role}: {json.dumps(result)}")


def install(name: str) -> None:
    if not meta(name)["clients"]:
        return
    if not APK.exists():
        sys.exit(f"{APK} not found: run tools/gradle-docker.sh :app:assembleDebug in android/")
    for client in meta(name)["clients"]:
        device = Device(name, client)
        device.connect()
        speech = RUNS / name / "speech"
        if not speech.exists():
            speech.mkdir()
            for seed, wav in enumerate(("alpha", "bravo", "charlie")):
                speech_wav(speech / f"{wav}.wav", seed)
        device.install(RUNS / name / "provisioning" / client, speech)
        print(f"{client}: installed on {device.serial}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="action", required=True)
    c = sub.add_parser("create")
    c.add_argument("--openmcx", default=str(ROOT.parents[1] / "openmcx-server"), help="the openmcx-server checkout (default: ../openmcx-server)")
    c.add_argument("--name", default="a1")
    c.add_argument("--debug-sip", action="store_true", help="clear SIP over TCP for capture-based checks")
    c.add_argument("--server-image", default="openmcx-all:0.1",
                   help="the openMCX application image of the lab, with every program (openmcx-server tools/build-images, docs/BUILD.md)")
    c.add_argument("--redroid", default=REDROID)
    c.add_argument("--phone", metavar="ROLE", help="a real phone takes this client's role (client-a, client-b or client-c)")
    c.add_argument("--phone-ip", help="the phone's address on the LAN")
    c.add_argument("--host-ip", help="the host's LAN address the phone reaches (default: the default route's source)")
    c.add_argument("--phone-device", action="append", metavar="ROLE:SERIAL:IP",
                   help="a real phone takes ROLE's place (repeatable: one per phone)")
    c.add_argument("--phones-only", action="store_true", help="no redroid clients: only the phones")
    c.add_argument("--sip-port", type=int, default=PHONE_SIP_PORT, help="host port published for the SIP core")
    c.add_argument("--idms-port", type=int, default=PHONE_IDMS_PORT, help="host port published for the IdMS")
    c.add_argument("--proxy-port", type=int, default=PHONE_PROXY_PORT, help="host port published for the HTTP proxy")
    c.add_argument("--fd-max-bytes", type=int, help="file distribution: the service, one-to-one and group file limit")
    c.add_argument("--mcvideo", action="store_true", help="add the MCVideo server and provision MCVideo (video calls)")
    c.add_argument("--location", action="store_true",
                   help="location reporting (TS 24.379 clause 13): configurations, reports, requests and the LMS")
    c.add_argument("--floor-queue", action="store_true",
                   help="queued floor requests: the server queues a request while the floor is taken (scenario S18)")
    c.add_argument("--functional-alias", action="store_true",
                   help="functional aliases (TS 24.379 clause 9A): duty-officer (client-a, client-b), patrol-car-12, incident-commander")
    c.add_argument("--priority", action="store_true",
                   help="emergency and imminent peril, and the MCPTT emergency alert (the group priority policy)")
    c.add_argument("--protected", action="store_true",
                   help="end-to-end protection (TS 33.180): the KMS, protected media and transmission control")
    c.add_argument("--kms-port", type=int, default=PHONE_KMS_PORT, help="host port published for the KMS (--protected)")
    c.add_argument("--admin", action="store_true", help="MCX Admin manages the accounts; phones activate from its codes")
    c.add_argument("--admin-image", help="the MCX Admin image (--admin)")
    c.add_argument("--admin-port", type=int, default=8446, help="host port published for MCX Admin (--admin)")
    c.add_argument("--credentials-port", type=int, default=8447, help="host port published for the Credential Service (--admin)")
    c.add_argument("--reference-client", action="append", choices=["client-c", "client-d"],
                   help="keep this client of the fixture: the C++ mcx-client, as a further party beside the phones")
    c.add_argument("--client-image", help="the mcx-client image for --reference-client (linux/tools/build-image of openmcx-client, flag --server)")
    c.add_argument("--fd-availability-seconds", type=int, default=900, help="how long an uploaded file stays available")
    c.add_argument("--file-transfer-timeout-ms", type=int, default=900_000, help="the deadline of one file upload or download")
    for action in ("up", "down", "ps", "logs", "install"):
        p = sub.add_parser(action)
        p.add_argument("name")
    ip = sub.add_parser("install-phone")
    ip.add_argument("name")
    ip.add_argument("--serial", help="the phone's adb serial (adb devices); default: the one given at create")
    ip.add_argument("--role", help="which phone, in a lab with several")
    aa = sub.add_parser("admin-activate", help="create a user in MCX Admin and activate a phone from its code (--admin labs)")
    aa.add_argument("name")
    aa.add_argument("role", nargs="?", help="the phone's role (not with --qr)")
    aa.add_argument("--user", required=True, help="the new user's login")
    aa.add_argument("--qr", action="store_true", help="show the code as a QR code for a phone the lab does not drive")
    q = sub.add_parser("qr", help="serve a phone's profile once over HTTPS and show its QR code")
    q.add_argument("name")
    q.add_argument("role", help="the phone's role, as given at create (client-a, ...)")
    q.add_argument("--port", type=int, default=8451, help="host port of the one-time profile server")
    q.add_argument("--apply", action="store_true", help="hand the link to the phone's app too (debug control import-qr)")
    q.add_argument("--timeout", type=float, default=600, help="seconds the link stays open")
    e = sub.add_parser("exec")
    e.add_argument("name")
    e.add_argument("client")
    e.add_argument("command")
    e.add_argument("args", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.action == "create":
        create(args)
    elif args.action == "up":
        compose(args.name, "up", "-d", "--wait")
        install(args.name)
        media_hairpin(args.name)
        for role, phone in (meta(args.name).get("phones") or {}).items():
            if phone.get("serial"):
                install_phone(args.name, None, role)
    elif args.action == "install-phone":
        install_phone(args.name, args.serial, args.role)
    elif args.action == "install":
        install(args.name)
        # The lab's phones too, as up does: a phones-only lab has no Android containers, and install left the old APK
        # on its phones without a word.
        for role, phone in (meta(args.name).get("phones") or {}).items():
            if phone.get("serial"):
                install_phone(args.name, None, role)
    elif args.action == "down":
        for client in meta(args.name)["clients"]:
            try:
                subprocess.run([str(ADB), "disconnect", Device(args.name, client).serial], capture_output=True)
            except (subprocess.CalledProcessError, RuntimeError):
                pass
        compose(args.name, "down", "-v")
    elif args.action == "ps":
        compose(args.name, "ps")
    elif args.action == "logs":
        compose(args.name, "logs", "--no-color")
    elif args.action == "admin-activate":
        if not args.qr and not args.role:
            sys.exit("admin-activate needs the phone's role, or --qr")
        admin_activate(args.name, args.role, args.user, args.qr)
    elif args.action == "qr":
        qr(args.name, args.role, args.port, args.apply, args.timeout)
    elif args.action == "exec":
        print(json.dumps(Device(args.name, args.client).control(args.command, *args.args), indent=2))


if __name__ == "__main__":
    main()
