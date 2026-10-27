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
MEDIA3_INTAKE=ROOT/'src/main/java/org/pss/stremio/tv/NativeMedia3FailureIntake.kt'
MEDIA3_INTAKE_TEST=ROOT/'src/test/java/org/pss/stremio/tv/NativeMedia3FailureIntakeTest.kt'
MEDIA3_RETRY_ACTUATOR=ROOT/'src/main/java/org/pss/stremio/tv/NativeMedia3RetryActuator.kt'
MEDIA3_RETRY_ACTUATOR_TEST=ROOT/'src/test/java/org/pss/stremio/tv/NativeMedia3RetryActuatorTest.kt'
ACTUATOR=ROOT/'src/main/java/org/pss/stremio/tv/NativePlaybackRecoveryActuator.kt'
ACTUATOR_TEST=ROOT/'src/test/java/org/pss/stremio/tv/NativePlaybackRecoveryActuatorTest.kt'
BRIDGE=ROOT/'src/main/java/org/pss/stremio/tv/NativeTvBridge.kt'
PORTS=ROOT/'src/main/java/org/pss/stremio/tv/NativeTvPorts.kt'
EPISODE=ROOT/'src/main/java/org/pss/stremio/tv/NativeEpisodeWatchedAction.kt'
TITLE_WATCHED_MENU=ROOT/'src/main/java/org/pss/stremio/tv/NativeTvTitleWatchedMenu.kt'
TITLE_WATCHED_MENU_TEST=ROOT/'src/test/java/org/pss/stremio/tv/NativeTvTitleWatchedMenuTest.kt'
MOBILE_TITLE_WATCHED_MENU=ROOT/'src/main/java/org/pss/stremio/tv/NativeMobileTitleWatchedMenu.kt'
MOBILE_TITLE_WATCHED_MENU_TEST=ROOT/'src/test/java/org/pss/stremio/tv/NativeMobileTitleWatchedMenuTest.kt'
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
media3_intake=MEDIA3_INTAKE.read_text() if MEDIA3_INTAKE.is_file() else ''
media3_intake_test=MEDIA3_INTAKE_TEST.read_text() if MEDIA3_INTAKE_TEST.is_file() else ''
media3_retry_actuator=MEDIA3_RETRY_ACTUATOR.read_text() if MEDIA3_RETRY_ACTUATOR.is_file() else ''
media3_retry_actuator_test=MEDIA3_RETRY_ACTUATOR_TEST.read_text() if MEDIA3_RETRY_ACTUATOR_TEST.is_file() else ''
actuator=ACTUATOR.read_text() if ACTUATOR.is_file() else ''
actuator_test=ACTUATOR_TEST.read_text() if ACTUATOR_TEST.is_file() else ''
ports=PORTS.read_text()
episode=EPISODE.read_text()
title_watched_menu=TITLE_WATCHED_MENU.read_text() if TITLE_WATCHED_MENU.is_file() else ''
title_watched_menu_test=TITLE_WATCHED_MENU_TEST.read_text() if TITLE_WATCHED_MENU_TEST.is_file() else ''
mobile_title_watched_menu=MOBILE_TITLE_WATCHED_MENU.read_text() if MOBILE_TITLE_WATCHED_MENU.is_file() else ''
mobile_title_watched_menu_test=MOBILE_TITLE_WATCHED_MENU_TEST.read_text() if MOBILE_TITLE_WATCHED_MENU_TEST.is_file() else ''
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
if ('class NativeTvTitleWatchedMenu' not in title_watched_menu or
    'class NativeTvTitleWatchedAction internal constructor' not in title_watched_menu):
    fail.append('native_tv_title_watched_menu_missing')
if ('val snapshot = snapshotOrNull() ?: return emptyList()' not in title_watched_menu or
    'coordinator.prepare(snapshot, scope, watched)' not in title_watched_menu):
    fail.append('title_watched_menu_not_built_from_one_fresh_core_snapshot')
if ('issued.remove(action.actionId)' not in title_watched_menu or
    'record.action !== action' not in title_watched_menu or
    'account.currentSnapshot()' not in title_watched_menu):
    fail.append('title_watched_menu_issuance_or_fresh_read_missing')
if ('focusedActionId != action.actionId' not in title_watched_menu or
    'remoteKey != "ENTER" && remoteKey != "DPAD_CENTER"' not in title_watched_menu or
    'unwatchAcknowledged = unwatchAcknowledged' not in title_watched_menu):
    fail.append('title_watched_remote_focus_or_unwatch_confirmation_missing')
if ('Thread.currentThread() === ownerThread' not in title_watched_menu or
    'private var closed = false' not in title_watched_menu or
    'const val MAX_PRESENTED_ACTIONS = 4' not in title_watched_menu):
    fail.append('title_watched_thread_lifecycle_or_bound_missing')
if ('NativeTvTitleWatchedMenu(' in java_text or 'NativeTvTitleWatchedAction(' in java_text):
    fail.append('title_watched_menu_wired_to_synthetic_activity')
