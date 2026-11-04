# First Java ME smoke test

This procedure validates the interim Java ME companion-runtime path. It does not validate an embedded MIDP/CLDC engine.

## Prerequisites

- Android 8.0 or newer.
- Retro Mobile World 0.2.0 or newer.
- J2ME Loader installed separately from its official project/release source.
- A self-authored, public-domain or otherwise lawfully obtained Java ME `.jar` test application.

Do not install a package from a source you do not trust. Retro Mobile World does not certify the safety of third-party MIDlets.

## Import

1. Open the selected Java ME-capable virtual phone profile.
2. Select **Java applications**.
3. Press the left soft key, **IMPORT**.
4. Choose a local `.jar` file in Android's document picker.
5. Confirm that the app title, vendor and version are shown after import.
6. Return to Java applications and confirm the package appears in the list.

The app copies the selected package into its app-private library. Importing does not execute the package.

## Launch

1. Select the imported item with the virtual D-pad.
2. Press the right soft key, **RUN**.
3. Android should open J2ME Loader's installer for the shared read-only package URI.
4. Review the MIDlet details in J2ME Loader and explicitly confirm installation there.
5. Launch the MIDlet from J2ME Loader and verify that it starts.

## Expected limitations in this milestone

- J2ME Loader runs as a separate Android app. Its runtime screen does not yet appear inside the Retro Mobile World phone display aperture.
- Retro Mobile World's virtual keypad is not yet routed into the running MIDlet.
- JAD descriptors with remote absolute HTTPS `MIDlet-Jar-URL` are accepted by the handoff; for relative/local descriptors, import the matching JAR directly.
- RMS persistence, per-MIDlet network permissions and runtime compatibility reporting remain future work.

## Failure information to collect

If import or launch fails, record:
- Android version and device model;
- phone profile selected in Retro Mobile World;
- package extension, displayed title and vendor;
- exact error dialog text;
- whether J2ME Loader is installed and whether it opens from its own launcher.

Do not share paid packages, copyrighted commercial games or private files in bug reports.
