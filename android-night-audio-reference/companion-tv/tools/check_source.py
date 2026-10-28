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
MOBILE_TITLE_HOST_BINDING=ROOT/'src/main/java/org/pss/stremio/tv/NativeMobileTitleWatchedHostBinding.kt'
MOBILE_TITLE_HOST_BINDING_TEST=ROOT/'src/test/java/org/pss/stremio/tv/NativeMobileTitleWatchedHostBindingTest.kt'
TV_TITLE_HOST_BINDING=ROOT/'src/main/java/org/pss/stremio/tv/NativeTvTitleWatchedHostBinding.kt'
TV_TITLE_HOST_BINDING_TEST=ROOT/'src/test/java/org/pss/stremio/tv/NativeTvTitleWatchedHostBindingTest.kt'
TV_TITLE_WIDGET_CONTROLLER=ROOT/'src/main/java/org/pss/stremio/tv/NativeTvTitleWatchedWidgetController.kt'
TV_TITLE_WIDGET_CONTROLLER_TEST=ROOT/'src/test/java/org/pss/stremio/tv/NativeTvTitleWatchedWidgetControllerTest.kt'
ANDROID_TV_TITLE_WIDGET_PORT=ROOT/'src/main/java/org/thiajay/stremiotvcompanion/AndroidTvTitleWatchedWidgetPort.kt'
MOBILE_TITLE_WIDGET_CONTROLLER=ROOT/'src/main/java/org/pss/stremio/tv/NativeMobileTitleWatchedWidgetController.kt'
MOBILE_TITLE_WIDGET_CONTROLLER_TEST=ROOT/'src/test/java/org/pss/stremio/tv/NativeMobileTitleWatchedWidgetControllerTest.kt'
ANDROID_MOBILE_TITLE_WIDGET_PORT=ROOT/'src/main/java/org/thiajay/stremiotvcompanion/AndroidMobileTitleWatchedWidgetPort.kt'
TITLE_WATCHED_VIEW_PORT_RELAY=ROOT/'src/main/java/org/pss/stremio/tv/NativeTitleWatchedViewPortRelay.kt'
TITLE_WATCHED_VIEW_PORT_RELAY_TEST=ROOT/'src/test/java/org/pss/stremio/tv/NativeTitleWatchedViewPortRelayTest.kt'
ANDROID_TITLE_WATCHED_SCREEN_OWNERS=ROOT/'src/main/java/org/thiajay/stremiotvcompanion/AndroidTitleWatchedScreenOwners.kt'
CANONICAL_WATCHED_ACCOUNT_ADAPTER=ROOT/'src/main/java/org/pss/stremio/tv/SessionBoundCanonicalWatchedAccountAdapter.kt'
CANONICAL_WATCHED_ACCOUNT_ADAPTER_TEST=ROOT/'src/test/java/org/pss/stremio/tv/SessionBoundCanonicalWatchedAccountAdapterTest.kt'
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
mobile_title_host_binding=MOBILE_TITLE_HOST_BINDING.read_text() if MOBILE_TITLE_HOST_BINDING.is_file() else ''
mobile_title_host_binding_test=MOBILE_TITLE_HOST_BINDING_TEST.read_text() if MOBILE_TITLE_HOST_BINDING_TEST.is_file() else ''
tv_title_host_binding=TV_TITLE_HOST_BINDING.read_text() if TV_TITLE_HOST_BINDING.is_file() else ''
tv_title_host_binding_test=TV_TITLE_HOST_BINDING_TEST.read_text() if TV_TITLE_HOST_BINDING_TEST.is_file() else ''
tv_title_widget_controller=TV_TITLE_WIDGET_CONTROLLER.read_text() if TV_TITLE_WIDGET_CONTROLLER.is_file() else ''
tv_title_widget_controller_test=TV_TITLE_WIDGET_CONTROLLER_TEST.read_text() if TV_TITLE_WIDGET_CONTROLLER_TEST.is_file() else ''
android_tv_title_widget_port=ANDROID_TV_TITLE_WIDGET_PORT.read_text() if ANDROID_TV_TITLE_WIDGET_PORT.is_file() else ''
mobile_title_widget_controller=MOBILE_TITLE_WIDGET_CONTROLLER.read_text() if MOBILE_TITLE_WIDGET_CONTROLLER.is_file() else ''
mobile_title_widget_controller_test=MOBILE_TITLE_WIDGET_CONTROLLER_TEST.read_text() if MOBILE_TITLE_WIDGET_CONTROLLER_TEST.is_file() else ''
android_mobile_title_widget_port=ANDROID_MOBILE_TITLE_WIDGET_PORT.read_text() if ANDROID_MOBILE_TITLE_WIDGET_PORT.is_file() else ''
title_watched_view_port_relay=TITLE_WATCHED_VIEW_PORT_RELAY.read_text() if TITLE_WATCHED_VIEW_PORT_RELAY.is_file() else ''
title_watched_view_port_relay_test=TITLE_WATCHED_VIEW_PORT_RELAY_TEST.read_text() if TITLE_WATCHED_VIEW_PORT_RELAY_TEST.is_file() else ''
android_title_watched_screen_owners=ANDROID_TITLE_WATCHED_SCREEN_OWNERS.read_text() if ANDROID_TITLE_WATCHED_SCREEN_OWNERS.is_file() else ''
canonical_watched_account_adapter=CANONICAL_WATCHED_ACCOUNT_ADAPTER.read_text() if CANONICAL_WATCHED_ACCOUNT_ADAPTER.is_file() else ''
canonical_watched_account_adapter_test=CANONICAL_WATCHED_ACCOUNT_ADAPTER_TEST.read_text() if CANONICAL_WATCHED_ACCOUNT_ADAPTER_TEST.is_file() else ''
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
if ('interface CanonicalWatchedStatePort : StremioAccountPort' not in title_watched_menu or
    'DISPATCHED_PENDING_READBACK' not in title_watched_menu or
    'private var pendingReadback: PendingReadback? = null' not in title_watched_menu or
    'if (!confirmPendingReadback(snapshot)) return emptyList()' not in title_watched_menu or
    'state.copy(watchedVideoIds = state.watchedVideoIds.toList())' not in title_watched_menu or
    'state.watchedVideoIds.size != state.watchedVideoIds.toSet().size' not in title_watched_menu):
    fail.append('title_watched_canonical_post_write_readback_gate_missing')
