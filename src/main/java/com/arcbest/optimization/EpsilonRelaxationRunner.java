package com.arcbest.optimization;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.imageio.ImageIO;

public class EpsilonRelaxationRunner {
    private static final double START_RELAXATION_PERCENT = 0.01;
    private static final double RELAXATION_STEP_PERCENT = 0.05;
    private static final double MAX_RELAXATION_PERCENT = 1.0;
    private static final double RHO = 0.08;
    private static final double START_GAMMA = 0.99;
    private static final double GAMMA_STEP = 0.05;
    private static final double MIN_GAMMA = 0.0;

    public static void main(String[] args) throws Exception {
        DataLoader dataLoader = new DataLoader();
        ModelData modelData = dataLoader.load(
                ProjectPaths.shipmentData(),
                ProjectPaths.carrierMetrics(),
                ProjectPaths.transitTime()
        );

        run(modelData);
    }

    public static void run(ModelData modelData) {
        BaselineWeightedSolver baselineSolver = new BaselineWeightedSolver();
        OptimizationModels.BaselineResult baselineResults = baselineSolver.solve(
                modelData,
                new OptimizationModels.BaselineWeights(0.25, 0.25, 0.25, 0.25)
        );

        printBaselineResults(baselineResults);

        OptimizationModels.KPIRanges kpiRanges = RangeGenerationRunner.computeRanges(modelData, false);
        EpsilonRelaxationSolver relaxationSolver = new EpsilonRelaxationSolver();
        List<Candidate> feasibleCandidates = new ArrayList<>();

        for (double gamma = START_GAMMA; gamma >= MIN_GAMMA - 1e-9; gamma -= GAMMA_STEP) {
            double normalizedGamma = Math.max(gamma, 0.0);
            List<Candidate> gammaCandidates = new ArrayList<>();
            for (double relaxationPercent = START_RELAXATION_PERCENT;
                 relaxationPercent <= MAX_RELAXATION_PERCENT + 1e-9;
                 relaxationPercent = nextRelaxationPercent(relaxationPercent)) {
                OptimizationModels.EpsilonRequest request = new OptimizationModels.EpsilonRequest(
                        ObjectiveMetric.TRANSIT_TIME,
                        relaxationPercent,
                        RHO,
                        normalizedGamma
                );

                try {
                    OptimizationModels.EpsilonResult results = relaxationSolver.solve(
                            modelData,
                            baselineResults,
                            kpiRanges,
                            request
                    );
                    feasibleCandidates.add(new Candidate(
                            results,
                            relaxationPercent,
                            normalizedGamma
                    ));
                    gammaCandidates.add(new Candidate(
                            results,
                            relaxationPercent,
                            normalizedGamma
                    ));
                } catch (IllegalStateException ex) {
                    // Keep relaxing epsilon until a feasible scenario is found for this gamma.
                }
            }
            if (!gammaCandidates.isEmpty()) {
                printEpsilonBreakdownForGamma(normalizedGamma, gammaCandidates);
            }
        }

        writeCandidateArtifacts(feasibleCandidates);

        if (!feasibleCandidates.isEmpty()) {
            Candidate bestCandidate = feasibleCandidates.stream()
                    .max(Comparator
                            .comparingDouble((Candidate candidate) -> candidate.results.relativeImprovement())
                            .thenComparing(Comparator.comparingDouble((Candidate candidate) -> candidate.relaxationPercent).reversed())
                            .thenComparingDouble(candidate -> candidate.results.shipmentChangeShare()))
                    .orElseThrow();

            printResult(
                    bestCandidate.results,
                    bestCandidate.relaxationPercent,
                    bestCandidate.gamma,
                    feasibleCandidates.size()
            );
            return;
        }

        System.out.printf(
                "No meaningful epsilon-relaxation scenario found between %.3f%% and %.3f%% with gamma between %.3f%% and %.3f%%.%n",
                START_RELAXATION_PERCENT * 100.0,
                MAX_RELAXATION_PERCENT * 100.0,
                START_GAMMA * 100.0,
                MIN_GAMMA * 100.0
        );
    }

    private static void printBaselineResults(OptimizationModels.BaselineResult baselineResults) {
        System.out.println("==================================================");
        System.out.println("BASELINE RESULTS");
        System.out.println("Baseline blended objective value: " + baselineResults.objectiveValue());
        System.out.println("Total cost: " + baselineResults.kpis().getTotalCost());
        System.out.println("Average transit time: " + baselineResults.kpis().getAverageTransitTime());
        System.out.println("Average claims: " + baselineResults.kpis().getAverageClaims());
        System.out.println("Average off-time delivery: " + baselineResults.kpis().getAverageOffTimeDelivery());
        System.out.println("Assignments returned: " + baselineResults.assignments().size());
        System.out.println("==================================================");
    }

    private static double nextRelaxationPercent(double currentRelaxationPercent) {
        return currentRelaxationPercent + RELAXATION_STEP_PERCENT;
    }

    private static void printResult(
            OptimizationModels.EpsilonResult results,
            double relaxationPercent,
            double gamma,
            int feasibleCandidateCount) {
        System.out.println("==================================================");
        System.out.println("EPSILON RELAXATION RESULT");
        System.out.println("Feasible candidates found: " + feasibleCandidateCount);
        System.out.println("Target objective: " + results.targetObjective());
        System.out.println("Relaxation percent: " + relaxationPercent);
        System.out.println("Gamma used: " + gamma);
        System.out.println("Objective value: " + results.objectiveValue());
        System.out.println("Total cost: " + results.kpis().getTotalCost());
        System.out.println("Average transit time: " + results.kpis().getAverageTransitTime());
        System.out.println("Average claims: " + results.kpis().getAverageClaims());
        System.out.println("Average off-time delivery: " + results.kpis().getAverageOffTimeDelivery());
        System.out.println("Cost epsilon: " + results.epsilonValues().costEpsilon());
        System.out.println("Claims epsilon: " + results.epsilonValues().claimsEpsilon());
        System.out.println("Off-time epsilon: " + results.epsilonValues().offTimeEpsilon());
        System.out.println("Shipment change share: " + results.shipmentChangeShare());
        System.out.println("Relative improvement: " + results.relativeImprovement());
        System.out.println("Assignments returned: " + results.assignments().size());
        System.out.println("==================================================");
    }

