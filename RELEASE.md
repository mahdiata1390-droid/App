# Game Booster 2.2.1 — Release Checklist

## Build
- Release variant: `assembleRelease`
- Application ID: `com.codleander.gamebooster`
- Version: `2.2.1 (221)`
- Target SDK: 35, Min SDK: 26
- Release build is obfuscated (R8) and resource-shrunk; `app/proguard-rules.pro` holds the keep rules.

## Security measures in the release build
- R8 renames classes/members and removes unused code, so the APK cannot be read back as source.
- `IntegrityCheck` pins the release certificate
  (`D0:01:85:24:…:A4:71`, see `RELEASE_SIGNING_FINGERPRINT.txt`). An APK that was unpacked and
  re-signed with another key refuses to start; debug builds skip the check.
- No cleartext network traffic, system trust anchors only (`res/xml/network_security_config.xml`).
- `allowBackup=false`, no cloud backup or device transfer of session data.
- No `android.util.Log` calls survive in release builds.

## Signing key — important
The keystore used by this repository is `app/gamebooster-release.jks` and its password is the
fallback in `app/build.gradle`. Because both are in the repository, that key is **public**: anyone
with the repo can produce an APK that Android accepts as an update. The integrity check above
detects re-signing with a *different* key, not a leaked one.

To make the key private:
1. Create a new keystore offline and keep it out of git:
   `keytool -genkeypair -v -keystore release.jks -alias gamebooster -keyalg RSA -keysize 4096 -validity 10000`
2. Add repository secrets: `GB_KEYSTORE_BASE64` (base64 of the keystore), `GB_STORE_PASSWORD`,
   `GB_KEY_ALIAS`.
3. The workflow decodes the keystore to `app/gamebooster-release.jks` before the build; the
   environment values override the fallbacks in `app/build.gradle`.
4. Update `IntegrityCheck.RELEASE_CERT_SHA256` and `RELEASE_SIGNING_FINGERPRINT.txt` to the new
   certificate, then delete the committed keystore and the fallback password.
5. Users of the old builds must uninstall once before installing a build signed with the new key.

## Before Myket upload
1. Build and install the release APK on a real Android phone.
2. Test app launch, game detection, Boost, Refresh, DND access, Usage Access and Overlay access.
3. Verify the app does not request permissions that are not used.
4. Keep the keystore and passwords backed up securely. Do not commit them to GitHub.
5. Upload the signed APK to Myket and complete the store listing, screenshots, icon, description and privacy information.

## Important
The GitHub Actions artifact produced by this repository is a release build, but it is not a
substitute for a signing key that only the developer holds.

## Actual booster scope
The app uses Android APIs available to ordinary apps. It does not claim to overclock CPU/GPU or
provide privileged/root-only controls.
