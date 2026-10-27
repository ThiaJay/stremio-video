#!/usr/bin/env python3
"""Fail-closed, source-only safeguards for the isolated Android TV companion candidate."""
from __future__ import annotations
import hashlib
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT=pathlib.Path(__file__).resolve().parents[1]
MANIFEST=ROOT/'src/main/AndroidManifest.xml'
ANDROID='{http://schemas.android.com/apk/res/android}'
SOURCE=ROOT/'src/main/java/org/thiajay/stremiotvcompanion/CompanionTvActivity.kt'
STORY=ROOT/'src/main/java/org/thiajay/stremiotvcompanion/StoryOrderContract.kt'
STORY_TEST=ROOT/'src/test/java/org/thiajay/stremiotvcompanion/StoryOrderContractTest.kt'
RECOVERY=ROOT/'src/main/java/org/pss/stremio/tv/NativePlaybackRecoveryPolicy.kt'
RECOVERY_TEST=ROOT/'src/test/java/org/pss/stremio/tv/NativePlaybackRecoveryPolicyTest.kt'
CLASSIFIER=ROOT/'src/main/java/org/pss/stremio/tv/NativeStreamFailureClassifier.kt'
CLASSIFIER_TEST=ROOT/'src/test/java/org/pss/stremio/tv/NativeStreamFailureClassifierTest.kt'
ACTUATOR=ROOT/'src/main/java/org/pss/stremio/tv/NativePlaybackRecoveryActuator.kt'
ACTUATOR_TEST=ROOT/'src/test/java/org/pss/stremio/tv/NativePlaybackRecoveryActuatorTest.kt'
BRIDGE=ROOT/'src/main/java/org/pss/stremio/tv/NativeTvBridge.kt'
PORTS=ROOT/'src/main/java/org/pss/stremio/tv/NativeTvPorts.kt'
EPISODE=ROOT/'src/main/java/org/pss/stremio/tv/NativeEpisodeWatchedAction.kt'
BUILD=ROOT/'build.gradle'
CHECKSUM='0e067446053abc456196d060bc96dff72d03902542fb9ae97cefd47fa143f23b'

manifest=ET.parse(MANIFEST).getroot()
fail=[]
permission_names=[p.get(ANDROID+'name') for p in manifest.findall('uses-permission') if p.get('{http://schemas.android.com/tools}node')!='remove']
if permission_names:fail.append('permissions_are_not_allowed:'+repr(permission_names))
app=manifest.find('application')
if app is None:fail.append('missing_application')
else:
    if app.get(ANDROID+'banner') != '@drawable/companion_tv_banner':fail.append('leanback_banner_missing')
    if app.get(ANDROID+'allowBackup') != 'false':fail.append('backup_not_disabled')
    if app.get(ANDROID+'usesCleartextTraffic') != 'false':fail.append('cleartext_not_disabled')
    activities=app.findall('activity')
    if len(activities)!=1:fail.append('unexpected_activity_count')
    for activity in activities:
        names=[a.get(ANDROID+'name') for a in activity.findall('.//action')]
        if 'android.intent.action.VIEW' in names:fail.append('external_media_view_exposed')
        if any('android.intent.action.BOOT_COMPLETED' in (name or '') for name in names):fail.append('boot_receiver')
    if app.findall('service') or app.findall('receiver') or app.findall('provider'):
        fail.append('unexpected_background_component')
BANNER=ROOT/'src/main/res/drawable/companion_tv_banner.xml'
if not BANNER.is_file():
    fail.append('native_tv_banner_resource_missing')
else:
    try:
        b=ET.parse(BANNER).getroot()
        if b.tag!='vector' and not b.tag.endswith('}vector'):
            fail.append('native_tv_banner_not_vector')
        if b.get(ANDROID+'width')!='320dp' or b.get(ANDROID+'height')!='180dp':
            fail.append('native_tv_banner_wrong_dimensions')
    except (ET.ParseError,OSError):
        fail.append('native_tv_banner_invalid_xml')
if hashlib.sha256(BRIDGE.read_bytes()).hexdigest()!=CHECKSUM:
    fail.append('tested_bridge_modified')
java_text=SOURCE.read_text()
story=STORY.read_text() if STORY.is_file() else ''
story_test=STORY_TEST.read_text() if STORY_TEST.is_file() else ''
recovery=RECOVERY.read_text() if RECOVERY.is_file() else ''
recovery_test=RECOVERY_TEST.read_text() if RECOVERY_TEST.is_file() else ''
classifier=CLASSIFIER.read_text() if CLASSIFIER.is_file() else ''
classifier_test=CLASSIFIER_TEST.read_text() if CLASSIFIER_TEST.is_file() else ''
actuator=ACTUATOR.read_text() if ACTUATOR.is_file() else ''
actuator_test=ACTUATOR_TEST.read_text() if ACTUATOR_TEST.is_file() else ''
ports=PORTS.read_text()
episode=EPISODE.read_text()
gradle=BUILD.read_text()
if "applicationId 'org.thiajay.stremiotvcompanion'" not in gradle:fail.append('app_package_changed')
if not re.search(r"EXTERNAL_SOURCE_ENABLED'\s*,\s*'false'",gradle):fail.append('external_source_gate_missing')
if not re.search(r"STREMIO_ACCOUNT_WRITES_ENABLED'\s*,\s*'false'",gradle):fail.append('account_gate_missing')
if 'TvSkipCoordinator' not in java_text or 'NightAudioMedia3Processor' not in java_text or 'ExoPlayer.Builder' not in java_text:
    fail.append('real_media3_bridge_or_pcm_missing')
