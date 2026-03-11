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

public class ObjectiveBoundsSolver {
    public SolveResults solve(
            ModelData modelData,
            ObjectiveMetric objectiveMetric,
            OptimizationDirection direction) {

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
                    Math.ceil(globalParameters.getDefaultMaxShipmentShare() * shipments.size());

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

        MPObjective objective = solver.objective();
        applyObjective(objective, shipments, carriers, assignmentVars, objectiveMetric, direction);

        MPSolver.ResultStatus status = solver.solve();
        if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
            throw new IllegalStateException("No feasible solution found. Solver status: " + status);
        }

        List<AssignmentRecord> assignments = extractAssignments(shipments, carriers, assignmentVars);
        KPISnapshot kpiSnapshot = calculateKPIs(assignments);

        return new SolveResults(
                objectiveMetric,
                direction,
                objective.value(),
                kpiSnapshot,
                assignments
        );
    }

    private void applyObjective(
            MPObjective objective,
            List<Shipment> shipments,
            Map<String, Carrier> carriers,
            Map<String, Map<String, MPVariable>> assignmentVars,
            ObjectiveMetric objectiveMetric,
            OptimizationDirection direction) {

        if (direction == OptimizationDirection.MINIMIZE) {
            objective.setMinimization();
        } else {
            objective.setMaximization();
        }

        for (Shipment shipment : shipments) {
            for (ShipmentOption option : shipment.getOptions()) {
                MPVariable assignmentVar = assignmentVars.get(shipment.getId()).get(option.getCarrierId());
                Carrier carrier = carriers.get(option.getCarrierId());

                double coefficient = switch (objectiveMetric) {
                    case COST -> option.getCost();
                    case TRANSIT_TIME -> option.getTransitTime();
                    case CLAIMS -> carrier.getClaimsRate();
                    case OFF_TIME_DELIVERY -> carrier.getOffTimeDeliveryRate();
                };

                objective.setCoefficient(assignmentVar, coefficient);
            }
        }
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
