# ADR-0004: GPLv3 product and community-friendly commercial model

- **Status:** Accepted as the product direction; store-specific distribution remains subject to a licensing review.
- **Date:** 2026-11-09

## Context

Retro Mobile World is intended to be useful as a long-lived preservation project and may also become a paid product. The project already declares GPL-3.0 in its root `LICENSE`, and the Android C3-00 runtime links the pinned upstream `c3emu` crate, declared GPL-3.0-or-later.

The product owner explicitly prefers a model in which the project can earn revenue while recipients remain free to study, modify, use, and redistribute the software. The goal is not to prevent forks or sharing.

## Decision

### 1. Keep the current copyleft foundation

- Keep the repository's existing GPL-3.0 license for the combined application for now. Do not switch to a proprietary license, MIT, Apache-2.0, AGPL, or a new dual-license scheme as part of this decision.
- The pinned `c3emu` dependency is GPL-3.0-or-later; the project chooses GPL version 3 for the combined distributed work, subject to the exact rights and notices of all included components.
- Do not claim that the app can be distributed as a closed-source binary. The Android app links the upstream c3emu native backend; the integrated work must be distributed in compliance with the applicable GPL terms.
- Preserve third-party copyright notices and licences. Record the exact dependency revision and all source changes for every release.

### 2. Release binaries with matching corresponding source

For every public binary release distributed by the project:

- publish the exact corresponding source for that release, including the source for GPL-covered native/Kotlin code and the scripts/configuration needed to build and install it, subject to the GPL definition of Corresponding Source;
- make source access no more restrictive and no more expensive than the corresponding binary access; for network distribution under GPLv3 section 6(d), source access must be available through equivalent access at no additional charge;
- publish a version tag and checksums linking the APK/artifact to its source revision;
- include the GPL text, third-party notices, attribution, and any required modification notices;
- do not add an EULA, account condition, DRM condition, or other term that takes away GPL rights.

A person who receives a copy may redistribute it, including for free or for a fee, and may publish modified versions under GPLv3. The project will not try to prohibit that.

### 3. Revenue should come from value, not copy restrictions

The project may charge for distribution of official builds, but this is not an exclusive licence and cannot prevent recipients from redistributing their copies. Because free mirrors and forks are permitted, binary sales alone are not the primary expected revenue source.

Preferred revenue avenues to evaluate after the product is useful and stable:

- voluntary donations, sponsorship and community funding;
- optional paid technical support, setup assistance and maintenance;
- commissioned device-profile work, compatibility testing, preservation research and custom integrations;
- separately operated hosted services, where technically and legally independent from the GPL application and where the service provides genuine ongoing value.

Do not promise or sell proprietary in-app features that depend on withholding GPL-covered source. Any service or separate work must be architected and licensed separately, with the separation reviewed before distribution.

### 4. Protect the product identity without restricting forks

- Keep the project name, logo and official presentation distinct from the source-code licence.
- GPL grants copyright permissions; it does not automatically grant rights to use project trademarks or imply endorsement.
- Allow compatible forks, but do not allow modified forks to misrepresent themselves as official Retro Mobile World builds.
- A trademark/brand policy must be permissive enough to avoid blocking redistribution of the GPL-covered code.

### 5. Keep firmware and third-party content out of distributions

- Continue to require users to select firmware images they are entitled to use.
- Do not bundle Nokia MCUSW, PPM, IMAGE, ROM, commercial games, or other copyrighted proprietary content unless redistribution rights are explicitly verified.
- The GPL on emulator code does not grant rights to phone firmware, games, skins, logos, manuals, or other third-party assets.

### 6. Store distribution is a separate release gate

- Android distribution through Google Play or another channel must be checked against the current store agreement, developer requirements and GPL source-delivery obligations before release.
- Do not assume that a paid listing removes the recipient's GPL redistribution rights.
- Do not submit an iOS build to the Apple App Store under the default store EULA without a specific compatibility review. Apple's standard Licensed Application EULA contains non-transferability and redistribution restrictions, while GPLv3 prohibits additional restrictions on recipients. Any App Store exception or custom licensing arrangement must be valid for every GPL component included in the app; Retro Mobile World cannot grant exceptions on behalf of upstream c3emu copyright holders.
- The fact that another GPL-related emulator appears in a store is not, by itself, evidence that its licensing arrangement can be copied.

This ADR is a product and engineering policy, not a substitute for jurisdiction-specific legal advice. Before accepting payments, signing distribution terms, or entering app-store agreements, review the arrangement with an appropriately qualified adult/legal adviser.

## Consequences

- The source remains publicly inspectable and redistributable; downstream improvements to the combined work remain under GPLv3.
- Users can obtain and share the application without asking the project for permission.
- Revenue must be earned through convenience, trust, support, expertise, commissioned work, sponsorship or independent services rather than exclusive control over copies.
- Release engineering must produce source-linked, reproducible-enough releases and maintain accurate dependency notices.
- Apple App Store distribution is not approved by this ADR and remains blocked pending a component-by-component licence and EULA review.
- The current licence is not being changed by this ADR; this decision documents the intent and release discipline around the existing licence.

## References

- GPLv3: https://www.gnu.org/licenses/gpl-3.0.html
- GNU GPL FAQ (commercial sale and source delivery): https://www.gnu.org/licenses/gpl-faq.html.en
- GNU guidance on selling free software: https://www.gnu.org/philosophy/selling.en.html
- Apple Standard Licensed Application EULA: https://www.apple.com/legal/internet-services/itunes/dev/stdeula/
- Existing component inventory: ../THIRD_PARTY_NOTICES.md
