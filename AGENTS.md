# AGENTS.md — AADisplay-101 LSPosed Module Development Guide

This file defines the repository-specific rules for coding agents and contributors. It applies to the entire repository unless a more specific `AGENTS.md` exists in a subdirectory.

## 1. Project Mission and Risk Profile

AADisplay-101 is an Android 12+ Xposed/LSPosed module that mirrors applications to Android Auto through a virtual display. It runs code inside privileged and third-party processes, including `system_server` and Android Auto. A hook failure can boot-loop the device, repeatedly crash Android Auto, or break unrelated framework behavior.

Treat every hook change as systems code:

- Prefer the smallest compatible hook surface.
- Fail closed or fall back to original behavior when discovery or assumptions fail.
- Never trade device stability for convenience.
- Do not claim compatibility without testing the affected Android and Android Auto versions.
- Keep ordinary application code independent from injected-process state whenever possible.

The user-facing product name is **AADisplay-101**. The application ID is `com.aadisplay101.app`. The Kotlin/Java namespace remains `io.github.nitsuya.aa.display` for source compatibility; do not rename either identifier casually.

## 2. Repository Map and Toolchain

| Path | Purpose |
| --- | --- |
| `aa-display/` | Main Android application and libxposed module |
| `aa-display/src/main/java/.../xposed/` | libxposed entry point, runtime adapters, config bridge, Binder bridge, and hooks |
| `aa-display/src/main/java/.../xposed/hook/aa/` | Android Auto process-specific hooks |
| `aa-display/src/main/java/.../ui/` | Module application and Android Auto UI |
| `aa-display/src/main/aidl/` | Cross-process Binder contracts |
| `aa-display/src/main/resources/META-INF/xposed/` | API 101 module metadata, entry point, and static scope |
| `lib-stub/` | Compile-only hidden Android framework stubs; never runtime implementation code |
| `version.conf` | CI release version and prerelease state |
| `.github/workflows/release.yml` | Signed, immutable GitHub release pipeline |

Current build baseline:

- JDK 17 to run Gradle; Java/Kotlin bytecode target 11.
- Gradle Wrapper 8.13; always use the checked-in wrapper.
- Android Gradle Plugin 8.13.2.
- `compileSdk` / `targetSdk` 37 and `minSdk` 31.
- libxposed API 101 (`minApiVersion=101`, `targetApiVersion=101`).
- Debug and release builds are minified and resource-shrunk, so R8 behavior is part of normal validation.

Do not silently upgrade the JDK, Gradle, AGP, Kotlin, SDK levels, libxposed API, or dependency set while implementing an unrelated feature.

## 3. Non-Negotiable Workflow Rules

### 3.1 Preserve the Worktree

Before editing, run `git status --short` and inspect the relevant diff. Existing modifications may belong to the user or another task.

- Do not discard, overwrite, reformat, stage, or commit unrelated changes.
- Keep edits scoped to the request.
- Never use destructive Git commands such as `git reset --hard` or `git checkout --` without explicit authorization.
- Do not create commits, push branches, create tags, or publish releases unless the user explicitly requests it.

### 3.2 Inspect Before Hooking

Before changing a hook, trace all of the following:

1. libxposed lifecycle callback and target process;
2. target class loader and application lifecycle point;
3. member discovery strategy and version assumptions;
4. configuration source and default behavior;
5. Binder, virtual-display, or UI consumers affected by the result;
6. failure logs and the fallback path.

Do not patch a single reflection call without checking its callers and the process in which it executes.

### 3.3 Device Installation Requires a Reboot Pause

After **any successful APK push or install to a device**, stop the workflow immediately. AADisplay hooks Android system processes, so killing only the module app or Android Auto is insufficient.

Ask the user to reboot the phone and reconnect ADB and Android Auto as applicable. Use the available user-question mechanism, or ask directly:

> AADisplay-101 has been installed on the device. Because it hooks Android system processes, the phone must be rebooted before this build can be tested. Please reboot the phone, reconnect ADB / Android Auto, and confirm when it is ready.

