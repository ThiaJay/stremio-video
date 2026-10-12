#!/usr/bin/env python3
"""Bounded source-only CI gate for an isolated SDK compile-only integration."""
from pathlib import Path
import json,sys,re
R=Path(__file__).resolve().parents[1]
ROOT=R.parent
build=(R/'build.gradle').read_text()
src=(R/'src/main/java/org/thiajay/stremiotvcore/OfficialCoreKotlinReadOnlySignals.kt').read_text()
gate=(R/'src/main/java/org/thiajay/stremiotvcore/CoreReadOnlyGate.kt').read_text()
settings=(ROOT/'settings.gradle').read_text()
appbuild=(ROOT/'companion-tv/build.gradle').read_text() if (ROOT/'companion-tv/build.gradle').is_file() else ''
errors=[]
def must(ok, reason):
 if not ok: errors.append(reason)
must("include ':processor', ':test-app', ':companion-tv', ':core-interop'" in settings, 'core_compat_module_not_isolated')
must("includeGroup 'com.github.Stremio'" in settings and "includeGroup 'com.github.Stremio.stremio-core-kotlin'" in settings and "url 'https://jitpack.io'" in settings, 'jitpack_multimodule_groups_not_scoped')
must("compileOnly 'com.github.Stremio:stremio-core-kotlin:1.15.0'" in build and "implementation 'com.github.Stremio" not in build, 'core_dependency_not_compile_only')
must("compileOnly 'pro.streem.pbandk:pbandk-runtime:0.16.0'" in build, 'pb_runtime_not_compile_only')
must('Core.getState(Field.CTX)' in src and 'Core.getState(Field.META_DETAILS)' in src, 'actual_core_reader_not_typed')
must('ctxBefore.profile.auth?.user?.id' in src and 'ctxAfter.profile.auth?.user?.id' in src and 'userA != userB' in src, 'account_identity_not_compared')
must('sessionEpoch()' in src and 'hostApprovedCoreReady()' in src, 'account_epoch_or_host_not_protected')
must(all(x not in src for x in ['Core.dispatch(', 'Core.initialize(', 'loginWithToken(', 'authenticate(', 'println(', 'Log.', 'Timber.', 'http://', 'https://']), 'interop_mutation_or_logging')
must('CoreReadOnlyResult.Held' in gate and 'STALE_OR_SWITCHED_SESSION' in gate, 'epoch_guard_not_fail_closed')
must("project(':core-interop')" not in appbuild and 'stremio-core-kotlin' not in appbuild, 'sdk_leaked_into_zero_permission_apk')
must(not (R/'src/main/AndroidManifest.xml').exists(), 'unexpected_core_module_manifest')
must(not re.search(r'\b(implementation|api)\s+[\'\"]com\.github\.Stremio',build), 'sdk_runtime_dependency_detected')
print(json.dumps({'ok':not errors,'checks':12,'issues':errors,'module':'core-interop','compiled_sdk_only':True,'official_client_modified':False},indent=2))
sys.exit(1 if errors else 0)
