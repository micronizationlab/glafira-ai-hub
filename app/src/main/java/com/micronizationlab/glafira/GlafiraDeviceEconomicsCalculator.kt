package com.micronizationlab.glafira

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

enum class DeviceMeasurementConfidence {
    MEASURED,
    ESTIMATED,
    UNKNOWN
}

/**
 * Inputs for one exact device + workload + model/quantization profile.
 * All monetary values use the same currency and effective date.
 *
 * This calculator is a forecast only. It never writes to a wallet or issues GLF.
 */
data class GlafiraDeviceEconomicsInput(
    val measuredThroughputPerHour: Double,
    val acceptanceRate: Double,
    val retryLoss: Double,
    val optedInHoursPerDay: Double,
    val idleAvailabilityFactor: Double,
    val thermalFactor: Double,
    val ownerPriorityFactor: Double,
    val connectivityFactor: Double,
    val incrementalPowerWatts: Double,
    val electricityTariffPerKwh: Double,
    val bandwidthCostPerDay: Double,
    val purchasePrice: Double,
    val residualValue: Double,
    val usefulMonths: Double,
    val expectedContributionHoursPerMonth: Double,
    val acceptedUnitValue: Double,
    val failureRate: Double,
    val disputeRate: Double,
    val supportRate: Double,
    val riskMultiplier: Double,
    val platformOperatingCostPerDay: Double,
    val contributorShare: Double,
    val glfReferenceValue: Double,
    val monthlyVpnPackageGlf: Double,
    val monthlyAiPackageGlf: Double,
    val measurementConfidence: DeviceMeasurementConfidence
)

data class GlafiraDeviceEconomicsResult(
    val availableHoursPerDay: Double,
    val candidateWorkUnitsPerDay: Double,
    val acceptedWorkUnitsPerDay: Double,
    val energyKwhPerDay: Double,
    val electricityCostPerDay: Double,
    val depreciationCostPerDay: Double,
    val acceptedWorkValuePerDay: Double,
    val riskReservePerDay: Double,
    val directOperatingCostPerDay: Double,
    val netValuePerDay: Double,
    val rewardPoolPerDay: Double,
    val estimatedGlfPerDay: Long,
    val eligibleGlfPerDay: Long,
    val eligibleForReward: Boolean,
    val estimatedDaysToCoverVpn: Double?,
    val estimatedDaysToCoverVpnAndAi: Double?
)

object GlafiraDeviceEconomicsCalculator {
    fun calculate(input: GlafiraDeviceEconomicsInput): GlafiraDeviceEconomicsResult {
        validate(input)

        val availableHours = input.optedInHoursPerDay *
            input.idleAvailabilityFactor *
            input.thermalFactor *
            input.ownerPriorityFactor *
            input.connectivityFactor

        val candidateWork = input.measuredThroughputPerHour * availableHours
        val acceptedWork = candidateWork * input.acceptanceRate * (1.0 - input.retryLoss)
        val energyKwh = input.incrementalPowerWatts / 1000.0 * availableHours
        val electricityCost = energyKwh * input.electricityTariffPerKwh

        val depreciationPerHour = max(0.0, input.purchasePrice - input.residualValue) /
            (input.usefulMonths * input.expectedContributionHoursPerMonth)
        val depreciationCost = depreciationPerHour * availableHours

        val acceptedValue = acceptedWork * input.acceptedUnitValue
        val riskReserve = acceptedValue *
            (input.failureRate + input.disputeRate + input.supportRate) *
            input.riskMultiplier
        val directCost = electricityCost + input.bandwidthCostPerDay
        val netValue = acceptedValue - directCost - depreciationCost -
            riskReserve - input.platformOperatingCostPerDay

        val rewardPool = max(0.0, min(netValue * input.contributorShare, netValue))
        val estimatedGlf = if (input.measurementConfidence == DeviceMeasurementConfidence.UNKNOWN) {
            0L
        } else {
            floor(rewardPool / input.glfReferenceValue).toLong()
        }
        val eligible = input.measurementConfidence == DeviceMeasurementConfidence.MEASURED &&
            netValue > 0.0 && estimatedGlf > 0L
        val eligibleGlf = if (eligible) estimatedGlf else 0L

        val vpnAndAiPackage = input.monthlyVpnPackageGlf + input.monthlyAiPackageGlf
        return GlafiraDeviceEconomicsResult(
            availableHoursPerDay = availableHours,
            candidateWorkUnitsPerDay = candidateWork,
            acceptedWorkUnitsPerDay = acceptedWork,
            energyKwhPerDay = energyKwh,
            electricityCostPerDay = electricityCost,
            depreciationCostPerDay = depreciationCost,
            acceptedWorkValuePerDay = acceptedValue,
            riskReservePerDay = riskReserve,
            directOperatingCostPerDay = directCost,
            netValuePerDay = netValue,
            rewardPoolPerDay = rewardPool,
            estimatedGlfPerDay = estimatedGlf,
            eligibleGlfPerDay = eligibleGlf,
            eligibleForReward = eligible,
            estimatedDaysToCoverVpn = estimatedDays(input.monthlyVpnPackageGlf, estimatedGlf),
            estimatedDaysToCoverVpnAndAi = estimatedDays(vpnAndAiPackage, estimatedGlf)
        )
    }

    private fun estimatedDays(packageGlf: Double, dailyGlf: Long): Double? {
        if (packageGlf <= 0.0) return 0.0
        if (dailyGlf <= 0L) return null
        return packageGlf / dailyGlf.toDouble()
    }

    private fun validate(input: GlafiraDeviceEconomicsInput) {
        val nonNegative = listOf(
            input.measuredThroughputPerHour,
            input.optedInHoursPerDay,
            input.incrementalPowerWatts,
            input.electricityTariffPerKwh,
            input.bandwidthCostPerDay,
            input.purchasePrice,
            input.residualValue,
            input.usefulMonths,
            input.expectedContributionHoursPerMonth,
            input.acceptedUnitValue,
            input.riskMultiplier,
            input.platformOperatingCostPerDay,
            input.monthlyVpnPackageGlf,
            input.monthlyAiPackageGlf
        )
        require(nonNegative.all { it.isFinite() && it >= 0.0 }) {
            "Throughput, hours, costs and package values must be finite and non-negative."
        }
        require(input.optedInHoursPerDay <= 24.0) {
            "Opt-in contribution time cannot exceed 24 hours per day."
        }
        require(input.usefulMonths > 0.0 && input.expectedContributionHoursPerMonth > 0.0) {
            "Useful life and expected monthly contribution hours must be greater than zero."
        }
        require(input.glfReferenceValue.isFinite() && input.glfReferenceValue > 0.0) {
            "GLF reference value must be greater than zero."
        }
        val unitFactors = listOf(
            input.acceptanceRate,
            input.retryLoss,
            input.idleAvailabilityFactor,
            input.thermalFactor,
            input.ownerPriorityFactor,
            input.connectivityFactor,
            input.failureRate,
            input.disputeRate,
            input.supportRate,
            input.contributorShare
        )
        require(unitFactors.all { it.isFinite() && it in 0.0..1.0 }) {
            "Rates and availability factors must be between zero and one."
        }
        require(input.residualValue <= input.purchasePrice) {
            "Residual value cannot exceed purchase price in this depreciation model."
        }
    }
}
