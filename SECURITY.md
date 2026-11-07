# Security self-audit

This is a code/configuration self-audit for the LAN Arcade v1 sideload build. It is not an independent penetration test, a MASVS certification, or a claim that no vulnerability can exist. No high-severity issue was identified in the reviewed implementation paths below. Multiplayer interoperability and release installation were verified only on an Android 14 emulator so far; a physical-device and two-device LAN match remain unverified.

## Release controls

| MASVS category | Control in this build | Enforcement location and status |
|---|---|---|
| STORAGE | No credentials or game data are persisted. The optional display name is app-private. Android backup is disabled. Host TLS keys are generated in AndroidKeyStore and deleted when the room closes. | `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/langamestudio/lanpartyarcade/network/TlsSessions.java`; basic baseline implemented. The display name is not encrypted separately because it is low-sensitivity UI data. |
| NETWORK | Cleartext Android traffic is denied. Lobby/game transport is TLS 1.2+ TCP with the advertised certificate fingerprint pinned for that room. A human-compared TLS session safety string and explicit host approval protect admission. UDP is discovery-only. Frames, payload fields, sequence numbers, concurrent joins, and rates are bounded. | `app/src/main/res/xml/network_security_config.xml`, `TlsSessions.java`, `WireProtocol.java`, and `LanRoomService.java`; implemented. UDP discovery metadata is visible on the local subnet. |
| PLATFORM | Only the launcher activity is exported. Backup is off. Network access is limited to LAN sockets; no dynamic code loading or external SDK is present. | `app/src/main/AndroidManifest.xml`; baseline implemented. Android runtime/network behavior still varies by device and Wi-Fi access point. |
| CODE | Gradle dependency verification stores SHA-256 hashes and rejects missing or mismatched artifacts. Release enables R8 and resource shrinking. Network parsing uses version/type/length checks, bounded strings/arrays, schema validation, and trailing-byte checks. | `gradle/verification-metadata.xml`, `app/build.gradle`, `WireProtocol.java`, `LanRoomService.java`; implemented for resolved Gradle artifacts and the current app protocol. Review checksum changes when dependencies change. |
| RESILIENCE | Release code is minified and malformed or excessive LAN traffic is rejected. | `app/build.gradle`, `LanRoomService.java`; basic hardening only. This build has no root/debugger/hooking detection, runtime tamper response, or dedicated anti-cheat attestation. Those controls are not required for the trusted-LAN proof of concept and are not claimed. |

### Discovery and admission

Wi-Fi Direct is not used. UDP broadcast advertises room metadata but carries no game data or secret room code. The host certificate fingerprint in the advertisement is only a pin candidate: the client verifies it in the TLS handshake. The host displays a short authentication string and requires explicit approval; the client UI asks the users to compare the same string before the host accepts. Connections are never auto-accepted. Join attempts are limited per source IP and only three handshakes/admissions can run at once. Connected peers are limited to 100 inbound frames per second; inputs have an additional 60-per-second limit.

### Residual risks and scope

- UDP discovery can be observed, spoofed, or jammed on the local network. A spoofed fingerprint can cause a failed or mismatched TLS connection, but a valid TLS pin is checked before game traffic is accepted. The design assumes users compare the safety string and reject mismatches.
- A six-character safety string is intended for an in-person comparison. Do not use this protocol over an untrusted public network.
- An attacker with access to the same LAN can consume discovery bandwidth or repeatedly attempt connections from multiple source addresses. Admission limits bound each source and concurrent handshakes but do not provide a network-wide DoS guarantee.
- The locally generated release signing key is a development/sideload key, not a publisher-managed Play Store key. It must be backed up securely by the release owner; losing it prevents updates signed with the same identity.
- Jetfire and 3D Racer are visible stubs, not playable content. Ink Arena has not yet completed a real two-device LAN match test.
- Physical-device install/launch has not been verified because `adb devices` reported only the API 34 emulator at the time of this audit. Repeat that check after connecting a phone and enabling USB debugging.
- This self-audit is not a formal MASVS assessment, external code review, or guarantee that no high-severity vulnerability exists.

## Reproduction

Use JDK 21 and Android SDK API 34/build-tools 34.0.0. Gradle 8.7 is pinned by the wrapper and its distribution SHA-256 checksum. The dependency-verification metadata is checked into `gradle/verification-metadata.xml`; normal Gradle dependency resolution uses strict verification and fails when a required checksum is missing or does not match.

On Windows PowerShell, set the local signing values before building release. Keep the keystore and passwords outside version control:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:ANDROID_HOME = 'C:\path\to\Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_KEYSTORE_PATH = 'C:\private\lan-arcade-release.jks'
$env:ANDROID_KEY_ALIAS = 'lanarcade-release'
$env:ANDROID_KEYSTORE_PASSWORD = '<locally supplied secret>'
$env:ANDROID_KEY_PASSWORD = '<locally supplied secret>'
```

The signing configuration in `app/build.gradle` reads only those environment variables. A local key can be generated with `keytool -genkeypair`; the key file and password must never be committed. This development session generated its keystore at `%USERPROFILE%\.lan-arcade-release\lan-arcade-release.jks` and stored its password using Windows account encryption. The public certificate SHA-256 for this APK is `2d52248e302f983418a75e8a52d1ca7b9f308f389bafa15530f0869fd0aab44d`.

Build, verify, and install:

```powershell
./gradlew.bat --no-daemon assembleRelease
$apk = 'app/build/outputs/apk/release/app-release.apk'
$env:ANDROID_HOME\build-tools\34.0.0\apksigner.bat verify --verbose --print-certs $apk
adb install -r $apk
adb shell monkey -p com.langamestudio.lanpartyarcade 1
```

The release build completed successfully, the APK signature verified with APK Signature Scheme v2, and `adb install -r` plus launch succeeded on the API 34 x86_64 emulator. Connect a physical API 24+ device and rerun the final two commands to complete the real-device gate.
