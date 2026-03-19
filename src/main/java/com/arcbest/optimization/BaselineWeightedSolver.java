package com.arcbest.optimization;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BaselineWeightedSolver {
    public OptimizationModels.BaselineResult solve(
            ModelData modelData,
            OptimizationModels.BaselineWeights weights) {
        Loader.loadNativeLibraries();

        MPSolver solver = MPSolver.createSolver("SCIP");
        if (solver == null) {
            solver = MPSolver.createSolver("CBC_MIXED_INTEGER_PROGRAMMING");
        }
        if (solver == null) {
            throw new IllegalStateException("Could not create OR-Tools solver.");
        }

        List<Shipment> shipments = modelData.getShipments();
        Map<String, Carrier> carriers = modelData.getCarriers();
        GlobalParameters globalParameters = modelData.getGlobalParameters();

        Map<String, MPVariable> carrierSelectedVars = new LinkedHashMap<>();
        Map<String, Map<String, MPVariable>> assignmentVars = new LinkedHashMap<>();

        for (String carrierId : carriers.keySet()) {
            carrierSelectedVars.put(carrierId, solver.makeBoolVar("y_" + sanitize(carrierId)));
        }

        for (Shipment shipment : shipments) {
            MPConstraint assignExactlyOne = solver.makeConstraint(1.0, 1.0, "assign_" + shipment.getId());
            Map<String, MPVariable> shipmentAssignmentVars = new LinkedHashMap<>();
            assignmentVars.put(shipment.getId(), shipmentAssignmentVars);

            for (ShipmentOption option : shipment.getOptions()) {
                String carrierId = option.getCarrierId();
                MPVariable assignmentVar = solver.makeBoolVar(
                        "x_" + sanitize(shipment.getId()) + "_" + sanitize(carrierId));
                shipmentAssignmentVars.put(carrierId, assignmentVar);

                if (option.isFeasible()) {
                    assignExactlyOne.setCoefficient(assignmentVar, 1.0);
                } else {
                    MPConstraint infeasibleConstraint = solver.makeConstraint(0.0, 0.0);
                    infeasibleConstraint.setCoefficient(assignmentVar, 1.0);
                }

                MPConstraint assignedImpliesSelected = solver.makeConstraint(
                        Double.NEGATIVE_INFINITY, 0.0);
                assignedImpliesSelected.setCoefficient(assignmentVar, 1.0);
                assignedImpliesSelected.setCoefficient(carrierSelectedVars.get(carrierId), -1.0);
            }
        }

        for (String carrierId : carriers.keySet()) {
            MPVariable selectedVar = carrierSelectedVars.get(carrierId);

            MPConstraint selectedNeedsShipment = solver.makeConstraint(
                    0.0, Double.POSITIVE_INFINITY, "selected_needs_shipment_" + sanitize(carrierId));
            selectedNeedsShipment.setCoefficient(selectedVar, -1.0);

            double maxShipmentsAllowed =
                    Math.floor(globalParameters.getDefaultMaxShipmentShare() * shipments.size());

            MPConstraint maxShipmentsConstraint = solver.makeConstraint(
                    Double.NEGATIVE_INFINITY,
                    0.0,
                    "max_shipments_" + sanitize(carrierId));
            MPConstraint minSpendConstraint = solver.makeConstraint(
                    0.0,
                    Double.POSITIVE_INFINITY,
                    "min_spend_" + sanitize(carrierId));

            maxShipmentsConstraint.setCoefficient(selectedVar, -maxShipmentsAllowed);
            minSpendConstraint.setCoefficient(selectedVar, -globalParameters.getDefaultMinSpend());

            for (Shipment shipment : shipments) {
                MPVariable assignmentVar = assignmentVars.get(shipment.getId()).get(carrierId);
                if (assignmentVar == null) {
                    continue;
                }

                selectedNeedsShipment.setCoefficient(assignmentVar, 1.0);
                maxShipmentsConstraint.setCoefficient(assignmentVar, 1.0);

                ShipmentOption option = findOption(shipment, carrierId);
                if (option != null) {
                    minSpendConstraint.setCoefficient(assignmentVar, option.getCost());
                }
            }
        }

        MPConstraint minCarriersConstraint = solver.makeConstraint(
                globalParameters.getMinCarriers(),
                Double.POSITIVE_INFINITY,
                "min_carriers");
        MPConstraint maxCarriersConstraint = solver.makeConstraint(
                Double.NEGATIVE_INFINITY,
                globalParameters.getMaxCarriers(),
                "max_carriers");

        for (MPVariable selectedVar : carrierSelectedVars.values()) {
            minCarriersConstraint.setCoefficient(selectedVar, 1.0);
            maxCarriersConstraint.setCoefficient(selectedVar, 1.0);
        }

        Map<String, Double> claimsNorm = buildClaimsNormalization(carriers);
        Map<String, Double> offTimeNorm = buildOffTimeNormalization(carriers);

        MPObjective objective = solver.objective();
        objective.setMinimization();

        for (Shipment shipment : shipments) {
            Map<String, Double> costNorm = buildShipmentCostNormalization(shipment);
            Map<String, Double> transitNorm = buildShipmentTransitNormalization(shipment);

            for (ShipmentOption option : shipment.getOptions()) {
                MPVariable assignmentVar = assignmentVars.get(shipment.getId()).get(option.getCarrierId());
                if (assignmentVar == null) {
                    continue;
                }

                double coefficient =
                        weights.costWeight() * costNorm.getOrDefault(option.getCarrierId(), 0.0)
                                + weights.transitWeight() * transitNorm.getOrDefault(option.getCarrierId(), 0.0)
                                + weights.claimsWeight() * claimsNorm.getOrDefault(option.getCarrierId(), 0.0)
                                + weights.offTimeWeight() * offTimeNorm.getOrDefault(option.getCarrierId(), 0.0);

                objective.setCoefficient(assignmentVar, coefficient);
            }
        }

        MPSolver.ResultStatus status = solver.solve();
        if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
            throw new IllegalStateException("No feasible solution found. Solver status: " + status);
        }

        List<AssignmentRecord> assignments = extractAssignments(shipments, carriers, assignmentVars);
        KPISnapshot kpiSnapshot = calculateKPIs(assignments);

        return new OptimizationModels.BaselineResult(objective.value(), kpiSnapshot, assignments);
    }

    private Map<String, Double> buildClaimsNormalization(Map<String, Carrier> carriers) {
        double minClaims = Double.POSITIVE_INFINITY;
        double maxClaims = Double.NEGATIVE_INFINITY;

        for (Carrier carrier : carriers.values()) {
            minClaims = Math.min(minClaims, carrier.getClaimsRate());
            maxClaims = Math.max(maxClaims, carrier.getClaimsRate());
        }

        Map<String, Double> claimsNorm = new LinkedHashMap<>();
        for (Carrier carrier : carriers.values()) {
            claimsNorm.put(
                    carrier.getId(),
                    normalizeLowerBetter(carrier.getClaimsRate(), minClaims, maxClaims));
        }

        return claimsNorm;
    }

    private Map<String, Double> buildOffTimeNormalization(Map<String, Carrier> carriers) {
        double minOffTime = Double.POSITIVE_INFINITY;
        double maxOffTime = Double.NEGATIVE_INFINITY;

        for (Carrier carrier : carriers.values()) {
            minOffTime = Math.min(minOffTime, carrier.getOffTimeDeliveryRate());
            maxOffTime = Math.max(maxOffTime, carrier.getOffTimeDeliveryRate());
        }

        Map<String, Double> offTimeNorm = new LinkedHashMap<>();
        for (Carrier carrier : carriers.values()) {
            offTimeNorm.put(
                    carrier.getId(),
                    normalizeLowerBetter(carrier.getOffTimeDeliveryRate(), minOffTime, maxOffTime));
        }

        return offTimeNorm;
    }

    private Map<String, Double> buildShipmentCostNormalization(Shipment shipment) {
        double minCost = Double.POSITIVE_INFINITY;
        double maxCost = Double.NEGATIVE_INFINITY;

        for (ShipmentOption option : shipment.getOptions()) {
            if (!option.isFeasible()) {
                continue;
            }
            minCost = Math.min(minCost, option.getCost());
            maxCost = Math.max(maxCost, option.getCost());
        }

        Map<String, Double> costNorm = new LinkedHashMap<>();
        for (ShipmentOption option : shipment.getOptions()) {
            if (!option.isFeasible()) {
                continue;
            }
            costNorm.put(
                    option.getCarrierId(),
                    normalizeLowerBetter(option.getCost(), minCost, maxCost));
        }

        return costNorm;
    }

    private Map<String, Double> buildShipmentTransitNormalization(Shipment shipment) {
        double minTransit = Double.POSITIVE_INFINITY;
        double maxTransit = Double.NEGATIVE_INFINITY;

        for (ShipmentOption option : shipment.getOptions()) {
            if (!option.isFeasible()) {
                continue;
            }
            minTransit = Math.min(minTransit, option.getTransitTime());
            maxTransit = Math.max(maxTransit, option.getTransitTime());
        }

        Map<String, Double> transitNorm = new LinkedHashMap<>();
        for (ShipmentOption option : shipment.getOptions()) {
            if (!option.isFeasible()) {
                continue;
            }
            transitNorm.put(
                    option.getCarrierId(),
                    normalizeLowerBetter(option.getTransitTime(), minTransit, maxTransit));
        }

        return transitNorm;
    }

    private double normalizeLowerBetter(double value, double minValue, double maxValue) {
        if (Double.compare(minValue, maxValue) == 0) {
            return 0.0;
        }
        return (value - minValue) / (maxValue - minValue);
    }

    private List<AssignmentRecord> extractAssignments(
            List<Shipment> shipments,
            Map<String, Carrier> carriers,
            Map<String, Map<String, MPVariable>> assignmentVars) {

        List<AssignmentRecord> assignments = new ArrayList<>();

        for (Shipment shipment : shipments) {
            for (ShipmentOption option : shipment.getOptions()) {
                MPVariable assignmentVar = assignmentVars.get(shipment.getId()).get(option.getCarrierId());
                if (assignmentVar != null && assignmentVar.solutionValue() > 0.5) {
                    Carrier carrier = carriers.get(option.getCarrierId());
                    assignments.add(new AssignmentRecord(
                            shipment.getId(),
                            option.getCarrierId(),
                            option.getCost(),
                            option.getTransitTime(),
                            carrier.getClaimsRate(),
                            carrier.getOffTimeDeliveryRate()
                    ));
                    break;
                }
            }
        }

        return assignments;
    }

    private KPISnapshot calculateKPIs(List<AssignmentRecord> assignments) {
        double totalCost = 0.0;
        double totalTransitTime = 0.0;
        double totalClaims = 0.0;
        double totalOffTimeDelivery = 0.0;

        for (AssignmentRecord assignment : assignments) {
            totalCost += assignment.getCost();
            totalTransitTime += assignment.getTransitTime();
            totalClaims += assignment.getClaimsRate();
            totalOffTimeDelivery += assignment.getOffTimeDeliveryRate();
        }

        int count = assignments.size();

        double averageTransitTime = count == 0 ? 0.0 : totalTransitTime / count;
        double averageClaims = count == 0 ? 0.0 : totalClaims / count;
        double averageOffTimeDelivery = count == 0 ? 0.0 : totalOffTimeDelivery / count;

        return new KPISnapshot(
                totalCost,
                averageTransitTime,
                averageClaims,
                averageOffTimeDelivery
        );
    }

    private ShipmentOption findOption(Shipment shipment, String carrierId) {
        for (ShipmentOption option : shipment.getOptions()) {
            if (option.getCarrierId().equals(carrierId)) {
                return option;
            }
        }
        return null;
    }

    private String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
