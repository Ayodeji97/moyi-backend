# ADR-0010 — Kotlin Multiplatform with Compose Multiplatform for shared UI

**Status:** Accepted · **Date:** 2026-08-30

## Context
Android and iOS clients are required. The developer is a senior Android/KMP engineer with limited iOS-native experience. Options: full native (Kotlin + Swift), KMP with shared logic and native UI per platform, KMP with Compose Multiplatform for shared UI, or a cross-platform framework such as Flutter or React Native.

## Decision
KMP with Compose Multiplatform for shared UI across Android and iOS. Platform-specific code confined to `expect`/`actual` implementations for secure storage, background work, notifications and permissions. The API client is generated from the backend's OpenAPI spec.

## Consequences
**Positive:** one language, one team of one, roughly 90% code sharing. Builds directly on existing expertise. Compose Multiplatform is mature enough in 2026 for an app of this complexity. Full-stack Kotlin — backend to watch — is a distinctive and coherent portfolio story.
**Negative:** iOS build times are slow and Xcode friction is real. Compose on iOS can feel subtly non-native without deliberate attention to platform navigation idioms. Some libraries remain Android-only, forcing `expect`/`actual` work. A smaller community than Flutter or React Native when something goes wrong.
**Neutral:** SwiftUI can be adopted for specific screens if Compose proves inadequate for one of them.

## Alternatives considered
- **KMP with native UI on each platform** — the safest for iOS feel; rejected because it roughly doubles UI work for one developer and requires real SwiftUI proficiency.
- **Flutter / React Native** — rejected: abandons Kotlin, which is the whole point, and abandons existing expertise.
- **Android only** — rejected: halves the portfolio value, and a couples app where only one partner can install it is not a product.

## Revisit when
Compose Multiplatform proves inadequate for a specific iOS interaction, or iOS build times start materially slowing the work.
