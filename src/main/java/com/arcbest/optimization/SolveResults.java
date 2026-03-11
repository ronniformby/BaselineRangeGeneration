package com.arcbest.optimization;

import java.util.List;

public class SolveResults {
    private final ObjectiveMetric objectiveMetric;
    private final OptimizationDirection direction;
    private final double objectiveValue;
    private final KPISnapshot kpis;
    private final List<AssignmentRecord> assignments;

    public SolveResults(
            ObjectiveMetric objectiveMetric,
            OptimizationDirection direction,
            double objectiveValue,
            KPISnapshot kpis,
            List<AssignmentRecord> assignments) {
        this.objectiveMetric = objectiveMetric;
        this.direction = direction;
        this.objectiveValue = objectiveValue;
        this.kpis = kpis;
        this.assignments = assignments;
    }

    public ObjectiveMetric getObjectiveMetric() {
        return objectiveMetric;
    }

    public OptimizationDirection getDirection() {
        return direction;
    }

    public double getObjectiveValue() {
        return objectiveValue;
    }

    public KPISnapshot getKpis() {
        return kpis;
    }

    public List<AssignmentRecord> getAssignments() {
        return assignments;
    }
}
