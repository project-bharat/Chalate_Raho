# Chalate Raho

A privacy-first Android walking and step-tracking application built with Kotlin and Jetpack Compose.

## Step detection

The pedometer uses the device accelerometer and maintains two independent counters:

- **Simple Steps:** Raw step-like events detected from the accelerometer signal.
- **Walk Steps:** A cadence-filtered subset used for the daily goal, distance and activity statistics.

The detector follows the published MIT App Inventor Pedometer approach:
- Squared acceleration magnitude
- 10-sample moving average
- 100-sample rolling signal window
- Peak/valley detection with a minimum signal range
- Two consecutive step intervals checked for regularity (250 ms tolerance)
- Separate raw and filtered step totals

This is a motion-pattern heuristic. It does not prove geographic forward displacement: rhythmic movement while standing or walking in place can still be classified as walking. The two counters are shown separately so users can compare raw detections with filtered walking steps. Thresholds require testing on real devices.

## Build APK without installing Android Studio

GitHub Actions builds the debug APK in the cloud. Open the **Actions** tab, select **Android CI**, and download the `app-debug` artifact after a successful run.

## Technology

- Kotlin
- Jetpack Compose + Material 3
- Android SensorManager accelerometer
- DataStore for daily step history and preferences
- Foreground service for ongoing tracking

## Privacy

Step history and preferences remain on the device. The app does not require an account or upload activity data. GPS is not used for step detection.

## Requirements

- Android 10 (API 29) or newer
- Device with an accelerometer
