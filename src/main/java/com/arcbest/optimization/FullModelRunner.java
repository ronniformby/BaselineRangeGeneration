package com.arcbest.optimization;

public class FullModelRunner {
    public static void main(String[] args) throws Exception {
        DataLoader dataLoader = new DataLoader();
        ModelData modelData = dataLoader.load(
                ProjectPaths.shipmentData(),
                ProjectPaths.carrierMetrics(),
                ProjectPaths.transitTime()
        );

        BaselineRunner.run(modelData);
        RangeGenerationRunner.run(modelData);
    }
}
