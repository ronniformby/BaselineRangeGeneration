package com.arcbest.optimization;

public class BaselineRunner {
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
        run(modelData, new OptimizationModels.BaselineWeights(0.25, 0.25, 0.25, 0.25));
    }

    public static void run(ModelData modelData, OptimizationModels.BaselineWeights weights) {
        BaselineWeightedSolver solver = new BaselineWeightedSolver();
        OptimizationModels.BaselineResult results = solver.solve(
                modelData,
                weights
        );

        System.out.println("==================================================");
        System.out.println("BASELINE RESULTS");
        System.out.println("Baseline blended objective value: " + results.objectiveValue());
        System.out.println("Total cost: " + results.kpis().getTotalCost());
        System.out.println("Average transit time: " + results.kpis().getAverageTransitTime());
        System.out.println("Average claims: " + results.kpis().getAverageClaims());
        System.out.println("Average off-time delivery: " + results.kpis().getAverageOffTimeDelivery());
        System.out.println("Assignments returned: " + results.assignments().size());
        System.out.println("==================================================");
    }
}
