package com.projectbharat.chalateraho

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.projectbharat.chalateraho.data.StepRepository
import com.projectbharat.chalateraho.data.StepSnapshot
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val Ink = Color(0xFF101713)
private val Panel = Color(0xFF1B2520)
private val PanelSoft = Color(0xFF24312A)
private val Mint = Color(0xFF9BE2B2)
private val Muted = Color(0xFF9BAAA0)
private val White = Color(0xFFF4F7F3)

private fun startStepTrackingSafely(context: android.content.Context) {
    try {
        ContextCompat.startForegroundService(
            context,
            Intent(context, StepTrackingService::class.java)
        )
    } catch (_: SecurityException) {
        // Sensor/foreground-service permission may be unavailable on this device.
    } catch (_: IllegalStateException) {
        // Android may reject a foreground-service start while the app is backgrounded.
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ChalateRahoApp() }
    }
}

@Composable
private fun ChalateRahoApp() {
    val context = LocalContext.current
    val repository = remember { StepRepository(context.applicationContext) }
    val snapshot by repository.snapshot.collectAsStateWithLifecycle(initialValue = StepSnapshot())
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) }
    var showGoalDialog by remember { mutableStateOf(false) }
    var goalText by remember(snapshot.goal) { mutableStateOf(snapshot.goal.toString()) }
    val sensorAvailable = remember {
        val manager = context.getSystemService(SensorManager::class.java)
        manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    }

    val permissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    var permissionGranted by remember {
        mutableStateOf(permissions.filter { it == Manifest.permission.ACTIVITY_RECOGNITION }
            .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionGranted = permissions.filter { it == Manifest.permission.ACTIVITY_RECOGNITION }
            .all { result[it] == true || ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (permissionGranted && sensorAvailable) {
            startStepTrackingSafely(context)
        }
    }

    LaunchedEffect(Unit) {
        if (permissions.isEmpty()) permissionGranted = true
        if (permissions.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(permissions)
        } else {
            permissionGranted = true
            if (sensorAvailable) ContextCompat.startForegroundService(context, Intent(context, StepTrackingService::class.java))
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Ink, surface = Panel, surfaceVariant = PanelSoft,
            primary = Mint, onPrimary = Ink, onBackground = White,
            onSurface = White, onSurfaceVariant = Muted, secondary = Color(0xFFF0C77A)
        )
    ) {
        Scaffold(
            containerColor = Ink,
            bottomBar = {
                NavigationBar(containerColor = Panel, contentColor = Muted) {
                    NavigationBarItem(
                        selected = selectedTab == 0, onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Rounded.DirectionsWalk, null) }, label = { Text("Today") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1, onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Rounded.Insights, null) }, label = { Text("Stats") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2, onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Rounded.Settings, null) }, label = { Text("Goals") }
                    )
                }
            }
        ) { padding ->
            when (selectedTab) {
                0 -> TodayScreen(snapshot, sensorAvailable, permissionGranted, Modifier.padding(padding))
                1 -> StatsScreen(snapshot, Modifier.padding(padding))
                else -> GoalsScreen(snapshot, onEdit = { showGoalDialog = true }, Modifier.padding(padding))
            }
        }

        if (showGoalDialog) {
            AlertDialog(
                onDismissRequest = { showGoalDialog = false },
                title = { Text("Daily step goal") },
                text = {
                    OutlinedTextField(
                        value = goalText, onValueChange = { goalText = it.filter(Char::isDigit).take(5) },
                        label = { Text("Steps") }, singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        goalText.toIntOrNull()?.let { scope.launch { repository.setGoal(it) } }
                        showGoalDialog = false
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showGoalDialog = false }) { Text("Cancel") } }
            )
        }
    }
}

