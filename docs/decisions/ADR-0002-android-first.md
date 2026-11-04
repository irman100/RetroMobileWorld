# ADR-0002: Android-first host

- **Status:** Accepted for first prototype.
- **Date:** 2026-11-04

## Context

The product's central experience is a virtual phone running inside a modern phone. Both shortlisted emulator projects have Android build paths, although EKA2L1 describes Android support as experimental.

## Decision

Build the first host for Android with native Kotlin and a custom Canvas/View shell renderer. Keep profile and catalogue data platform-neutral. Evaluate Windows after the Java ME and Symbian backend contracts are proven.

## Consequences

- The first technical goal is a working Android shell, not a cross-platform framework.
- A desktop host remains possible later.
- EKA2L1 Android performance and ABI support are explicit release gates, not assumptions.
