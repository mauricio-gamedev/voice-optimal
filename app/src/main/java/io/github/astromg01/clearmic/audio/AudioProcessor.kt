package io.github.astromg01.clearmic.audio

interface AudioProcessor {
    fun process(buffer: ShortArray, size: Int)
}

/**
 * Tiny low-cost preprocessing placeholder.
 * Keeps allocations out of the realtime loop and provides a conservative
 * DC blocker + soft noise gate until the native DSP stage is introduced.
 */
class LightweightVoiceProcessor(
    private val gateThreshold: Int = 220,
) : AudioProcessor {
    private var previousInput = 0f
    private var previousOutput = 0f

    override fun process(buffer: ShortArray, size: Int) {
        val alpha = 0.995f
        for (i in 0 until size) {
            val x = buffer[i].toFloat()
            var y = x - previousInput + alpha * previousOutput
            previousInput = x
            previousOutput = y

            if (kotlin.math.abs(y) < gateThreshold) {
                y *= 0.18f
            }

            buffer[i] = y.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }
    }
}
