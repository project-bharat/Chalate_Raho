package com.projectbharat.chalateraho

import kotlin.math.abs

/**
 * MIT App Inventor-style accelerometer pedometer.
 *
 * Simple steps are peak/valley detections from the accelerometer magnitude.
 * Walk steps are the subset whose recent cadence is regular. The algorithm
 * uses a 100-sample rolling window, a 10-sample magnitude average and a
 * two-interval cadence test, following MIT's published Pedometer.java design.
 *
 * A rhythmic cadence is a useful walking heuristic, not proof of geographic
 * forward displacement. Walking in place can still be counted.
 */
internal class WalkingMotionFilter(
    private val onSimpleStep: (walkStepsToAdd: Int) -> Unit
) {
    private val magnitudeAverage = FloatArray(AVERAGE_WINDOW)
    private val signalWindow = FloatArray(WINDOW_SIZE)
    private val stepIntervals = LongArray(INTERVAL_COUNT)

    private var averagePosition = 0
    private var windowPosition = 0
    private var intervalPosition = 0
    private var samplesSeen = 0
    private var peakingStarted = false
    private var foundValley = true
    private var lastValley = 0f
    private var foundNonWalkingStep = true
    private var lastStepTimestampMs = 0L

    fun onAcceleration(x: Float, y: Float, z: Float, timestampNs: Long) {
        // MIT Pedometer uses squared acceleration magnitude, retaining signal energy.
        val magnitude = x * x + y * y + z * z
        magnitudeAverage[averagePosition] = magnitude
        averagePosition = (averagePosition + 1) % AVERAGE_WINDOW

        var average = 0f
        for (value in magnitudeAverage) average += value
        average /= AVERAGE_WINDOW

        signalWindow[windowPosition] = average
        if (peakingStarted || windowPosition > 1) {
            val previous1 = (windowPosition - 1 + WINDOW_SIZE) % WINDOW_SIZE
            val previous2 = (windowPosition - 2 + WINDOW_SIZE) % WINDOW_SIZE
            signalWindow[windowPosition] =
                (signalWindow[windowPosition] + 2f * signalWindow[previous1] +
                    signalWindow[previous2]) / 4f
        } else if (windowPosition == 1) {
            signalWindow[1] = (signalWindow[1] + signalWindow[0]) / 2f
        }

        val midpoint = (windowPosition + WINDOW_SIZE / 2) % WINDOW_SIZE
        if (peakingStarted && isPeak(midpoint)) {
            if (foundValley && signalWindow[midpoint] - lastValley > PEAK_VALLEY_RANGE) {
                handleDetectedStep(timestampNs / 1_000_000L)
                foundValley = false
            }
        }

        if (peakingStarted && isValley(midpoint)) {
            foundValley = true
            lastValley = signalWindow[midpoint]
        }

        samplesSeen++
        if (samplesSeen >= WINDOW_SIZE && !peakingStarted) {
            peakingStarted = true
        }
        windowPosition = (windowPosition + 1) % WINDOW_SIZE
    }

    private fun handleDetectedStep(timestampMs: Long) {
        var walkStepsToAdd = 0
        if (lastStepTimestampMs > 0L) {
            val interval = timestampMs - lastStepTimestampMs
            stepIntervals[intervalPosition] = interval
            intervalPosition = (intervalPosition + 1) % INTERVAL_COUNT

            if (hasRegularCadence()) {
                // MIT backfills the two intervals that establish the walking pattern.
                if (foundNonWalkingStep) {
                    walkStepsToAdd += INTERVAL_COUNT
                    foundNonWalkingStep = false
                }
                walkStepsToAdd += 1
            } else {
                foundNonWalkingStep = true
            }
        }
        lastStepTimestampMs = timestampMs

        // Every peak/valley is a simple step; walk count is independently filtered.
        onSimpleStep(walkStepsToAdd)
    }

    private fun hasRegularCadence(): Boolean {
        if (stepIntervals.any { it <= 0L }) return false
        val average = stepIntervals.average()
        return stepIntervals.all { abs(it - average) <= INTERVAL_VARIATION_MS }
    }

    private fun isPeak(midpoint: Int): Boolean {
        for (i in signalWindow.indices) {
            if (i != midpoint && signalWindow[i] > signalWindow[midpoint]) return false
        }
        return true
    }

    private fun isValley(midpoint: Int): Boolean {
        for (i in signalWindow.indices) {
            if (i != midpoint && signalWindow[i] < signalWindow[midpoint]) return false
        }
        return true
    }

    companion object {
        private const val WINDOW_SIZE = 100
        private const val AVERAGE_WINDOW = 10
        private const val INTERVAL_COUNT = 2
        private const val INTERVAL_VARIATION_MS = 250L
        private const val PEAK_VALLEY_RANGE = 40f
    }
}
