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
NATIVE_SUBTITLE_PROVENANCE=ROOT/'src/main/java/org/thiajay/stremiotvcompanion/NativeSubtitlePreferenceProvenance.kt'
NATIVE_SUBTITLE_PROVENANCE_TEST=ROOT/'src/test/java/org/thiajay/stremiotvcompanion/NativeSubtitlePreferenceProvenanceTest.kt'
STORY=ROOT/'src/main/java/org/thiajay/stremiotvcompanion/StoryOrderContract.kt'
STORY_TEST=ROOT/'src/test/java/org/thiajay/stremiotvcompanion/StoryOrderContractTest.kt'
MOBILE_STORY=ROOT/'src/main/java/org/thiajay/stremiotvcompanion/MobileStoryOrderController.kt'
MOBILE_STORY_TEST=ROOT/'src/test/java/org/thiajay/stremiotvcompanion/MobileStoryOrderControllerTest.kt'
MOBILE_STORY_HOST=ROOT/'src/main/java/org/thiajay/stremiotvcompanion/MobileStoryOrderViewHost.kt'
MOBILE_STORY_HOST_TEST=ROOT/'src/test/java/org/thiajay/stremiotvcompanion/MobileStoryOrderViewHostTest.kt'
ANDROID_MOBILE_STORY_WIDGET_PORT=ROOT/'src/main/java/org/thiajay/stremiotvcompanion/AndroidMobileStoryOrderWidgetPort.kt'
ANDROID_MOBILE_STORY_SCREEN_OWNER=ROOT/'src/main/java/org/thiajay/stremiotvcompanion/AndroidMobileStoryOrderScreenOwner.kt'
MOBILE_STORY_ACTIVITY=ROOT/'src/main/java/org/thiajay/stremiotvcompanion/MobileStoryOrderLabActivity.kt'
SYNTHETIC_WATCHED_HOST=ROOT/'src/main/java/org/thiajay/stremiotvcompanion/SyntheticTitleWatchedAccountHost.kt'
SYNTHETIC_WATCHED_HOST_TEST=ROOT/'src/test/java/org/thiajay/stremiotvcompanion/SyntheticTitleWatchedAccountHostTest.kt'
MOBILE_ICON=ROOT/'src/main/res/drawable/companion_mobile_icon.xml'
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
CHECKSUM='789e29b997b503ee6cac03d638a7da023ecef551c866fbafe36287ce36862715'

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
    if app.get(ANDROID+'icon') != '@drawable/companion_mobile_icon':
        fail.append('mobile_launcher_icon_missing')
    features={
        feature.get(ANDROID+'name'): feature.get(ANDROID+'required')
        for feature in manifest.findall('uses-feature')
    }
    if features.get('android.software.leanback') != 'false':
        fail.append('leanback_still_required_for_mobile_lab')
    activities=app.findall('activity')
    by_name={activity.get(ANDROID+'name'): activity for activity in activities}
    expected={'.CompanionTvActivity', '.MobileStoryOrderLabActivity'}
    if set(by_name) != expected:fail.append('unexpected_activity_set:'+repr(set(by_name)))
    for activity in activities:
        names=[a.get(ANDROID+'name') for a in activity.findall('.//action')]
        if activity.get(ANDROID+'exported') != 'true':
            fail.append('launcher_activity_not_explicitly_exported')
        if 'android.intent.action.VIEW' in names:fail.append('external_media_view_exposed')
        if any('android.intent.action.BOOT_COMPLETED' in (name or '') for name in names):fail.append('boot_receiver')
    tv=by_name.get('.CompanionTvActivity')
    mobile=by_name.get('.MobileStoryOrderLabActivity')
    tv_categories=[] if tv is None else [c.get(ANDROID+'name') for c in tv.findall('.//category')]
    mobile_categories=[] if mobile is None else [c.get(ANDROID+'name') for c in mobile.findall('.//category')]
    if tv_categories != ['android.intent.category.LEANBACK_LAUNCHER']:
        fail.append('tv_launcher_category_changed')
    if mobile_categories != ['android.intent.category.LAUNCHER']:
        fail.append('mobile_launcher_category_missing_or_broadened')
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
if not MOBILE_ICON.is_file():
    fail.append('mobile_launcher_icon_resource_missing')
else:
    try:
        icon=ET.parse(MOBILE_ICON).getroot()
        if icon.tag!='vector' and not icon.tag.endswith('}vector'):
            fail.append('mobile_launcher_icon_not_vector')
        if icon.get(ANDROID+'width')!='108dp' or icon.get(ANDROID+'height')!='108dp':
            fail.append('mobile_launcher_icon_wrong_dimensions')
    except (ET.ParseError,OSError):
        fail.append('mobile_launcher_icon_invalid_xml')
if hashlib.sha256(BRIDGE.read_bytes()).hexdigest()!=CHECKSUM:
    fail.append('tested_bridge_modified')
if 'return order.mapNotNull { byId[it] }.filter { it.season > 0 }' not in BRIDGE.read_text():
    fail.append('series_watched_story_order_season_zero_exclusion_missing')
