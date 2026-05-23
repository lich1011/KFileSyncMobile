# KFileSync Mobile - Zero-Trust LAN Direct Transfer & Sync Mobile Application Design Document v1.0

> **Document Purpose**: LLM-assisted development, technical review, architecture communication, and MVP scheduling.
> Covers product goals, system architecture, domain model, protocol compatibility, module decomposition, database design, phased tasks, and acceptance criteria.
>
> **Reference Project**: KFileSync desktop (Tauri 2 + Vue 3 + Rust) - this mobile application must be fully interoperable with the desktop client.
>
> **Engineering Methodology**: Domain-Driven Design (DDD), Clean Architecture (Hexagonal variant), SOLID Principles,
> State Machine Modeling, Event-Driven Architecture, Repository Pattern.

---

## Document Metadata

| Field | Content |
| :--- | :--- |
| Document Version | v1.0 |
| Target Platforms | Android / iOS |
| Core Capabilities | LAN device discovery, zero-trust pairing, direct file transfer, shared folder authorization, background incremental sync |
| Default Security Assumption | No node on the LAN is trusted by default |
| Tech Stack | Kotlin Multiplatform (KMP) + Compose Multiplatform + Ktor + SQLDelight |
| Protocol Compatibility | Fully interoperable with desktop KFileSync (lansync v1 protocol) |
| Team Size | 2-3 engineers |

---

## Table of Contents

| # | Section | Description |
| :--- | :--- | :--- |
| 1 | [Product Overview](01-product-overview.md) | Product definition, goals, non-goals, differentiating position |
| 2 | [User Scenarios](02-user-scenarios.md) | Key usage scenarios for mobile file sync |
| 3 | [Feature Scope and Priority](03-feature-scope.md) | MVP and Phase 2 feature breakdown |
| 4 | [Non-Functional Requirements](04-non-functional-requirements.md) | Security, reliability, performance, battery requirements |
| 5 | [Technology Choices](05-technology-choices.md) | KMP tech stack selection and rationale |
| 6 | [Software Engineering Methodology](06-engineering-methodology.md) | DDD, Clean Architecture, KMP modules, design patterns, error handling |
| 7 | [Protocol Compatibility Design](07-protocol-compatibility.md) | lansync v1 protocol, REST endpoints, mobile adaptations |
| 8 | [Mobile-Specific Design](08-mobile-specific-design.md) | Background tasks, battery awareness, storage, notifications |
| 9 | [Security Design](09-security-design.md) | TLS, certificate pinning, platform key stores, anti-replay |
| 10 | [Database Design (SQLDelight)](10-database-design.md) | Schema definitions, queries, database initialization |
| 11 | [UI Design (Compose Multiplatform)](11-ui-design.md) | Navigation, screen mockups, ViewModel design |
| 12 | [Dependency Injection (Koin)](12-dependency-injection.md) | Koin module configuration for shared/Android/iOS |
| 13 | [Phased Development Plan](13-development-plan.md) | 7-phase timeline overview |
| 14 | [Detailed Tasks and Acceptance Criteria](14-detailed-tasks.md) | Per-phase tasks, acceptance criteria |
| 15 | [Testing Strategy](15-testing-strategy.md) | Test pyramid, coverage scope, execution environments |
| 16 | [Risks and Mitigations](16-risks-mitigations.md) | Technical risks and mitigation strategies |
| 17 | [Gradle Dependency Configuration](17-gradle-configuration.md) | Gradle build scripts reference |
| 18 | [Glossary](18-glossary.md) | Key terms and definitions |
| 19 | [Future Evolution Directions](19-future-evolution.md) | Post-MVP features and roadmap |