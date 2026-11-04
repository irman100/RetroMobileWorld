# Catalogue and website strategy

## Recommendation

Do **not** build a mandatory backend or crawler before the phone shell and Java ME installation flow work. Start with a versioned static catalogue registry in this repository. Later publish the same data through a small static website (GitHub Pages is sufficient for the first public directory).

The website should be an index and preservation guide, not an unauthorised mirror of every old game.

## Static-first model

The app ships with a compact catalogue snapshot and can refresh it from a versioned JSON endpoint. The website provides:

- source directory (live / archived / historical / unavailable / not checked);
- source type (original store, WAP portal, community, preservation project, emulator);
- supported formats and known device families;
- source URL and separately labelled archive URL, if one is verified;
- last-checked date and verification notes;
- licence/redistribution status (`metadata_only`, `link_only`, `redistribution_confirmed`, `unknown`);
- a compatibility catalogue tied to engine versions and device profiles.

## Source registry entries

A source is not called "working" merely because its domain resolves. A source must be checked for actual catalogue pages and package downloads. Record these statuses:

- `verified_live`: page and at least one expected workflow checked recently;
- `archived`: preserved capture exists, but original service is not assumed to work;
- `historical`: source is documented but availability is unverified;
- `unavailable`: checked and failed at a stated date;
- `not_checked`: candidate awaiting verification.

Initial candidate list:

- GetJar — historical mobile app catalogue; current site must be checked for original Java ME material.
- Nokia Ovi Store — discontinued official Nokia store; document as history, do not imply it is live.
- Opera Mobile Store — discontinued; use archive references only when verified.
- Sefan / Spaces / Seclub — historical Russian-language WAP/mobile communities; availability and archive coverage must be rechecked before any live adapter is written.
- Kahvibreak / Flashpoint preservation — preservation catalogue, not an original store.
- ArchiveTeam Mobile Phone Applications — preservation index/reference source.

## Adapter model

Each adapter implements a small contract: identify source, fetch catalogue metadata, normalize package records and report source status. Prefer static feeds or documented pages; avoid scraping that violates terms or overloads a site. Adapters must use allowlisted HTTPS hosts, explicit redirects and strict size/time limits.

The catalogue registry itself must remain usable even if all network adapters are offline. User-imported files always remain available locally.

## Why a website is useful later

A website gives the project one canonical public index for old sources, device profiles, compatibility notes and preservation status. It is easier to update than releasing a new Android APK for every source correction. It can also accept community corrections through GitHub issues/PRs without hosting executable files.

## Website phases

1. v0: `catalog/sources.json` and profile JSON in Git.
2. v0.2: static catalogue site generated from those JSON files.
3. v0.5: compatibility search and source-health review workflow.
4. Later: optional metadata API; no account system or package mirror unless there is a clear legal/operational reason.
