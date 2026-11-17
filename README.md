# Chalate Raho

A privacy-first Android walking and step-tracking application built with Kotlin and Jetpack Compose.

## Step detection

The tracker uses Android's dedicated hardware step sensors instead of building a step count from raw accelerometer peaks:

1. **Primary: `Sensor.TYPE_STEP_DETECTOR`** — each sensor event represents one detected step. The app adds one to today's count immediately when the event is delivered. It requests zero report-batching latency.
2. **Fallback: `Sensor.TYPE_STEP_COUNTER`** — used only when the step detector is unavailable. The app stores the last cumulative value and adds only the positive difference, handling service restarts and counter resets after reboot.
3. **No raw accelerometer counter** — raw acceleration samples and diagnostic detections are not shown in the interface or added to the user's walking total.

Android documents expected step-detector latency below two seconds. The cumulative step-counter sensor can report with latency up to ten seconds, and Android describes it as higher accuracy over longer measurement periods. Both sensors require `ACTIVITY_RECOGNITION` on Android 10 and newer. Hardware availability and actual behavior vary by device.

The UI displays the walking-step total only. The daily goal, distance estimate and calorie estimate use that same total.

## Build APK without installing Android Studio

GitHub Actions builds the debug APK in the cloud. Open the **Actions** tab, select **Android CI**, and download the `chalate-raho-debug-apk` artifact after a successful run.

## Technology

- Kotlin
- Jetpack Compose + Material 3
- Android `SensorManager` hardware step detector / step counter
- DataStore for daily step history and preferences
- Foreground service for ongoing tracking

## Privacy

Step history and preferences remain on the device. The app does not need an account or internet connection to count steps. GPS is not used for step detection.

## Requirements

- Android 10 (API 29) or newer
- Device with Android step detector or step counter sensor