if any(name not in title_watched_menu_test for name in [
    'readyCoreSnapshotPresentsFourTitleActionsWithoutWriting',
    'futureEpisodeSuppressesUnsafeSeasonActions',
    'focusAndRemoteConfirmationDispatchExactSeriesAction',
    'unwatchRequiresAcknowledgementAndPresentationIsOneShot',
    'accountDriftRefusesPresentedAction',
    'staleAndForgedActionsCannotExecute',
    'ownerThreadAndCloseFailClosed',
    'snapshotAndDispatchExceptionsFailClosed'
]):
    fail.append('native_tv_title_watched_menu_regressions_missing')
if ('class NativeMobileTitleWatchedMenu' not in mobile_title_watched_menu or
    'class NativeMobileTitleWatchedAction internal constructor' not in mobile_title_watched_menu):
    fail.append('native_mobile_title_watched_menu_missing')
if ('private val delegate = NativeTvTitleWatchedMenu(coordinator, account)' not in mobile_title_watched_menu or
    'val delegated = delegate.present(seasonNumber)' not in mobile_title_watched_menu):
    fail.append('mobile_title_watched_menu_bypasses_guarded_core_delegate')
if ('const val SEMANTIC_ACTIVATION = "ACTIVATE"' not in mobile_title_watched_menu or
    'semanticEvent != SEMANTIC_ACTIVATION' not in mobile_title_watched_menu or
    'selectedActionId != action.actionId' not in mobile_title_watched_menu):
    fail.append('mobile_semantic_accessibility_activation_gate_missing')
if ('issued.remove(action.actionId)' not in mobile_title_watched_menu or
    mobile_title_watched_menu.find('issued.remove(action.actionId)') > mobile_title_watched_menu.find('delegate.activate(')):
    fail.append('mobile_title_action_not_consumed_before_core_delegate')
if ('Thread.currentThread() === ownerThread' not in mobile_title_watched_menu or
    'private var closed = false' not in mobile_title_watched_menu or
    'return delegate.close()' not in mobile_title_watched_menu):
    fail.append('mobile_title_thread_or_lifecycle_guard_missing')
if ('NativeMobileTitleWatchedMenu(' in java_text or 'NativeMobileTitleWatchedAction(' in java_text):
    fail.append('mobile_title_watched_menu_wired_to_synthetic_activity')
if any(name not in mobile_title_watched_menu_test for name in [
    'mobilePresentationMirrorsFourGuardedTitleActionsWithoutWriting',
    'semanticAccessibilityActivationDispatchesExactSeriesAction',
    'rawPointerAndLongPressEventsNeverImplyConsent',
    'mobileUnwatchRequiresAcknowledgementAndConsumesIssuance',
    'futureEpisodeStillSuppressesUnsafeMobileSeasonActions',
    'staleSelectedAndForgedMobileActionsFailClosed',
    'accountDriftAndRejectedDispatchFailClosedOnMobile',
    'mobileOwnerThreadAndCloseRevokeOutstandingActions'
]):
    fail.append('native_mobile_title_watched_menu_regressions_missing')
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
if 'object NativeMedia3FailureIntake' not in media3_intake or 'errorCode: Int' not in media3_intake:
    fail.append('media3_numeric_failure_intake_missing')
if 'httpStatus = null' not in media3_intake or 'playbackHealthEligible = false' not in media3_intake:
    fail.append('media3_intake_invents_http_or_playback_recovery')
if 'error.message' in java_text+media3_intake or 'error.cause' in java_text+media3_intake or 'error: PlaybackException' in media3_intake:
    fail.append('media3_raw_exception_boundary_exposed')
if 'NativeMedia3FailureIntake.classify(error.errorCode)' not in java_text:
    fail.append('media3_player_error_not_bound_to_sanitised_intake')
if any(name not in media3_intake_test for name in ['networkSignalsMapToOneBoundedTransportRetry','genericHttpErrorNeverInventsResponseStatusOrCredentialFailure','parserSignalsStopWithoutInventingMatroskaEvidence','decoderAudioAndDrmSignalsRemainTerminal','unrequestedEpisodeAdvanceWinsBeforeRetryableTransport']):
    fail.append('media3_failure_intake_regressions_missing')
if 'class NativeMedia3RetryActuator' not in media3_retry_actuator or 'interface NativeMedia3RetryPort' not in media3_retry_actuator:
    fail.append('media3_retry_actuator_boundary_missing')
if ('NativeMedia3FailureIntake.sanitise(' not in media3_retry_actuator or
    'NativePlaybackRecoveryPolicy.plan(facts = facts, observation = null)' not in media3_retry_actuator):
    fail.append('media3_retry_not_composed_through_current_classifier_policy')
snapshot_class=media3_retry_actuator[
    media3_retry_actuator.find('data class NativeMedia3RetrySnapshot'):
    media3_retry_actuator.find('interface NativeMedia3RetryPort')
]
if 'sameEpisodeRetryCount' in snapshot_class:
    fail.append('media3_retry_snapshot_exposes_caller_controlled_attempt_count')
if ('class NativeMedia3RetryBudget private constructor' not in media3_retry_actuator or
    'private val budget: NativeMedia3RetryBudget' not in media3_retry_actuator):
    fail.append('media3_episode_scoped_retry_budget_missing')