java_text=SOURCE.read_text()
native_subtitle_provenance=NATIVE_SUBTITLE_PROVENANCE.read_text() if NATIVE_SUBTITLE_PROVENANCE.is_file() else ''
native_subtitle_provenance_test=NATIVE_SUBTITLE_PROVENANCE_TEST.read_text() if NATIVE_SUBTITLE_PROVENANCE_TEST.is_file() else ''
story=STORY.read_text() if STORY.is_file() else ''
story_test=STORY_TEST.read_text() if STORY_TEST.is_file() else ''
mobile_story=MOBILE_STORY.read_text() if MOBILE_STORY.is_file() else ''
mobile_story_test=MOBILE_STORY_TEST.read_text() if MOBILE_STORY_TEST.is_file() else ''
mobile_story_host=MOBILE_STORY_HOST.read_text() if MOBILE_STORY_HOST.is_file() else ''
mobile_story_host_test=MOBILE_STORY_HOST_TEST.read_text() if MOBILE_STORY_HOST_TEST.is_file() else ''
android_mobile_story_widget_port=ANDROID_MOBILE_STORY_WIDGET_PORT.read_text() if ANDROID_MOBILE_STORY_WIDGET_PORT.is_file() else ''
android_mobile_story_screen_owner=ANDROID_MOBILE_STORY_SCREEN_OWNER.read_text() if ANDROID_MOBILE_STORY_SCREEN_OWNER.is_file() else ''
mobile_story_activity=MOBILE_STORY_ACTIVITY.read_text() if MOBILE_STORY_ACTIVITY.is_file() else ''
synthetic_watched_host=SYNTHETIC_WATCHED_HOST.read_text() if SYNTHETIC_WATCHED_HOST.is_file() else ''
synthetic_watched_host_test=SYNTHETIC_WATCHED_HOST_TEST.read_text() if SYNTHETIC_WATCHED_HOST_TEST.is_file() else ''
if ('class MobileStoryOrderLabActivity : Activity()' not in mobile_story_activity or
    'AndroidMobileStoryOrderScreenOwner.create(' not in mobile_story_activity or
    'createdStory.render(MobileStoryOrderMode.STORY)' not in mobile_story_activity):
    fail.append('mobile_story_launcher_composition_missing')
if ('current.consumeSelection()' not in mobile_story_activity or
    'current.render(MobileStoryOrderMode.STORY)' not in mobile_story_activity):
    fail.append('mobile_story_selection_mailbox_or_rerender_missing')
if ('AndroidMobileTitleWatchedScreenOwner.create(' not in mobile_story_activity or
    'createdWatched.render(1)' not in mobile_story_activity or
    'current.consumeOutcome()' not in mobile_story_activity):
    fail.append('mobile_title_action_lab_composition_or_mailbox_missing')
if ('ui.removeCallbacks(outcomePump)' not in mobile_story_activity or
    'storyOwner?.close()' not in mobile_story_activity or
    'watchedOwner?.close()' not in mobile_story_activity or
    'override fun onDestroy()' not in mobile_story_activity):
    fail.append('mobile_lab_lifecycle_close_missing')
if ('local:mobile-story:1:1' not in mobile_story_activity or
    'Synthetic local metadata and watched state only.' not in mobile_story_activity or
    'change production watched history' not in mobile_story_activity):
    fail.append('mobile_lab_synthetic_disclosure_missing')
allowed_pss_imports={
    'import org.pss.stremio.tv.NativeMobileTitleWatchedOutcome',
    'import org.pss.stremio.tv.TvWatchedCoordinator',
}
actual_pss_imports={
    line for line in mobile_story_activity.splitlines()
    if line.startswith('import org.pss.stremio.tv.')
}
if actual_pss_imports != allowed_pss_imports:
    fail.append('mobile_lab_unreviewed_pss_surface:'+repr(actual_pss_imports))
if any(token in mobile_story_activity for token in [
    'androidx.media3', 'android.net.', 'java.net.',
    'getIntent(', 'intent.get', 'startActivity(', 'startService(',
    'ContentResolver', 'AccountManager', 'Datastore'
]):
    fail.append('mobile_lab_external_or_player_surface')
if ('Looper.getMainLooper()' not in mobile_story_activity or
    'ui.post(outcomePump)' not in mobile_story_activity or
    'ui.postDelayed(this, OUTCOME_POLL_MS)' not in mobile_story_activity):
    fail.append('mobile_lab_main_thread_mailbox_pump_missing')
if ('class SyntheticTitleWatchedAccountHost' not in synthetic_watched_host or
    ': CanonicalWatchedAccountHostPort' not in synthetic_watched_host or
    'private val epoch = 1L' not in synthetic_watched_host):
    fail.append('synthetic_watched_host_missing_or_unbounded')
if ('message.keys != setOf("action", "args")' not in synthetic_watched_host or
    'inner.keys != setOf("action", "args")' not in synthetic_watched_host or
    '"MarkAsWatched"' not in synthetic_watched_host or
    '"MarkSeasonAsWatched"' not in synthetic_watched_host):
    fail.append('synthetic_watched_host_exact_dispatch_gate_missing')
if ('.filter { it.season > 0 && released(it) }' not in synthetic_watched_host or
    '.filter { it.season == season && released(it) }' not in synthetic_watched_host):
    fail.append('synthetic_watched_host_scope_or_season_zero_gate_missing')
if ('snapshot.videos.map { it.copy() }' not in synthetic_watched_host or
    'watchedVideoIds = watched.toList()' not in synthetic_watched_host):
    fail.append('synthetic_watched_host_detached_observation_missing')