if ('private val account: CanonicalWatchedStatePort' not in title_watched_menu or
    'account: CanonicalWatchedStatePort' not in mobile_title_watched_menu or
    'account: CanonicalWatchedStatePort' not in mobile_title_host_binding or
    'account: CanonicalWatchedStatePort' not in tv_title_host_binding or
    any('account: StremioAccountPort' in source for source in [
        title_watched_menu,
        mobile_title_watched_menu,
        mobile_title_host_binding,
        tv_title_host_binding
    ]) or
    '(account as? CanonicalWatchedStatePort)' in title_watched_menu):
    fail.append('android_title_ui_canonical_readback_port_not_mandatory')
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
    'snapshotAndDispatchExceptionsFailClosed',
    'acceptedDispatchBlocksNewGenerationUntilCanonicalReadbackMatches',
    'mismatchedOrDuplicateCanonicalReadbackNeverReopensGeneration',
    'throwingCanonicalWatchedCollectionFailsClosedWithoutEscaping'
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
    'mobileOwnerThreadAndCloseRevokeOutstandingActions',
    'mobileAcceptedDispatchCannotReissueBeforeCanonicalReadback'
]):
    fail.append('native_mobile_title_watched_menu_regressions_missing')
if ('class NativeMobileTitleWatchedHostBinding' not in mobile_title_host_binding or
    'interface NativeMobileTitleWatchedViewPort' not in mobile_title_host_binding or
    'class NativeMobileTitleWatchedViewAction internal constructor' not in mobile_title_host_binding):
    fail.append('native_mobile_title_host_binding_missing')
if ('private val menu = NativeMobileTitleWatchedMenu(coordinator, account)' not in mobile_title_host_binding or
    'menu.activate(' not in mobile_title_host_binding):
    fail.append('mobile_host_binding_bypasses_canonical_core_menu')
