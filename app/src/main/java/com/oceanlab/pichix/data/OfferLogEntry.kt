package com.oceanlab.pichix.data

data class OfferLogEntry(
    val price: Double,
    val hourlyRate: Double,
    val durationHours: Double = 0.0,
    val timeWindow: String = "",
    val blockDate: String = "",
    val station: String,
    val status: OfferStatus,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis(),
    val firstSeenAt: Long = 0L,
    val actionStartedAt: Long = 0L,
    val actionCompletedAt: Long = 0L,
    val rejectStep1At: Long = 0L,
    val rejectConfirmedAt: Long = 0L,
) {
    val accepted: Boolean get() = status == OfferStatus.ACCEPTED
}

data class DayStats(
    val seen: Int = 0,
    val accepted: Int = 0,
    val rejected: Int = 0,
    val miss: Int = 0,
    val simulated: Int = 0,
    val cancelled: Int = 0,
    val avgHourly: Double = 0.0,
    val bestOffer: Double = 0.0,
    val totalEarned: Double = 0.0,
    val totalHours: Double = 0.0,
)