    private static void writeCandidateArtifacts(List<Candidate> feasibleCandidates) {
        Path reportsDir = Path.of("build", "reports");
        Path pngPath = reportsDir.resolve("epsilon_feasible_candidates.png");
        try {
            Files.createDirectories(reportsDir);
            writeCandidatesScatter(feasibleCandidates, pngPath);
            System.out.println("Feasible candidate plot: " + pngPath.toAbsolutePath());
        } catch (IOException ioException) {
            System.err.println("Could not write candidate artifacts: " + ioException.getMessage());
        }
    }

    private static void writeCandidatesScatter(List<Candidate> feasibleCandidates, Path pngPath) throws IOException {
        int width = 1100;
        int height = 700;
        int left = 90;
        int right = 40;
        int top = 50;
        int bottom = 80;
        int plotWidth = width - left - right;
        int plotHeight = height - top - bottom;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);

        g.setColor(new Color(40, 40, 40));
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        g.drawString("Feasible Epsilon Candidates", left, 28);

        g.setStroke(new BasicStroke(1.2f));
        g.drawLine(left, height - bottom, width - right, height - bottom);
        g.drawLine(left, top, left, height - bottom);

        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.drawString("Epsilon (%)", width / 2 - 25, height - 25);
        g.drawString("Relative Improvement (%)", 10, top - 10);

        if (!feasibleCandidates.isEmpty()) {
            double minE = feasibleCandidates.stream().mapToDouble(c -> c.relaxationPercent * 100.0).min().orElse(0.0);
            double maxE = feasibleCandidates.stream().mapToDouble(c -> c.relaxationPercent * 100.0).max().orElse(1.0);
            double minR = feasibleCandidates.stream().mapToDouble(c -> c.results.relativeImprovement() * 100.0).min().orElse(0.0);
            double maxR = feasibleCandidates.stream().mapToDouble(c -> c.results.relativeImprovement() * 100.0).max().orElse(1.0);

            if (Math.abs(maxE - minE) < 1e-9) {
                maxE = minE + 1.0;
            }
            if (Math.abs(maxR - minR) < 1e-9) {
                maxR = minR + 1.0;
            }

            g.setColor(new Color(160, 160, 160));
            for (int i = 0; i <= 10; i++) {
                int gx = left + (int) Math.round((i / 10.0) * plotWidth);
                int gy = top + (int) Math.round((i / 10.0) * plotHeight);
                g.drawLine(gx, top, gx, height - bottom);
                g.drawLine(left, gy, width - right, gy);
            }

            g.setColor(new Color(20, 20, 20));
            for (int i = 0; i <= 5; i++) {
                double xVal = minE + (i / 5.0) * (maxE - minE);
                int x = left + (int) Math.round(((xVal - minE) / (maxE - minE)) * plotWidth);
                g.drawString(String.format("%.1f", xVal), x - 10, height - bottom + 20);

                double yVal = maxR - (i / 5.0) * (maxR - minR);
                int y = top + (int) Math.round((i / 5.0) * plotHeight);
                g.drawString(String.format("%.2f", yVal), left - 70, y + 4);
            }

            for (Candidate candidate : feasibleCandidates) {
                double e = candidate.relaxationPercent * 100.0;
                double r = candidate.results.relativeImprovement() * 100.0;
                int x = left + (int) Math.round(((e - minE) / (maxE - minE)) * plotWidth);
                int y = top + (int) Math.round((1.0 - ((r - minR) / (maxR - minR))) * plotHeight);

                float hue = (float) (0.75 - 0.75 * (candidate.gamma / Math.max(START_GAMMA, 1e-9)));
                g.setColor(Color.getHSBColor(Math.max(0f, Math.min(1f, hue)), 0.85f, 0.85f));
                g.fillOval(x - 4, y - 4, 8, 8);
            }
        } else {
            g.setColor(new Color(80, 80, 80));
            g.drawString("No feasible candidates found.", left + 20, top + 30);
        }

        g.dispose();
        ImageIO.write(image, "png", pngPath.toFile());
    }

    private static void printEpsilonBreakdownForGamma(
            double gamma,
            List<Candidate> gammaCandidates) {
        System.out.println("--------------------------------------------------");
        System.out.printf("Feasible candidates for gamma %.2f%%%n", gamma * 100.0);
        for (Candidate candidate : gammaCandidates) {
            System.out.printf(
                    "epsilon=%.2f%%, relativeImprovement=%.4f%%, shipmentChangeShare=%.4f%%, avgTransit=%.4f%n",
                    candidate.relaxationPercent * 100.0,
                    candidate.results.relativeImprovement() * 100.0,
                    candidate.results.shipmentChangeShare() * 100.0,
                    candidate.results.kpis().getAverageTransitTime()
            );
        }
    }

    private static final class Candidate {
        private final OptimizationModels.EpsilonResult results;
        private final double relaxationPercent;
        private final double gamma;

        private Candidate(
                OptimizationModels.EpsilonResult results,
                double relaxationPercent,
                double gamma) {
            this.results = results;
            this.relaxationPercent = relaxationPercent;
            this.gamma = gamma;
        }
    }
}
