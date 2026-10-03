# Attachments in Messages

Images, videos and documents are sent from the Message tab as **MCData file distribution (FD) over HTTP**
(TS 24.282 V19.7.0 §10.2): the file is uploaded to the media storage function through the HTTP proxy, and a SIP
MESSAGE carries its URL and metadata (name, type, size, SHA-256). Text keeps using SDS.

## What is implemented

| Part | Where |
|---|---|
| FD codecs (FD SIGNALLING PAYLOAD, FD NOTIFICATION, FD NETWORK NOTIFICATION, file metadata) | `core/…/mcdata/FdMessages.kt`, `FileMetadata.kt` |
| Limits from provisioning (`mcdata.fd`) and the group document | `core/…/mcdata/FdPolicy.kt`, `documents/Documents.kt` |
| Streaming multipart upload and download, verified TLS, endpoint map | `core/…/http/SocketFileHttpClient.kt` |
| FD service: discovery, upload, FD request, offers with TDU2, download with size and hash checks, notifications, expiry, restart recovery | `core/…/mcdata/FdService.kt`, `FdRecord.kt` |
| Private staging (limit on the bytes read), records, FileProvider viewer grants | `app/…/attachments/AttachmentFiles.kt`, `data/AttachmentStore.kt` |
| Messages UI: paperclip in the message box (Image, Video, Document), picked file with size and limit, file rows with progress, Download / Reject / Retry / Cancel / Open | `app/…/ui/Screens.kt`, `UiModel.kt`, `MainActivity.kt` |
| Debug commands `fd-send`, `fd-send-group`, `fd-download`, `fd-reject`, `fd-cancel`, `fd-files`, `fd-hash`, `fd-open`, `fd-config`, `fd-discover` | `app/…/DebugControl.kt` |

The server side needs openmcx-server with large-file support: `mcx-http-proxy` `--content-request-limit-bytes` and
`--file-transfer-timeout-ms`, the same timeout for `mcx-common --role content`, 413 for an overrunning streamed
upload, and the group limit not replacing the service limit (see the server's MCData file distribution tests).

## Two-phone lab

```bash
lab/androidlab.py create --name fd1 --phones-only --fd-max-bytes 100000000 \
    --phone-device client-a:SERIAL_A:<ip-a> --phone-device client-b:SERIAL_B:<ip-b>
lab/androidlab.py up fd1                      # servers, then install-phone for each phone with a serial
tests/attachments.py fd1 --screenshots [--large]
lab/androidlab.py down fd1
```

`--fd-max-bytes` sets the service limit, the phone users' one-to-one limit and the group limit alike, lets the phone
users send to and receive from anyone, keeps files 900 s (`--fd-availability-seconds`), passes the limit plus 64 KiB
of multipart framing through the proxy and gives one transfer 900 s (`--file-transfer-timeout-ms`). The phones are
provisioned with the content server URI and the same limits. Installing replaces only the phones' provisioning, test
speech and received audio; history and attachments stay.

## Results

Two phones of different makes (alice and bob) on the same Wi-Fi as the host, recorded in the full edition:
**63/63 checks passed**.

| From → to | File | Bytes | Upload s | Download s | Hash |
|---|---|---:|---:|---:|---|
| alice → bob | gradient.png | 105,885 | 1.66 | 0.81 | match |
| bob → alice | report.pdf | 615 | 0.17 | 0.17 | match |
| alice → bob | clip.mp4 (H.264/AAC) | 66,140 | 0.24 | 0.38 | match |
| bob → alice | clip.mp4 | 66,140 | 0.12 | 0.52 | match |
| alice → bob | report.pdf | 615 | 0.21 | 0.23 | match |
| bob → alice | gradient.png | 105,885 | 0.20 | 0.34 | match |
| alice → group `operations` | gradient.png | 105,885 | 0.24 | 0.47 | match |
| alice → bob | limit.bin | 100,000,000 | 32.68 | 340.5 | match |

Also checked: every offer names the right sender (and group), the sender gets COMPLETED from the recipient, the group
sender is not offered its own file, a rejection reaches the sender as REJECTED and the file can no longer be
downloaded, 100,000,001 bytes is refused before upload, and text SDS still works both ways. Received files open in
the phones' own apps (checked with screenshots of the viewers); without a default app Android shows
its "Open with" chooser.

The 100 MB download time is the host's Wi-Fi, not the client or the servers: the host is on the same access point at
-70 dBm. Through the same proxy port, 20 MB downloads to the host in 0.09 s; back to back, a 20 MB app download to the
moto ran at 10.3 Mbit/s against 12.1 Mbit/s for raw TCP from the host.

Other checks: core unit tests 95/95 (FdService 15); storage tests 4/4 on both phones; the Messages screen tests pass
one at a time on the moto. On the Xiaomi the Compose screen tests hang (MIUI appears to block the test activity), and
run as a whole class on either phone the screen tests (old and new) fail with "No compose hierarchies found"; they
had only been run on an emulator before. Server: `go test ./...` passes (4 new tests); the existing FD scenario
(64 checks) and SDS scenario (163) pass on the new image.

## Not covered

Camera capture, several files per message, FD over MSRP, message-store integration, releasing a distribution,
end-to-end file protection (PCK), and expiry on the phones (covered by the unit tests and the server's FD-03).
