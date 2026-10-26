# Native Fire TV companion MVP (source and device gate)

This is a **separately packaged Android TV proof application**, `org.thiajay.stremiotvcompanion`, staged alongside the already accepted Night Audio Android Media3 reference. It does not replace or alter `com.stremio.one`. It is **not** the official Stremio Android TV client, a deployed app, a production account client, or proof of physical Fire TV usability. No Stremio credentials, source links, account data or remote debugging operations are included.

## Currently implemented

* An Android TV Leanback launcher activity with actual `Media3 ExoPlayer`, native TV focusable buttons, real seek commands and lifecycle release on stop/destroy. The source now also supplies a unique 320dp × 180dp, neutral Companion Lab launcher vector banner required by Android TV. It is not the official Stremio artwork.
* A deterministic **offline 12-second PCM WAV** sample, generated only on the device after explicit activation. The intro, recap and credits ranges in this mode are **synthetic fixtures only**. They do not claim to identify timings in any actual television programme.
* The exact previous `NativeTvBridge.kt` is reused without editing (SHA-256 `0e067446053abc456196d060bc96dff72d03902542fb9ae97cefd47fa143f23b`). The original 53-assertion offline regression is retained and executed as a JVM test, plus additional host Core-dispatch and source-switching tests.
* `SnapshotBoundNativeSeekPort` now supplies a **one-use, final player-state lease** to close the gap between Core dismissal and the actual Media3 seek. It rechecks generation, canonical video identity, readiness, duration, native transition flags, Next Episode popup and whether playback already passed the intended target immediately before seeking. It must run on the host-owned single player/Looper thread; it is not an atomic cross-device account transaction. Regression tests deliberately switch the selected video after Core acceptance and verify that Media3 does not seek the wrong episode.
* `NativeTvSkipActuator` is an injection boundary which dispatches the existing Core `Player.DismissSkipSegment` **before** issuing a same-video native Media3 seek. A changed generation, selected video, duration, buffering, next-episode popup or Core refusal blocks seeking. Its source contains no direct `NextVideo` or watched-state mutation.
* `NativeTvWatchedActuator` prepares whole-series and season watched actions only after a real account-side `StremioAccountPort` supplies fresh Core metadata and account record, then reconfirms before dispatch. `NativeTvEpisodeWatchedActuator` now also prepares individual canonical episode watched and unwatched from the **original fully loaded Core Video** using `MetaDetails.MarkVideoAsWatched([Video, bool])`, rejecting future episodes, changed video objects, account drift and repeated remote activation. The single-episode adapter now also checks the **entire canonical nested Video payload twice** and dispatches only a **deeply detached** copy, never a mutable Core-owned map, eliminating a stale-same-ID metadata race. Nonfinite numeric metadata fails closed. Neither source is reconstructed from the on-screen story order. The **actual app does not supply an authenticated account port**. Its watched controls are disabled, rather than silently fabricating watched history.
* A pure Kotlin Story Order presentation adapter now validates version 1 hints, rejects duplicate, unknown or incomplete regular-episode sequences, returns the original canonical episode objects and supplies a stable-ID Next Story override candidate without bypassing existing player gates. A focusable TV control demonstrates canonical versus Story Order presentation using only synthetic local metadata, including one narrative Season 0 entry and one omitted ancillary extra. It has no catalogue, player, account or watched-state binding and is not native-client or device acceptance.
* A native Kotlin classification-first recovery planner now implements the strict Foundation PR #75 to PR #77 boundary. Provider, source and transport retries exclusively own their one classifier attempt; Matroska, container, decoder, DRM, EGL, backend-loop, episode and unclassified stops cannot fall through to playback recovery. Only the exact version-1 playback-health delegation with matching immutable video/source/load context and an explicit bounded attempt ledger can select an existing recovery action. The planner performs no actuation, network, account or provider operation.
* `NativePlaybackRecoveryActuator` now supplies a source-only one-shot handoff for an accepted playback-health plan. It leases the exact context, video, source, load generation, watched-state fingerprint and resume-state fingerprint, re-reads the complete snapshot immediately before the injected port call and consumes the lease before execution. Terminal and classifier-owned retries can never prepare an actuator call. No concrete Media3 recovery, source operation, account write or device binding is included.
* Night Audio Off/Gentle/Balanced/Strong buttons reuse the already implemented `NightAudioMedia3Processor` and PCM processor. It is **Off by default**. The panel says that processing was observed only when actual PCM frames were processed, not when a button was merely selected. Audible quality and soundbar identification remain unverified.
* Separate package identity, no Android network/storage/account/overlay/boot/accessibility permissions, `allowBackup=false`, `usesCleartextTraffic=false` and no exported activity except the launcher. No external video URL ingestion in this build. No user data persistence except the locally generated throwaway WAV in app cache.

## Build/test

Use the existing Gradle 8.11.1 and Android SDK 35/36 setup for `android-night-audio-reference`:

```sh
gradle -p android-night-audio-reference :processor:testDebugUnitTest :companion-tv:compileDebugKotlin :companion-tv:testDebugUnitTest :companion-tv:assembleDebug
```

Source-only offline tests on a machine with JDK 21 and Kotlin compiler 1.9+:

```sh
kotlinc android-night-audio-reference/companion-tv/src/main/java/org/pss/stremio/tv/NativeTvBridge.kt \
  android-night-audio-reference/companion-tv/src/main/java/org/pss/stremio/tv/NativeTvPorts.kt \
  android-night-audio-reference/companion-tv/src/test/java/org/pss/stremio/tv/BridgeSmokeTest.kt \
  -include-runtime -d /tmp/native-tv-companion-bridge.jar
java -jar /tmp/native-tv-companion-bridge.jar
```

CI must run the native SDK compilation, test and manifest audit on the **exact candidate head**, preserve the APK's SHA-256 and avoid any release/upload/install. Passing Kotlin JVM tests alone is insufficient for a functioning Android APK.

## Missing before production integration

1. A licenced, maintained Stremio Core native account/session client and proper addon/stream selection, with no reuse of private credentials. The existing `stremio-core-kotlin` runtime requires independent architecture, SDK, dependency, privacy and trust review before adoption. Neither NuvioTV nor an unrelated external player provides proven Stremio account parity.
2. Real provider-neutral resolved skip evidence with exact canonical episode/stream identity. Synthetic time windows must **never** be applied to actual programmes. Core skip message acknowledgement and Media3 seek must be wired to the actual player.
3. Fresh Core watched bitmap, per-episode video object, full series/season controls and race-safe writes with isolated account acceptance. Story Order still needs authorised real Core metadata binding, Android mobile implementation, player/Continue Watching integration and remote/device acceptance. Do not infer completion from local UI actions.
4. Playback of authorised real video/audio streams, DRM/format support, subtitles, remote accessibility, resume and crash recovery with test account and verified before/after state. The native classification-first planner and one-shot actuator boundary still require a reviewed source-bound classifier input, a real host snapshot implementation and individually reviewed native action bindings before any recovery can affect playback. Real TV ADB is not currently connected; don't loop reconnection attempts.
5. Full Night Audio routing and listener/physical acoustic acceptance and more codec cases. No processing state is advertised as hardware-accepted.
6. Exact-source supply chain, dependency security, licence, rollback and user-approved installation acceptance. The working official Stremio 1.11.2 and account stay untouched.

**Install gate:** never install or distribute this MVP as a final Stremio client. It is a development candidate. Its source, tests and expected APK identity are preserved for independent review and later approved device tests.
