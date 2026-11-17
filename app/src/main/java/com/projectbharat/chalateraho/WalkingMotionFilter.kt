package com.projectbharat.chalateraho

import kotlin.math.sqrt

/**
 * Validates hardware step-detector events against body-like acceleration
 * and a plausible human walking cadence. This is deliberately conservative:
 * a missed step is preferable to adding obvious stationary phone movement.
 */
internal class WalkingMotionFilter(
    private val onValidatedSteps: (Int) -> Unit
) {
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var gravityInitialized = false
    private var latestDynamicMagnitude = 0f
    private var latestAccelerationTimestamp = 0L

    private var lastCandidateTimestamp = 0L
    private var consecutiveCandidates = 0
    private var previousIntervalMs = 0L
    private var walkingConfirmed = false

    fun onAcceleration(x: Float, y: Float, z: Float, timestampNs: Long, isLinear: Boolean) {
        val dynamicMagnitude: Float
        if (isLinear) {
            dynamicMagnitude = sqrt(x * x + y * y + z * z)
        } else {
            // Estimate gravity with a low-pass filter and remove it from raw acceleration.
            if (!gravityInitialized) {
                gravityX = x
                gravityY = y
                gravityZ = z
                gravityInitialized = true
            }
            val alpha = 0.88f
            gravityX = alpha * gravityX + (1f - alpha) * x
            gravityY = alpha * gravityY + (1f - alpha) * y
            gravityZ = alpha * gravityZ + (1f - alpha) * z
            val dx = x - gravityX
            val dy = y - gravityY
            val dz = z - gravityZ
            dynamicMagnitude = sqrt(dx * dx + dy * dy + dz * dz)
        }

        // Keep a short peak envelope around the detector event timestamp.
        if (timestampNs - latestAccelerationTimestamp > 250_000_000L) {
            latestDynamicMagnitude = dynamicMagnitude
        } else {
            latestDynamicMagnitude = maxOf(latestDynamicMagnitude, dynamicMagnitude)
        }
        latestAccelerationTimestamp = timestampNs
    }

    fun onStepDetector(timestampNs: Long) {
        val accelerationIsStepLike =
            timestampNs - latestAccelerationTimestamp in 0L..350_000_000L &&
            latestDynamicMagnitude >= MIN_DYNAMIC_ACCELERATION

        if (!accelerationIsStepLike) {
            resetCadence()
            return
        }

        if (lastCandidateTimestamp == 0L) {
            lastCandidateTimestamp = timestampNs
            consecutiveCandidates = 1
            return
        }

        val intervalMs = (timestampNs - lastCandidateTimestamp) / 1_000_000L
        if (intervalMs !in MIN_STEP_INTERVAL_MS..MAX_STEP_INTERVAL_MS ||
            (previousIntervalMs > 0L &&
                (intervalMs.toDouble() / previousIntervalMs.toDouble() !in MIN_CADENCE_RATIO..MAX_CADENCE_RATIO))
        ) {
            // A new possible walking sequence begins with this event.
            lastCandidateTimestamp = timestampNs
            consecutiveCandidates = 1
            previousIntervalMs = 0L
            walkingConfirmed = false
            return
        }

        lastCandidateTimestamp = timestampNs
        previousIntervalMs = intervalMs
        consecutiveCandidates++

        if (!walkingConfirmed && consecutiveCandidates >= REQUIRED_RHYTHMIC_STEPS) {
            walkingConfirmed = true
            // Confirm the three-step sequence together, not just the final step.
            onValidatedSteps(consecutiveCandidates)
        } else if (walkingConfirmed) {
            onValidatedSteps(1)
        }
    }

    fun resetIfStale(nowNs: Long) {
        if (lastCandidateTimestamp != 0L &&
            nowNs - lastCandidateTimestamp > WALKING_TIMEOUT_NS
        ) {
            resetCadence()
        }
    }

    private fun resetCadence() {
        lastCandidateTimestamp = 0L
        consecutiveCandidates = 0
        previousIntervalMs = 0L
        walkingConfirmed = false
    }

    private companion object {
        const val MIN_DYNAMIC_ACCELERATION = 1.30f
        const val MIN_STEP_INTERVAL_MS = 280L
        const val MAX_STEP_INTERVAL_MS = 1_800L
        const val REQUIRED_RHYTHMIC_STEPS = 3
        const val MIN_CADENCE_RATIO = 0.65
        const val MAX_CADENCE_RATIO = 1.55
        const val WALKING_TIMEOUT_NS = 2_500_000_000L
    }
}
