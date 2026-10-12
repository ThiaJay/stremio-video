# Android Night Audio PCM reference

Status: isolated development proof only, **not installed, not released and not part of Stremio Android TV**.

This is a standalone Media3 PCM16 `AudioProcessor` and separate Android TV listening-test app candidate for Foundation issue 65. Its permanent owner is a native Stremio Android/TV player adapter with the shared activation and profile contract in Core. It must **not** be represented as an installed Fire TV feature. The official Android TV client source and player wiring were not available when this proof was prepared. The independent APK is not a modified Stremio client, is not an official installer and is not approved for installation without a separate acceptance step.

## Safe scope

- The default request is Off. Valid 16-bit decoded PCM passes through unchanged when Off, and unsupported formats are not processed.
- When explicitly enabled, Gentle, Balanced and Strong reduce dynamic range with linked multichannel frame gain, attack and release envelopes, and a bounded output limiter. Preset parameters follow the existing Stremio Video Night Audio policy values.
- The algorithm preserves frame count, sample rate, channel count, channel positions, track selection, source identity, playback position and all account state. It cannot choose the active audio output, speaker configuration, subtitle track or codec.
- The processor makes no change to offload, bitstream passthrough, Dolby formats, Atmos, ARC/eARC or HDMI routing. Media3 `DefaultAudioSink` invokes audio processors only on eligible PCM, not passthrough or offload streams.
- `isPcmProcessingObserved()` means that this isolated processor received PCM frames for the current request. It is **not** proof of audibility or even that a client used it. `NightAudioPlaybackGate` additionally requires matching playback, confirmed output route and strength before a host could display Active.
- Separate dialogue enhancement and personalised acoustic testing are **not** implemented. This candidate is broadband DRC, not speech isolation or automatic room calibration.
- No private audio is recorded, uploaded or logged. Test content is generated synthetic PCM only.

## How to test

Run the JDK-only test without an Android SDK.

```sh
base=processor/src
mkdir -p .classes
javac -d .classes "$base/main/java/org/stremio/nightaudio/reference/PcmDynamicsProcessor.java" \
  "$base/main/java/org/stremio/nightaudio/reference/NightAudioPlaybackGate.java" \
  "$base/test/java/org/stremio/nightaudio/reference/PcmDynamicsProcessorCheck.java"
java -cp .classes org.stremio.nightaudio.reference.PcmDynamicsProcessorCheck
```

For Android API and Media3 compile and unit tests with a configured SDK, use Gradle 8.11.1 and Java 17.

```sh
gradle :processor:testDebugUnitTest :processor:assembleDebug :test-app:assembleDebug
```

GitHub CI compiles both the processor and a separate Android TV debug test APK without deploying, installing or publishing a release. The proof app has a separate package identity, generates local audio after an explicit Play action, starts at 20% app volume, does not use any Stremio account and has no network permission. It displays observed PCM processing without claiming verified speaker routing. `compileSdk 35` is an isolated reference choice rather than a claim that it matches the official Stremio Android TV application's current build.

## Integration still required

1. Obtain an authorised Android TV client source tree and determine its actual Media3 version, renderer factory, audio sink, offload, passthrough and routing controls.
2. Add this processor only to a host-owned `DefaultAudioSink` PCM processor chain, preserving existing processors and never forcing PCM for unsupported tracks. The host must produce real route and playback identifiers, not transient numeric Android device IDs or invented soundbar topology.
3. Supply Core-compatible Off/Always/Scheduled and Automatic/Custom policy, overnight and daylight saving cases, per-route storage, manual override expiry, output switch handling and accessible in-player controls. The proof does not invent these facilities in a client whose source it cannot change.
4. Confirm real codec transitions (stereo AAC, E-AC-3 5.1, passthrough DTS-HD/TrueHD), repeated seek/pause/resume, route changes, AV synchronisation, peak levels, CPU/battery cost and device-specific listening. If processing cannot be proven, report bypassed or unavailable.
5. Perform optional user-approved testing of the separate debug APK only after hash, package-identity, no-permission and on-device safety review. Never replace official Stremio or import user credentials.
6. Complete Foundation security, privacy, exact artefact, recovery, usability and publication gates before any optional side-by-side test package. Do not replace the installed client, touch account state or change audio preferences without a separate authorised test.

This reference is not release-ready and is not a substitute for official Android TV source availability or real Fire TV acceptance.