Do not run post-install functional checks, launch DHU, collect conclusions, or make a second installation until the user confirms that the device has rebooted and reconnected. Never reboot the user's phone without explicit permission.

### 3.4 Never Guess an ADB Target

List connected devices before any device command:

```powershell
adb devices -l
```

If zero or multiple usable devices are present, ask the user which serial number to use. Store it in a task-specific variable and pass `-s` on every ADB command:

```powershell
$deviceSerial = "<confirmed-serial>"
adb -s $deviceSerial get-state
```

Do not use an unqualified `adb install`, `adb shell`, `adb reboot`, or port-forward command.

## 4. libxposed Architecture Contract

### 4.1 API 101 Entry and Metadata

This project uses the modern libxposed entry point:

- `META-INF/xposed/java_init.list` points to `io.github.nitsuya.aa.display.xposed.LibXposedInit`.
- `module.prop` declares API 101, `staticScope=true`, and `exceptionMode=protective`.
- `scope.list` defines the packaged static scope.

Do not add the legacy `assets/xposed_init` entry or restore legacy callback-driven initialization. New hooks must flow through `LibXposedInit`, `XposedRuntimeContext`, and the existing `BaseHook` / `AaHook` abstractions.

Any entry-point, scope, packaging, or shrinker change must be validated against the built APK, not only the source tree. Keep the Gradle metadata verification task passing.

### 4.2 Process Boundaries

Current primary targets are:

| Target | Process / callback | Owner |
| --- | --- | --- |
| Android framework | `system_server` via `onSystemServerStarting` | `AndroidHook` |
| Android Auto main | `com.google.android.projection.gearhead` | Process-filtered `AaHook` implementations |
| Android Auto projection | `com.google.android.projection.gearhead:projection` | `AaBasicsHook`, `AaBtnEventHook`, `AaUiHook`, or other explicitly supporting hooks |
| Android Auto car | `com.google.android.projection.gearhead:car` | `AaSignatureHook`, `AaDpiHook`, or other explicitly supporting hooks |
| Other scoped launchers/apps | Package-ready lifecycle | `OtherHook` |

Use both package and process checks. A package can host several processes with different classes and lifecycles. Never assume `packageName == processName`.

`AndroidAuoHook` is an established source identifier despite the typo. Renaming it is a compatibility/refactor task and must not be bundled into unrelated work.

### 4.3 Scope Changes Are Security-Sensitive

The static scope is stored in `aa-display/src/main/resources/META-INF/xposed/scope.list`. Adding a package injects module code into that package and increases crash, performance, and privacy exposure.

For every scope change:

- justify why injection is required instead of IPC or ordinary Android APIs;
- add an exact package/process gate in code;
- avoid broad or speculative scopes;
- document the required LSPosed scope selection for the user;
- rebuild and inspect the APK metadata;
- reinstall, then follow the mandatory reboot pause.

### 4.4 Framework Capabilities and Configuration

Do not assume every compatible framework exposes every capability.

- Check `PROP_CAP_SYSTEM` before system-server initialization.
- Check `PROP_CAP_REMOTE` before using remote preferences.
- Preserve the existing safe-default behavior when remote preferences are unavailable.
- Settings are written locally under `AADisplayConfig.ConfigName` and synchronized through `XposedConfigSync`; injected processes read them through `RemoteConfigProvider`.
- When adding or changing a setting, update the preference UI, typed config access, synchronization expectations, hook consumer, and default behavior together.
- Configuration reads in injected processes must remain safe during framework service death, first boot, and partial initialization.

## 5. Hook Engineering Standards

### 5.1 Class Loading and Member Discovery