if ('view.replaceTitleActions(viewActions.toList())' not in mobile_title_host_binding or
    'view.clearTitleActions()' not in mobile_title_host_binding):
    fail.append('mobile_host_render_or_clear_port_missing')
if ('record.viewAction === action' not in mobile_title_host_binding or
    'action.mobileFor(bindingSeal)' not in mobile_title_host_binding or
    'selectedActionId == action.actionId' not in mobile_title_host_binding):
    fail.append('mobile_host_exact_object_or_selection_gate_missing')
if ('issued.remove(action.actionId)' not in mobile_title_host_binding or
    mobile_title_host_binding.find('issued.remove(action.actionId)') > mobile_title_host_binding.find('menu.activate(')):
    fail.append('mobile_host_action_not_consumed_before_core_menu')
if ('revokeRenderedGeneration()' not in mobile_title_host_binding or
    'menu.present(lastSeasonNumber)' not in mobile_title_host_binding):
    fail.append('mobile_host_generation_revocation_missing')
if ('Thread.currentThread() === ownerThread' not in mobile_title_host_binding or
    'private var closed = false' not in mobile_title_host_binding or
    'val menuClosed = menu.close()' not in mobile_title_host_binding):
    fail.append('mobile_host_thread_or_lifecycle_guard_missing')
if ('NativeMobileTitleWatchedHostBinding(' in java_text or
    'NativeMobileTitleWatchedViewAction(' in java_text):
    fail.append('mobile_title_host_binding_wired_to_synthetic_activity')
if any(name not in mobile_title_host_binding_test for name in [
    'hostRenderPublishesFourDisplayModelsWithoutWriting',
    'exactSemanticViewActionDispatchesAndClearsWholeGeneration',
    'rawPointerAndLongPressCallbacksNeverImplyHostConsent',
    'staleOrForgedViewObjectsRevokeCurrentPresentation',
    'unwatchAcknowledgementPassesOnlyThroughExactIssuedViewAction',
    'accountDriftAndRejectedDispatchClearHostPresentation',
    'futureEpisodeFilteringSurvivesRealHostBoundary',
    'rejectedOrThrowingViewPortPermanentlyPoisonsBinding',
    'hostOwnerThreadAndCloseRevokeOutstandingCallbacks',
    'mobileHostCannotRenderAgainWhileAcceptedWriteLacksReadback'
]):
    fail.append('native_mobile_title_host_binding_regressions_missing')
if ('class NativeTvTitleWatchedHostBinding' not in tv_title_host_binding or
    'interface NativeTvTitleWatchedViewPort' not in tv_title_host_binding or
    'class NativeTvTitleWatchedViewAction internal constructor' not in tv_title_host_binding):
    fail.append('native_tv_title_host_binding_missing')
if ('private val menu = NativeTvTitleWatchedMenu(coordinator, account)' not in tv_title_host_binding or
    'menu.activate(' not in tv_title_host_binding):
    fail.append('tv_host_binding_bypasses_canonical_core_menu')
if ('view.replaceTitleActions(viewActions.toList())' not in tv_title_host_binding or
    'view.clearTitleActions()' not in tv_title_host_binding):
    fail.append('tv_host_render_or_clear_port_missing')
if ('record.viewAction === action' not in tv_title_host_binding or
    'action.menuFor(bindingSeal)' not in tv_title_host_binding or
    'focusedActionId == action.actionId' not in tv_title_host_binding):
    fail.append('tv_host_exact_object_or_focus_gate_missing')
if ('remoteKey = remoteKey' not in tv_title_host_binding or
    'focusedActionId = focusedActionId' not in tv_title_host_binding):
    fail.append('tv_host_remote_confirmation_not_delegated')
if ('issued.remove(action.actionId)' not in tv_title_host_binding or
    tv_title_host_binding.find('issued.remove(action.actionId)') > tv_title_host_binding.find('menu.activate(')):
    fail.append('tv_host_action_not_consumed_before_core_menu')
if ('revokeRenderedGeneration()' not in tv_title_host_binding or
    'menu.present(lastSeasonNumber)' not in tv_title_host_binding):
    fail.append('tv_host_generation_revocation_missing')
