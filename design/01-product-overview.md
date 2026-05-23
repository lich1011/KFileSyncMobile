# # 1. Product Overview

[< Back to Overview](00-overview.md)

---

## 1.1 Product Definition

This product is the mobile companion application of KFileSync, running on Android and iOS devices within the same LAN.
It enables zero-trust direct file transfer and shared folder synchronization with desktop clients (Windows / macOS) and other mobile devices.

The mobile client implements the same lansync v1 protocol as the desktop client, ensuring seamless cross-platform interoperability.
All transfer and sync operations are built on device identity authentication, explicit authorization, end-to-end encryption, and integrity verification.

## 1.2 Product Goals

- Achieve high-reliability direct file transfer between Android / iOS and desktop clients (Windows / macOS) within the same LAN.
- Support peer-level participation in shared folders, member authorization, and continuous background sync alongside the desktop client.
- Reuse the desktop's zero-trust security model without reducing the security standard due to platform differences.
- Support resumable transfers, conflict copy retention, background sync, and battery-efficient strategies.

## 1.3 Non-Goals (Out of MVP Scope)

- Public network traversal, relay services, user accounts.
- Automatic conflict merging (three-way merge), Content-Defined Chunking (CDC).
- Mobile device acting as the creator/owner of shared folders (MVP phase: participant only).
- Offline-first editing, document preview, media playback.

## 1.4 Differentiating Position

- **Compared to LocalSend mobile**: adds shared folder sync capability beyond one-time transfers.
- **Compared to Syncthing Android**: offers a more intuitive pairing experience and permission management without relying on global discovery servers.
- **Compared to KFileSync desktop**: the mobile client has platform-specific strategies for background execution, storage permissions, and battery management.