- Resolve target classes with the injected process class loader from `XposedRuntimeContext`; do not default to the module application's class loader.
- Prefer structural predicates: method name plus parameter count, parameter types, return type, modifiers, or stable call-site evidence.
- Do not select an overload by name alone when more than one can exist.
- Treat class names, method signatures, fields, resource names, enum constants, and argument indexes in Android/Android Auto internals as version-dependent.
- Use DexKit only when reflection against stable structure is insufficient. Keep searches narrow, run them after the target APK/application is available, close the bridge, and log discovery time and failure.
- If a symbol is optional, isolate its discovery in `runCatching` and allow unrelated hooks to continue.
- If a symbol is required for a feature, disable only that feature and emit one actionable diagnostic rather than guessing another member.

When supporting multiple implementations, make branches explicit by SDK, target-package version, ROM family, or verified structure. Avoid catch-all fallbacks that can hook the wrong method.

### 5.2 Lifecycle, Idempotence, and Hook Handles

- Initialize a hook once per process/package/system-server tuple. Preserve `HookRuntime.markHookInitialized` behavior.
- Register application-dependent hooks only after the target `Application` and its class loader are ready.
- Retain and unhook temporary bootstrap hooks after they have served their purpose.
- Do not use global mutable state without accounting for process lifetime, duplicate callbacks, Binder death, and concurrency.
- Avoid holding target `Activity`, `View`, or short-lived `Context` instances in process-wide singletons. Prefer application context where valid.
- Make initialization order explicit when one hook provides a service or object consumed by another.

### 5.3 Interception Semantics

Use the wrappers in `XposedRuntimeContext` so hook priority and protective exception behavior remain consistent.

- `hookBefore`: modify only validated arguments; return early only when the replacement contract is fully known.
- `hookAfter`: preserve the original result unless the feature explicitly requires a change and the result type has been checked.
- `hookReplace`: highest risk; use only when before/after interception cannot express the behavior.
- Call original behavior exactly once unless intentionally returning early or replacing it.
- Never turn an unexpected null, cast failure, or new enum value into a target-process crash.

Before mutating a hook result, reason about callers that expect object identity, nullability, exceptions, thread affinity, and Binder parcelability.

### 5.4 Performance and Threading

Hook callbacks may run on the system-server main thread, Binder threads, Android Auto UI thread, or startup-critical paths.

- Keep hot-path callbacks allocation-light and non-blocking.
- Do not perform disk I/O, network I/O, shell commands, full DexKit scans, or long Binder calls in a hot callback.
- Cache only data whose lifetime and invalidation rules are understood.
- Use thread-safe collections or synchronization for state touched by multiple callbacks.
- Marshal UI work to the correct main thread.
- Never block `system_server` waiting for the module app.

### 5.5 Hidden APIs, Stubs, and Dependencies

- `lib-stub` and `dev.rikka.hidden:stub` are `compileOnly`. They describe platform APIs but must not supply runtime classes.
- `io.github.libxposed:api` must remain `compileOnly`; the framework supplies it at runtime.
- Keep libxposed service code and other genuine runtime dependencies as deliberately configured.
- Do not move Android framework or target-app classes into `implementation` to fix a runtime linkage error.
- When adding a hidden API stub, match the platform signature required by the supported SDK path and keep the stub minimal.
- Inspect the final APK if dependency or packaging changes could introduce duplicate framework/Xposed classes.

### 5.6 Binder and Privileged Services

Changes to `BridgeService`, `CoreManagerService`, AIDL, exported components, shell execution, task launching, or virtual-display ownership cross a security boundary.

- Validate caller UID/package and Binder availability where the protocol permits it.
- Keep transaction codes, descriptors, AIDL signatures, and nullability compatible across both ends.
- Handle dead binders, process restarts, missing services, and version skew.
- Do not widen exported components or privileged command surfaces without an explicit requirement and threat review.
- Never log Binder payloads, shell content, app data, credentials, tokens, or other sensitive user information.

### 5.7 Resources, Shrinking, and Obfuscation

Both debug and release variants are minified and resource-shrunk.

