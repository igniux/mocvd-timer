# Acceptance testing

Run accelerated tests only as simulations, without relying on them for a live laboratory procedure. Return to normal speed before real use. Android 16 / OnePlus Nord 5 hardware checks remain required when the phone is available.

## Core workflow

| Check | Procedure | Expected result |
| --- | --- | --- |
| Stage ordering / three cycles | Start and manually advance six stages | V1 → A1 → V2 → A2 → V3 → A3 → Growth Ready |
| No automatic transition | Let V1 reach zero; wait another minute | V1 remains completed; no A1 countdown until tapped |
| Stage alarm | Enable sound and exact alarms; expire a stage | Two double beeps on alarm volume, optional vibration; no separate push notification; ongoing card shows waiting |
| Early transition | Tap Advance early, cancel, then confirm | Cancel preserves stage; confirm starts next stage and records early advance |
| Pause / resume | Pause a stage, wait, resume | Remaining time stays fixed while paused; reactor operation disclaimer is visible |
| Restart | Restart an active/completed stage and confirm | Full stage duration restored; old alarm does not complete the restarted timer |
| Growth readiness | Finish all six stages and continue | Growth Ready waits silently, with no ready push; only Start Growth starts growth |
| No early measurement warning | In simulation, wait 13 real seconds after Start Growth | No measurement notification; growth continues |
| Measurement start | Wait 23 real seconds after Start Growth | Start measurement notification; growth remains active |
| Completion warning / finish | Default simulation: 140 / 150 real seconds | Five-minute warning, then estimated completion; manual dismissal is available |
| One-click completion | Tap Process complete after growth ends | No confirmation popup; reminders cleared; Prepare next growth appears |
| Repeat growth | Tap Prepare next growth, edit hold if needed, then Start Growth | No preparation cycles; retained hold; manual start with fresh measurement/completion deadlines and no previous reminder IDs |
| Full preparation after growth | After Process complete, tap Start full preparation | Starts Vacuum 1 with a full timer |
| Reactor edit before measurement | Increase the hold from 600 to 900 seconds | Display-only total becomes 4800 s; growth start and measurement deadline unchanged |
| Reactor edit after measurement | Increase hold after 11:30 elapsed | Measurement is not repeated; completion reminders shift |
| Shorten into the past | After more than 65 elapsed minutes, reduce hold enough to put the derived estimate in the past | Explicit overdue estimate, no assertion of physical completion |
| Reactor-only dialog | Open timing settings and inspect inputs | Only peak-temperature hold is editable; total is displayed; no manual-mode or conversion-factor controls |
| Reset | Reset, cancel once, then confirm | Cancel retains session; confirm cancels current and future reminders |
| Test stage alarm | Use Settings → Test stage alarm | Hear beep-beep, pause, beep-beep using alarm volume; process unchanged; no push notification |
| Test growth notification | Use Settings → Test growth notification | Notification follows chosen sound/vibration channel; process unchanged |
| Ongoing notification | View the status notification during each stage | Compact neutral row, process name and larger bold live countdown; no colored card, expanded content panel, or activity/progress bar; paused/completed stages show a static time |

## Android background and recovery

1. **Permissions denied:** fresh install, deny notifications and exact access. Verify the setup banner and working foreground timers. Preparation beeps do not need notification permission; confirm they work with notification access denied, alarm volume enabled, and exact alarms granted. Growth notifications do require notification access. Revoke exact access; reopen and verify the warning and saved timer.
2. **Channel blocked:** block the selected reminder channel in Android notification settings. Reopen; setup banner must appear. Restore the channel and use Test alarm.
3. **Locked screen:** start a stage, lock the device, and wait. Hear two double beeps; verify no stage push and the correct waiting state on unlock. Repeat measurement and completion reminders during a locked growth session.
4. **Normal process termination:** start a timer, press Home, then run `adb shell am kill lt.ignas.mocvd`. Do not use `force-stop` for this check. Verify native alarm delivery restarts the process, posts one notice, and restores the stage. Relaunch and verify no countdown restart.
5. **Reboot:** start a normal-speed stage/growth timer and reboot. After unlocking, verify a recovery notification and explicit review screen. Confirm no critical reminders resume until the recovered estimate is accepted. Test both a future and an overdue recovered deadline.
6. **Changed wall clock:** during an active timer on the same boot, change device time (disable automatic time first if needed). Remaining elapsed time must not jump. The next system alarm must be rescheduled to the new wall-clock equivalent. Restore automatic time afterward.
7. **Delayed/missed reminders:** reopen after multiple deadlines have passed. Estimated completion supersedes a stale completion pre-warning; no obsolete measurement pre-warning is scheduled. The current preparation stage must not automatically advance.
8. **Stale intent / duration edit:** extend growth before its old finish time. Leave the app. At the old deadline, verify there is no obsolete completion notification; verify the new completion time.
9. **Force stop:** force-stop through Android settings, then reopen. Verify recovery of elapsed/overdue state. Alarms during the force-stopped interval are not guaranteed by Android.
10. **OnePlus Nord 5 / Android 16:** repeat alarm checks on battery, in power-saving mode, with screen locked for the full growth duration. Inspect the app's battery/background settings if needed. Check notification permission, sound volume, selected channel, vibration, and DND. An emulator does not establish device-specific reliability.

Useful commands (add `-s emulator-5554` after `adb` to select a specific emulator):

```powershell
adb devices
adb install -r app\build-v1.2.0\outputs\apk\debug\app-debug.apk
adb shell am start -n lt.ignas.mocvd/.MainActivity
adb shell am kill lt.ignas.mocvd
adb shell dumpsys alarm
adb shell dumpsys notification
adb shell run-as lt.ignas.mocvd cat files/mocvd-session.json
```

The last command reads debug-app state for verification; it is unavailable for a production-signed non-debuggable app. Keep test reports separate from laboratory records.
