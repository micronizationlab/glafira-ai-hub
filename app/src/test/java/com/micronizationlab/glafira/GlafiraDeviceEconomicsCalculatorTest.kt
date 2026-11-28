package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraDeviceEconomicsCalculatorTest {
    private fun input(
        confidence: DeviceMeasurementConfidence = DeviceMeasurementConfidence.MEASURED,
        acceptedUnitValue: Double = 2.0
    ) = GlafiraDeviceEconomicsInput(
        measuredThroughputPerHour = 100.0,
        acceptanceRate = 0.9,
        retryLoss = 0.1,
        optedInHoursPerDay = 4.0,
        idleAvailabilityFactor = 0.5,
        thermalFactor = 0.8,
        ownerPriorityFactor = 0.9,
        connectivityFactor = 1.0,
        incrementalPowerWatts = 200.0,
        electricityTariffPerKwh = 5.0,
        bandwidthCostPerDay = 3.0,
        purchasePrice = 60_000.0,
        residualValue = 10_000.0,
        usefulMonths = 24.0,
        expectedContributionHoursPerMonth = 40.0,
        acceptedUnitValue = acceptedUnitValue,
        failureRate = 0.02,
        disputeRate = 0.01,
        supportRate = 0.01,
        riskMultiplier = 2.0,
        platformOperatingCostPerDay = 10.0,
        contributorShare = 0.6,
        glfReferenceValue = 0.03,
        monthlyVpnPackageGlf = 10_000.0,
        monthlyAiPackageGlf = 5_000.0,
        measurementConfidence = confidence
    )

    @Test
    fun calculatesAcceptedWorkEnergyDepreciationAndReward() {
        val result = GlafiraDeviceEconomicsCalculator.calculate(input())

        assertEquals(1.44, result.availableHoursPerDay, 0.000001)
        assertEquals(144.0, result.candidateWorkUnitsPerDay, 0.000001)
        assertEquals(116.64, result.acceptedWorkUnitsPerDay, 0.000001)
        assertEquals(0.288, result.energyKwhPerDay, 0.000001)
        assertEquals(1.44, result.electricityCostPerDay, 0.000001)
        assertEquals(75.0, result.depreciationCostPerDay, 0.000001)
        assertEquals(125.1776, result.netValuePerDay, 0.000001)
        assertEquals(2_503L, result.eligibleGlfPerDay)
        assertTrue(result.eligibleForReward)
        assertEquals(10_000.0 / 2_503.0, result.estimatedDaysToCoverVpn!!, 0.000001)
        assertEquals(15_000.0 / 2_503.0, result.estimatedDaysToCoverVpnAndAi!!, 0.000001)
    }

    @Test
    fun negativeNetValueProducesNoReward() {
        val result = GlafiraDeviceEconomicsCalculator.calculate(
            input(acceptedUnitValue = 0.01)
        )

        assertTrue(result.netValuePerDay < 0.0)
        assertEquals(0L, result.estimatedGlfPerDay)
        assertEquals(0L, result.eligibleGlfPerDay)
        assertFalse(result.eligibleForReward)
        assertNull(result.estimatedDaysToCoverVpn)
    }

    @Test
    fun estimatedMeasurementsShowForecastButCannotEarnEligibleGlf() {
        val result = GlafiraDeviceEconomicsCalculator.calculate(
            input(confidence = DeviceMeasurementConfidence.ESTIMATED)
        )

        assertTrue(result.estimatedGlfPerDay > 0L)
        assertEquals(0L, result.eligibleGlfPerDay)
        assertFalse(result.eligibleForReward)
    }

    @Test
    fun unknownMeasurementsCannotProduceRewardQuote() {
        val result = GlafiraDeviceEconomicsCalculator.calculate(
            input(confidence = DeviceMeasurementConfidence.UNKNOWN)
        )

        assertEquals(0L, result.estimatedGlfPerDay)
        assertEquals(0L, result.eligibleGlfPerDay)
        assertNull(result.estimatedDaysToCoverVpn)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidAvailabilityFactors() {
        GlafiraDeviceEconomicsCalculator.calculate(
            input().copy(thermalFactor = 1.5)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroGlfReferenceValue() {
        GlafiraDeviceEconomicsCalculator.calculate(
            input().copy(glfReferenceValue = 0.0)
        )
    }
}