- Keep reflectively loaded entry points, models, constructors, fields, and resources through precise R8 rules when necessary.
- Do not disable shrinking as the default fix.
- Resource IDs from Android Auto are target-package resources; resolve them against the target context and handle `0` as not found.
- Preserve the reserved package-ID configuration unless a deliberate migration has been designed and device-tested.
- Build the minified debug APK after changes involving reflection, serialization, AIDL, native libraries, resources, or entry points.

## 6. Logging and Diagnostics

Use the existing logging adapters:

- injected processes: `io.github.nitsuya.aa.display.xposed.log(...)` / `XposedLogAdapter`;
- ordinary app code: `AADisplayLogger` or Android logging patterns already used by the owning component.

Logs should identify the hook, package/process, lifecycle phase, discovery decision, and fallback. Avoid noisy per-frame, per-motion-event, or per-Binder-call logging in production paths.

Useful read-only diagnostics, after confirming the device serial:

```powershell
$deviceSerial = "<confirmed-serial>"
adb -s $deviceSerial shell getprop ro.build.version.sdk
adb -s $deviceSerial shell dumpsys package com.google.android.projection.gearhead | Select-String "versionName|versionCode"
adb -s $deviceSerial logcat -d -v threadtime | Select-String "AAD_|AADisplay|libxposed|LSPosed|AndroidRuntime"
```

For crash diagnosis, capture the first relevant exception and its full `Caused by` chain, the process name, device SDK/ROM, Android Auto version, LSPosed/libxposed version, enabled scope, and exact APK build. Do not infer a root cause from a later cascading exception.

Do not clear logcat, delete LSPosed logs, force-stop processes, disable modules, or change device settings unless the user asks or approves it.

## 7. Build and Static Validation

Run commands from the repository root with the checked-in wrapper. On Windows/PowerShell:

```powershell
.\gradlew.bat :aa-display:verifyDebugXposedMetadata
```

This is the minimum build check for module changes. It builds the minified debug APK and verifies that the required API 101 metadata entries are packaged.

Add checks according to the changed surface:

```powershell
# Kotlin/Java/Android lint; lint is currently non-blocking in Gradle, so inspect its report/output.
.\gradlew.bat :aa-display:lintDebug

# JVM unit tests when present or added.
.\gradlew.bat :aa-display:testDebugUnitTest

# Clean rebuild for packaging, R8, generated-source, or stale-output concerns.
.\gradlew.bat clean :aa-display:verifyDebugXposedMetadata
```

There is currently no established automated test suite in the repository. Do not describe a build-only check as comprehensive testing. New pure logic—signature selection, configuration parsing, compatibility branching, or Binder-independent transformations—should receive unit tests when practical. Device-only hook behavior requires device validation.

Before handing off any change:

```powershell
git diff --check
git status --short
git diff -- AGENTS.md aa-display lib-stub version.conf .github
```

Adjust the final diff command to the files in scope. Report which checks ran, their result, and what could not be tested.

## 8. Device Deployment and Validation

### 8.1 Install a Confirmed Debug APK

Build first, confirm the target serial, and resolve the actual APK path instead of guessing a stale artifact:

```powershell
.\gradlew.bat :aa-display:verifyDebugXposedMetadata
$deviceSerial = "<confirmed-serial>"
$apk = Get-ChildItem "aa-display\build\outputs\apk\debug\*.apk" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
adb -s $deviceSerial install -r $apk.FullName
```

After `adb install` succeeds, apply the mandatory reboot pause in section 3.3. Do not continue until the user confirms reboot and reconnection.

### 8.2 Post-Reboot Smoke Test

After confirmation:

1. Re-run `adb devices -l` and verify the same serial is online.
2. Confirm the module is enabled in LSPosed and scoped to the required targets—normally **System Framework** and **Android Auto**, plus only explicitly supported optional apps.
3. Confirm `AADisplay_LibXposedInit` loaded in expected processes and no startup exception occurred.
4. Launch the module settings and verify changed configuration persists and synchronizes.
5. Connect Android Auto or DHU and exercise the smallest scenario covering the change.
6. Verify original Android Auto behavior still works when the feature is disabled or its target cannot be discovered.
7. Check `system_server`, Android Auto, and the module app for crashes or repeated hook failures.