if any(name not in synthetic_watched_host_test for name in [
    'seriesActionMutatesOnlyReleasedRegularSyntheticEpisodes',
    'seasonActionIsBoundedToRequestedSyntheticSeason',
    'malformedOrStaleDispatchNeverMutatesSyntheticState',
    'tvHostBindingAppliesOnlyLocalSyntheticSeriesState',
    'observationsAndReadbackListsAreDetached',
]):
    fail.append('synthetic_watched_host_regressions_missing')
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
if not all(fragment in episode for fragment in (
    'videoFingerprint',
    'snapshotCoreVideo(videoSource)',
    'snapshotCoreVideo(secondSource)',
    'MAX_CANONICAL_VIDEO_DEPTH',
    'MAX_CANONICAL_VIDEO_NODES',
    'MAX_CANONICAL_VIDEO_COLLECTION_ENTRIES',
    'MAX_CANONICAL_VIDEO_STRING_LENGTH',
    'MAX_CANONICAL_VIDEO_TOTAL_STRING_LENGTH',
    'Cyclic Core Video metadata',
)):
    fail.append('core_video_double_read_or_bounded_snapshot_missing')
if not all(fragment in episode for fragment in (
    'is Int -> "i32:$value"',
    'is Long -> "i64:$value"',
    '"f32:${java.lang.Integer.toUnsignedString(value.toRawBits(), 16)}"',
    '"f64:${java.lang.Long.toUnsignedString(value.toRawBits(), 16)}"',
)):
    fail.append('core_video_numeric_type_fingerprint_missing')
if ('class NativeTvTitleWatchedMenu' not in title_watched_menu or
    'class NativeTvTitleWatchedAction internal constructor' not in title_watched_menu):
    fail.append('native_tv_title_watched_menu_missing')
if ('val snapshot = snapshotOrNull() ?: return emptyList()' not in title_watched_menu or
    'coordinatorFor(watched).prepare(snapshot, scope, watched)' not in title_watched_menu):
    fail.append('title_watched_menu_not_built_from_one_fresh_core_snapshot')
if ('val record = issued[action.actionId] ?: return NativeTvTitleWatchedOutcome.REFUSED\n        issued.clear()' not in title_watched_menu or
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
    'if (currentPlan != pending.plan) return false' not in title_watched_menu or
    'coordinators[!pending.plan.watched] = TvWatchedCoordinator()' not in title_watched_menu or
    'coordinatorFor(record.plan.watched).confirm(' not in title_watched_menu or
    'if (currentPlan.selectedIds != pending.plan.selectedIds)' in title_watched_menu or
    'state.copy(watchedVideoIds = state.watchedVideoIds.toList())' not in title_watched_menu or
    'state.watchedVideoIds.size != state.watchedVideoIds.toSet().size' not in title_watched_menu):
    fail.append('title_watched_canonical_post_write_readback_gate_missing')
if ('val canonicalVideoIds = snapshot.videos.map { it.id }.toSet()' not in title_watched_menu or
    'if (!canonicalVideoIds.containsAll(state.watchedVideoIds)) return false' not in title_watched_menu):
    fail.append('title_watched_unknown_canonical_id_gate_missing')
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
if ('val record = issued[action.actionId] ?: return NativeTvTitleWatchedOutcome.REFUSED\n        issued.clear()' not in title_watched_menu):
    fail.append('tv_title_watched_activation_does_not_revoke_generation')
if any(name not in title_watched_menu_test for name in [
    'readyCoreSnapshotPresentsFourTitleActionsWithoutWriting',
    'futureEpisodeSuppressesUnsafeSeasonActions',
    'focusAndRemoteConfirmationDispatchExactSeriesAction',
    'activatingOneActionRevokesEverySiblingFromThatGeneration',
    'unwatchRequiresAcknowledgementAndPresentationIsOneShot',
    'accountDriftRefusesPresentedAction',
    'staleAndForgedActionsCannotExecute',
    'ownerThreadAndCloseFailClosed',
    'snapshotAndDispatchExceptionsFailClosed',
    'canonicalReadbackRearmsOnlyOppositeWatchedDirection',
    'acceptedDispatchBlocksNewGenerationUntilCanonicalReadbackMatches',
    'pendingReadbackCannotCrossAccountOrLibraryIdentity',
    'mismatchedOrDuplicateCanonicalReadbackNeverReopensGeneration',
    'unknownCanonicalWatchedIdNeverReopensGeneration',
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
if ('val record = issued[action.actionId] ?: return NativeMobileTitleWatchedOutcome.REFUSED\n        issued.clear()' not in mobile_title_watched_menu or
    mobile_title_watched_menu.find('val record = issued[action.actionId]') > mobile_title_watched_menu.find('delegate.activate(')):
    fail.append('mobile_title_action_not_consumed_before_core_delegate')
if ('Thread.currentThread() === ownerThread' not in mobile_title_watched_menu or
    'private var closed = false' not in mobile_title_watched_menu or
    'return delegate.close()' not in mobile_title_watched_menu):
    fail.append('mobile_title_thread_or_lifecycle_guard_missing')
if ('NativeMobileTitleWatchedMenu(' in java_text or 'NativeMobileTitleWatchedAction(' in java_text):
    fail.append('mobile_title_watched_menu_wired_to_synthetic_activity')
if ('val record = issued[action.actionId] ?: return NativeMobileTitleWatchedOutcome.REFUSED\n        issued.clear()' not in mobile_title_watched_menu):
    fail.append('mobile_title_watched_activation_does_not_revoke_generation')
if any(name not in mobile_title_watched_menu_test for name in [
    'mobilePresentationMirrorsFourGuardedTitleActionsWithoutWriting',
    'semanticAccessibilityActivationDispatchesExactSeriesAction',
    'mobileActivationRevokesEverySiblingFromThatGeneration',
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
    'val menuClosed = menu.close()' not in mobile_title_host_binding or
    'fun isOperational(): Boolean = onOwnerThread() && !closed' not in mobile_title_host_binding):
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
    'mobileHostCannotRenderAgainWhileAcceptedWriteLacksReadback',
    'mobileHostOperationalStateDistinguishesFatalFailureFromPendingReadback'
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
    'val menuClosed = menu.close()' not in tv_title_host_binding or
    'fun isOperational(): Boolean = onOwnerThread() && !closed' not in tv_title_host_binding):
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
    'tvHostCannotRenderAgainWhileAcceptedWriteLacksReadback',
    'tvHostOperationalStateDistinguishesFatalFailureFromPendingReadback'
]):
    fail.append('native_tv_title_host_binding_regressions_missing')
