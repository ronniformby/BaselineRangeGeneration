package com.arcbest.optimization;

import java.util.List;

public final class OptimizationModels {
    private OptimizationModels() {
    }

    public record BaselineWeights(
            double costWeight,
            double transitWeight,
            double claimsWeight,
            double offTimeWeight) {
    }

    public record SolveResult(
            ObjectiveMetric objectiveMetric,
            OptimizationDirection direction,
            double objectiveValue,
            KPISnapshot kpis,
            List<AssignmentRecord> assignments) {
    }

    public record BaselineResult(
            double objectiveValue,
            KPISnapshot kpis,
            List<AssignmentRecord> assignments) {
    }

    public record KPIRanges(
            double costMin,
            double costMax,
            double transitMin,
            double transitMax,
            double claimsMin,
            double claimsMax,
            double offTimeMin,
            double offTimeMax) {
        public double costRange() {
            return costMax - costMin;
        }

        public double transitRange() {
            return transitMax - transitMin;
        }

        public double claimsRange() {
            return claimsMax - claimsMin;
        }

        public double offTimeRange() {
            return offTimeMax - offTimeMin;
        }
    }

    public record EpsilonValues(
            double costEpsilon,
            double claimsEpsilon,
            double offTimeEpsilon) {
        public static EpsilonValues fromRanges(KPIRanges ranges, double relaxationPercent) {
            return new EpsilonValues(
                    ranges.costRange() * relaxationPercent,
                    ranges.claimsRange() * relaxationPercent,
                    ranges.offTimeRange() * relaxationPercent
            );
        }
    }

    public record EpsilonRequest(
            ObjectiveMetric targetObjective,
            double relaxationPercent,
            double minimumShipmentChangeShare,
            double minimumRelativeImprovement) {
    }

    public record EpsilonResult(
            ObjectiveMetric targetObjective,
            double objectiveValue,
            KPISnapshot kpis,
            EpsilonValues epsilonValues,
            double shipmentChangeShare,
            double relativeImprovement,
            List<AssignmentRecord> assignments) {
    }
}