if ('Thread.currentThread() === ownerThread' not in tv_title_host_binding or
    'private var closed = false' not in tv_title_host_binding or
    'val menuClosed = menu.close()' not in tv_title_host_binding):
    fail.append('tv_host_thread_or_lifecycle_guard_missing')
if ('val focusable: Boolean = true' not in tv_title_host_binding or
    'val requiresAcknowledgement: Boolean = !watched' not in tv_title_host_binding):
    fail.append('tv_host_focus_or_destructive_metadata_missing')
if ('NativeTvTitleWatchedHostBinding(' in java_text or
    'NativeTvTitleWatchedViewAction(' in java_text):
    fail.append('tv_title_host_binding_wired_to_synthetic_activity')
if any(name not in tv_title_host_binding_test for name in [
    'tvHostRenderPublishesFourFocusableModelsWithoutWriting',
    'enterOnExactFocusedTvActionDispatchesAndClearsGeneration',
    'dpadCenterIsTheOnlyAlternateAcceptedTvConfirmation',
    'focusMismatchAndOtherRemoteKeysFailClosedAndRevoke',
    'staleOrForgedTvViewObjectsRevokeCurrentPresentation',
    'tvUnwatchRequiresAcknowledgementOnExactIssuedAction',
    'tvAccountDriftAndRejectedDispatchClearPresentation',
    'futureEpisodeFilteringSurvivesTvHostBoundary',
    'rejectedOrThrowingTvViewPortPermanentlyPoisonsBinding',
    'clearFailureAfterAppliedTvActionClosesWithoutReplay',
    'tvHostOwnerThreadAndCloseRevokeOutstandingCallbacks',
    'tvHostCannotRenderAgainWhileAcceptedWriteLacksReadback'
]):
    fail.append('native_tv_title_host_binding_regressions_missing')
if ('class NativeTvTitleWatchedWidgetController' not in tv_title_widget_controller or
    'class NativeTvTitleWatchedWidget internal constructor' not in tv_title_widget_controller or
    'data class NativeTvTitleWatchedWidgetIntent' not in tv_title_widget_controller):
    fail.append('native_tv_title_widget_controller_missing')
if ('actions.size > NativeTvTitleWatchedMenu.MAX_PRESENTED_ACTIONS' not in tv_title_widget_controller or
    'actions.map { it.actionId }.toSet().size != actions.size' not in tv_title_widget_controller or
    'it.confirmation.isBlank() || !it.focusable' not in tv_title_widget_controller):
    fail.append('tv_widget_generation_validation_missing')
if ('record.widget !== widget' not in tv_title_widget_controller or
    'widget.actionFor(controllerSeal)' not in tv_title_widget_controller or
    'focusedActionId != widget.actionId' not in tv_title_widget_controller):
    fail.append('tv_widget_exact_object_or_focus_gate_missing')
if ('if (keyAction != KEY_ACTION_UP) return null' not in tv_title_widget_controller or
    'remoteKey != KEY_ENTER && remoteKey != KEY_DPAD_CENTER' not in tv_title_widget_controller):
    fail.append('tv_widget_remote_keyup_gate_missing')
if ('val record = issued.remove(widget.actionId)' not in tv_title_widget_controller or
    tv_title_widget_controller.find('issued.clear()', tv_title_widget_controller.find('fun consume(')) >
        tv_title_widget_controller.find('widget.actionFor(controllerSeal)')):
    fail.append('tv_widget_generation_not_revoked_before_validation')
if ('Thread.currentThread() === ownerThread' not in tv_title_widget_controller or
    'fun clear(): Boolean' not in tv_title_widget_controller or
    'fun close(): Boolean' not in tv_title_widget_controller):
    fail.append('tv_widget_thread_or_lifecycle_guard_missing')
if ('class AndroidTvTitleWatchedWidgetPort' not in android_tv_title_widget_port or
    ': NativeTvTitleWatchedViewPort' not in android_tv_title_widget_port or
    'private val controller = NativeTvTitleWatchedWidgetController()' not in android_tv_title_widget_port):
    fail.append('actual_android_tv_title_widget_port_missing')
