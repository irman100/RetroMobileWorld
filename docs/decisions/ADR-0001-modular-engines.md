# ADR-0001: One phone shell, separate emulator engines

- **Status:** Accepted for prototype; final engine packaging remains open.
- **Date:** 2026-11-04

## Context

The product should reproduce multiple historic phone models without maintaining a separate emulator for each. Java ME and Symbian are different execution environments and cannot be modelled as mere visual themes.

## Decision

Use a shared phone shell driven by device profiles. Keep Java ME and Symbian behind separate backend adapters. Device profiles define body art, logical display size, keypad geometry, input mapping and compatibility hints. Backends own their operating-system/runtime semantics.

## Consequences

- Adding a phone shell should usually require a new profile and licensed artwork, not a new runtime.
- A Symbian-capable device profile advertises a Symbian runtime target that requires a compatible user-provided system image. A device profile can advertise multiple runtimes; for example, an N73-like S60 profile supports both Java ME and native Symbian packages. A mock Symbian theme is not accepted as runtime support.
- Java ME MIDP/CLDC settings and optional APIs remain backend concerns.
- Engine integration can be independently replaced or upgraded.
- The common API must remain provisional until the render-surface and input-routing spikes are complete.

## Alternatives considered

- One emulator per phone model: rejected because it duplicates runtime work and couples presentation to execution.
- One generic skin over one Java emulator: rejected because it cannot execute native Symbian software or reproduce Symbian system services.
