package com.arcbest.optimization;

public class ShipmentOption {
    private final String carrierId;
    private final double cost;
    private final double transitTime;
    private final boolean feasible;

    public ShipmentOption(String carrierId, double cost, double transitTime, boolean feasible) {
        this.carrierId = carrierId;
        this.cost = cost;
        this.transitTime = transitTime;
        this.feasible = feasible;
    }

    public String getCarrierId() {
        return carrierId;
    }

    public double getCost() {
        return cost;
    }

    public double getTransitTime() {
        return transitTime;
    }

    public boolean isFeasible() {
        return feasible;
    }
}
