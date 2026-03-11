package com.arcbest.optimization;

public class AssignmentRecord {
    private final String shipmentId;
    private final String carrierId;
    private final double cost;
    private final double transitTime;
    private final double claimsRate;
    private final double offTimeDeliveryRate;

    public AssignmentRecord(
            String shipmentId,
            String carrierId,
            double cost,
            double transitTime,
            double claimsRate,
            double offTimeDeliveryRate) {
        this.shipmentId = shipmentId;
        this.carrierId = carrierId;
        this.cost = cost;
        this.transitTime = transitTime;
        this.claimsRate = claimsRate;
        this.offTimeDeliveryRate = offTimeDeliveryRate;
    }

    public String getShipmentId() {
        return shipmentId;
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

    public double getClaimsRate() {
        return claimsRate;
    }

    public double getOffTimeDeliveryRate() {
        return offTimeDeliveryRate;
    }
}
