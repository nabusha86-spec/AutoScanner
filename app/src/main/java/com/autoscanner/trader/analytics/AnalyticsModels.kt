package com.autoscanner.trader.analytics

enum class PredictionDirection {
    BUY,
    SELL,
    NO_TRADE,
}

enum class TrendDirection {
    UP,
    DOWN,
    SIDEWAYS,
    POSSIBLE_REVERSAL,
    WEAKENING,
    STRENGTHENING,
    UNCONFIRMED,
}

enum class MarketCondition {
    STABLE_TREND,
    WEAK_TREND,
    SIDEWAYS,
    CONSOLIDATION,
    IMPULSE,
    HIGH_VOLATILITY,
    POSSIBLE_REVERSAL,
    UNDEFINED,
}

enum class DirectionInfluence {
    UP,
    DOWN,
    NEUTRAL,
}

enum class CandleDirection {
    UP,
    DOWN,
    DOJI,
}

enum class CandleColor {
    GREEN,
    RED,
    UNKNOWN,
}

enum class PatternDirection {
    BULLISH,
    BEARISH,
    NEUTRAL,
}

data class DetailedCandle(
    val index: Int,
    val timestampMillis: Long?,
    val open: Double?,
    val close: Double?,
    val high: Double?,
    val low: Double?,
    val bodySize: Double,
    val upperShadow: Double,
    val lowerShadow: Double,
    val color: CandleColor,
    val direction: CandleDirection,
    val relativeStrength: Double,
    val changeFromPrevious: Double?,
)

data class CandlePattern(
    val name: String,
    val type: PatternDirection,
    val timestampMillis: Long?,
    val direction: PatternDirection,
    val confidence: Double,
    val confirmingFeatures: List<String>,
)

data class CandleAnalysisResult(
    val candles: List<DetailedCandle>,
    val patterns: List<CandlePattern>,
)

data class TrendResult(
    val direction: TrendDirection,
    val strength: Double,
    val confidence: Double,
    val durationCandles: Int,
    val explanation: String,
)

data class MomentumResult(
    val direction: DirectionInfluence,
    val strength: Double,
    val confidence: Double,
    val speed: Double?,
    val acceleration: Double?,
    val slowing: Boolean,
    val measuredSamples: Int,
)

data class PriceMovementResult(
    val direction: DirectionInfluence,
    val speed: Double?,
    val acceleration: Double?,
    val amplitudeRatio: Double?,
    val upMoves: Int,
    val downMoves: Int,
    val sharpJumpDetected: Boolean,
)

enum class VolatilityLevel {
    LOW,
    MODERATE,
    HIGH,
    SHARP_INCREASE,
    SHARP_DECREASE,
    UNDEFINED,
}

data class VolatilityResult(
    val level: VolatilityLevel,
    val confidence: Double,
    val rangeRatio: Double?,
)

data class SupportResistanceZone(
    val kind: String,
    val exactPrice: Double?,
    val approximateImageY: Int?,
    val confirmations: Int,
    val detectedAtMillis: Long?,
    val strength: Double,
    val distanceFromCurrent: Double?,
    val confidence: Double,
)

data class SupportResistanceResult(
    val support: List<SupportResistanceZone>,
    val resistance: List<SupportResistanceZone>,
    val breaks: List<String>,
    val falseBreaks: List<String>,
)

data class IndicatorResult(
    val name: String,
    val recognized: Boolean,
    val exactValue: Double?,
    val visualApproximation: String?,
    val direction: DirectionInfluence,
    val state: String,
    val confidence: Double,
)

data class SignalFactor(
    val name: String,
    val value: String,
    val influence: DirectionInfluence,
    val weight: Double,
    val confidence: Double,
    val source: String,
)

data class RiskFactor(
    val name: String,
    val description: String,
)

data class PredictionWeights(
    val trend: Double = 0.28,
    val momentum: Double = 0.20,
    val candles: Double = 0.12,
    val indicators: Double = 0.12,
    val levels: Double = 0.10,
    val market: Double = 0.18,
)

data class ExpirationRecommendation(
    val seconds: Int?,
    val reason: String,
    val usedIndicators: Map<String, String>,
    val confidence: Double,
    val limitations: List<String>,
)

data class PredictionResult(
    val instrument: String?,
    val currentPrice: Double?,
    val direction: PredictionDirection,
    val confidence: Double,
    val analysisTimeMillis: Long,
    val timeframe: String?,
    val marketCondition: MarketCondition,
    val trendDirection: TrendDirection,
    val trendStrength: Double?,
    val momentumStrength: Double?,
    val volatility: VolatilityLevel,
    val candlePatterns: List<CandlePattern>,
    val indicators: List<IndicatorResult>,
    val supportResistance: SupportResistanceResult,
    val reasons: List<String>,
    val risks: List<RiskFactor>,
    val factors: List<SignalFactor>,
    val expiration: ExpirationRecommendation,
)

data class MlFeatureVector(
    val version: Int = 1,
    val trendDirection: Double?,
    val trendStrength: Double?,
    val momentumStrength: Double?,
    val momentumSpeed: Double?,
    val volatility: Double?,
    val candleBodyRatio: Double?,
    val supportDistance: Double?,
    val resistanceDistance: Double?,
    val imageQuality: Double?,
)

data class MlArchitecture(
    val modelRequired: Boolean = false,
    val normalization: String = "Минмакс-нормализация только по обучающей выборке",
    val modelVersion: String = "НЕ ПОДКЛЮЧЕНА",
    val outputClasses: List<String> = listOf("ПОКУПКА", "ПРОДАЖА", "НЕТ СДЕЛКИ"),
    val missingDataPolicy: String = "Отсутствующий признак сохраняется как НЕ ОПРЕДЕЛЕНО",
)
