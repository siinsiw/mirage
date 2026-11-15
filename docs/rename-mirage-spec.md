# Relay → Mirage: the rename spec

The app is called Mirage. Every place that still says Relay is renamed, by
these rules, so that three people working on different parts arrive at the
same names. Decided with the owner on 2026-11-15.

## Rules

1. **The brand.** `Relay` → `Mirage`, `relay` → `mirage`, `RELAY_` → `MIRAGE_`,
   in names of files, folders, classes, functions, variables, constants,
   environment variables, log/file names, UI text, comments and docs.
2. **The generic word.** Where "relay" means *a server in between*, not the
   app, it becomes **server** (or "server in between" where that is clearer):
   "15 relays" → "15 servers", "the Iran relay" → "the server in Iran".
3. **Never renamed**, because they are someone else's names:
   - sing-box / Xray / TUIC protocol fields: `udp_relay`, `udp_relay_mode`,
     `UDPRelay`, `udpRelay`, and anything else that is a key in a sing-box,
     Xray, gost or Clash configuration;
   - `relayout` and other words that merely contain the letters;
   - the GitHub repository path `siinsiw/ovpn-pin`;
   - **GitHub Actions secret names** `secrets.RELAY_*` (they already exist in
     the repository settings and cannot be renamed from code). A workflow may
     map them onto `MIRAGE_*` environment variables:
     `MIRAGE_KEYSTORE_PASSWORD: ${{ secrets.RELAY_KEYSTORE_PASSWORD }}`.
4. **The server key.** New keys are `mirage://…`. Every parser - Python
   (`app/tunnel.py`), Go (`mobile/core`), Kotlin (import, deep links) - accepts
   **both** `mirage://` and `relay://`, forever; only the prefix differs, the
   payload is unchanged. Error messages name `mirage://`.
5. **Things that already exist on people's machines are moved, not orphaned:**
   - Windows data folder `%LOCALAPPDATA%\Relay` → `%LOCALAPPDATA%\Mirage`:
     on start, if `Mirage` does not exist and `Relay` does, move it (rename the
     folder); if both exist, use `Mirage` and leave `Relay` alone.
   - Windows registry `HKLM\SOFTWARE\Relay\KillSwitch` → `HKLM\SOFTWARE\Mirage\KillSwitch`:
     read the new one, fall back to the old one, and when releasing the kill
     switch also delete the old key if present.
   - Windows Firewall rules and groups named Relay… → Mirage…; whatever
     removes the rules also removes any left with the old names.
   - Autostart (Run key) and any shortcuts the app itself writes: new name,
     old one removed.
   - Server installer: `/etc/relay` → `/etc/mirage`, `/opt/relay` → `/opt/mirage`,
     nginx `relay-*` sites and any `relay-*` systemd units → `mirage-*`.
     Re-running the installer on a server that has the old ones moves them
     (data kept: passwords, ids, certificates), removes the old units/sites,
     and leaves `/etc/relay` as a symlink to `/etc/mirage` for anything that
     still reads the old path.
6. **Android** gets a new identity: `applicationId` and `namespace`
   **`app.mirage`**, Kotlin package `com.erelay.relay` → `app.mirage`
   (source folders moved to `app/mirage/...`), classes `RelayVpnService` →
   `MirageVpnService`, `RelayApp` → `MirageApp`, `RelayTile` → `MirageTile`,
   `RelayCore` → `MirageCore`, `RelayTheme` → `MirageTheme`, drawable
   `ic_relay` → `ic_mirage`, shared prefs `relay.xml` → `mirage.xml`. (A new
   install; nothing to migrate on the phone.) The theme already has an
   `object Mirage`; where it clashes with another `Mirage`, use an import
   alias rather than another name.
7. **Go (gomobile).** The bound package `relay` (`mobile/core/bind`) →
   `mirage`, `relay.aar` → `mirage.aar`; `relaykey.go` → `miragekey.go`
   (and its test), `ParseRelayKey` → `ParseMirageKey`, `RelayKey*` →
   `MirageKey*`, and so on. Kotlin follows. The Go module path stays.
8. **Windows build.** `Relay.exe` → `Mirage.exe`, `app/Relay.pyw` →
   `app/Mirage.pyw`, `dist/Relay` → `dist/Mirage`, release zip
   `Mirage-windows-x64-*.zip`, artifact `mirage-windows-x64`.
9. **Releases and CI.** APKs `mirage-android-*.apk`, artifact `mirage-apk`,
   everything the workflows name after the app. Tags (`android-v*`,
   `desktop-v*`) are unchanged.
10. Example hostnames: `relay.example.com` → `mirage.example.com`.
11. Images that contain the word in their pixels (e.g. `docs/relay-banner.png`)
    are renamed as files only; say so in the report.

## When you are done

`git grep -i relay` in your files should show only rule-3 names and the
`relay://` acceptance from rule 4. Report anything you were unsure of.
