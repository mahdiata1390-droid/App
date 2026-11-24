# FLASH BOOSTER

Production Android game booster for supported Android devices.

## Release
- Display name: FLASH BOOSTER
- Version: 1.2.0
- Build: 3 (adds Liquid Glass, thermal guard, break reminders, game shortcuts and backup)
- Application ID: com.codleander.gamebooster
- Min SDK: 26
- Target SDK: 35

## Core features
- Game library and game-only detection
- One-tap game session / booster
- Per-game profiles
- Gaming DND
- Floating HUD / toolbox
- RAM, battery, thermal and refresh-rate telemetry
- Network type and latency check
- Optional private DNS: DNS-over-TLS through a DNS-only tunnel, four built-in resolvers
  plus a custom resolver (any IP and TLS hostname)
- Brightness lock
- Keep-awake support
- Session timer and history
- Crash/process-death recovery state
- Thermal Guard: a session ends by itself once the device runs too hot
- Break reminders during long sessions
- Launcher shortcuts: long-press the icon to boost an installed game in one tap
- Backup & restore of every setting plus the session history as one JSON file
- Liquid Glass appearance (iOS-27 style frosted, edge-lit panels over a live light field,
  with real RenderEffect blur on Android 12+)

## Icons
The master artwork is `art/icon-source-1024.png`. `python3 tools/make_icon.py` adds the
FLASH BOOSTER wordmark and renders every derived asset: the adaptive background/foreground layers,
all mipmap densities, the legacy and round launcher icons, and the 512 px store icons in `art/`.
Edit the artwork or the script, never the generated PNGs.

## Bundled assets
- `tools/make_art.py` renders eight full-resolution (1080×2400) backdrops into
  `res/drawable-nodpi/`; they are selectable in Settings → Appearance → Backdrop.
- `tools/make_sounds.py` synthesises the three sound packs (four effects each) plus a 50-second
  ambient loop per pack, written as 16-bit 44.1 kHz stereo PCM. The fourth pack, Silent, has no
  audio. Ambient audio is opt-in in Settings and plays only while a session runs.
- `res/font/` holds Vazirmatn (SIL Open Font License 1.1) for both Latin and Persian text.
- Together these are what make the release APK about 61 MB; nothing in it is padding.

## Store release
`bash tools/build_release.sh` builds the signed release APK for Myket into
`dist/flash-booster-1.2.0.apk`. The same signed file is committed as
`release/flash-booster-<version>.apk` so the published build can be downloaded straight from the
repository; the committed `release/flash-booster-1.1.0.apk` is the previous build and needs a
rerun of the script to publish 1.2.0. Listing copy is in `myket/listing.md`, the signing checklist in `RELEASE.md`.

## Important
This app uses standard Android APIs available to ordinary applications. It does not claim privileged CPU/GPU overclocking or root-only performance controls.
