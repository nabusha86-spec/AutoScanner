package com.autoscanner.trader.analytics

import com.autoscanner.trader.vision.FrameAnalysis
import kotlin.math.abs

class MomentumAnalysisEngine {
    private val movement = PriceMovementAnalyzer()

    fun analyze(frames: List<FrameAnalysis>, candles: List<DetailedCandle>): MomentumResult {
        val priceMovement = movement.analyze(frames)
        val candleChanges = candles.mapNotNull { it.changeFromPrevious }
            .filter { abs(it) > 0.0000001 }
        val up = candleChanges.count { it > 0 }
        val down = candleChanges.count { it < 0 }
        val direction = if (up > down) DirectionInfluence.UP
            else if (down > up) DirectionInfluence.DOWN else DirectionInfluence.NEUTRAL
        val amplitude = candleChanges.map { abs(it) }.average().takeIf { !it.isNaN() } ?: 0.0
        val prior = candleChanges.take(maxOf(1, candleChanges.size / 2)).map { abs(it) }.average()
        val recent = candleChanges.takeLast(maxOf(1, candleChanges.size / 2)).map { abs(it) }.average()
        val slowing = recent < prior * 0.75
        val strength = if (candleChanges.isEmpty()) 0.0 else
            (amplitude.coerceAtMost(1.0) * 0.55 + maxOf(up, down).toDouble() / candleChanges.size * 0.45)
                .coerceIn(0.0, 1.0)
        val measured = priceMovement.upMoves + priceMovement.downMoves + candleChanges.size
        val confidence = when {
            measured >= 8 -> 0.75
            measured >= 5 -> 0.55
            measured >= 3 -> 0.35
            else -> 0.15
        }
        return MomentumResult(direction, strength, confidence, priceMovement.speed,
            priceMovement.acceleration, slowing, measured)
    }
}

class PriceMovementAnalyzer {
    fun analyze(frames: List<FrameAnalysis>): PriceMovementResult {
        val valid = frames.mapNotNull { frame ->
            val instrument = frame.instrument.takeIf { it != "НЕ ОПРЕДЕЛЕНО" }
            val price = frame.price.replace(',', '.').toDoubleOrNull()
            if (instrument != null && price != null) Triple(frame.timestampMillis, instrument, price) else null
        }
        if (valid.size < 2) return PriceMovementResult(DirectionInfluence.NEUTRAL, null, null, null,
            0, 0, false)
        val groupedInstrument = valid.groupBy { it.second }.maxByOrNull { it.value.size }?.value ?: valid
        val ordered = groupedInstrument.sortedBy { it.first }
        val moves = ordered.zipWithNext().mapNotNull { (a, b) ->
            val seconds = (b.first - a.first).coerceAtLeast(1L) / 1000.0
            if (seconds <= 0.0) null else b.third - a.third to seconds
        }
        val up = moves.count { it.first > 0.0 }
        val down = moves.count { it.first < 0.0 }
        val speeds = moves.map { it.first / it.second }
        val speed = if (speeds.isNotEmpty()) speeds.average() else null
        val accelerations = speeds.zipWithNext { a, b -> b - a }
        val acceleration = if (accelerations.isNotEmpty()) accelerations.average() else null
        val amplitudes = moves.map { abs(it.first) }
        val amplitudeRatio = if (amplitudes.size >= 2) {
            amplitudes.takeLast(maxOf(1, amplitudes.size / 2)).average() /
                amplitudes.take(maxOf(1, amplitudes.size / 2)).average().coerceAtLeast(0.0000001)
        } else null
        val median = amplitudes.sorted().let { it[it.size / 2] }
        val sharp = amplitudes.count { it > median * 2.5 } > 0
        val direction = if (up > down) DirectionInfluence.UP
            else if (down > up) DirectionInfluence.DOWN else DirectionInfluence.NEUTRAL
        return PriceMovementResult(direction, speed, acceleration, amplitudeRatio, up, down, sharp)
    }
}