@Composable
private fun TodayScreen(data: StepSnapshot, sensorAvailable: Boolean, permissionGranted: Boolean, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 22.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("CHALATE RAHO", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("Your daily movement", color = White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                }
                Surface(shape = CircleShape, color = PanelSoft) {
                    Icon(Icons.Rounded.DirectionsWalk, null, tint = Mint, modifier = Modifier.padding(12.dp).size(22.dp))
                }
            }
        }
        item { StepHero(data) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("SIMPLE STEPS", NumberFormat.getIntegerInstance().format(data.simpleSteps), "Raw step patterns", Modifier.weight(1f))
                MetricCard("WALK STEPS", NumberFormat.getIntegerInstance().format(data.steps), "Cadence-filtered", Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("DISTANCE", String.format(Locale.getDefault(), "%.2f km", data.distanceKm), "Estimated", Modifier.weight(1f))
                MetricCard("CALORIES", "${data.calories} kcal", "Estimated", Modifier.weight(1f))
            }
        }
        item { WeekCard(data.week) }
        if (!sensorAvailable || !permissionGranted) {
            item {
                Surface(color = Panel, shape = RoundedCornerShape(18.dp)) {
                    Text(
                        if (!sensorAvailable) "This phone does not provide the motion sensors needed for validated step tracking. Step tracking is unavailable on this device."
                        else "Allow Activity Recognition permission to start step tracking.",
                        color = Muted, modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.EmojiEvents, null, tint = Color(0xFFF0C77A), modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Small steps, every day", color = White, fontWeight = FontWeight.SemiBold)
                        Text("Consistency matters more than a perfect number.", color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepHero(data: StepSnapshot) {
    val progress = (data.steps.toFloat() / data.goal.coerceAtLeast(1)).coerceIn(0f, 1f)
    Surface(color = Panel, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 22.dp, horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("TODAY'S WALK", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text(LocalDate.now().dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(), color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(14.dp))
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(228.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 13.dp.toPx()
                    drawArc(PanelSoft, 135f, 270f, false, style = Stroke(stroke, cap = StrokeCap.Round),
                        topLeft = Offset(stroke, stroke), size = Size(size.width - stroke * 2, size.height - stroke * 2))
                    drawArc(Mint, 135f, 270f * progress, false, style = Stroke(stroke, cap = StrokeCap.Round),
                        topLeft = Offset(stroke, stroke), size = Size(size.width - stroke * 2, size.height - stroke * 2))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("WALK STEPS", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
                    Text(NumberFormat.getIntegerInstance().format(data.steps), color = White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                    Text("${(progress * 100).toInt()}% of goal", color = Mint, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("GOAL", color = Muted, fontSize = 10.sp)
                    Text(NumberFormat.getIntegerInstance().format(data.goal), color = White, fontWeight = FontWeight.SemiBold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("REMAINING", color = Muted, fontSize = 10.sp)
                    Text(NumberFormat.getIntegerInstance().format((data.goal - data.steps).coerceAtLeast(0)), color = White, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp),
                color = Mint, trackColor = PanelSoft)
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, note: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = Panel, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            Text(value, color = White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Text(note, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun WeekCard(week: List<Int>) {
    val max = (week.maxOrNull() ?: 0).coerceAtLeast(1)
    Surface(color = Panel, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("THIS WEEK", color = White, fontWeight = FontWeight.SemiBold)
            Text("Your daily steps over the last 7 days", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                week.forEachIndexed { index, value ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Box(Modifier.width(22.dp).height((10 + 72f * value / max).dp).background(if (index == 6) Mint else PanelSoft, RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp)))
                        Spacer(Modifier.height(7.dp))
                        Text(LocalDate.now().minusDays((6 - index).toLong()).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                            color = Muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsScreen(data: StepSnapshot, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(20.dp), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Your activity", color = White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text("Your walking history at a glance.", color = Muted)
        }
        item { WeekCard(data.week) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("7-DAY TOTAL", NumberFormat.getIntegerInstance().format(data.week.sum()), "Steps this week", Modifier.weight(1f))
                MetricCard("ACTIVE DAYS", "${data.week.count { it > 0 }} / 7", "Days with recorded steps", Modifier.weight(1f))
            }
        }
        item { MonthCard(data.month) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("30-DAY AVERAGE", NumberFormat.getIntegerInstance().format(data.averageSteps), "Steps per active day", Modifier.weight(1f))
                MetricCard("BEST DAY", NumberFormat.getIntegerInstance().format(data.bestDaySteps), "Highest daily count", Modifier.weight(1f))
            }
        }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("About these estimates", color = White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("Distance uses an approximate 0.75 m stride. Calories use a simple step-based estimate. Actual results vary by height, pace and body weight.", color = Muted, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun MonthCard(month: List<Int>) {
    val max = (month.maxOrNull() ?: 0).coerceAtLeast(1)
    Surface(color = Panel, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("LAST 30 DAYS", color = White, fontWeight = FontWeight.SemiBold)
            Text("Daily step history", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).height(116.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                month.forEachIndexed { index, value ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Box(
                            Modifier.width(7.dp)
                                .height((5 + 72f * value / max).dp)
                                .background(if (index == month.lastIndex) Mint else PanelSoft, RoundedCornerShape(4.dp))
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            LocalDate.now().minusDays((month.lastIndex - index).toLong()).dayOfMonth.toString(),
                            color = Muted, fontSize = 9.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Days with steps: ${month.count { it > 0 }} of 30", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun GoalsScreen(data: StepSnapshot, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Your goals", color = White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Surface(color = Panel, shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("DAILY STEP TARGET", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                Text(NumberFormat.getIntegerInstance().format(data.goal), color = White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("steps per day", color = Muted)
                Spacer(Modifier.height(18.dp))
                Button(onClick = onEdit, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) {
                    Text("Change goal")
                }
            }
        }
        Text("Choose a target that feels achievable. You can increase it as your routine grows.", color = Muted)
        Surface(color = Panel, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp)) {
                Text("Your data stays on this device", color = White, fontWeight = FontWeight.SemiBold)
                Text("Chalate Raho does not need an account or an internet connection to count steps.", color = Muted, fontSize = 13.sp)
            }
        }
    }
}
