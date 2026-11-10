package com.waterwatch

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertStatusPolicyTest {
    @Test fun activeAlertBecomesAcknowledgedOnOrdinaryResponse() {
        assertEquals("acknowledged", AlertStatusPolicy.nextStatus("active", "motor_off"))
    }
    @Test fun switchingOffMovesAlertToInProgress() {
        assertEquals("in_progress", AlertStatusPolicy.nextStatus("active", "switching_off"))
    }
    @Test fun laterResponsesDoNotDowngradeInProgress() {
        assertEquals("in_progress", AlertStatusPolicy.nextStatus("in_progress", "not_my_floor"))
    }
    @Test(expected = IllegalArgumentException::class)
    fun closedAlertRejectsResponses() {
        AlertStatusPolicy.nextStatus("resolved", "motor_off")
    }
}
