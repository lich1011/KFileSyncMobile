# # 4. Non-Functional Requirements

[< Back to Overview](00-overview.md)

---

| Dimension | Requirement |
| :--- | :--- |
| Security | All channels encrypted with TLS 1.3; unpaired devices must not access shared metadata; protocol identical to desktop. |
| Reliability | Resumable transfers, chunk verification (BLAKE3), file-level verification (SHA-256), reconnection on disconnect. |
| Usability | A typical user can complete discovery, pairing, and first transfer within 3 minutes. |
| Performance | Transfer rate on LAN Wi-Fi should approach the Wi-Fi bandwidth ceiling; support for 1 GB+ file transfers. |
| Battery | Background sync must not cause significant battery drain; support for on-demand / scheduled / charging-only sync policies. |
| Storage | Manage temporary files and caches responsibly; allow user-defined sync directories. |
| Protocol Compatibility | Fully interoperable with the desktop lansync v1 protocol without any adaptation layer. |