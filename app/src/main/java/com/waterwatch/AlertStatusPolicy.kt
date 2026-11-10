package com.waterwatch

object AlertStatusPolicy {
    fun nextStatus(currentStatus: String, responseType: String): String {
        require(currentStatus !in setOf("resolved", "dismissed")) { "Alert is already closed" }
        return when {
            responseType == "switching_off" -> "in_progress"
            currentStatus == "active" -> "acknowledged"
            else -> currentStatus
        }
    }
}
