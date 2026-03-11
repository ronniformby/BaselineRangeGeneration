package com.arcbest.optimization;

public class GlobalParameters {
    private final int minCarriers;
    private final int maxCarriers;
    private final double defaultMaxShipmentShare;
    private final double defaultMinSpend;

    public GlobalParameters(
            int minCarriers,
            int maxCarriers,
            double defaultMaxShipmentShare,
            double defaultMinSpend) {
        this.minCarriers = minCarriers;
        this.maxCarriers = maxCarriers;
        this.defaultMaxShipmentShare = defaultMaxShipmentShare;
        this.defaultMinSpend = defaultMinSpend;
    }

    public int getMinCarriers() {
        return minCarriers;
    }

    public int getMaxCarriers() {
        return maxCarriers;
    }

    public double getDefaultMaxShipmentShare() {
        return defaultMaxShipmentShare;
    }

    public double getDefaultMinSpend() {
        return defaultMinSpend;
    }
}
