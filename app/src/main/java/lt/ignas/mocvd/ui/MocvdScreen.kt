package lt.ignas.mocvd.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import lt.ignas.mocvd.AppState
import lt.ignas.mocvd.TimerController
import lt.ignas.mocvd.domain.*
import kotlin.math.*

private val Ink = Color(0xFFF0F7F6)
private val Muted = Color(0xFFB3C9CA)
private val Teal = Color(0xFF77E6D5)
private val Panel = Color(0xFF102D35)

private data class Palette(val base: Color, val glow: Color, val accent: Color)
private fun palette(stage: Stage) = when {
    stage == Stage.GROWTH_FINISHED -> Palette(Color(0xFF160E2B), Color(0xFF503074), Color(0xFFD3B1FF))
    stage == Stage.GROWTH_READY -> Palette(Color(0xFF09241F), Color(0xFF214E3E), Color(0xFFA1D8A9))
    stage == Stage.GROWTH_RUNNING -> Palette(Color(0xFF032B24), Color(0xFF08634C), Color(0xFF8EF0C2))
    stage.isPreparation && !stage.isVacuum -> Palette(Color(0xFF2A1B0B), Color(0xFF694219), Color(0xFFFFD08A))
    else -> Palette(Color(0xFF061B24), Color(0xFF124B60), Color(0xFF77DFED))
}

