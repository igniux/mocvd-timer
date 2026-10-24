# MOCVD Process Monitor — Android App Specification v2

> **Confirmed v1.2 changes (19 October 2026):** Development resumed at the user's request. These override conflicting requirements below.
>
> - Remove the **Growth ready push notification**.
> - Restore the earlier simple ongoing process notification, with slightly larger, more readable remaining-time numbers.
> - Do not include the expandable activity/progress bar or the v1.1 expanded custom card.
> - Process color is optional. Omit it if it requires the expanded/custom layout; prioritize the simple notification and legible time.
> - After growth finishes, acknowledging completion should take one click, without a confirmation popup. Use wording such as **Process complete**.
> - After acknowledging completion, offer **Prepare next growth**. Preserve the reactor hold setting, skip all vacuum/atmosphere cycles, and wait for a separate **Start Growth** action. Start a fresh growth session with new measurement and completion reminders. Keep **Start full preparation** available for a new preparation sequence.

> **Confirmed v1.1 changes (17 October 2026):** The following supersede conflicting initial requirements below.
>
> - Measurement: notify **only at 690 seconds (11:30) after growth starts**. Remove the 390-second / five-minute pre-warning. Growth continues uninterrupted.
> - Vacuum/atmosphere expiry: play **two double beeps** (beep-beep … beep-beep) using alarm audio, with optional vibration. Do not post a separate stage-completion push notification. Keep the silent ongoing process card and manual transitions.
> - The editable reactor setting is the **hold time at the highest growth temperature**. The estimated total growth duration is **display-only**, calculated using the user-confirmed provisional rule `4500 + (reactorSeconds - 600)` seconds. It remains an estimate, not a verified physical model. No manual total-duration field or conversion-mode switch in the dialog.
> - Editing the hold time during growth retains the original growth start and fixed measurement deadline; reschedule undelivered completion reminders.
> - Put the current process name and a larger remaining-time display in the notification content. Match its content panel to stage colors; provide a system-managed live countdown and an animated activity line when expanded. Android/EMUI controls the surrounding notification header and layout.
> - Repeated growth runs skip preparation cycles. The **Prepare next growth** action was deferred in v1.1 and is included in v1.2 above.

## Project objective

Create a minimalist, offline Android application for timing MOCVD growth procedures and notifying the user when laboratory attention is required.

The application tracks six manually advanced vacuum/atmosphere stages followed by a longer growth period.

Use **Kotlin + Jetpack Compose** for the implementation.

The deliverable is an Android Studio project and an installable APK for personal use.

## 1. Process state machine

Implement the following process:

IDLE → VACUUM_1 → ATMOSPHERE_1 → VACUUM_2 → ATMOSPHERE_2 → VACUUM_3 → ATMOSPHERE_3 → GROWTH_READY → GROWTH_RUNNING → GROWTH_FINISHED

### Stage durations

- Vacuum: 110 seconds (1:50).
- Atmosphere: 47 seconds (0:47).
- Number of cycles: 3.
- Stage sequence: Vacuum → Atmosphere.
- Every transition requires manual confirmation.

### Manual transition behavior

When the current countdown reaches zero:

1. Play a notification sound and optionally vibrate.
2. Display "Stage timer completed."
3. Show a prominent "Start next stage" control with the next stage clearly identified.
4. Do NOT automatically begin the next countdown.
5. Remain in a waiting state until the user confirms the next stage.

The next stage's countdown begins when the confirmation button is pressed.

Allow a user to manually advance before a countdown reaches zero, but require explicit confirmation and distinguish an early manual transition from a completed timer.

After Atmosphere 3, show GROWTH_READY rather than automatically starting growth.

Do not interpret countdown expiry as confirmation that the physical reactor has reached vacuum or atmospheric pressure.

## 2. Growth mode

### Start

The growth-ready screen displays a prominent "Start Growth" button.

Pressing it records the growth start time and begins the countdown.

Default total growth duration: 4500 seconds (75 minutes).

### Measurement reminders

The measurement is scheduled relative to the recorded growth start time.

- At 390 seconds elapsed (6:30): "Measurement starts in 5 minutes."
- At 690 seconds elapsed (11:30): "Start measurement."

The measurement reminder is notification-only.

Do NOT:
- Pause the main growth timer.
- Require confirmation.
- Switch to another mandatory screen.
- Change the growth start time.

After the measurement reminder, continue displaying the main growth countdown.

### Growth finishing reminders

- Five minutes before estimated growth completion: "Growth finishes in 5 minutes."
- At estimated growth completion: "Estimated growth time finished."

Once the timer reaches zero, show GROWTH_FINISHED.

Include a manual confirmation or dismissal control and avoid implying that the reactor has physically completed its procedure.

## 3. Editable reactor time

The user has a reactor parameter representing the time set at the highest growth temperature.

Reference values:
- Reactor setting: 600 seconds.
- Observed or expected overall growth duration: approximately 4500 seconds.

**Important: The exact relationship between reactor setting and total growth duration has not yet been verified.**

Do not hard-code an unverified physical relationship as fact.

### Initial implementation

Create a timing configuration with:

- Reactor time setting: 600 seconds by default.
- Estimated total growth duration: 4500 seconds by default.
- Editable estimated total growth duration.
- A configurable reactor-to-total-duration conversion rule.

Provide two possible modes:

**Mode A — Manual duration**

Changing the reactor parameter does not automatically alter the total growth timer. The user enters the new estimated total duration explicitly.

**Mode B — Linear duration adjustment**

