# Game Booster 2.0.0 — Release Checklist

## Build
- Release variant: `assembleRelease`
- Application ID: `com.codleander.gamebooster`
- Version: `2.0.0 (200)`
- Target SDK: 35
- Min SDK: 26

## Before Myket upload
1. Build and install the release APK on a real Android phone.
2. Test app launch, game detection, Boost, Refresh, DND access, Usage Access and Overlay access.
3. Verify the app does not request permissions that are not used.
4. Sign the APK with the developer's permanent release keystore.
5. Keep the keystore and passwords backed up securely. Do not commit them to GitHub.
6. Upload the signed APK to Myket and complete the store listing, screenshots, icon, description and privacy information.

## Important
The GitHub Actions artifact produced by this repository is a release build, but it is not a substitute for the developer's permanent signing key. For a store release, the final APK must be signed with the key that will be retained for future updates.

## Actual booster scope
The app uses Android APIs available to ordinary apps. It does not claim to overclock CPU/GPU or provide privileged/root-only controls.
