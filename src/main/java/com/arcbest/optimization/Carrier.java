package com.arcbest.optimization;

public class Carrier {
    private final String id;
    private final double claimsRate;
    private final double offTimeDeliveryRate;
    private final boolean retailCompliant;
    private final boolean liftgateCapable;

    public Carrier(
            String id,
            double claimsRate,
            double offTimeDeliveryRate,
            boolean retailCompliant,
            boolean liftgateCapable) {
        this.id = id;
        this.claimsRate = claimsRate;
        this.offTimeDeliveryRate = offTimeDeliveryRate;
        this.retailCompliant = retailCompliant;
        this.liftgateCapable = liftgateCapable;
    }

    public String getId() {
        return id;
    }

    public double getClaimsRate() {
        return claimsRate;
    }

    public double getOffTimeDeliveryRate() {
        return offTimeDeliveryRate;
    }

    public boolean isRetailCompliant() {
        return retailCompliant;
    }

    public boolean isLiftgateCapable() {
        return liftgateCapable;
    }
}