Use a provisional relationship:

totalGrowthSeconds = 4500 + (reactorSeconds - 600)

Clearly label this mode as an assumption, configurable by the user.

Default to Mode A until the relationship is confirmed.

When the estimated duration changes during growth:

1. Preserve the original growth start timestamp.
2. Recalculate the remaining growth time.
3. Recalculate the five-minute completion reminder.
4. Reschedule outstanding notifications.
5. Do not duplicate previously delivered reminders.
6. Do not change the measurement deadline, which remains 11:30 after growth start.

If the newly calculated completion time has already passed, show a clear overdue state rather than silently treating the reactor as completed.

Provide simple editing with numeric input in seconds and optional +/- adjustment buttons.

## 4. Interface design

Create an immersive, minimalist, nearly full-screen interface.

### Visual elements

- Dominant animated background color.
- Extremely large remaining-time display.
- Current process name.
- Cycle number when relevant.
- Minimal controls.
- Subtle animated gradients or progress rings.
- Smooth transitions.
- High-contrast typography.

Suggested colors:
- Vacuum: dark blue/cyan.
- Atmosphere: amber.
- Growth ready: subdued green.
- Growth running: emerald green.
- Growth finished: violet.

Use seconds-resolution countdowns.

### Vacuum/atmosphere screen

Display:
- Current stage name.
- Current cycle, e.g. "Cycle 2 / 3".
- Remaining stage time.
- Progress indication.
- Next stage.
- Pause and reset controls as appropriate.

At zero, retain the completed stage screen and show the manual next-stage action.

### Growth screen

Display:
- "GROWTH".
- Large growth time remaining.
- Reactor parameter setting.
- Next upcoming milestone.
- Subtle overall progress visualization.
- Access to duration editing.

Allow editing without resetting the growth session.

The measurement notification must not obscure or replace the primary growth timer unnecessarily.

### Completion screen

Show estimated growth completion and clearly distinguish it from confirmed physical reactor completion.

## 5. Android notifications and background behavior

Implement reliable scheduling using Android's supported alarm and notification APIs.

Required notifications:
- Vacuum timer completed.
- Atmosphere timer completed.
- Growth ready.
- Measurement pre-reminder.
- Measurement start.
- Growth pre-completion reminder.
- Estimated growth completion.

Create appropriate notification channels with sound and vibration options.

Request necessary permissions and guide the user to system settings when exact alarms are unavailable.

Use persistent notification visibility during an active process.

Example:

MOCVD — Growth  
Remaining: 00:42:15  
Next event: Estimated growth completion

Tapping the notification opens the current process screen.

The notification must not incorrectly imply a new physical stage has started.

## 6. Timing and persistence

Use a monotonic clock for elapsed-time calculations while the device remains booted.

Persist:
- Current stage.
- Cycle number.
- Stage start timestamp.
- Growth start timestamp.
- Growth duration.
- Reactor setting.
- Timing conversion mode.
- Scheduled reminders.
- Delivered reminder identifiers.
- App preferences.

Handle:
- Locked-screen operation.
- App process termination.
- App relaunch.
- Device reboot.
- Changed system clock.
- Missing notification permissions.
- Exact-alarm permission revocation.
- Overdue timers.
- User corrections.

Keep timing calculations independent of Compose UI rendering.

Use native Android alarm scheduling for critical reminders.

If Android prevents reliable scheduling, make this limitation visible in the application.

## 7. User controls

Support:
- Start process.
- Start next stage manually.
- Pause/resume a stage timer.
- Edit growth-duration estimate.
- Edit reactor parameter.
- Restart current stage.
- Reset entire session with confirmation.
- Test alarm.
- Toggle vibration and sound.
- Optional keep-screen-awake mode.

During a growth run, pausing or resetting must never be performed accidentally.

Distinguish pausing an app timer from pausing the physical reactor process.

## 8. Testing requirements

Implement unit tests and manual test instructions covering:

1. Correct vacuum → atmosphere ordering.
2. Three complete cycles.
3. No automatic transitions.
4. Stage-completion notification.
5. Waiting indefinitely for manual stage confirmation.
6. Growth starting only after user action.
7. Measurement warning at 6:30 elapsed.
8. Measurement notification at 11:30 elapsed.
9. Growth remaining active after measurement notification.
10. Editing growth duration before measurement.
11. Editing growth duration after measurement.
12. Rescheduling completion reminders.
13. Avoiding duplicate or stale notifications.
14. Background and locked-screen operation.
15. Process termination and application relaunch.
16. Reboot recovery.
17. Overdue and missed reminders.
18. Permission-denied behavior.

Provide a testing mode with accelerated countdowns.

## 9. Development deliverables

- Complete Kotlin Android Studio project.
- Jetpack Compose source code.
- Process-state and timing engine.
- Android alarm scheduling.
- Notification management.
- Persistent state storage.
- Settings and timer adjustment interface.
- Automated unit tests.
- Locally installable debug APK.
- README with build and installation instructions.

## 10. Development priorities

Implement in this order:

**Phase 1:** Core timer engine and manual stage transitions.

**Phase 2:** Growth mode, measurement reminders, adjustable duration.

**Phase 3:** Background scheduling and notification reliability.

**Phase 4:** Persistent session recovery and permissions.

**Phase 5:** Polished minimalist interface and animations.

**Phase 6:** Automated tests, device tests, and APK packaging.

Do not treat mobile reminders as a substitute for mandatory reactor monitoring, equipment alarms, or laboratory safety procedures.
