# 7. Protocol Compatibility Design

[< Back to Overview](00-overview.md)

---

## 7.1 Protocol Compatibility Principle

The mobile client **fully implements the desktop's lansync v1 protocol**, ensuring cross-platform interoperability. All REST API paths, message formats, and security mechanisms are identical to the desktop.

## 7.2 Mobile as a Peer Node

The mobile device is not subordinate to the desktop; it is a fully equal peer node. Each mobile device:

* Launches its own HTTPS service (Ktor Server), listening on port 53317.
* Can be discovered, paired with, and receive files from other devices.
* Can actively discover and connect to other devices.

## 7.3 REST API Endpoints (Server-side endpoints the mobile must implement)

The mobile Ktor Server must implement the following endpoints, fully aligned with the desktop's axum server:

| Endpoint | Method | Purpose |
| --- | --- | --- |
| `/api/lansync/v1/info` | GET | Returns this device's information (for discovery) |
| `/api/lansync/v1/register` | POST | Discovery registration response |
| `/api/lansync/v1/pair/request` | POST | Receives a pairing request |
| `/api/lansync/v1/pair/confirm` | POST | Confirms pairing |
| `/api/lansync/v1/pair/revoke` | POST | Revokes trust |
| `/api/lansync/v1/transfer/request` | POST | Receives a transfer request |
| `/api/lansync/v1/transfer/chunks` | POST | Receives file chunks |
| `/api/lansync/v1/share/invite` | POST | Receives a share invitation |
| `/api/lansync/v1/share/authorize` | POST | Receives an authorization notification |
| `/api/lansync/v1/sync/index` | GET | Returns the shared file index |
| `/api/lansync/v1/sync/blocks` | POST | Returns requested file blocks |

## 7.4 Device Type Extension

The `device_type` and `platform` fields in discovery messages are extended:

```json
{
  "protocol": "lansync",
  "version": "1.0",
  "device_id": "<SHA-256 of TLS certificate>",
  "alias": "My iPhone",
  "device_type": "mobile",
  "platform": "ios",
  "port": 53317,
  "announce": true
}
```

`device_type` values: `desktop` | `mobile`
`platform` values: `windows` | `macos` | `linux` | `android` | `ios`

## 7.5 Mobile Protocol Differences and Adaptations

| Difference | Desktop Behavior | Mobile Adaptation |
| --- | --- | --- |
| Port Listening | Always listens on 53317 | Listens while in foreground; pauses or maintains based on policy while in background |
| mDNS Discovery | FSEvents / ReadDirChangesW | Android: NsdManager; iOS: NWBrowser |
| File Paths | Absolute filesystem paths | Uses platform URIs (SAF / Document Provider) |
| Background Execution | Always online | Constrained by platform limits; uses WorkManager / BGTask |
| File Watching | OS-native file system events | Android: FileObserver; iOS: DispatchSource (reliable only in foreground) |
| Large File Transfer | No constraints | Mobile focuses on memory usage with streaming processing |
