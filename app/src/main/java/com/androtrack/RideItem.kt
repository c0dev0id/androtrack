package com.androtrack

data class RideItem(
    val file: java.io.File,
    val date: String,
    val startTime: String,
    val durationMs: Long,
    val distanceKm: Double,
    val trackPoints: List<Pair<Double, Double>>
)
