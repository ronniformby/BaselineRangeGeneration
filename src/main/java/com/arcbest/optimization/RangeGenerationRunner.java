package com.arcbest.optimization;

import java.util.ArrayList;
import java.util.List;

public class RangeGenerationRunner {
    public static void main(String[] args) throws Exception {
        DataLoader dataLoader = new DataLoader();
        ModelData modelData = dataLoader.load(
                ProjectPaths.shipmentData(),
                ProjectPaths.carrierMetrics(),
                ProjectPaths.transitTime()
        );

        run(modelData);
    }

    public static void run(ModelData modelData) {
        OptimizationModels.KPIRanges kpiRanges = computeRanges(modelData, true);
        printRanges(kpiRanges);
    }

    public static OptimizationModels.KPIRanges computeRanges(ModelData modelData) {
        return computeRanges(modelData, true);
    }

    public static OptimizationModels.KPIRanges computeRanges(ModelData modelData, boolean verbose) {
        ObjectiveBoundsSolver solver = new ObjectiveBoundsSolver();
        List<OptimizationModels.SolveResult> results = new ArrayList<>();

        results.add(solver.solve(modelData, ObjectiveMetric.COST, OptimizationDirection.MINIMIZE));
        results.add(solver.solve(modelData, ObjectiveMetric.COST, OptimizationDirection.MAXIMIZE));

        results.add(solver.solve(modelData, ObjectiveMetric.TRANSIT_TIME, OptimizationDirection.MINIMIZE));
        results.add(solver.solve(modelData, ObjectiveMetric.TRANSIT_TIME, OptimizationDirection.MAXIMIZE));

        results.add(solver.solve(modelData, ObjectiveMetric.CLAIMS, OptimizationDirection.MINIMIZE));
        results.add(solver.solve(modelData, ObjectiveMetric.CLAIMS, OptimizationDirection.MAXIMIZE));

        results.add(solver.solve(modelData, ObjectiveMetric.OFF_TIME_DELIVERY, OptimizationDirection.MINIMIZE));
        results.add(solver.solve(modelData, ObjectiveMetric.OFF_TIME_DELIVERY, OptimizationDirection.MAXIMIZE));

        if (verbose) {
            for (OptimizationModels.SolveResult result : results) {
                printResult(result);
            }
        }

        return buildRanges(results);
    }

    private static void printResult(OptimizationModels.SolveResult result) {
        System.out.println("--------------------------------------------------");
        System.out.println("Objective metric: " + result.objectiveMetric());
        System.out.println("Direction: " + result.direction());
        System.out.println("Objective value: " + result.objectiveValue());
        System.out.println("Total cost: " + result.kpis().getTotalCost());
        System.out.println("Average transit time: " + result.kpis().getAverageTransitTime());
        System.out.println("Average claims: " + result.kpis().getAverageClaims());
        System.out.println("Average off-time delivery: " + result.kpis().getAverageOffTimeDelivery());
        System.out.println("Assignments returned: " + result.assignments().size());
    }

    private static OptimizationModels.KPIRanges buildRanges(List<OptimizationModels.SolveResult> results) {
        double costMin = findKpiValue(results, ObjectiveMetric.COST, OptimizationDirection.MINIMIZE);
        double costMax = findKpiValue(results, ObjectiveMetric.COST, OptimizationDirection.MAXIMIZE);
        double transitMin = findKpiValue(results, ObjectiveMetric.TRANSIT_TIME, OptimizationDirection.MINIMIZE);
        double transitMax = findKpiValue(results, ObjectiveMetric.TRANSIT_TIME, OptimizationDirection.MAXIMIZE);
        double claimsMin = findKpiValue(results, ObjectiveMetric.CLAIMS, OptimizationDirection.MINIMIZE);
        double claimsMax = findKpiValue(results, ObjectiveMetric.CLAIMS, OptimizationDirection.MAXIMIZE);
        double offTimeMin = findKpiValue(results, ObjectiveMetric.OFF_TIME_DELIVERY, OptimizationDirection.MINIMIZE);
        double offTimeMax = findKpiValue(results, ObjectiveMetric.OFF_TIME_DELIVERY, OptimizationDirection.MAXIMIZE);

        return new OptimizationModels.KPIRanges(
                costMin,
                costMax,
                transitMin,
                transitMax,
                claimsMin,
                claimsMax,
                offTimeMin,
                offTimeMax
        );
    }

    private static void printRanges(OptimizationModels.KPIRanges ranges) {
        System.out.println("==================================================");
        System.out.println("KPI RANGE SUMMARY");
        System.out.println("Cost min: " + ranges.costMin());
        System.out.println("Cost max: " + ranges.costMax());
        System.out.println("Cost range: " + ranges.costRange());
        System.out.println();
        System.out.println("Average transit min: " + ranges.transitMin());
        System.out.println("Average transit max: " + ranges.transitMax());
        System.out.println("Average transit range: " + ranges.transitRange());
        System.out.println();
        System.out.println("Average claims min: " + ranges.claimsMin());
        System.out.println("Average claims max: " + ranges.claimsMax());
        System.out.println("Average claims range: " + ranges.claimsRange());
        System.out.println();
        System.out.println("Average off-time min: " + ranges.offTimeMin());
        System.out.println("Average off-time max: " + ranges.offTimeMax());
        System.out.println("Average off-time range: " + ranges.offTimeRange());
        System.out.println("==================================================");
    }

    private static double findKpiValue(
            List<OptimizationModels.SolveResult> results,
            ObjectiveMetric metric,
            OptimizationDirection direction) {

        for (OptimizationModels.SolveResult result : results) {
            if (result.objectiveMetric() == metric && result.direction() == direction) {
                return switch (metric) {
                    case COST -> result.kpis().getTotalCost();
                    case TRANSIT_TIME -> result.kpis().getAverageTransitTime();
                    case CLAIMS -> result.kpis().getAverageClaims();
                    case OFF_TIME_DELIVERY -> result.kpis().getAverageOffTimeDelivery();
                };
            }
        }

        throw new IllegalArgumentException("Missing result for " + metric + " " + direction);
    }
}
