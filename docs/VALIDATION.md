# Validation record

## Version 1.2.0 — 19 October 2026

- Removed Growth Ready push events. A defensive notification guard suppresses legacy ready events, and status refresh removes an already-posted ready notification from an older build.
- Replaced the colored expanded notification with a neutral compact row: process name plus a bold 24 sp countdown. No separate expanded content or animated progress bar. Paused/completed states show a static clock with a state label.
- Process complete acknowledges a finished estimate immediately, clears reminders, and retains a persisted next-growth option. Prepare next growth skips preparation, preserves the hold, and waits for manual Start Growth. New session identity and empty delivery history ensure measurement/completion reminders run again.
- All 44 unit tests passed, including repeat-growth reminder timing, preservation of the hold, rejection of premature completion/preparation skipping, and backward-compatible persistence of next-growth availability.
- Seven UI checks passed on the connected OnePlus Nord 5 (CPH2709), Android 16: compact live-clock notification, silent Growth Ready, one-click completion with no confirmation dialog, next-growth manual start, reactor dialog, stage transitions, and retained measurement anchors. Six passed in the full run; the new completion-flow test passed on rerun after correcting its Compose button selector.
- Build and Android lint passed (zero errors, seven advisory warnings). The audio initialization fix from v1.1.1 is retained.
- Visually inspected the actual Nord 5 notification: process/cycle/completion label and readable clock, without an animated bar or separate expansion panel. The screenshot remains in local test evidence rather than the source package because it also contains another app's notification.
- Installed v1.2.0 in place on the phone. The existing waiting Vacuum 2 timer and saved preferences were backed up before UI testing and restored afterward.
- Current outputs: app/build-v1.2.0. Earlier protected output directories are left untouched.
- Full-duration locked-screen and manufacturer battery-policy checks are not repeated for this interface update.

## Version 1.1.1 — 19 October 2026

- Fixed the stage-alarm initialization error: a static AudioTrack may be successfully created in STATE_NO_STATIC_DATA; require STATE_INITIALIZED only after writing the PCM sound. Reference: https://developer.android.com/reference/android/media/AudioTrack.
- Built the app and test APKs, passed all 39 unit tests, and passed Android lint with no errors.
- Installed v1.1.1 as an in-place update from v1.1.0 on the connected OnePlus Nord 5 (CPH2709), Android 16.
- Ran StageAlarmPlaybackTest on the actual phone: two consecutive complete alarm patterns finished without playback/initialization errors; one instrumented test passed. This test does not reset the timer or change saved preferences.
- The user confirmed hearing both sets of double beeps on the Nord 5.
- Deferred notification-layout and one-click completion requirements remain pending; this release only fixes sound playback.
- Windows again prevented deletion of earlier generated test outputs. Current release outputs are under app/build-v1.1.1. No protected generated directories were deleted or had their permissions changed.
- Verification covers direct alarm playback. Locked-screen stage expiry and manufacturer battery behavior still require a separate real-device check.

## Version 1.1.0 — 17 October 2026

- **39 unit tests passed:** timer state, fixed 11:30 measurement deadline without an early warning, reactor-derived estimates, persistence compatibility, and the four-beep waveform with the required spacing.
- **6 Android tests passed:** manual stage completion and early-advance confirmation; no separate stage push notification; reactor dialog with exactly one editable field and a calculated total; original growth/measurement anchors retained after changing reactor hold; test reminder leaves state unchanged; expanded notification with a large running countdown and separate stage name.
- **Build and lint passed:** debug APK assembled; zero lint errors. Advisory dependency/target/backup warnings remain.
- **Signing:** v1.1 uses the same certificate and package ID as v1.0, supporting an in-place APK update.
- **Locked-screen/process-death check passed:** no early measurement warning; the measurement-start alert was not early and was delivered at its deadline; growth continued; completion warning and final notification arrived once each. Exactly three growth reminder IDs were recorded and the original growth start was unchanged.
- **Visual inspection:** the expanded system notification shows the green growth panel, a separate process label and a large live countdown. The reactor dialog shows one hold-time input and the display-only calculated estimate. Screenshots: `docs/screenshots/notification-v1.1.png` and `docs/screenshots/reactor-v1.1.png`.
- The previous generated resource directory was protected from deletion by Windows. This release builds into `app/build-v1.1`; the old `app/build` output is left untouched.
- Android tests used a temporary read-only Android 17 emulator on port 5556. A System UI startup hang initially blocked UI interaction; after restarting the test UI and reducing its resolution to 720×1600, all six checks passed. The test emulator is shut down after verification.
- Audio content/spacing is verified in the generated waveform. Audible output, alarm volume, EMUI notification appearance and Huawei battery behavior still require testing on the actual phone.

