package com.arcbest.optimization;

import java.util.ArrayList;
import java.util.List;

public class RangeGenerationRunner {
    public static void main(String[] args) throws Exception {
        DataLoader dataLoader = new DataLoader();
        ModelData modelData = dataLoader.load(
                "/Users/ronniformby/Shipment Data.csv",
                "/Users/ronniformby/Carrier Metrics.csv",
                "/Users/ronniformby/Transit Time.csv"
        );

        ObjectiveBoundsSolver solver = new ObjectiveBoundsSolver();
        List<SolveResults> results = new ArrayList<>();

        results.add(solver.solve(modelData, ObjectiveMetric.COST, OptimizationDirection.MINIMIZE));
        results.add(solver.solve(modelData, ObjectiveMetric.COST, OptimizationDirection.MAXIMIZE));

        results.add(solver.solve(modelData, ObjectiveMetric.TRANSIT_TIME, OptimizationDirection.MINIMIZE));
        results.add(solver.solve(modelData, ObjectiveMetric.TRANSIT_TIME, OptimizationDirection.MAXIMIZE));

        results.add(solver.solve(modelData, ObjectiveMetric.CLAIMS, OptimizationDirection.MINIMIZE));
        results.add(solver.solve(modelData, ObjectiveMetric.CLAIMS, OptimizationDirection.MAXIMIZE));

        results.add(solver.solve(modelData, ObjectiveMetric.OFF_TIME_DELIVERY, OptimizationDirection.MINIMIZE));
        results.add(solver.solve(modelData, ObjectiveMetric.OFF_TIME_DELIVERY, OptimizationDirection.MAXIMIZE));

        for (SolveResults result : results) {
            printResult(result);
        }

        printRanges(results);
    }

    private static void printResult(SolveResults result) {
        System.out.println("--------------------------------------------------");
        System.out.println("Objective metric: " + result.getObjectiveMetric());
        System.out.println("Direction: " + result.getDirection());
        System.out.println("Objective value: " + result.getObjectiveValue());
        System.out.println("Total cost: " + result.getKpis().getTotalCost());
        System.out.println("Average transit time: " + result.getKpis().getAverageTransitTime());
        System.out.println("Average claims: " + result.getKpis().getAverageClaims());
        System.out.println("Average off-time delivery: " + result.getKpis().getAverageOffTimeDelivery());
        System.out.println("Assignments returned: " + result.getAssignments().size());
    }

    private static void printRanges(List<SolveResults> results) {
        double costMin = findKpiValue(results, ObjectiveMetric.COST, OptimizationDirection.MINIMIZE);
        double costMax = findKpiValue(results, ObjectiveMetric.COST, OptimizationDirection.MAXIMIZE);

        double transitMin = findKpiValue(results, ObjectiveMetric.TRANSIT_TIME, OptimizationDirection.MINIMIZE);
        double transitMax = findKpiValue(results, ObjectiveMetric.TRANSIT_TIME, OptimizationDirection.MAXIMIZE);

        double claimsMin = findKpiValue(results, ObjectiveMetric.CLAIMS, OptimizationDirection.MINIMIZE);
        double claimsMax = findKpiValue(results, ObjectiveMetric.CLAIMS, OptimizationDirection.MAXIMIZE);

        double offTimeMin = findKpiValue(results, ObjectiveMetric.OFF_TIME_DELIVERY, OptimizationDirection.MINIMIZE);
        double offTimeMax = findKpiValue(results, ObjectiveMetric.OFF_TIME_DELIVERY, OptimizationDirection.MAXIMIZE);

        System.out.println("==================================================");
        System.out.println("KPI RANGE SUMMARY");
        System.out.println("Cost min: " + costMin);
        System.out.println("Cost max: " + costMax);
        System.out.println("Cost range: " + (costMax - costMin));
        System.out.println();
        System.out.println("Average transit min: " + transitMin);
        System.out.println("Average transit max: " + transitMax);
        System.out.println("Average transit range: " + (transitMax - transitMin));
        System.out.println();
        System.out.println("Average claims min: " + claimsMin);
        System.out.println("Average claims max: " + claimsMax);
        System.out.println("Average claims range: " + (claimsMax - claimsMin));
        System.out.println();
        System.out.println("Average off-time min: " + offTimeMin);
        System.out.println("Average off-time max: " + offTimeMax);
        System.out.println("Average off-time range: " + (offTimeMax - offTimeMin));
        System.out.println("==================================================");
    }

    private static double findKpiValue(
            List<SolveResults> results,
            ObjectiveMetric metric,
            OptimizationDirection direction) {

        for (SolveResults result : results) {
            if (result.getObjectiveMetric() == metric && result.getDirection() == direction) {
                return switch (metric) {
                    case COST -> result.getKpis().getTotalCost();
                    case TRANSIT_TIME -> result.getKpis().getAverageTransitTime();
                    case CLAIMS -> result.getKpis().getAverageClaims();
                    case OFF_TIME_DELIVERY -> result.getKpis().getAverageOffTimeDelivery();
                };
            }
        }

        throw new IllegalArgumentException("Missing result for " + metric + " " + direction);
    }

    private static double findObjectiveValue(
            List<SolveResults> results,
            ObjectiveMetric metric,
            OptimizationDirection direction) {

        for (SolveResults result : results) {
            if (result.getObjectiveMetric() == metric && result.getDirection() == direction) {
                return result.getObjectiveValue();
            }
        }

        throw new IllegalArgumentException("Missing result for " + metric + " " + direction);
    }
}