For framework-hook changes, also test a normal phone interaction unrelated to AADisplay. For display/input changes, cover creation, rotation/configuration changes, touch or key routing, task launch, and teardown as applicable.

### 8.3 Local Android Auto USB Emulation (DHU)

Use DHU only after the device is connected by a USB **data** cable, the serial is confirmed, and any required post-install reboot has completed:

```powershell
$deviceSerial = "<confirmed-serial>"
adb -s $deviceSerial forward --remove tcp:5277 2>$null
adb -s $deviceSerial forward tcp:5277 tcp:5277 | Out-Null
$dhuPath = Join-Path $env:LOCALAPPDATA "Android\Sdk\extras\google\auto\desktop-head-unit.exe"
if (-not (Test-Path -LiteralPath $dhuPath)) {
    throw "Android Auto DHU was not found at $dhuPath"
}
& $dhuPath
```

If DHU fails, verify the phone-side Android Auto developer server is enabled, the forwarding belongs to the confirmed device, port 5277 is not stale, and the executable exists. Do not install SDK components or change Android Auto developer settings without user approval.

## 9. Compatibility and Regression Matrix

Choose cases based on the touched code rather than claiming universal coverage.

| Change | Minimum evidence |
| --- | --- |
| Module metadata, entry point, scope, or R8 | Clean APK build, metadata verification, install, reboot, expected-process load logs |
| `system_server` hook | Relevant Android SDK/ROM, boot stability, hook success/fallback logs, unrelated framework smoke test |
| Android Auto reflection/DexKit hook | Exact Android Auto version, each affected process, discovery success and missing-symbol fallback |
| Remote preferences | Fresh process start, setting update, service unavailable/death fallback, reboot persistence |
| Binder/AIDL/service | Both endpoints, reconnect/death path, invalid/unavailable service behavior |
| Virtual display/input/task flow | Create, render, input, rotate/configure, app switch, close/teardown |
| UI/resource injection | Supported layout variants, missing resources, LHD/RHD or portrait/landscape when relevant |

If only one device/version is available, state that limitation. Do not convert a single successful DHU run into a broad compatibility claim.

## 10. Release Discipline

`version.conf` is release control, not a general build file. A push to `main` that changes it triggers the signed GitHub Actions release workflow.

- Change `VERSION_NAME`, `VERSION_CODE`, or `PRERELEASE` only when the user explicitly requests release preparation.
- `VERSION_CODE` must increase monotonically; `VERSION_NAME` must produce a new `v<VERSION_NAME>` tag.
- Never use local debug signing for a release claim.
- Never expose, print, copy into the repository, or request the contents of signing secrets.
- Do not manually create or mutate an immutable release outside the authorized CI workflow.
- Before an authorized release, run the debug validation ladder and ensure release packaging changes have CI-equivalent review.

The authoritative release build is produced by GitHub Actions with the configured keystore. A locally assembled APK is a validation artifact unless the user explicitly defines another distribution flow.

## 11. Definition of Done

A change is complete only when all applicable items are true:

- the implementation respects package, process, class-loader, lifecycle, and threading boundaries;
- discovery failures degrade safely and produce actionable, non-sensitive logs;
- configuration defaults and cross-process synchronization remain coherent;
- API 101 metadata and required R8/runtime classes are present in the built APK;
- relevant Gradle checks pass and `git diff --check` is clean;
- device installation, if performed, was followed by the required user-confirmed reboot;
- the smallest relevant post-reboot LSPosed/Android Auto/DHU scenario passes;
- limitations, untested versions, and residual risks are reported honestly;
- unrelated user work remains untouched;
- no commit, push, tag, device mutation, or release was performed beyond the user's authorization.

When handing off, summarize the behavior changed, files changed, validation performed, device/Android Auto versions tested, and any remaining compatibility risk.