if ('Looper.myLooper() === Looper.getMainLooper()' not in android_tv_title_widget_port or
    '!view.hasFocus()' not in android_tv_title_widget_port or
    'KeyEvent.KEYCODE_ENTER' not in android_tv_title_widget_port or
    'KeyEvent.KEYCODE_DPAD_CENTER' not in android_tv_title_widget_port):
    fail.append('android_tv_widget_main_thread_focus_or_remote_gate_missing')
if ('.setPositiveButton("Confirm")' not in android_tv_title_widget_port or
    '.setNegativeButton("Cancel")' not in android_tv_title_widget_port or
    '.setOnCancelListener { resolve(false) }' not in android_tv_title_widget_port):
    fail.append('android_tv_unwatch_confirmation_or_cancel_gate_missing')
if ('onActivation(action, focusedActionId, remoteKey, acknowledged)' not in android_tv_title_widget_port or
    'setOnClickListener' in android_tv_title_widget_port):
    fail.append('android_tv_widget_exact_callback_or_remote_only_gate_missing')
if ('AndroidTvTitleWatchedWidgetPort(' in java_text or
    'NativeTvTitleWatchedWidgetController(' in java_text):
    fail.append('android_tv_title_widget_port_wired_to_synthetic_activity')
if any(name not in tv_title_widget_controller_test for name in [
    'widgetControllerBuildsFocusableModelsWithoutActivation',
    'actionUpEnterReturnsExactIntentAndRevokesWidgetGeneration',
    'actionUpDpadCenterReturnsExactIntent',
    'keyDownDoesNotConsumeWidgetGeneration',
    'focusMismatchAndOtherRemoteKeyRevokeWidgetGeneration',
    'staleAndForgedWidgetObjectsCannotActivate',
    'malformedOrNonFocusableWidgetGenerationFailsClosed',
    'widgetOwnerThreadClearAndCloseFailClosed'
]):
    fail.append('native_tv_title_widget_controller_regressions_missing')
if ('class NativeMobileTitleWatchedWidgetController' not in mobile_title_widget_controller or
    'class NativeMobileTitleWatchedWidget internal constructor' not in mobile_title_widget_controller or
    'data class NativeMobileTitleWatchedWidgetIntent' not in mobile_title_widget_controller):
    fail.append('native_mobile_title_widget_controller_missing')
if ('actions.size > NativeMobileTitleWatchedMenu.MAX_PRESENTED_ACTIONS' not in mobile_title_widget_controller or
    'actions.map { it.actionId }.toSet().size != actions.size' not in mobile_title_widget_controller or
    'it.confirmation.isBlank()' not in mobile_title_widget_controller):
    fail.append('mobile_widget_generation_validation_missing')
if ('record.widget !== widget' not in mobile_title_widget_controller or
    'widget.actionFor(controllerSeal)' not in mobile_title_widget_controller or
    'selectedActionId != widget.actionId' not in mobile_title_widget_controller):
    fail.append('mobile_widget_exact_object_or_selection_gate_missing')
if ('semanticEvent != NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION' not in mobile_title_widget_controller):
    fail.append('mobile_widget_semantic_activation_gate_missing')
if ('val record = issued.remove(widget.actionId)' not in mobile_title_widget_controller or
    mobile_title_widget_controller.find('issued.clear()', mobile_title_widget_controller.find('fun consume(')) >
        mobile_title_widget_controller.find('widget.actionFor(controllerSeal)')):
    fail.append('mobile_widget_generation_not_revoked_before_validation')
if ('Thread.currentThread() === ownerThread' not in mobile_title_widget_controller or
    'fun clear(): Boolean' not in mobile_title_widget_controller or
    'fun close(): Boolean' not in mobile_title_widget_controller):
    fail.append('mobile_widget_thread_or_lifecycle_guard_missing')
if ('class AndroidMobileTitleWatchedWidgetPort' not in android_mobile_title_widget_port or
    ': NativeMobileTitleWatchedViewPort' not in android_mobile_title_widget_port or
    'private val controller = NativeMobileTitleWatchedWidgetController()' not in android_mobile_title_widget_port):
    fail.append('actual_android_mobile_title_widget_port_missing')