if ('class NativeTvTitleWatchedWidgetController' not in tv_title_widget_controller or
    'class NativeTvTitleWatchedWidget internal constructor' not in tv_title_widget_controller or
    'class NativeTvTitleWatchedWidgetIntent internal constructor' not in tv_title_widget_controller or
    'class NativeTvTitleWatchedConfirmation internal constructor' not in tv_title_widget_controller):
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
if ('confirmableIntent !== intent' not in tv_title_widget_controller or
    'pendingConfirmation !== confirmation' not in tv_title_widget_controller or
    '!confirmation.issuedBy(confirmationSeal)' not in tv_title_widget_controller or
    'fun invalidateConfirmation(): Boolean' not in tv_title_widget_controller):
    fail.append('tv_widget_confirmation_exact_lease_missing')
if (tv_title_widget_controller.count('pendingConfirmation = null') != 5 or
    tv_title_widget_controller.count('confirmableIntent = null') != 5 or
    tv_title_widget_controller.count(
        'confirmableIntent = null\n        pendingConfirmation = null'
    ) != 4):
    fail.append('tv_widget_confirmation_lifecycle_revocation_missing')
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
if ('result.intent.action' not in android_tv_title_widget_port or
    'result.intent.focusedActionId' not in android_tv_title_widget_port or
    'result.intent.remoteKey' not in android_tv_title_widget_port or
    'setOnClickListener' in android_tv_title_widget_port):
    fail.append('android_tv_widget_exact_callback_or_remote_only_gate_missing')
if ('activeConfirmationDialog' not in android_tv_title_widget_port or
    'controller.invalidateConfirmation()' not in android_tv_title_widget_port or
    'controller.beginConfirmation(intent)' not in android_tv_title_widget_port or
    'controller.resolveConfirmation(' not in android_tv_title_widget_port or
    'dialog.dismiss()' not in android_tv_title_widget_port):
    fail.append('android_tv_stale_confirmation_dialog_gate_missing')
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
    'widgetOwnerThreadClearAndCloseFailClosed',
    'destructiveConfirmationLeaseReturnsExactIntentOnce',
    'replacementInvalidatesStaleConfirmationWithoutRevokingCurrentLease',
    'forgedAndForeignConfirmationCallbacksPreserveOwnerLease',
    'clearAndCloseRevokePendingConfirmations'
]):
    fail.append('native_tv_title_widget_controller_regressions_missing')
if ('class NativeMobileTitleWatchedWidgetController' not in mobile_title_widget_controller or
    'class NativeMobileTitleWatchedWidget internal constructor' not in mobile_title_widget_controller or
    'class NativeMobileTitleWatchedWidgetIntent internal constructor' not in mobile_title_widget_controller or
    'class NativeMobileTitleWatchedConfirmation internal constructor' not in mobile_title_widget_controller):
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
if ('confirmableIntent !== intent' not in mobile_title_widget_controller or
    'pendingConfirmation !== confirmation' not in mobile_title_widget_controller or
    '!confirmation.issuedBy(confirmationSeal)' not in mobile_title_widget_controller or
    'fun invalidateConfirmation(): Boolean' not in mobile_title_widget_controller):
    fail.append('mobile_widget_confirmation_exact_lease_missing')
if (mobile_title_widget_controller.count('pendingConfirmation = null') != 5 or
    mobile_title_widget_controller.count('confirmableIntent = null') != 5 or
    mobile_title_widget_controller.count(
        'confirmableIntent = null\n        pendingConfirmation = null'
    ) != 4):
    fail.append('mobile_widget_confirmation_lifecycle_revocation_missing')
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
if ('result.intent.action' not in android_mobile_title_widget_port or
    'result.intent.selectedActionId' not in android_mobile_title_widget_port or
    'result.intent.semanticEvent' not in android_mobile_title_widget_port or
    'setOnTouchListener' in android_mobile_title_widget_port):
    fail.append('android_mobile_widget_exact_callback_or_raw_touch_gate_missing')
