package com.arcbest.optimization;

import java.util.List;

public class Shipment {
    private final String id;
    private final boolean retailRequired;
    private final boolean liftgateRequired;
    private final List<ShipmentOption> options;

    public Shipment(
            String id,
            boolean retailRequired,
            boolean liftgateRequired,
            List<ShipmentOption> options) {
        this.id = id;
        this.retailRequired = retailRequired;
        this.liftgateRequired = liftgateRequired;
        this.options = options;
    }

    public String getId() {
        return id;
    }

    public boolean isRetailRequired() {
        return retailRequired;
    }

    public boolean isLiftgateRequired() {
        return liftgateRequired;
    }

    public List<ShipmentOption> getOptions() {
        return options;
    }
}