if ('Looper.myLooper() === Looper.getMainLooper()' not in android_mobile_title_widget_port or
    'setOnClickListener' not in android_mobile_title_widget_port or
    'setOnLongClickListener { true }' not in android_mobile_title_widget_port):
    fail.append('android_mobile_widget_main_thread_or_semantic_click_gate_missing')
if ('.setPositiveButton("Confirm")' not in android_mobile_title_widget_port or
    '.setNegativeButton("Cancel")' not in android_mobile_title_widget_port or
    '.setOnCancelListener { resolve(false) }' not in android_mobile_title_widget_port):
    fail.append('android_mobile_unwatch_confirmation_or_cancel_gate_missing')
if ('onActivation(action, selectedActionId, semanticEvent, acknowledged)' not in android_mobile_title_widget_port or
    'setOnTouchListener' in android_mobile_title_widget_port):
    fail.append('android_mobile_widget_exact_callback_or_raw_touch_gate_missing')
if ('AndroidMobileTitleWatchedWidgetPort(' in java_text or
    'NativeMobileTitleWatchedWidgetController(' in java_text):
    fail.append('android_mobile_title_widget_port_wired_to_synthetic_activity')
if any(name not in mobile_title_widget_controller_test for name in [
    'mobileWidgetControllerBuildsDisplayModelsWithoutActivation',
    'semanticMobileClickReturnsExactIntentAndRevokesGeneration',
    'nonSemanticMobileEventNeverActivatesAndRevokesGeneration',
    'mobileSelectionMismatchRevokesWidgetGeneration',
    'staleAndForgedMobileWidgetObjectsCannotActivate',
    'malformedMobileWidgetGenerationFailsClosed',
    'mobileWidgetPreservesDestructiveAcknowledgementMetadata',
    'mobileWidgetOwnerThreadClearAndCloseFailClosed'
]):
    fail.append('native_mobile_title_widget_controller_regressions_missing')
if ('class NativeMobileTitleWatchedViewPortRelay : NativeMobileTitleWatchedViewPort' not in title_watched_view_port_relay or
    'class NativeTvTitleWatchedViewPortRelay : NativeTvTitleWatchedViewPort' not in title_watched_view_port_relay):
    fail.append('mobile_or_tv_title_view_port_relay_missing')
if (title_watched_view_port_relay.count('if (delegate != null || view === this) return poison()') != 2 or
    title_watched_view_port_relay.count('delegate = view') != 2):
    fail.append('title_view_port_relay_one_time_bind_missing')
if (title_watched_view_port_relay.count('Thread.currentThread() === ownerThread') != 2 or
    title_watched_view_port_relay.count('fun close(): Boolean') != 2):
    fail.append('title_view_port_relay_thread_or_close_guard_missing')
if (title_watched_view_port_relay.count('if (!accepted) return poison()') != 4 or
    title_watched_view_port_relay.count('catch (_: Exception)') != 2 or
    title_watched_view_port_relay.count('catch (_: LinkageError)') != 2):
    fail.append('title_view_port_relay_rejection_or_exception_poison_missing')
if ('class AndroidMobileTitleWatchedScreenOwner private constructor' not in android_title_watched_screen_owners or
    'class AndroidTvTitleWatchedScreenOwner private constructor' not in android_title_watched_screen_owners):
    fail.append('actual_android_title_screen_composition_owners_missing')
if (android_title_watched_screen_owners.count('Looper.myLooper() !== Looper.getMainLooper()') != 4 or
    android_title_watched_screen_owners.count('Thread.currentThread() === ownerThread') != 2):
    fail.append('android_title_screen_main_owner_thread_gate_missing')
if ('NativeMobileTitleWatchedHostBinding(coordinator, account, relay)' not in android_title_watched_screen_owners or
    'NativeTvTitleWatchedHostBinding(coordinator, account, relay)' not in android_title_watched_screen_owners or
    'private var ready = relay.bind(port)' not in android_title_watched_screen_owners):
    fail.append('android_title_screen_host_widget_composition_missing')