if ('activeConfirmationDialog' not in android_mobile_title_widget_port or
    'controller.invalidateConfirmation()' not in android_mobile_title_widget_port or
    'controller.beginConfirmation(intent)' not in android_mobile_title_widget_port or
    'controller.resolveConfirmation(' not in android_mobile_title_widget_port or
    'dialog.dismiss()' not in android_mobile_title_widget_port):
    fail.append('android_mobile_stale_confirmation_dialog_gate_missing')
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
    'mobileWidgetOwnerThreadClearAndCloseFailClosed',
    'destructiveMobileConfirmationLeaseReturnsExactIntentOnce',
    'mobileReplacementInvalidatesStaleConfirmationWithoutRevokingCurrentLease',
    'forgedAndForeignMobileConfirmationCallbacksPreserveOwnerLease',
    'mobileClearAndCloseRevokePendingConfirmations'
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
    'private val lifecycleLock = Any()' not in canonical_watched_account_adapter or
    canonical_watched_account_adapter.count('synchronized(lifecycleLock)') != 4 or
    '@Volatile\n    private var closed = false' not in canonical_watched_account_adapter or
    'fun close(): Boolean = synchronized(lifecycleLock)' not in
        canonical_watched_account_adapter):
    fail.append('canonical_account_owner_thread_or_close_missing')
if ('SessionBoundCanonicalWatchedAccountAdapter(' in java_text or
    'CanonicalWatchedAccountHostPort' in java_text):
    fail.append('canonical_account_adapter_wired_to_synthetic_activity')
if ('latestIdentity = null\n        val observation = observeOrNull()' not in
        canonical_watched_account_adapter):
    fail.append('canonical_account_failed_refresh_retains_snapshot_lease')
if ('if (observation == null || observation.identity != expected) {' not in
        canonical_watched_account_adapter or
    'latestIdentity = null\n                return@synchronized null' not in
        canonical_watched_account_adapter):
    fail.append('canonical_account_failed_readback_retains_snapshot_lease')
if ('val expected = latestIdentity ?: return@synchronized false\n        latestIdentity = null' not in
        canonical_watched_account_adapter):
    fail.append('canonical_account_dispatch_preconditions_retain_snapshot_lease')
if any(name not in canonical_watched_account_adapter_test for name in [
    'detachesSnapshotAndWatchedIdsFromHostCollections',
    'watchedReadbackRequiresMatchingObservedSnapshot',
    'failedSnapshotRefreshCannotReviveEarlierLease',
    'mismatchedReadbackCannotReviveEarlierLeaseAfterIdentityAba',
    'failedDispatchPreconditionsConsumeLeaseBeforeIdentityAba',
    'dispatchAcceptsOnlyExactTitleWatchedCommands',
    'dispatchConsumesSnapshotLeaseBeforeRejectedHostCall',
    'dispatchBindsHostCallToObservedSessionEpoch',
    'sessionEpochDriftFailsClosed',
    'closedAdapterNeverTouchesHostCallbacks',
    'ownedScreenConstructionExceptionRevokesAdapterWithoutHostAccess',
    'ownedScreenNullRevokesButSuccessfulConstructionTransfersLifecycle',
    'externalScreenConstructionFailuresAreContainedWithoutOwnership',
    'foreignThreadCloseRevokesWithoutHostAccess',
    'foreignCloseWaitsForInFlightOwnerDispatchThenRevokes',
    'screenOwnedLeaseForeignRevokeStopsAccountWithoutHostAccess',
    'externalScreenAccountLeaseNeverOwnsCallerPort',
    'screenOwnedLeaseTerminationRequestIsStickyAndRevokesAccount',
    'externalScreenLeaseTerminationNeverOwnsCallerPort',
    'throwingHostCollectionsAndCallbacksFailClosed'
]):
    fail.append('canonical_account_adapter_regressions_missing')
if (android_title_watched_screen_owners.count('pendingOutcome != null) return false') != 2 or
    android_title_watched_screen_owners.count('val outcome = host.activate(') != 2 or
    android_title_watched_screen_owners.count('pendingOutcome = outcome') != 2):
    fail.append('android_title_screen_one_outcome_mailbox_missing')
if (android_title_watched_screen_owners.count('fun consumeOutcome()') != 2 or
    android_title_watched_screen_owners.count('pendingOutcome = null') < 8 or
    android_title_watched_screen_owners.count(
        'ownedAccountLease.isTerminationRequested() || !host.isOperational()'
    ) != 2 or
    android_title_watched_screen_owners.count('return outcome') != 4):
    fail.append('android_title_screen_outcome_consumption_missing')
if (android_title_watched_screen_owners.count('val hostClosed = host.close()') != 2 or
    android_title_watched_screen_owners.count('val relayClosed = relay.close()') != 2):
    fail.append('android_title_screen_lifecycle_close_missing')
if (android_title_watched_screen_owners.count(
        'val rendered = host.render(seasonNumber)'
    ) != 2 or
    android_title_watched_screen_owners.count(
        'if (!rendered && !host.isOperational()) poison()'
    ) != 2 or
    android_title_watched_screen_owners.count(
        'if (!host.isOperational()) {\n            ownedAccountLease.requestTermination()'
    ) != 2):
    fail.append('android_title_screen_fatal_host_liveness_not_propagated')
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
if ('class CanonicalWatchedScreenOwnedAccountLease' not in
        canonical_watched_account_adapter or
    'private val terminationRequested = AtomicBoolean(false)' not in
        canonical_watched_account_adapter or
    'private val revoked = AtomicBoolean(false)' not in
        canonical_watched_account_adapter or
    'terminationRequested.set(true)' not in canonical_watched_account_adapter or
    'fun isTerminationRequested(): Boolean = terminationRequested.get()' not in
        canonical_watched_account_adapter or
    'if (!revoked.compareAndSet(false, true)) return true' not in
        canonical_watched_account_adapter):
    fail.append('android_title_screen_owned_account_termination_lease_missing')