Current build reports: `app/build-v1.1/reports/`, `app/build-v1.1/test-results/`, and `app/build-v1.1/outputs/androidTest-results/`.

## Earlier v1.0 verification (historical behavior)

Date: **17 October 2026** (Europe/Vilnius).

## Environment

- Windows; Android Studio Rabbit 1 bundled JBR 25.
- Installed SDK Platform 37; Build Tools 36.0.0.
- Gradle 9.6.0; Android Gradle plugin 9.4.0.
- App target: **API 36 / Android 16**; minimum API 26.
- Device available for execution: **Medium Phone emulator, Android 17 / API 37**, x86_64, hardware acceleration through WHPX. This is the SDK image already installed on this PC.
- The OnePlus Nord 5 running Android 16 was unavailable. Hardware-specific behavior is not yet validated.

## Executed checks

| Check | Result |
| --- | --- |
| Debug Kotlin/Compose compilation and APK assembly | Passed |
| Pure timer engine tests | 32 passed |
| Persistence codec / relaunch tests | 3 passed |
| Android lint | 0 errors, 8 advisory warnings: version-update suggestions, the intentional Android 16 target, and a legacy-backup configuration advisory (backups are explicitly disabled) |
| Instrumented emulator smoke tests | 4 passed |
| Stage completion waits on the same screen | Passed |
| Early-transition dialog cancel and confirm | Passed |
| Growth estimate edit retains original start and measurement deadline | Passed |
| Test alarm leaves session unchanged | Passed |
| Actual UI inspection and screen captures | Idle, Vacuum, Atmosphere, Growth, reboot recovery inspected |
| Process death before a native measurement alarm | Passed; app process confirmed absent before the alarm |
| Screen-off / forced-idle simulation | Measurement warning and measurement start delivered; growth stayed running |
| Growth completion reminders | Both final-five-minute and estimated-completion notices delivered |
| Duplicate prevention in background fixture | Exactly four distinct growth reminder IDs; original monotonic start retained |
| Device reboot | Saved wall-clock estimate reconstructed; recovery review required; pending critical reminders empty until acceptance |
| Recovery acceptance through UI | Cleared review flag and restored outstanding reminders |
| Notifications and exact-alarm access denied | Setup banner appeared; foreground preparation timer remained usable; permissions restored after test |
| APK signature and permissions | Signature verified; compiled APK has no internet permission |

The background fixture uses a 900-second growth estimate at 30× speed, with fixed 390/690-second measurement offsets. The app is killed without setting Android's force-stopped flag. The final persisted state and notification/alarm diagnostics were inspected.

Unit regressions also cover all three preparation cycles, indefinite manual waiting, pause/resume, restarted-stage stale alarms, default 390/690/4200/4500-second milestones, pre/post-measurement edits, overdue edits, wall-clock changes, reboot reconstruction, missed-notification rescheduling, invalid input, and extending a previously finished estimate without repeating delivered notices.

## Reproducible commands

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -Command "& '.\tools\build.ps1' -Tasks @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug', ':app:connectedDebugAndroidTest')"
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\check-background.ps1
```

Gradle reports are under `app/build/reports/` and `app/build/outputs/androidTest-results/`. Local emulator diagnostics are under ignored `.tools/background-check/`; captured UI images are in `docs/screenshots/`.

## Still requires physical-device acceptance

Actual sound/vibration, OnePlus battery restrictions, a full normal-speed locked-screen run on Android 16, Do Not Disturb/channel settings, and hands-on laboratory usability. The emulator was launched without audio, so notification posting was verified rather than audible output. The manual checklist is in [TESTING.md](TESTING.md).