if 'player.seekTo(ms)' not in java_text or 'fun seekWithinSelectedVideo' not in ports:
    fail.append('same_media_seek_binding_missing')
if 'MarkVideoAsWatched' not in episode or 'canonicalVideoFromReadyMetaDetails' not in episode:
    fail.append('individual_episode_core_action_not_present')
if 'issued.add(plan.fingerprint)' not in episode or 'port.currentSnapshot()' not in episode:
    fail.append('episode_account_refresh_or_one_shot_not_enforced')
if 'videoFingerprint' not in episode or 'detached(secondSource)' not in episode:
    fail.append('core_video_double_read_or_detached_payload_missing')
if 'SnapshotBoundNativeSeekPort' not in java_text or 'SnapshotBoundNativeSeekPort' not in ports:
    fail.append('atomic_enough_main_looper_seek_guard_missing')
if 'watchedButtons.forEach { it.isEnabled = false }' not in java_text:
    fail.append('watched_controls_not_fail_closed')
if 'StoryOrderContract.presentation' not in java_text or 'StoryOrderContract.next' not in java_text:
    fail.append('story_order_not_bound_to_focusable_local_ui')
if 'storyOrderToggle = button(' not in java_text or 'isFocusable = true' not in java_text:
    fail.append('story_order_tv_focus_path_missing')
if 'canonical.any { it.season > 0 && it.id !in seen }' not in story:
    fail.append('story_order_regular_episode_completeness_guard_missing')
if 'NextStoryDecision.UseExistingContinuation' not in story or 'byId[id]' not in story:
    fail.append('story_order_stable_identity_or_fail_closed_continuation_missing')
if 'assertSame(narrativeSpecial' not in story_test or 'missingRegularEpisodeFailsClosed' not in story_test:
    fail.append('story_order_identity_regression_missing')
if 'private val TERMINAL' not in recovery or 'private val RETRYABLE' not in recovery:
    fail.append('classification_first_authority_sets_missing')
if 'playback_health:delegate_to_bounded_playback_recovery' not in recovery:
    fail.append('exact_playback_delegation_missing')
if 'observation?.attemptedRecoveries == null' not in recovery or 'recoveryContextId != value.currentContextId' not in recovery:
    fail.append('bounded_context_ledger_gate_missing')
if 'terminalClassificationsCannotBeBypassedByPlayerCapabilities' not in recovery_test:
    fail.append('terminal_composition_regression_missing')
if 'dolbyVisionProfileSevenRecoveryIsOrderedAndNeverLoops' not in recovery_test:
    fail.append('dolby_vision_recovery_order_regression_missing')
if 'data class SanitisedStreamFailureFacts' not in classifier or 'object NativeStreamFailureClassifier' not in classifier:
    fail.append('native_sanitised_stream_classifier_missing')
if 'contractVersion = 1' not in classifier or 'rawLogExportAllowed = false' not in classifier:
    fail.append('native_classifier_preservation_envelope_missing')
if classifier.find('episodeAdvanced && !value.userRequestedNextEpisode') > classifier.find('if (value.playbackHealthEligible)'):
    fail.append('native_classifier_terminal_order_weakened')
if 'delegate_to_bounded_playback_recovery' not in classifier or 'sameEpisodeRetryCount' not in classifier:
    fail.append('native_classifier_delegation_or_retry_budget_missing')
if any(name not in classifier_test for name in ['invalidSanitisedFactsFailClosed','terminalEpisodeResourceContainerAndDecoderStopsWinBeforeDelegation','providerSourceAndTransportOwnExactlyOneSameEpisodeRetry','exactPlaybackDelegationOccursOnlyAfterClassificationClears']):
    fail.append('native_classifier_contract_regressions_missing')
if 'classifierOutputComposesWithNativeBudgetWithoutParallelRetry' not in classifier_test or 'NativePlaybackRecoveryPolicy.plan' not in classifier_test:
    fail.append('native_classifier_policy_composition_regression_missing')
if 'class ClassifiedStreamFailure internal constructor' not in recovery or 'private val provenanceSeal: Any' not in recovery or 'data class ClassifiedStreamFailure' in recovery:
    fail.append('classifier_result_not_opaque_or_copy_safe')
if 'NativeStreamFailureClassifier.isAuthentic(value)' not in recovery or 'value.wasIssuedBy(CLASSIFIER_SEAL)' not in classifier:
    fail.append('classifier_authenticity_not_enforced')