if android_title_watched_screen_owners.count(
        'private val ownedAccount: SessionBoundCanonicalWatchedAccountAdapter?'
    ) != 2:
    fail.append('android_title_screen_owned_adapter_not_retained')
if (android_title_watched_screen_owners.count('if (!ready) {') != 2 or
    android_title_watched_screen_owners.count(
        'private val ownedAccountLease = CanonicalWatchedScreenOwnedAccountLease(ownedAccount)'
    ) != 2 or
    android_title_watched_screen_owners.count(
        'ownedAccountLease.requestTermination()'
    ) != 12 or
    android_title_watched_screen_owners.count(
        'ownedAccountLease.isTerminationRequested()'
    ) != 6):
    fail.append('android_title_screen_owned_adapter_failure_or_close_missing')
if (android_title_watched_screen_owners.count(
        'val accountClosed = ownedAccountLease.requestTermination()'
    ) != 2 or
    android_title_watched_screen_owners.count(
        'return hostClosed && relayClosed && accountClosed'
    ) != 2):
    fail.append('android_title_screen_close_does_not_revoke_owned_adapter')
if android_title_watched_screen_owners.count(
        'if (!onMainOwnerThread()) {\n            ownedAccountLease.requestTermination()'
    ) != 4:
    fail.append('android_title_screen_foreign_teardown_or_callback_not_contained')
if (android_title_watched_screen_owners.count(
        'if (ownedAccountLease.isTerminationRequested()) {\n            poison()'
    ) != 2 or
    android_title_watched_screen_owners.count(
        'ownedAccountLease.isTerminationRequested() || !host.isOperational()'
    ) != 2):
    fail.append('android_title_screen_requested_termination_not_cleaned_on_owner_thread')
if any(name in java_text for name in [
        'AndroidMobileTitleWatchedScreenOwner',
        'NativeMobileTitleWatchedViewPortRelay',
        'NativeTvTitleWatchedViewPortRelay'
    ]):
    fail.append('unreviewed_title_screen_surface_wired_to_tv_activity')
if ('AndroidTvTitleWatchedScreenOwner.create(' not in java_text or
    'createdWatched.render(1)' not in java_text or
    'current.consumeOutcome()' not in java_text):
    fail.append('tv_title_action_lab_composition_or_mailbox_missing')
if ('SyntheticTitleWatchedAccountHost()' not in java_text or
    'watchedHost.watchedIds()' not in java_text or
    'current.render(1)' not in java_text):
    fail.append('tv_title_action_synthetic_readback_or_rerender_missing')
if ('watchedOwner?.close()' not in java_text or
    'watchedOwner = null' not in java_text or
    'ui.removeCallbacks(refresh)' not in java_text):
    fail.append('tv_title_action_lab_lifecycle_close_missing')
if ('local in-memory fixture only' not in java_text or
    'cannot read' not in java_text or
    'change a Stremio account' not in java_text):
    fail.append('tv_title_action_synthetic_disclosure_missing')
if any(name in java_text for name in [
        'SessionBoundCanonicalWatchedAccountAdapter(',
        'NativeTvTitleWatchedHostBinding(',
        'NativeTvTitleWatchedViewPortRelay(',
        'CanonicalWatchedStatePort',
        'CanonicalWatchedAccountHostPort'
    ]):
    fail.append('tv_activity_bypasses_reviewed_screen_owner')
if ('NativeTvTitleWatchedOutcome.APPLIED' not in java_text or
    'DISPATCHED_PENDING_READBACK' not in java_text or
    'NativeTvTitleWatchedOutcome.REFUSED' not in java_text):
    fail.append('tv_title_action_outcome_handling_missing')
if ('tvHostBindingAppliesOnlyLocalSyntheticSeriesState' not in
        synthetic_watched_host_test or
    'binding.activate(selected, selected.actionId, "ENTER")' not in
        synthetic_watched_host_test):
    fail.append('tv_title_action_synthetic_host_composition_regression_missing')
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
if ('watchedButtons' in java_text or
    'watched (unavailable)' in java_text):
    fail.append('stale_disabled_tv_watched_placeholders_present')
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
if (('private fun <T> snapshotList(value: List<T>, maximum: Int)' not in story) or
    ('repeat(expectedSize)' not in story) or
    ('if (iterator.hasNext()) return null' not in story) or
    ('Collections.unmodifiableList(detached)' not in story) or
    ('INPUT_SNAPSHOT_FAILED' not in story) or
    ('snapshotHints(hints) ?: return canonicalSnapshot' not in story) or
    ('assertEquals(canonical, fallback)' not in story_test) or
    ('fallback !== canonical' not in story_test)):
    fail.append('story_order_host_collection_snapshot_guard_missing')
if any(name not in story_test for name in [
    'callerMutationCannotChangeDetachedStoryPresentation',
    'changingCardinalityFailsClosedWithoutEscaping',
    'throwingCanonicalCollectionFailsClosedWithoutEscaping'
]):
    fail.append('story_order_host_collection_snapshot_regressions_missing')
if any(token not in mobile_story for token in [
    'class MobileStoryOrderController',
    'StoryOrderContract.resolve(canonicalSnapshot, hints)',
    'record.row !== row',
    'semanticEvent != SEMANTIC_SELECTION',
    'activeEpisodes.indexOfFirst { it.id == currentVideoId }'
]):
    fail.append('android_mobile_story_order_controller_missing')
