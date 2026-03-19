package com.arcbest.optimization;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EpsilonRelaxationSolver {
    public OptimizationModels.EpsilonResult solve(
            ModelData modelData,
            OptimizationModels.BaselineResult baselineResults,
            OptimizationModels.KPIRanges kpiRanges,
            OptimizationModels.EpsilonRequest request) {

        OptimizationModels.EpsilonValues epsilonValues = OptimizationModels.EpsilonValues.fromRanges(
                kpiRanges,
                request.relaxationPercent()
        );

        if (request.targetObjective() != ObjectiveMetric.TRANSIT_TIME) {
            throw new UnsupportedOperationException(
                    "Current epsilon relaxation implementation is set up for TRANSIT_TIME as the target objective.");
        }

        Map<String, String> baselineAssignmentByShipment = toAssignmentMap(baselineResults);

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

        addBaselineRelaxationConstraints(
                solver,
                shipments,
                carriers,
                assignmentVars,
                baselineResults,
                baselineAssignmentByShipment,
                epsilonValues,
                request
        );

        MPObjective objective = solver.objective();
        objective.setMinimization();
        for (Shipment shipment : shipments) {
            for (ShipmentOption option : shipment.getOptions()) {
                MPVariable assignmentVar = assignmentVars.get(shipment.getId()).get(option.getCarrierId());
                if (assignmentVar != null) {
                    objective.setCoefficient(assignmentVar, option.getTransitTime());
                }
            }
        }

        MPSolver.ResultStatus status = solver.solve();
        if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
            throw new IllegalStateException("No feasible epsilon-relaxation solution found. Solver status: " + status);
        }

        List<AssignmentRecord> assignments = extractAssignments(shipments, carriers, assignmentVars);
        KPISnapshot kpis = calculateKPIs(assignments);
        double shipmentChangeShare = calculateShipmentChangeShare(assignments, baselineAssignmentByShipment);
        double relativeImprovement = calculateRelativeImprovement(
                baselineResults.kpis().getAverageTransitTime(),
                kpis.getAverageTransitTime()
        );

        return new OptimizationModels.EpsilonResult(
                request.targetObjective(),
                kpis.getAverageTransitTime(),
                kpis,
                epsilonValues,
                shipmentChangeShare,
                relativeImprovement,
                assignments
        );
    }

    private void addBaselineRelaxationConstraints(
            MPSolver solver,
            List<Shipment> shipments,
            Map<String, Carrier> carriers,
            Map<String, Map<String, MPVariable>> assignmentVars,
            OptimizationModels.BaselineResult baselineResults,
            Map<String, String> baselineAssignmentByShipment,
            OptimizationModels.EpsilonValues epsilonValues,
            OptimizationModels.EpsilonRequest request) {

        int shipmentCount = shipments.size();

        MPConstraint costConstraint = solver.makeConstraint(
                Double.NEGATIVE_INFINITY,
                baselineResults.kpis().getTotalCost() + epsilonValues.costEpsilon(),
                "baseline_cost_epsilon");

        MPConstraint claimsConstraint = solver.makeConstraint(
                Double.NEGATIVE_INFINITY,
                baselineResults.kpis().getAverageClaims() + epsilonValues.claimsEpsilon(),
                "baseline_claims_epsilon");

        MPConstraint offTimeConstraint = solver.makeConstraint(
                Double.NEGATIVE_INFINITY,
                baselineResults.kpis().getAverageOffTimeDelivery() + epsilonValues.offTimeEpsilon(),
                "baseline_offtime_epsilon");

        double maxSameAssignments = shipmentCount * (1.0 - request.minimumShipmentChangeShare());
        MPConstraint shipmentChangeConstraint = solver.makeConstraint(
                Double.NEGATIVE_INFINITY,
                maxSameAssignments,
                "minimum_shipment_change_share");

        double maxAverageTransit =
                baselineResults.kpis().getAverageTransitTime() * (1.0 - request.minimumRelativeImprovement());
        MPConstraint improvementConstraint = solver.makeConstraint(
                Double.NEGATIVE_INFINITY,
                maxAverageTransit,
                "minimum_relative_improvement");

        for (Shipment shipment : shipments) {
            String baselineCarrierId = baselineAssignmentByShipment.get(shipment.getId());
            if (baselineCarrierId == null) {
                throw new IllegalArgumentException(
                        "Missing baseline assignment for shipment " + shipment.getId());
            }

            for (ShipmentOption option : shipment.getOptions()) {
                MPVariable assignmentVar = assignmentVars.get(shipment.getId()).get(option.getCarrierId());
                if (assignmentVar == null) {
                    continue;
                }

                Carrier carrier = carriers.get(option.getCarrierId());
                costConstraint.setCoefficient(assignmentVar, option.getCost());
                claimsConstraint.setCoefficient(assignmentVar, carrier.getClaimsRate() / shipmentCount);
                offTimeConstraint.setCoefficient(assignmentVar, carrier.getOffTimeDeliveryRate() / shipmentCount);
                improvementConstraint.setCoefficient(assignmentVar, option.getTransitTime() / shipmentCount);

                if (option.getCarrierId().equals(baselineCarrierId)) {
                    shipmentChangeConstraint.setCoefficient(assignmentVar, 1.0);
                }
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

    private double calculateShipmentChangeShare(
            List<AssignmentRecord> assignments,
            Map<String, String> baselineAssignmentByShipment) {
        int changedCount = 0;

        for (AssignmentRecord assignment : assignments) {
            String baselineCarrierId = baselineAssignmentByShipment.get(assignment.getShipmentId());
            if (!assignment.getCarrierId().equals(baselineCarrierId)) {
                changedCount++;
            }
        }

        return assignments.isEmpty() ? 0.0 : (double) changedCount / assignments.size();
    }

    private double calculateRelativeImprovement(double baselineTransitAverage, double newTransitAverage) {
        if (baselineTransitAverage == 0.0) {
            return 0.0;
        }
        return (baselineTransitAverage - newTransitAverage) / baselineTransitAverage;
    }

    private Map<String, String> toAssignmentMap(OptimizationModels.BaselineResult baselineResults) {
        Map<String, String> assignmentMap = new HashMap<>();
        for (AssignmentRecord assignment : baselineResults.assignments()) {
            assignmentMap.put(assignment.getShipmentId(), assignment.getCarrierId());
        }
        return assignmentMap;
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
