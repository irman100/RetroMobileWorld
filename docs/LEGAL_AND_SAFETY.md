# Legal, safety and preservation rules

This project runs legacy executable packages. It must handle them as untrusted software.

## Software and content

- Do not bundle commercial games, paid apps, copyrighted ringtones, wallpapers, themes or proprietary phone firmware unless redistribution rights are documented.
- A source link does not grant a redistribution licence.
- Personal or non-commercial nostalgia use does not, by itself, create blanket permission to copy, modify, extract or redistribute proprietary firmware, program code, icons, wallpapers, sounds or other assets.
- For France, the current Intellectual Property Code distinguishes permitted use, necessary backup copies, observation/testing by a person entitled to use the software, and narrowly conditioned decompilation for interoperability. These are specific exceptions, not a general licence to build and redistribute a substantially copied software product. See [Code de la propriété intellectuelle, Article L122-6-1](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000044365559). Obtain qualified advice for any planned reuse that goes beyond clearly licensed material or permitted interoperability analysis.
- Keep a provenance record for every package: source URL, retrieval timestamp, source status, package SHA-256, author/vendor metadata and rights status.
- Provide removal/rights-contact information for any later public catalogue or hosted asset.
- Do not scrape sites against their terms, evade access controls or bypass authentication/paywalls.

## Firmware and ROM files

- Do not ship proprietary Symbian ROMs, Nokia firmware, RPKG files, certificates or other phone system images in source control, APKs or the website.
- The user must select and provide a system image from a source they are authorised to use.
- Firmware may be inspected locally as a reference input only within the user's applicable rights. An image supplied by a user is not automatically cleared for copying its contents into the repository or APK.
- Keep original firmware binaries outside Git, CI artifacts and public releases. Store a local path/reference, product code, version, SHA-256 and analysis notes; do not upload firmware to project servers.
- Before an extracted bitmap, icon, sound, font or other resource is added to the distributable app, record the licence/permission basis. Otherwise, use the dump to learn behaviour and recreate the visual element independently.

## Legacy app sandbox

- Downloaded JAR/SIS packages are untrusted code, even if their age makes them look harmless.
- Never auto-launch after download or import.
- Validate file type, archive structure, sizes, checksums, descriptor URLs and metadata before installation.
- Reject path traversal entries and malformed archives.
- Keep each MIDlet's RMS/storage isolated; offer reset/remove controls.
- Network access from an emulated app is disabled by default for unknown packages; show a per-app confirmation before enabling it.
- Do not expose host files or Android permissions to an emulated app unless a narrowly scoped adapter explicitly supports the operation and the user approves it.
- Add download timeouts, maximum sizes, cancellation, redirect limits and HTTPS host policy.

## Third-party code

- Keep each engine's original LICENSE, NOTICE and required attribution files intact.
- Pin source dependencies to immutable commit IDs or release tags.
- Maintain an SBOM and licence scan before each distributable build.
- Do not treat a separate-process architecture as a guaranteed legal exemption from copyleft obligations; obtain legal review for distribution.

## Branding

Use phone model names only as compatibility labels. Create original shell artwork or use assets with explicit licences. Do not imply endorsement by Nokia, Sony Ericsson, Symbian Ltd. or other trademark owners.