@Composable
fun MocvdScreen(state: AppState, controller: TimerController, onNotifications: () -> Unit, onExactAlarms: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Teal, onPrimary = Color(0xFF062A2C),
        background = Color(0xFF061B24), surface = Panel, onSurface = Ink, onBackground = Ink,
        secondary = Teal, surfaceVariant = Color(0xFF193940), onSurfaceVariant = Muted)) {
        val session = state.saved.session
        var now by remember { mutableStateOf(controller.clock.read()) }
        LaunchedEffect(Unit) { while (true) { now = controller.clock.read(); delay(200) } }
        var settings by remember { mutableStateOf(false) }
        var editor by remember { mutableStateOf(false) }
        var setup by remember { mutableStateOf(false) }
        var confirmation by remember { mutableStateOf<String?>(null) }
        val colors = palette(session.stage)
        val base by animateColorAsState(colors.base, tween(900), label = "stage background")
        val glow by animateColorAsState(colors.glow, tween(900), label = "stage glow")
        val accent by animateColorAsState(colors.accent, tween(600), label = "stage accent")
        val motion = rememberInfiniteTransition(label = "ambient motion")
        val drift by motion.animateFloat(0.35f, 0.65f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")

        Box(Modifier.fillMaxSize().background(base)) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.radialGradient(listOf(glow.copy(alpha = .75f), Color.Transparent),
                    center = Offset(size.width * drift, size.height * .39f), radius = size.height * .66f))
                for (i in 1..6) drawLine(Ink.copy(alpha = .018f), Offset(size.width * i / 7, 0f),
                    Offset(size.width * i / 7, size.height), strokeWidth = 1f)
            }
            if (!state.loaded) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = accent)
            } else BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val compact = maxHeight < 690.dp
                Column(Modifier.align(Alignment.TopCenter).widthIn(max = 560.dp).fillMaxHeight()
                    .padding(horizontal = 24.dp, vertical = if (compact) 10.dp else 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Canvas(Modifier.size(22.dp)) {
                                drawCircle(accent, style = Stroke(2.dp.toPx()))
                                drawLine(accent, center, Offset(center.x, size.height * .24f), 2.dp.toPx(), StrokeCap.Round)
                                drawLine(accent, center, Offset(size.width * .72f, size.height * .62f), 2.dp.toPx(), StrokeCap.Round)
                            }
                            Column {
                                Text("MOCVD", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 3.sp)
                                Text("PROCESS TIMER", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
                            }
                        }
                        OutlinedButton(onClick = { settings = true }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink), border = ButtonDefaults.outlinedButtonBorder(enabled = true)) {
                            Text("Settings", fontSize = 12.sp)
                        }
                    }
                    if (session.speed > 1 || (!session.isActive && state.saved.preferences.simulation)) {
                        Spacer(Modifier.height(8.dp))
                        Pill("SIMULATION  ·  30× SPEED", Color(0xFFFFD08A))
                    }
                    if (!state.exactAlarms || !state.notifications || state.alarmError != null) {
                        TextButton(onClick = { setup = true }, modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(4.dp)) {
                            Text("●  Alerts need setup — tap to review", color = Color(0xFFFFD08A), fontSize = 12.sp)
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Spacer(Modifier.height(if (compact) 10.dp else 22.dp))
                        Text(when {
                            session.stage.isPreparation -> "PREPARATION  /  CYCLE ${session.stage.cycle} OF 3"
                            session.isGrowth -> "GROWTH SESSION"
                            session.stage == Stage.GROWTH_READY -> "PREPARATION COMPLETE"
                            else -> "YOUR LAB, IN TIME"
                        }, color = accent, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(12.dp))
                        Text(when (session.stage) {
                            Stage.IDLE -> if (session.readyForNextGrowth) "Process complete" else "A little more focus."
                            Stage.GROWTH_FINISHED -> "Process complete"
                            else -> session.stage.title
                        }, color = Ink, fontSize = if (compact) 28.sp else 34.sp,
                            fontWeight = FontWeight.Light, textAlign = TextAlign.Center)
                        val displaySeconds = when (session.stage) {
                            Stage.IDLE -> if (session.readyForNextGrowth) session.config.growthSeconds else 110L
                            Stage.GROWTH_READY -> session.config.growthSeconds
                            else -> session.remainingSeconds(now).coerceAtLeast(0)
                        }
                        val fraction = when {
                            session.stage == Stage.IDLE || session.stage == Stage.GROWTH_READY -> 0f
                            else -> (1.0 - session.remainingMillis(now).coerceAtLeast(0).toDouble() / (session.logicalDuration * 1000)).coerceIn(0.0, 1.0).toFloat()
                        }
                        TimerDial(displaySeconds, fraction, accent, if (compact) 260 else 310,
                            when {
                                session.recoveryRequired -> "REVIEW RECOVERED TIME"
                                session.phase == Phase.PAUSED -> "APP TIMER PAUSED"
                                session.stage == Stage.IDLE -> if (session.readyForNextGrowth) "NEXT GROWTH ESTIMATE" else "FIRST VACUUM STAGE"
                                session.stage == Stage.GROWTH_READY -> "ESTIMATED DURATION"
                                session.phase == Phase.WAITING -> "TIMER COMPLETED"
                                else -> "REMAINING"
                            })
                        if (session.stage.isPreparation) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                (1..6).forEach { index -> Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape)
                                        .background(if (index <= session.stage.ordinal) accent else Ink.copy(alpha = .12f)))
                                    Spacer(Modifier.height(7.dp))
                                    Text(if (index % 2 == 1) "V${(index + 1) / 2}" else "A${index / 2}",
                                        color = if (index == session.stage.ordinal) Ink else Muted.copy(alpha = .6f), fontSize = 10.sp)
                                } }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        if (session.recoveryRequired) InfoCard("REVIEW REQUIRED",
                            "Device restarted. This timing was reconstructed from the saved clock time. Check the estimate before continuing alerts.", accent)
                        else when {
                            session.stage == Stage.IDLE && session.readyForNextGrowth -> InfoCard("NEXT GROWTH",
                                "Prepare another growth without repeating vacuum/atmosphere cycles. The timer starts only when you tap Start Growth.", accent)
                            session.stage == Stage.IDLE -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                Metric("3", "CYCLES"); Metric("1:50 / 0:47", "VACUUM / ATMOSPHERE"); Metric("${session.config.growthSeconds / 60} min", "GROWTH EST.")
                            }
                            session.stage.isPreparation -> InfoCard(
                                if (session.phase == Phase.WAITING) "STAGE TIMER COMPLETED" else "UP NEXT",
                                if (session.stage == Stage.ATMOSPHERE_3) "Growth ready · start growth separately"
                                else "${session.stage.next.title} · ${formatTime(session.stage.next.durationSeconds)}",
                                accent,
                            )
                            session.stage == Stage.GROWTH_READY -> InfoCard("YOU SET THE START", "Growth and measurement reminders begin only after you tap Start Growth.", accent)
                            session.stage == Stage.GROWTH_FINISHED -> InfoCard("ESTIMATED TIME ONLY",
                                "Check the reactor. Physical process completion is unconfirmed.${if (session.overdueByEdit) " The edited estimate is ${formatTime(-session.remainingSeconds(now))} overdue." else ""}", accent)
                            session.isGrowth -> {
                                val next = TimerEngine.pending(session).firstOrNull()
                                InfoCard("NEXT MILESTONE", next?.let {
                                    "${it.kind.message.removeSuffix(".")} · in ${formatTime(((it.atElapsed - now.elapsed) * session.speed / 1000).coerceAtLeast(0))}"
                                } ?: "All reminders handled", accent)
                            }
                        }
                        if (session.isGrowth || session.stage == Stage.GROWTH_READY) {
                            TextButton(onClick = { editor = true }) {
                                Text("Peak-temperature hold: ${session.config.reactorSeconds} s  ·  Edit", color = accent, fontSize = 12.sp)
                            }
                        }
                        if (session.lastAction.startsWith("Advanced early")) Text(session.lastAction,
                            Modifier.padding(top = 10.dp), color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(14.dp))
                    }
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        when {
                            state.storageFailure -> PrimaryAction("Reset unreadable session", accent) { confirmation = "reset" }
                            session.recoveryRequired -> PrimaryAction("Accept recovered estimate", accent) { controller.acceptRecovery() }
                            session.stage == Stage.IDLE && session.readyForNextGrowth -> PrimaryAction("Prepare next growth", accent) { controller.prepareNextGrowth() }
                            session.stage == Stage.IDLE -> PrimaryAction("Start process", accent) { controller.start() }
                            session.stage == Stage.GROWTH_READY -> PrimaryAction("Start Growth", accent) { controller.startGrowth() }
                            session.stage == Stage.GROWTH_FINISHED -> PrimaryAction("Process complete", accent) { controller.completeGrowth() }
                            session.stage.isPreparation && session.phase == Phase.WAITING -> PrimaryAction(
                                if (session.stage == Stage.ATMOSPHERE_3) "Continue to Growth Ready" else "Start next stage · ${session.stage.next.title}", accent) { controller.advance() }
                            session.stage.isPreparation -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = { if (session.phase == Phase.PAUSED) controller.resume() else controller.pause() },
                                    modifier = Modifier.weight(1f).height(54.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)) {
                                    Text(if (session.phase == Phase.PAUSED) "Resume timer" else "Pause timer")
                                }
                                Button(onClick = { confirmation = "early" }, modifier = Modifier.weight(1f).height(54.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = base)) { Text("Advance early") }
                            }
                            session.stage == Stage.GROWTH_RUNNING -> PrimaryAction("Adjust reactor hold time", accent) { editor = true }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            if (session.stage == Stage.IDLE && session.readyForNextGrowth) TextButton(onClick = { controller.start() }) {
                                Text("Start full preparation", color = Muted, fontSize = 12.sp)
                            }
                            if (session.stage.isPreparation) TextButton(onClick = { confirmation = "restart" }) { Text("Restart stage", color = Muted, fontSize = 12.sp) }
                            if (session.isActive) TextButton(onClick = { confirmation = "reset" }) { Text("Reset session", color = Muted, fontSize = 12.sp) }
                            else TextButton(onClick = { editor = true }) { Text("Configure timing", color = Muted, fontSize = 12.sp) }
                        }
                        Text(if (session.phase == Phase.PAUSED) "Pausing this timer does not pause the reactor."
                            else "A timing companion. Reactor operation remains manual.",
                            color = Muted.copy(alpha = .6f), fontSize = 10.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }

        if (settings) SettingsSheet(state, onClose = { settings = false }, onPreferences = controller::preferences,
            onEdit = { settings = false; editor = true }, onSetup = { settings = false; setup = true },
            onTest = controller::testAlarm, onTestStage = controller::testStageAlarm)
        if (editor) TimingDialog(session.config, session.isGrowth, onDismiss = { editor = false }, onSave = { controller.edit(it); editor = false })
        if (setup) AlertDialog(onDismissRequest = { setup = false }, title = { Text("Set up reminders") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Notification access: ${if (state.notifications) "enabled" else "needs attention"}")
                Text("Exact alarms: ${if (state.exactAlarms) "enabled" else "needs permission"}")
                state.alarmError?.let { Text(it, color = Color(0xFFFFD08A)) }
                Text("Growth notifications need notification access. Stage beeps use alarm audio and do not need notification permission. Exact alarms are needed for timely background alerts. Phone volume and battery settings still apply.", fontSize = 13.sp)
                OutlinedButton(onClick = onNotifications, modifier = Modifier.fillMaxWidth()) { Text("Notification access & settings") }
                OutlinedButton(onClick = onExactAlarms, modifier = Modifier.fillMaxWidth()) { Text("Alarms & reminders access") }
                Text("Test with the screen locked. On Huawei and OnePlus, review app battery/background settings if alerts are delayed.", fontSize = 12.sp, color = Muted)
            } }, confirmButton = { TextButton(onClick = { setup = false; controller.refresh() }) { Text("Done") } })
        confirmation?.let { action ->
            val (title, message) = when (action) {
                "early" -> "Advance before completion?" to "The current timer has not completed. Confirm that you want to advance manually. This will be recorded as an early transition."
                "restart" -> "Restart this stage timer?" to "The current countdown will start again from its full duration. Reactor operation is unchanged."
                else -> "Reset the entire session?" to "This clears the current app timer and all its reminders. The physical reactor process is unchanged."
            }
            AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(title) }, text = { Text(message) },
                confirmButton = { TextButton(onClick = {
                    when (action) { "early" -> controller.advance(true); "restart" -> controller.restart(); else -> controller.reset() }
                    confirmation = null
                }) { Text("Confirm") } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } })
        }
        state.error?.let { error -> AlertDialog(onDismissRequest = controller::dismissError, title = { Text("Timer needs attention") },
            text = { Text(error) }, confirmButton = { TextButton(onClick = controller::dismissError) { Text("OK") } }) }
    }
}

