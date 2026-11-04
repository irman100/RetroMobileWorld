# Legal, safety and preservation rules

This project runs legacy executable packages. It must handle them as untrusted software.

## Software and content

- Do not bundle commercial games, paid apps, copyrighted ringtones, wallpapers, themes or proprietary phone firmware unless redistribution rights are documented.
- A source link does not grant a redistribution licence.
- Keep a provenance record for every package: source URL, retrieval timestamp, source status, package SHA-256, author/vendor metadata and rights status.
- Provide removal/rights-contact information for any later public catalogue or hosted asset.
- Do not scrape sites against their terms, evade access controls or bypass authentication/paywalls.

## Firmware and ROM files

- Do not ship proprietary Symbian ROMs, Nokia firmware, RPKG files, certificates or other phone system images in source control, APKs or the website.
- The user must select and provide a system image from a source they are authorised to use.
- Store only a user-selected path/reference and verification metadata where feasible; do not upload firmware to project servers.

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
