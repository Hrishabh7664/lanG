# LAN Arcade Android architecture

LAN Arcade is a native Android landscape game for devices on the same Wi-Fi LAN. The project uses Java and Gradle; it does not use Unity, a browser, or Android Studio to build. Android Studio is not required.

## Modules

| Module | Responsibility |
|---|---|
| `app` | Android screens, game registry, LAN discovery, TLS session, lobby, host simulation loop, and canvas renderer. |
| `core` | Platform-independent `GameModule`, `PlayerHandle`, `MatchResult`, and delta-snapshot interfaces. |
| `games:ink-arena` | Playable authoritative territory game. |
| `games:jetfire` | Registry-compatible unavailable-game stub. |
| `games:racer3d` | Registry-compatible unavailable-game stub. |

The registry in `GameModuleRegistry` maps stable game IDs to module instances and availability. The UI reads this registry. A new module can be registered without changing the lobby protocol.

## Room and match flow

The native UI flows through Landing, Game Select, Join Room, Lobby, Match, and Results. The host chooses a game, creates a six-character uppercase alphanumeric room code with `SecureRandom`, and advertises the room. Clients scan or select a discovered room, enter its code, and request admission. The host sees the player's name, LAN address, and TLS safety code, then explicitly approves or declines. Both screens instruct the users to compare the safety code before approval.

The lobby displays the room code, roster, readiness, and start action. Ink Arena requires two to six ready players. The host owns the simulation and advances it at 20 Hz. Clients send a direction byte with a monotonically increasing sequence number. The host validates the frame and sequence, applies input to the simulation, and sends snapshots. A match ends when one player remains or the five-minute tick limit is reached; territory determines scores.

Jetfire and 3D Racer appear as cards and explain that they are unavailable in this build. Selecting either presents a message and returns to selection; they are not playable modules yet.

## LAN transport and discovery

Discovery uses bounded UDP broadcast on port `45882`, chosen so devices already on one Wi-Fi subnet can find one another without forming a Wi-Fi Direct group. Advertisements contain a random room ID, game ID, display name, TCP port, player count/capacity, and host certificate fingerprint. They do not contain the room code, player identity, or game state. Room-code queries are rate-limited per source IP. Discovery metadata is visible to other devices on the local network.

Lobby and game messages use TLS 1.2 or TLS 1.3 over TCP. A host-only P-256 key and short-lived certificate are generated in AndroidKeyStore when a room opens and deleted when it closes. The client pins the SHA-256 certificate fingerprint from discovery and verifies it during the TLS handshake. A six-character safety string derived from the negotiated TLS session identifier is displayed to both users for an out-of-band comparison. The host must separately approve each join. There is no Wi-Fi Direct and no automatic admission.

Frames use a four-byte length followed by a versioned binary message. Input/control frames are limited to 4 KiB and snapshots to 64 KiB. The codec rejects invalid lengths, unknown types, and unsupported versions before payload parsing. Per-message schemas enforce bounded fields and trailing-byte rejection. Connected peers are capped at 100 inbound messages per second; game input has an additional 60-per-second limit. Input sequence numbers are checked before dispatch. Join attempts are limited to five per source IP per minute, with at most three concurrent handshakes/admissions. Invalid and over-limit connections are closed.

## Ink Arena

The board is 64 by 64 cells, stored in compact owner and trail arrays. Player spawn positions use `SecureRandom`. Each host tick moves living players one cell, resolves boundary, head, and trail collisions, and claims enclosed territory when a trail returns to owned cells. Claims use a flood fill from the board edge. Rules, territory, survival, and results are authoritative on the host; client input cannot directly set scores or state.

The host emits a full state at match start and every 60 ticks. In between, it sends cell changes against the last acknowledged full/delta tick, with player state included. Clients apply a delta only when its baseline is available and request a full snapshot otherwise. The canvas view reads render state and sends touch direction input through the room service.

## Android and persistence

The launcher activity is landscape. Menus and lobby use Android native views; Ink Arena uses a custom `View`/`Canvas`. The app stores only an optional display name in private preferences. Room code, player roster, and game state stay in memory. Android backup and cleartext traffic are disabled in the manifest/network security configuration. There are no analytics, ads, cloud APIs, or accounts in this build.

## Build and release

Use JDK 21, Android SDK platform 34/build tools 34, and the checked-in Gradle 8.7 wrapper. See `SECURITY.md` for dependency verification, signing setup, exact build commands, APK validation, and remaining release checks. A locally signed APK is for sideloading; distributing a store build requires a release key controlled by the publisher.
