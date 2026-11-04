# Third-party notices

No emulator source code or binary is vendored in this branch.

The first Java ME integration calls the separately installed J2ME Loader application through its exported Android file-opening activity. Retro Mobile World does not copy, bundle or modify J2ME Loader code or binaries. Users must install the runtime separately. If J2ME Loader is ever vendored or linked into this app, copy its unmodified licence and required notice files into a dedicated directory under `third_party/j2me-loader/`, pin the exact upstream commit and review all dependency licences.

Candidates researched on 2026-11-04:

- `nikita36078/J2ME-Loader` — Apache-2.0: https://github.com/nikita36078/J2ME-Loader
- `EKA2L1/EKA2L1` — GPL-3.0: https://github.com/EKA2L1/EKA2L1
- `hex007/freej2me` — GPL-3.0-or-later for FreeJ2ME; the bundled ObjectWeb ASM code has its own licence: https://github.com/hex007/freej2me

This file is an integration checklist, not a substitute for the original licences.

## Gradle Wrapper

The Gradle Wrapper launcher scripts and bootstrap JAR are distributed with Gradle 9.6.0 under the Apache License 2.0. The scripts retain their upstream copyright and licence headers. Upstream source: https://github.com/gradle/gradle/tree/v9.6.0.