if any(name not in classifier_test+recovery_test+actuator_test for name in ['classifierAuthenticityCannotBeRecreatedFromMatchingFields','callerConstructedClassificationCannotBypassClassifier','forgedPlaybackDelegationCannotPrepare']):
    fail.append('classifier_bypass_regressions_missing')
if 'class NativePlaybackRecoveryActuator' not in actuator or 'interface NativePlaybackRecoveryPort' not in actuator:
    fail.append('native_recovery_actuator_boundary_missing')
if 'val current = snapshotOrNull() ?: return NativeRecoveryExecutionOutcome.REFUSED' not in actuator or 'current != prepared.lease' not in actuator:
    fail.append('final_native_recovery_state_lease_missing')
if actuator.find('consumed.add(prepared.token)') > actuator.find('executeOrFalse(prepared.plan.action)'):
    fail.append('native_recovery_lease_not_consumed_before_port_call')
if 'watchedStateFingerprint' not in actuator or 'resumeStateFingerprint' not in actuator:
    fail.append('account_state_fingerprint_lease_missing')
if 'issued.add(token)' not in actuator or 'prepared.token !in issued' not in actuator:
    fail.append('actuator_instance_bound_issuance_missing')
if 'preparedLeaseCannotCrossActuatorInstanceOrBeReissued' not in actuator_test:
    fail.append('actuator_cross_instance_replay_regression_missing')
if ('ownerThread = Thread.currentThread()' not in actuator or
    'Thread.currentThread() === ownerThread' not in actuator or
    'Thread.currentThread().id' in actuator):
    fail.append('actuator_player_thread_ownership_missing')
if 'preparationAndExecutionStayOnConstructionThread' not in actuator_test:
    fail.append('actuator_cross_thread_regression_missing')
if 'supportedRecoveryActions' not in actuator or 'plan.action !in lease.supportedRecoveryActions' not in actuator:
    fail.append('actuator_native_capability_binding_missing')
if 'supportedRecoveryActions.sorted().joinToString(",")' not in actuator:
    fail.append('actuator_capability_set_not_bound_to_lease_token')
if any(name not in actuator_test for name in ['unadvertisedNativeCapabilityCannotPrepare','capabilityChangeBeforeExecutionRefuses','malformedCapabilitySetFailsClosed']):
    fail.append('actuator_capability_regressions_missing')
if 'private var closed = false' not in actuator or 'fun close(): Boolean' not in actuator or 'issued.clear()' not in actuator or 'consumed.clear()' not in actuator:
    fail.append('actuator_lifecycle_revocation_missing')
if any(name not in actuator_test for name in ['ownerThreadCloseRevokesOutstandingLeaseAndFutureUse','foreignThreadCannotCloseOrRevokeOwnerLease']):
    fail.append('actuator_lifecycle_regressions_missing')
if 'const val MAX_ISSUED_LEASES = 256' not in actuator or 'issued.size >= MAX_ISSUED_LEASES' not in actuator:
    fail.append('actuator_issued_lease_resource_cap_missing')
if 'issuedLeaseSetIsBoundedWithoutReplayEviction' not in actuator_test:
    fail.append('actuator_issued_lease_resource_cap_regression_missing')
if ('private fun snapshotOrNull()' not in actuator or 'private fun executeOrFalse(action: String)' not in actuator or
        actuator.count('catch (_: Exception)') < 2 or actuator.count('catch (_: LinkageError)') < 2):
    fail.append('actuator_port_exception_boundary_missing')
if actuator.find('consumed.add(prepared.token)') > actuator.find('executeOrFalse(prepared.plan.action)'):
    fail.append('actuator_exception_path_not_consumed_before_call')
if any(name not in actuator_test for name in ['throwingSnapshotDuringPreparationFailsClosed','throwingFinalSnapshotRefusesWithoutExecution','throwingPortExecutionConsumesLeaseAndFailsClosed']):
    fail.append('actuator_port_exception_regressions_missing')
if 'exactPlaybackPlanExecutesOnlyOnce' not in actuator_test or 'rejectedPortExecutionStillConsumesLease' not in actuator_test:
    fail.append('native_recovery_one_shot_regressions_missing')
if 'finalLeaseRejectsVideoSourceOrGenerationDrift' not in actuator_test or 'finalLeaseRejectsWatchedOrResumeFingerprintDrift' not in actuator_test:
    fail.append('native_recovery_final_state_drift_regressions_missing')
if 'terminalAndClassifierPlansNeverPrepare' not in actuator_test:
    fail.append('classifier_exclusive_actuator_regression_missing')
if any(x in java_text+story+classifier+recovery+actuator+ports+episode for x in ['datastorePut','datastoreDelete','http://','https://','getAuthKey','System.getenv','Log.d(']):
    fail.append('account_network_or_credential_surface')
print(json.dumps({'ok':not fail,'checks':64,'issues':fail,'tested_bridge_sha256':CHECKSUM,
                  'production_package':'com.stremio.one','candidate_package':'org.thiajay.stremiotvcompanion'},indent=2))
sys.exit(1 if fail else 0)
