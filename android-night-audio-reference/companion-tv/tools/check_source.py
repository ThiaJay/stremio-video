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
BRIDGE=ROOT/'src/main/java/org/pss/stremio/tv/NativeTvBridge.kt'
PORTS=ROOT/'src/main/java/org/pss/stremio/tv/NativeTvPorts.kt'
BUILD=ROOT/'build.gradle'
CHECKSUM='0e067446053abc456196d060bc96dff72d03902542fb9ae97cefd47fa143f23b'

manifest=ET.parse(MANIFEST).getroot()
fail=[]
permission_names=[p.get(ANDROID+'name') for p in manifest.findall('uses-permission') if p.get('{http://schemas.android.com/tools}node')!='remove']
if permission_names:fail.append('permissions_are_not_allowed:'+repr(permission_names))
app=manifest.find('application')
if app is None:fail.append('missing_application')
else:
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
if hashlib.sha256(BRIDGE.read_bytes()).hexdigest()!=CHECKSUM:
    fail.append('tested_bridge_modified')
java_text=SOURCE.read_text()
ports=PORTS.read_text()
gradle=BUILD.read_text()
if "applicationId 'org.thiajay.stremiotvcompanion'" not in gradle:fail.append('app_package_changed')
if not re.search(r"EXTERNAL_SOURCE_ENABLED'\s*,\s*'false'",gradle):fail.append('external_source_gate_missing')
if not re.search(r"STREMIO_ACCOUNT_WRITES_ENABLED'\s*,\s*'false'",gradle):fail.append('account_gate_missing')
if 'TvSkipCoordinator' not in java_text or 'NightAudioMedia3Processor' not in java_text or 'ExoPlayer.Builder' not in java_text:
    fail.append('real_media3_bridge_or_pcm_missing')
if 'player.seekTo(ms)' not in java_text or 'fun seekWithinSelectedVideo' not in ports:
    fail.append('same_media_seek_binding_missing')
if 'watchedButtons.forEach { it.isEnabled = false }' not in java_text:
    fail.append('watched_controls_not_fail_closed')
if any(x in java_text+ports for x in ['datastorePut','datastoreDelete','http://','https://','getAuthKey','System.getenv','Log.d(']):
    fail.append('account_network_or_credential_surface')
print(json.dumps({'ok':not fail,'checks':17,'issues':fail,'tested_bridge_sha256':CHECKSUM,
                  'production_package':'com.stremio.one','candidate_package':'org.thiajay.stremiotvcompanion'},indent=2))
sys.exit(1 if fail else 0)
