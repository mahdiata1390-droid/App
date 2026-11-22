# FLASH BOOSTER

Production Android game booster for supported Android devices.

## Release
- Display name: FLASH BOOSTER
- Version: 2.3.0
- Build: 230
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
- Brightness lock
- Keep-awake support
- Session timer and history
- Crash/process-death recovery state

## Icons
The master artwork is `art/icon-source-1024.png`. `python3 tools/make_icon.py` adds the
FLASH BOOSTER wordmark and renders every derived asset: the adaptive background/foreground layers,
all mipmap densities, the legacy and round launcher icons, and the 512 px store icons in `art/`.
Edit the artwork or the script, never the generated PNGs.

## Store release
`bash tools/build_release.sh` builds the signed release APK for Myket into
`dist/flash-booster-2.3.0.apk`. Listing copy is in `myket/listing.md`.

## Important
This app uses standard Android APIs available to ordinary applications. It does not claim privileged CPU/GPU overclocking or root-only performance controls.