@Composable
private fun TimerDial(seconds: Long, progress: Float, accent: Color, diameter: Int, caption: String) {
    Box(Modifier.padding(top = 16.dp).size(diameter.dp).semantics { contentDescription = "Timer ${formatTime(seconds)}" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val ring = size.minDimension * .44f
            drawCircle(accent.copy(alpha = .08f), radius = ring, style = Stroke(1.dp.toPx()))
            drawCircle(accent.copy(alpha = .035f), radius = ring - 13.dp.toPx(), style = Stroke(1.dp.toPx()))
            for (index in 0 until 60) {
                val a = (index * 6 - 90) * PI / 180
                val outer = ring + 10.dp.toPx()
                val inner = outer - (if (index % 5 == 0) 6.dp.toPx() else 2.dp.toPx())
                drawLine(accent.copy(alpha = if (index % 5 == 0) .35f else .12f),
                    center + Offset((cos(a) * inner).toFloat(), (sin(a) * inner).toFloat()),
                    center + Offset((cos(a) * outer).toFloat(), (sin(a) * outer).toFloat()), strokeWidth = 1.dp.toPx())
            }
            if (progress > 0) drawArc(accent, -90f, progress * 360, false,
                topLeft = center - Offset(ring, ring), size = Size(ring * 2, ring * 2), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatTime(seconds), color = Ink, fontSize = if (seconds >= 3600) (diameter * .17f).sp else (diameter * .23f).sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light, letterSpacing = (-3).sp, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Text(caption, color = accent.copy(alpha = .8f), fontSize = 9.sp, letterSpacing = 1.7.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(text, Modifier.clip(CircleShape).background(color.copy(alpha = .1f)).padding(horizontal = 14.dp, vertical = 7.dp),
        color = color, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Metric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(5.dp))
        Text(label, color = Muted, fontSize = 7.sp, letterSpacing = .7.sp)
    }
}

@Composable
private fun InfoCard(label: String, text: String, accent: Color) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ink.copy(alpha = .035f))
        .border(1.dp, accent.copy(alpha = .1f), RoundedCornerShape(18.dp)).padding(17.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, color = accent, fontSize = 9.sp, letterSpacing = 1.4.sp)
        Text(text, color = Ink.copy(alpha = .9f), fontSize = 13.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun PrimaryAction(label: String, accent: Color, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF082A2D)), contentPadding = PaddingValues(14.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(state: AppState, onClose: () -> Unit, onPreferences: (Preferences) -> Unit,
                          onEdit: () -> Unit, onSetup: () -> Unit, onTest: () -> Unit, onTestStage: () -> Unit) {
    var prefs by remember { mutableStateOf(state.saved.preferences) }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Panel) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text("Make it yours.", fontSize = 28.sp, fontWeight = FontWeight.Light)
            Text("MOCVD TIMER  /  SETTINGS", Modifier.padding(top = 8.dp, bottom = 18.dp), color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
            fun update(p: Preferences) { prefs = p; onPreferences(p) }
            SettingSwitch("Sound", "Stage beeps use alarm volume; growth notices use notification sound.", prefs.sound) { update(prefs.copy(sound = it)) }
            SettingSwitch("Vibration", "A short vibration at each reminder.", prefs.vibration) { update(prefs.copy(vibration = it)) }
            SettingSwitch("Keep screen awake", "Only while this app is visible.", prefs.keepAwake) { update(prefs.copy(keepAwake = it)) }
            SettingSwitch("Accelerated testing", "30× faster. Change only between sessions.", prefs.simulation,
                enabled = !state.saved.session.isActive) { update(prefs.copy(simulation = it)) }
            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = Muted.copy(alpha = .15f))
            OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("Timing & reactor setting") }
            OutlinedButton(onClick = onSetup, modifier = Modifier.fillMaxWidth()) { Text("Reminder permissions") }
            Button(onClick = onTestStage, modifier = Modifier.fillMaxWidth()) { Text("Test stage alarm · two double beeps") }
            OutlinedButton(onClick = onTest, modifier = Modifier.fillMaxWidth()) { Text("Test growth notification") }
            Text("Offline · Stored on this device\nTimers do not verify pressure or reactor completion. Continue mandatory equipment monitoring and laboratory procedures.",
                Modifier.padding(top = 18.dp), color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 15.sp)
            Text(detail, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Switch(checked, onCheckedChange = onChecked, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun TimingDialog(config: TimingConfig, duringGrowth: Boolean, onDismiss: () -> Unit, onSave: (TimingConfig) -> Unit) {
    var reactor by remember { mutableStateOf(config.reactorSeconds.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    val calculated = runCatching { TimingConfig.forReactor(reactor.toLong()) }.getOrNull()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Timing & reactor setting") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Reactor setting is the time held at the highest growth temperature.", color = Muted, fontSize = 13.sp)
            SecondsInput("Peak-temperature hold (seconds)", reactor, onValue = { reactor = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { reactor = ((reactor.toLongOrNull() ?: 600) - 30).coerceAtLeast(0).toString() }) { Text("−30 s") }
                OutlinedButton(onClick = { reactor = ((reactor.toLongOrNull() ?: 600) + 30).coerceAtMost(600900).toString() }) { Text("+30 s") }
            }
            InfoCard("ESTIMATED TOTAL GROWTH TIME", calculated?.let {
                "${formatTime(it.growthSeconds)}  ·  ${it.growthSeconds} seconds"
            } ?: "Enter a valid reactor hold time", Teal)
            Text("Provisional estimate: 4500 + (reactor seconds − 600). The calculated total is display-only.", fontSize = 12.sp, color = Muted)
            if (duringGrowth) Text("Saving retains the original growth start and the 11:30 measurement deadline.", color = Muted, fontSize = 12.sp)
            if (duringGrowth && calculated != null && calculated.growthSeconds != config.growthSeconds) {
                Text("Current session estimate: ${formatTime(config.growthSeconds)}. Saving applies the calculated estimate above.", color = Muted, fontSize = 12.sp)
            }
            error?.let { Text(it, color = Color(0xFFFFB4AB), fontSize = 12.sp) }
        } },
        confirmButton = { TextButton(onClick = {
            runCatching { TimingConfig.forReactor(reactor.toLong()) }
                .onSuccess(onSave).onFailure { error = it.message ?: "Enter valid numeric values." }
        }) { Text("Save reactor setting") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun SecondsInput(label: String, value: String, enabled: Boolean = true, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValueChange = { if (it.length <= 7 && it.all(Char::isDigit)) onValue(it) },
        label = { Text(label, fontSize = 12.sp) }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}
