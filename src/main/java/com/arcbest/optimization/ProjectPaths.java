package com.arcbest.optimization;

import java.nio.file.Path;

public final class ProjectPaths {
    private ProjectPaths() {
    }

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));

    public static String shipmentData() {
        return PROJECT_ROOT.resolve("Shipment Data.csv").toString();
    }

    public static String carrierMetrics() {
        return PROJECT_ROOT.resolve("Carrier Metrics.csv").toString();
    }

    public static String transitTime() {
        return PROJECT_ROOT.resolve("Transit Time.csv").toString();
    }
}