if ('import org.pss.stremio.tv.CanonicalWatchedStatePort' not in android_title_watched_screen_owners or
    android_title_watched_screen_owners.count('account: CanonicalWatchedStatePort') != 4 or
    'account: StremioAccountPort' in android_title_watched_screen_owners):
    fail.append('android_title_screen_canonical_readback_port_not_mandatory')
if ('class SessionBoundCanonicalWatchedAccountAdapter' not in canonical_watched_account_adapter or
    ': CanonicalWatchedStatePort' not in canonical_watched_account_adapter or
    'interface CanonicalWatchedAccountHostPort' not in canonical_watched_account_adapter):
    fail.append('session_bound_canonical_watched_account_adapter_missing')
if ('val before = host.sessionEpoch()' not in canonical_watched_account_adapter or
    'raw.sessionEpoch != before' not in canonical_watched_account_adapter or
    'val after = host.sessionEpoch()' not in canonical_watched_account_adapter or
    'after != before' not in canonical_watched_account_adapter):
    fail.append('canonical_account_epoch_sandwich_missing')
if ('raw.videos.map' not in canonical_watched_account_adapter or
    'raw.storyOrder?.toList()' not in canonical_watched_account_adapter or
    'raw.watchedVideoIds.toList()' not in canonical_watched_account_adapter):
    fail.append('canonical_account_host_collections_not_detached')
if ('message.keys != setOf("action", "args")' not in canonical_watched_account_adapter or
    '"MarkAsWatched"' not in canonical_watched_account_adapter or
    '"MarkSeasonAsWatched"' not in canonical_watched_account_adapter or
    'else -> return null' not in canonical_watched_account_adapter):
    fail.append('canonical_account_dispatch_not_narrowly_allowlisted')
adapter_dispatch = canonical_watched_account_adapter[
    canonical_watched_account_adapter.find('override fun dispatch('):
    canonical_watched_account_adapter.find('fun close(): Boolean')
]
if ('latestIdentity = null' not in adapter_dispatch or
    adapter_dispatch.find('latestIdentity = null') >
        adapter_dispatch.find('host.dispatchTitleWatched(expected.sessionEpoch, command)')):
    fail.append('canonical_account_lease_not_consumed_before_dispatch')
if ('expectedSessionEpoch: Long' not in canonical_watched_account_adapter or
    'host.dispatchTitleWatched(expected.sessionEpoch, command)' not in canonical_watched_account_adapter):
    fail.append('canonical_account_atomic_session_epoch_dispatch_missing')
if ('Thread.currentThread() === ownerThread' not in canonical_watched_account_adapter or
    'fun close(): Boolean' not in canonical_watched_account_adapter or
    'private var closed = false' not in canonical_watched_account_adapter):
    fail.append('canonical_account_owner_thread_or_close_missing')
if ('SessionBoundCanonicalWatchedAccountAdapter(' in java_text or
    'CanonicalWatchedAccountHostPort' in java_text):
    fail.append('canonical_account_adapter_wired_to_synthetic_activity')
if any(name not in canonical_watched_account_adapter_test for name in [
    'detachesSnapshotAndWatchedIdsFromHostCollections',
    'watchedReadbackRequiresMatchingObservedSnapshot',
    'dispatchAcceptsOnlyExactTitleWatchedCommands',
    'dispatchConsumesSnapshotLeaseBeforeRejectedHostCall',
    'dispatchBindsHostCallToObservedSessionEpoch',
    'sessionEpochDriftFailsClosed',
    'closedAdapterNeverTouchesHostCallbacks',
    'ownedScreenConstructionExceptionRevokesAdapterWithoutHostAccess',
    'ownedScreenNullRevokesButSuccessfulConstructionTransfersLifecycle',
    'externalScreenConstructionFailuresAreContainedWithoutOwnership',
    'foreignThreadAndCloseRejectAllOperations',
    'throwingHostCollectionsAndCallbacksFailClosed'
]):
    fail.append('canonical_account_adapter_regressions_missing')
if (android_title_watched_screen_owners.count('pendingOutcome != null) return false') != 2 or
    android_title_watched_screen_owners.count('pendingOutcome = host.activate(') != 2):
    fail.append('android_title_screen_one_outcome_mailbox_missing')