if any(name not in mobile_story_test for name in [
    'mobileStoryModePresentsNarrativeSpecialWithStableIdentity',
    'malformedHintsFallBackToCanonicalAndDisableStoryNext',
    'semanticSelectionRequiresExactIssuedRowAndStableId',
    'rawPointerAndForgedRowsNeverAuthorizeSelection',
    'mobileNextUsesPresentedStableIdentityAndSurvivesCallerMutation',
    'ownerThreadAndCloseFailClosed'
]):
    fail.append('android_mobile_story_order_regressions_missing')
if any(token not in mobile_story_host for token in [
    'class MobileStoryOrderViewAction internal constructor',
    'interface MobileStoryOrderViewPort',
    'class MobileStoryOrderHostBinding',
    'const val MAX_PRESENTED_ROWS = 500',
    'record.action === action',
    'action.rowFor(bindingSeal)',
    'semanticEvent == MobileStoryOrderController.SEMANTIC_SELECTION',
    'view.replaceStoryRows(mode, actions.toList())',
    'view.clearStoryRows()'
]):
    fail.append('android_mobile_story_order_host_binding_missing')
if any(name not in mobile_story_host_test for name in [
    'hostRendersStoryRowsWithoutSelecting',
    'exactSemanticActionReturnsOriginalCanonicalAndRevokesGeneration',
    'canonicalModeAndMalformedHintsPreserveOrdinaryRows',
    'rawStaleAndForgedActionsFailClosed',
    'viewReplacementAndClearFailuresPoisonHost',
    'hostNextUsesPresentedStoryIdentityOnly',
    'oversizedMobilePresentationFailsClosedBeforeView',
    'hostOwnerThreadAndCloseRevokeOutstandingActions'
]):
    fail.append('android_mobile_story_order_host_regressions_missing')
if any(token not in android_mobile_story_widget_port for token in [
    'class AndroidMobileStoryOrderWidgetPort',
    'Looper.myLooper() === Looper.getMainLooper()',
    'createModeButton(mode)',
    '"Show season order"',
    '"Show story order"',
    'setOnLongClickListener { true }',
    'MobileStoryOrderController.SEMANTIC_SELECTION',
    'container.addView(view)'
]):
    fail.append('android_mobile_story_order_widget_port_missing')
if ('setOnTouchListener' in android_mobile_story_widget_port or
    'MotionEvent' in android_mobile_story_widget_port):
    fail.append('android_mobile_story_order_raw_touch_admission')
if any(token not in android_mobile_story_screen_owner for token in [
    'class AndroidMobileStoryOrderScreenOwner private constructor',
    'Looper.myLooper() !== Looper.getMainLooper()',
    'private var pendingSelection: CanonicalEpisode? = null',
    'private val host = MobileStoryOrderHostBinding(canonical, hints, port)',
    'fun consumeSelection(): CanonicalEpisode?',
    'pendingSelection != null',
    'return host.next(currentVideoId)'
]):
    fail.append('android_mobile_story_order_screen_owner_missing')
if any(name in java_text for name in [
    'MobileStoryOrderHostBinding',
    'AndroidMobileStoryOrderWidgetPort',
    'AndroidMobileStoryOrderScreenOwner'
]):
    fail.append('android_mobile_story_order_wired_to_synthetic_activity')
if any(token in android_mobile_story_screen_owner+android_mobile_story_widget_port for token in [
    'ExoPlayer',
    'StremioAccountPort',
    'http://',
    'https://',
    'getAuthKey',
    'System.getenv'
]):
    fail.append('android_mobile_story_order_ui_crosses_player_account_or_network_boundary')
if 'private val TERMINAL' not in recovery or 'private val RETRYABLE' not in recovery:
    fail.append('classification_first_authority_sets_missing')
if 'playback_health:delegate_to_bounded_playback_recovery' not in recovery:
    fail.append('exact_playback_delegation_missing')
if 'observation?.attemptedRecoveries == null' not in recovery or 'recoveryContextId != value.currentContextId' not in recovery:
    fail.append('bounded_context_ledger_gate_missing')
if (('private fun snapshotAttemptHistory(value: List<String>?)' not in recovery) or
    ('val history = snapshotAttemptHistory(value.attemptedRecoveries) ?: return null' not in recovery) or
    ('repeat(expectedSize) { index ->' not in recovery) or
    ('recoveryHistoryIsSnapshottedOnceAndFailuresFailClosed' not in recovery_test)):
    fail.append('native_recovery_history_single_snapshot_missing')
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
if (('snapshotRecoveryActions(value.supportedRecoveryActions)' not in actuator) or
    ('Collections.unmodifiableSet(snapshot)' not in actuator) or
    ('recoveryCapabilitiesAreSnapshottedOnceAndFailuresFailClosed' not in actuator_test)):
    fail.append('actuator_capability_set_single_immutable_snapshot_missing')
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
# Native subtitle preference provenance remains a pure, unwired translation of
# Foundation PR #70. These checks prevent enabled/account/language state from
# becoming implicit consent and keep private-client/device acceptance open.
if not all(x in native_subtitle_provenance for x in [
    '99ea3173184029bbd7ee5ae7dc33d31d553fc135',
    'e63e9a35ec9e0bdeab0de17debaf5a3ce929d8d4',
    'object NativeSubtitlePreferenceProvenance',
    'This pure boundary performs no storage, account, player, network or UI work.'
]):
    fail.append('native_subtitle_provenance_contract_pin')
