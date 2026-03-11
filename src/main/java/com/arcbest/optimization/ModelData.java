package com.arcbest.optimization;

import java.util.List;
import java.util.Map;

public class ModelData {
    private final List<Shipment> shipments;
    private final Map<String, Carrier> carriers;
    private final GlobalParameters globalParameters;

    public ModelData(
            List<Shipment> shipments,
            Map<String, Carrier> carriers,
            GlobalParameters globalParameters) {
        this.shipments = shipments;
        this.carriers = carriers;
        this.globalParameters = globalParameters;
    }

    public List<Shipment> getShipments() {
        return shipments;
    }

    public Map<String, Carrier> getCarriers() {
        return carriers;
    }

    public GlobalParameters getGlobalParameters() {
        return globalParameters;
    }
}