if (android_title_watched_screen_owners.count('fun consumeOutcome()') != 2 or
    android_title_watched_screen_owners.count('pendingOutcome = null') < 6):
    fail.append('android_title_screen_outcome_consumption_missing')
if (android_title_watched_screen_owners.count('val hostClosed = host.close()') != 2 or
    android_title_watched_screen_owners.count('val relayClosed = relay.close()') != 2):
    fail.append('android_title_screen_lifecycle_close_missing')
if (android_title_watched_screen_owners.count('accountHost: CanonicalWatchedAccountHostPort') != 2 or
    android_title_watched_screen_owners.count(
        'CanonicalWatchedScreenConstructionBoundary.owned(accountHost)'
    ) != 2 or
    android_title_watched_screen_owners.count(
        'CanonicalWatchedScreenConstructionBoundary.external'
    ) != 2):
    fail.append('android_title_screen_owned_account_host_overloads_missing')
if ('internal object CanonicalWatchedScreenConstructionBoundary' not in
        canonical_watched_account_adapter or
    'if (owner == null) account.close()' not in canonical_watched_account_adapter or
    'catch (_: Exception)' not in canonical_watched_account_adapter or
    'catch (_: LinkageError)' not in canonical_watched_account_adapter):
    fail.append('android_title_screen_construction_failure_boundary_missing')
if android_title_watched_screen_owners.count(
        'private val ownedAccount: SessionBoundCanonicalWatchedAccountAdapter?'
    ) != 2:
    fail.append('android_title_screen_owned_adapter_not_retained')
if (android_title_watched_screen_owners.count('if (!ready) {') != 2 or
    android_title_watched_screen_owners.count('ownedAccount?.close()') != 6):
    fail.append('android_title_screen_owned_adapter_failure_or_close_missing')
if (android_title_watched_screen_owners.count(
        'val accountClosed = ownedAccount?.close() ?: true'
    ) != 2 or
    android_title_watched_screen_owners.count(
        'return hostClosed && relayClosed && accountClosed'
    ) != 2):
    fail.append('android_title_screen_close_does_not_revoke_owned_adapter')
if (any(name in java_text for name in [
        'AndroidMobileTitleWatchedScreenOwner',
        'AndroidTvTitleWatchedScreenOwner',
        'NativeMobileTitleWatchedViewPortRelay',
        'NativeTvTitleWatchedViewPortRelay'
    ])):
    fail.append('android_title_screen_owners_wired_to_synthetic_activity')
if any(name not in title_watched_view_port_relay_test for name in [
    'mobileRelayRefusesBeforeBindingAndDelegatesAfterBinding',
    'mobileRelayCloseClearsAndPermanentlyRevokes',
    'mobileRelayRejectingPortPermanentlyPoisons',
    'mobileRelayThrowingPortPermanentlyPoisons',
    'tvRelayRefusesBeforeBindingAndDelegatesAfterBinding',
    'tvRelayCloseClearsAndPermanentlyRevokes',
    'tvRelayRejectingPortPermanentlyPoisons',
    'tvRelayThrowingPortPermanentlyPoisons',
    'foreignThreadCannotTakeOverOrCloseEitherRelay'
]):
    fail.append('native_title_view_port_relay_regressions_missing')
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
if any(x in java_text+story+classifier+media3_intake+media3_retry_actuator+recovery+actuator+ports+episode+title_watched_menu+mobile_title_watched_menu+mobile_title_host_binding+tv_title_host_binding+tv_title_widget_controller+android_tv_title_widget_port+mobile_title_widget_controller+android_mobile_title_widget_port+title_watched_view_port_relay+android_title_watched_screen_owners+canonical_watched_account_adapter for x in ['datastorePut','datastoreDelete','http://','https://','getAuthKey','System.getenv','Log.d(']):
    fail.append('account_network_or_credential_surface')
print(json.dumps({'ok':not fail,'checks':189,'issues':fail,'tested_bridge_sha256':CHECKSUM,
                  'production_package':'com.stremio.one','candidate_package':'org.thiajay.stremiotvcompanion'},indent=2))
sys.exit(1 if fail else 0)