if not all(x in native_subtitle_provenance for x in [
    '^[0-9a-f]{64}$', 'MAX_SAFE_INTEGER', 'hasExactKeys',
    '"same-installation-setting"', '"explicit-local-choice"',
    '"schemaVersion"', '"preferenceRevision"', '"binding"'
]):
    fail.append('native_subtitle_provenance_exact_schema')
if not all(x in native_subtitle_provenance for x in [
    'lowercase(Locale.ROOT)', "replace('_', '-')",
    'it == "embedded" || it == "external"', 'bindingKeys.all'
]):
    fail.append('native_subtitle_provenance_binding')
if not all(x in native_subtitle_provenance for x in [
    'setOf("first-run", "subtitle-settings")',
    'event["choice"] != "enabled"',
    'sessionPreference?.enabled != true',
    'An enabled flag, account hydration or a preferred language is never consent.'
]):
    fail.append('native_subtitle_provenance_explicit_choice')
if not all(x in native_subtitle_provenance for x in [
    'readback["scope"] != "installation-local"',
    'positiveRevision(readback["preferenceRevision"]) != revision',
    '!sameBinding(readback["binding"], stored["binding"])',
    '"kind" to "same-installation-setting"'
]):
    fail.append('native_subtitle_provenance_storage_promotion')
if (
    not all(x in native_subtitle_provenance for x in [
        'Collections.unmodifiableMap(LinkedHashMap(value))',
        'This function validates that event and cannot create it.',
        'not prove UI, signed package, private-client or device acceptance.'
    ]) or
    'NativeSubtitlePreferenceProvenance' in java_text or
    not all(x in native_subtitle_provenance_test for x in ['sideBySideFirstRunRequiresLocalChoiceDespiteLanguageDefaults', 'explicitOffRemainsAuthoritativeWithoutInventingConsent', 'sameInstallationReceiptMustBindExactPreferenceAndInstallation', 'malformedDigestAndRevisionFailClosed', 'receiptCannotSurviveBoundLanguageOrSourceChange', 'bindingIsNormalisedDetachedAndClosed', 'explicitChoiceAcceptsOnlyReviewedSurfacesAndEnabledSession', 'explicitChoiceEventUsesExactClosedSchema', 'sameInstallationReceiptCannotMasqueradeAsUiChoice', 'exactInstallationLocalReadbackPromotesReceipt', 'promotionRejectsScopeInstallationRevisionAndBindingDrift', 'promotionRejectsInjectedStorageOrReceiptClaimsWithoutMutation'])
):
    fail.append('native_subtitle_provenance_unwired_regressions')
if not all(x in native_subtitle_provenance for x in [
    'private fun snapshotExactObject(',
    'val expectedSize = source.size',
    'if (expectedSize !in 1..maxEntries) return null',
    'if (count >= expectedSize) return null',
    'if (count != expectedSize) return null',
    'Collections.unmodifiableMap(copy)'
]):
    fail.append('native_subtitle_provenance_single_snapshot')
if not all(x in native_subtitle_provenance for x in [
    'snapshotExactObject(preferenceProvenance, explicitChoiceKeys.size, true)',
    'snapshotExactObject(localChoiceEvent, choiceEventKeys.size, false)',
    'snapshotExactObject(storedReceipt, explicitChoiceKeys.size, true)',
    'snapshotExactObject(storageReadback, storageReadbackKeys.size, true)'
]):
    fail.append('native_subtitle_provenance_snapshot_entrypoints')
if not all(x in native_subtitle_provenance for x in [
    'catch (_: RuntimeException)',
    'catch (_: LinkageError)'
]) or 'catch (_: Throwable)' in native_subtitle_provenance:
    fail.append('native_subtitle_provenance_exception_boundary')
if not all(x in native_subtitle_provenance_test for x in ['callerMapsAreSnapshottedOnceBeforeValidation','throwingTopLevelAndNestedMapsFailClosedWithoutEscaping','iteratorCardinalityDriftFailsClosed','detachedReceiptCannotBeChangedByLaterCallerMutation']):
    fail.append('native_subtitle_provenance_hostile_map_regressions')
if any(x in java_text+native_subtitle_provenance+native_subtitle_provenance_test+story+mobile_story+mobile_story_host+android_mobile_story_widget_port+android_mobile_story_screen_owner+mobile_story_activity+classifier+media3_intake+media3_retry_actuator+recovery+actuator+ports+episode+title_watched_menu+mobile_title_watched_menu+mobile_title_host_binding+tv_title_host_binding+tv_title_widget_controller+android_tv_title_widget_port+mobile_title_widget_controller+android_mobile_title_widget_port+title_watched_view_port_relay+android_title_watched_screen_owners+canonical_watched_account_adapter+synthetic_watched_host+synthetic_watched_host_test for x in ['datastorePut','datastoreDelete','http://','https://','getAuthKey','System.getenv','Log.d(']):
    fail.append('account_network_or_credential_surface')
print(json.dumps({'ok':not fail,'checks':258,'issues':fail,'tested_bridge_sha256':CHECKSUM,
                  'production_package':'com.stremio.one','candidate_package':'org.thiajay.stremiotvcompanion'},indent=2))
sys.exit(1 if fail else 0)
