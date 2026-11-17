# Chalate Raho

A privacy-first Android walking and step tracking application built with Kotlin and Jetpack Compose.

## Project status
Initial Android project scaffold. The app is designed to track steps locally using the device step-counter sensor, show daily and weekly activity, and preserve step history on-device.

## Build APK without installing Android Studio
GitHub Actions builds the debug APK in the cloud. Open the **Actions** tab, select **Android CI**, and download the `app-debug` artifact after a successful run.

## Technology
- Kotlin
- Jetpack Compose + Material 3
- Android SensorManager step counter
- Room database for daily history
- DataStore for preferences
- Foreground service for ongoing tracking

## Privacy
Step history and preferences remain on the device. The app does not require an account or upload activity data.

## Requirements
- Android 10 (API 29) or newer
- Device with a hardware step-counter sensor