if ('internal fun attemptCount(value: NativeMedia3RetrySnapshot): Int?' not in media3_retry_actuator or
    'val sameEpisodeRetryCount = budget.attemptCount(lease) ?: return null' not in media3_retry_actuator or
    'sameEpisodeRetryCount = sameEpisodeRetryCount' not in media3_retry_actuator):
    fail.append('media3_retry_attempt_count_not_owned_by_shared_budget')
if ('if (!budget.reserve(lease)) return null' not in media3_retry_actuator or
    '!budget.contains(prepared.lease)' not in media3_retry_actuator):
    fail.append('media3_retry_not_bound_to_shared_episode_budget')
if ('issuedEpisodeBudgets' in media3_retry_actuator or
    'MAX_EPISODE_BUDGETS' in media3_retry_actuator):
    fail.append('media3_retry_actuator_local_budget_reintroduced')
budget_class=media3_retry_actuator[
    media3_retry_actuator.find('class NativeMedia3RetryBudget'):
    media3_retry_actuator.find('/**\n * Binds the numeric-only Media3 intake')
]
if ('ownerThread = Thread.currentThread()' not in budget_class or
    'Thread.currentThread() === ownerThread' not in budget_class or
    'fun close(): Boolean' not in budget_class or
    'fun reset' in budget_class):
    fail.append('media3_retry_budget_thread_lifecycle_or_reset_guard_missing')
actuator_close=media3_retry_actuator[
    media3_retry_actuator.find('fun close(): Boolean', media3_retry_actuator.find('class NativeMedia3RetryActuator')):
    media3_retry_actuator.find('private fun trustedTransportRetryPlan')
]
if 'budget.close()' in actuator_close:
    fail.append('media3_actuator_close_resets_episode_budget')
if (media3_retry_actuator.count('catch (_: Exception)') < 2 or
    media3_retry_actuator.count('catch (_: LinkageError)') < 2):
    fail.append('media3_retry_port_exception_boundary_missing')
if any(name not in media3_retry_actuator_test for name in [
    'firstTransportFailureExecutesOnceAndReservesEpisodeBudget',
    'repeatedPreparationCannotMintAnotherAttemptBeforeOrAfterExecution',
    'rejectedPortExecutionStillConsumesSameEpisodeBudget',
    'finalSnapshotDriftRefusesAndBudgetStaysReserved',
    'episodeBudgetCannotResetThroughGenerationOrSourceChange',
    'terminalAndUnknownMedia3CodesNeverPrepare',
    'episodeAdvanceAndInvalidStateFailClosed',
    'preparedLeaseCannotCrossActuatorInstance',
    'ownerThreadAndCloseRevokeOutstandingLeaseAndFutureUse',
    'snapshotAndExecutionExceptionsFailClosedWithOneShotConsumption',
    'sharedBudgetSurvivesActuatorCloseAndReplacement',
    'retrySnapshotExposesNoCallerControlledAttemptCounter',
    'budgetIdentityThreadAndLifecycleFailClosed'
]):
    fail.append('media3_retry_actuator_regressions_missing')
if 'NativeMedia3RetryActuator(' in java_text:
    fail.append('media3_retry_actuator_wired_without_live_host_review')
if 'class ClassifiedStreamFailure internal constructor' not in recovery or 'private val provenanceSeal: Any' not in recovery or 'data class ClassifiedStreamFailure' in recovery:
    fail.append('classifier_result_not_opaque_or_copy_safe')
if 'NativeStreamFailureClassifier.isAuthentic(value)' not in recovery or 'value.wasIssuedBy(CLASSIFIER_SEAL)' not in classifier:
    fail.append('classifier_authenticity_not_enforced')
if 'facts: SanitisedStreamFailureFacts?' not in recovery or 'val classification = NativeStreamFailureClassifier.classify(facts)' not in recovery:
    fail.append('policy_does_not_reclassify_current_facts')
if 'facts: SanitisedStreamFailureFacts?' not in actuator or 'NativePlaybackRecoveryPolicy.plan(facts, observation)' not in actuator or 'classification: ClassifiedStreamFailure?' in actuator:
    fail.append('actuator_accepts_preclassified_decision')
if any(name not in classifier_test+recovery_test+actuator_test for name in ['classifierAuthenticityCannotBeRecreatedFromMatchingFields','policyExposesNoPreclassifiedDecisionParameter','actuatorExposesNoPreclassifiedDecisionParameter']):
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
if any(x in java_text+story+classifier+media3_intake+media3_retry_actuator+recovery+actuator+ports+episode+title_watched_menu+mobile_title_watched_menu for x in ['datastorePut','datastoreDelete','http://','https://','getAuthKey','System.getenv','Log.d(']):
    fail.append('account_network_or_credential_surface')
print(json.dumps({'ok':not fail,'checks':104,'issues':fail,'tested_bridge_sha256':CHECKSUM,
                  'production_package':'com.stremio.one','candidate_package':'org.thiajay.stremiotvcompanion'},indent=2))
sys.exit(1 if fail else 0)
