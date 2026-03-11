package com.arcbest.optimization;

public class KPISnapshot {
    private final double totalCost;
    private final double averageTransitTime;
    private final double averageClaims;
    private final double averageOffTimeDelivery;

    public KPISnapshot(
            double totalCost,
            double averageTransitTime,
            double averageClaims,
            double averageOffTimeDelivery) {
        this.totalCost = totalCost;
        this.averageTransitTime = averageTransitTime;
        this.averageClaims = averageClaims;
        this.averageOffTimeDelivery = averageOffTimeDelivery;
    }

    public double getTotalCost() {
        return totalCost;
    }

    public double getAverageTransitTime() {
        return averageTransitTime;
    }

    public double getAverageClaims() {
        return averageClaims;
    }

    public double getAverageOffTimeDelivery() {
        return averageOffTimeDelivery;
    }
}

