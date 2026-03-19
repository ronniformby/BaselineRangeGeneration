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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

public class RelaxationWithNormalizedKpisRunner {
    private static final double START_RELAXATION_PERCENT = 0.01;
    private static final double RELAXATION_STEP_PERCENT = 0.05;
    private static final double MAX_RELAXATION_PERCENT = 1.0;
    // No hard rho constraint in this runner; change share is used only in ranking/tie-breaks.
    private static final double RHO_DISABLED = 0.0;
    // Five test bands: 0.80, 0.60, 0.40, 0.20, 0.00
    private static final double START_GAMMA = 0.08;
    private static final double GAMMA_STEP = 0.01;
    private static final double MIN_GAMMA = 0.0;
    // Per-gamma plateau cutoff: stop scanning larger epsilons after repeated identical assignments.
    private static final int PLATEAU_REPEAT_LIMIT = 4;

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
                new OptimizationModels.BaselineWeights(0.55, 0.15, 0.15, 0.15)
        );

        printBaselineResults(baselineResults);

        OptimizationModels.KPIRanges kpiRanges = RangeGenerationRunner.computeRanges(modelData, false);
        NormalizedKpis baselineNormalized = normalizeKpis(baselineResults.kpis(), kpiRanges);
        printBaselineNormalizedKpis(baselineNormalized);
        EpsilonRelaxationSolver relaxationSolver = new EpsilonRelaxationSolver();
        List<ScoredCandidate> epsilonCandidates = new ArrayList<>();

        // Solve once per epsilon at the loosest gamma; then filter by gamma thresholds below.
        for (double relaxationPercent = START_RELAXATION_PERCENT;
             relaxationPercent <= MAX_RELAXATION_PERCENT + 1e-9;
             relaxationPercent = nextRelaxationPercent(relaxationPercent)) {
            OptimizationModels.EpsilonRequest request = new OptimizationModels.EpsilonRequest(
                    ObjectiveMetric.TRANSIT_TIME,
                    relaxationPercent,
                    RHO_DISABLED,
                    MIN_GAMMA
            );

            try {
                OptimizationModels.EpsilonResult result = relaxationSolver.solve(
                        modelData,
                        baselineResults,
                        kpiRanges,
                        request
                );

                ScoreBreakdown score = scoreCandidate(result, baselineNormalized, kpiRanges);
                epsilonCandidates.add(new ScoredCandidate(
                        result,
                        relaxationPercent,
                        MIN_GAMMA,
                        score
                ));
            } catch (IllegalStateException ex) {
                // Continue searching other epsilon values.
            }
        }

        List<ScoredCandidate> feasibleCandidates = new ArrayList<>();

        for (double gamma = START_GAMMA; gamma >= MIN_GAMMA - 1e-9; gamma -= GAMMA_STEP) {
            double normalizedGamma = Math.max(gamma, 0.0);
            List<ScoredCandidate> gammaCandidates = new ArrayList<>();
            ScoredCandidate previousGammaCandidate = null;
            int plateauRepeatCount = 0;
            for (ScoredCandidate baseCandidate : epsilonCandidates) {
                if (baseCandidate.result.relativeImprovement() + 1e-9 >= normalizedGamma) {
                    ScoredCandidate gammaQualifiedCandidate = new ScoredCandidate(
                            baseCandidate.result,
                            baseCandidate.relaxationPercent,
                            normalizedGamma,
                            baseCandidate.score
                    );
                    feasibleCandidates.add(gammaQualifiedCandidate);
                    gammaCandidates.add(gammaQualifiedCandidate);

                    if (previousGammaCandidate != null
                            && sameAssignmentPattern(previousGammaCandidate, gammaQualifiedCandidate)) {
                        plateauRepeatCount++;
                    } else {
                        plateauRepeatCount = 0;
                    }
                    previousGammaCandidate = gammaQualifiedCandidate;

                    if (plateauRepeatCount >= PLATEAU_REPEAT_LIMIT) {
                        System.out.printf(
                                "Plateau cutoff at gamma %.2f%% after epsilon %.2f%%%n",
                                normalizedGamma * 100.0,
                                gammaQualifiedCandidate.relaxationPercent * 100.0
                        );
                        break;
                    }
                }
            }
            if (!gammaCandidates.isEmpty()) {
                printEpsilonBreakdownForGamma(normalizedGamma, gammaCandidates);
            }
        }

        writeCandidateArtifacts(feasibleCandidates);

        if (!feasibleCandidates.isEmpty()) {
            printPortfolioResults(feasibleCandidates);
            return;
        }

        System.out.printf(
                "No meaningful normalized-KPI relaxation scenario found between %.3f%% and %.3f%% with gamma between %.3f%% and %.3f%%.%n",
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

    private static void writeCandidateArtifacts(List<ScoredCandidate> feasibleCandidates) {
        Path reportsDir = Path.of("build", "reports");
        Path pngPath = reportsDir.resolve("normalized_relaxation_feasible_candidates.png");
        try {
            Files.createDirectories(reportsDir);
            writeCandidatesScatter(feasibleCandidates, pngPath);
            System.out.println("Feasible candidate plot: " + pngPath.toAbsolutePath());
        } catch (IOException ioException) {
            System.err.println("Could not write normalized candidate artifacts: " + ioException.getMessage());
        }
    }

    private static void writeCandidatesScatter(List<ScoredCandidate> feasibleCandidates, Path pngPath) throws IOException {
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
        g.drawString("Normalized KPI Feasible Candidates", left, 28);

        g.setStroke(new BasicStroke(1.2f));
        g.drawLine(left, height - bottom, width - right, height - bottom);
        g.drawLine(left, top, left, height - bottom);

        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.drawString("Epsilon (%)", width / 2 - 25, height - 25);
        g.drawString("Relative Improvement (%)", 10, top - 10);

        if (!feasibleCandidates.isEmpty()) {
            double minE = feasibleCandidates.stream().mapToDouble(c -> c.relaxationPercent * 100.0).min().orElse(0.0);
            double maxE = feasibleCandidates.stream().mapToDouble(c -> c.relaxationPercent * 100.0).max().orElse(1.0);
            double minR = feasibleCandidates.stream().mapToDouble(c -> c.result.relativeImprovement() * 100.0).min().orElse(0.0);
            double maxR = feasibleCandidates.stream().mapToDouble(c -> c.result.relativeImprovement() * 100.0).max().orElse(1.0);

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

            for (ScoredCandidate candidate : feasibleCandidates) {
                double e = candidate.relaxationPercent * 100.0;
                double r = candidate.result.relativeImprovement() * 100.0;
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

    private static void printBaselineNormalizedKpis(NormalizedKpis baselineNormalized) {
        System.out.println("==================================================");
        System.out.println("BASELINE NORMALIZED KPIS");
        System.out.println("Cost norm: " + baselineNormalized.costNorm);
        System.out.println("Transit norm: " + baselineNormalized.transitNorm);
        System.out.println("Claims norm: " + baselineNormalized.claimsNorm);
        System.out.println("Off-time norm: " + baselineNormalized.offTimeNorm);
        System.out.println("==================================================");
    }

    private static ScoreBreakdown scoreCandidate(
            OptimizationModels.EpsilonResult candidate,
            NormalizedKpis baselineNormalized,
            OptimizationModels.KPIRanges ranges) {
        NormalizedKpis candidateNormalized = normalizeKpis(candidate.kpis(), ranges);

        double deltaTransitNorm = baselineNormalized.transitNorm - candidateNormalized.transitNorm;
        double deltaCostNorm = candidateNormalized.costNorm - baselineNormalized.costNorm;
        double deltaClaimsNorm = candidateNormalized.claimsNorm - baselineNormalized.claimsNorm;
        double deltaOffTimeNorm = candidateNormalized.offTimeNorm - baselineNormalized.offTimeNorm;

        double nonTargetDeterioration =
                Math.max(0.0, deltaCostNorm)
                        + Math.max(0.0, deltaClaimsNorm)
                        + Math.max(0.0, deltaOffTimeNorm);
        double deltaNetNorm = deltaTransitNorm - nonTargetDeterioration;

        return new ScoreBreakdown(
                candidateNormalized,
                deltaTransitNorm,
                deltaCostNorm,
                deltaClaimsNorm,
                deltaOffTimeNorm,
                nonTargetDeterioration,
                deltaNetNorm
        );
    }

    private static NormalizedKpis normalizeKpis(
            KPISnapshot kpis,
            OptimizationModels.KPIRanges ranges) {
        double costNorm = (kpis.getTotalCost() - ranges.costMin()) / safeDenominator(ranges.costRange());
        double transitNorm = (kpis.getAverageTransitTime() - ranges.transitMin()) / safeDenominator(ranges.transitRange());
        double claimsNorm = (kpis.getAverageClaims() - ranges.claimsMin()) / safeDenominator(ranges.claimsRange());
        double offTimeNorm = (kpis.getAverageOffTimeDelivery() - ranges.offTimeMin()) / safeDenominator(ranges.offTimeRange());
        return new NormalizedKpis(costNorm, transitNorm, claimsNorm, offTimeNorm);
    }

    private static double safeDenominator(double value) {
        return Math.abs(value) < 1e-9 ? 1.0 : value;
    }

    private static double nextRelaxationPercent(double currentRelaxationPercent) {
        return currentRelaxationPercent + RELAXATION_STEP_PERCENT;
    }

    private static void printEpsilonBreakdownForGamma(
            double gamma,
            List<ScoredCandidate> gammaCandidates) {
        System.out.println("--------------------------------------------------");
        System.out.printf("Feasible candidates for gamma %.2f%%%n", gamma * 100.0);
        for (ScoredCandidate candidate : gammaCandidates) {
            System.out.printf(
                    "epsilon=%.2f%%, deltaTransitNorm=%.5f, nonTargetDeterioration=%.5f, deltaNetNorm=%.5f, relImprovement=%.4f%%, change=%.4f%%%n",
                    candidate.relaxationPercent * 100.0,
                    candidate.score.deltaTransitNorm,
                    candidate.score.nonTargetDeterioration,
                    candidate.score.deltaNetNorm,
                    candidate.result.relativeImprovement() * 100.0,
                    candidate.result.shipmentChangeShare() * 100.0
            );
        }
    }

    private static void printPortfolioResults(List<ScoredCandidate> feasibleCandidates) {
        Comparator<ScoredCandidate> conservativeComparator = Comparator
                .comparingDouble((ScoredCandidate c) -> c.score.deltaNetNorm)
                .thenComparingDouble(c -> c.score.deltaTransitNorm)
                .thenComparing(Comparator.comparingDouble((ScoredCandidate c) -> c.relaxationPercent).reversed());
        Comparator<ScoredCandidate> balancedComparator = Comparator
                .comparingDouble(RelaxationWithNormalizedKpisRunner::stabilityScore)
                .thenComparingDouble(c -> c.score.deltaTransitNorm)
                .thenComparing(Comparator.comparingDouble((ScoredCandidate c) -> c.relaxationPercent).reversed());
        Comparator<ScoredCandidate> aggressiveComparator = Comparator
                .comparingDouble((ScoredCandidate c) -> c.score.deltaTransitNorm)
                .thenComparingDouble(c -> c.result.shipmentChangeShare())
                .thenComparingDouble(c -> c.relaxationPercent);
        Comparator<ScoredCandidate> stressComparator = Comparator
                .comparingDouble((ScoredCandidate c) -> c.score.nonTargetDeterioration)
                .thenComparingDouble(c -> c.score.deltaTransitNorm)
                .thenComparingDouble(c -> c.relaxationPercent);

        List<ScoredCandidate> chosen = new ArrayList<>();

        ScoredCandidate conservative = feasibleCandidates.stream()
                .max(Comparator
                        .comparingDouble((ScoredCandidate c) -> c.score.deltaNetNorm)
                        .thenComparingDouble(c -> c.score.deltaTransitNorm)
                        .thenComparing(Comparator.comparingDouble((ScoredCandidate c) -> c.relaxationPercent).reversed()))
                .orElseThrow();
        chosen.add(conservative);

        ScoredCandidate balanced = pickDistinctCandidate(feasibleCandidates, balancedComparator, chosen);
        if (balanced != null) {
            chosen.add(balanced);
        }

        ScoredCandidate aggressive = pickDistinctCandidate(feasibleCandidates, aggressiveComparator, chosen);
        if (aggressive != null) {
            chosen.add(aggressive);
        }

        ScoredCandidate stressTest = pickDistinctCandidate(feasibleCandidates, stressComparator, chosen);

        Map<String, ScoredCandidate> portfolio = new LinkedHashMap<>();
        portfolio.put("Conservative", conservative);
        if (balanced != null) {
            portfolio.put("Balanced", balanced);
        }
        if (aggressive != null) {
            portfolio.put("Aggressive", aggressive);
        }
        if (stressTest != null) {
            portfolio.put("Stress-test", stressTest);
        }

        System.out.println("==================================================");
        System.out.println("NORMALIZED KPI RELAXATION PORTFOLIO");
        System.out.println("Feasible candidates found: " + feasibleCandidates.size());
        for (Map.Entry<String, ScoredCandidate> entry : portfolio.entrySet()) {
            printPortfolioCandidate(entry.getKey(), entry.getValue());
        }
        if (portfolio.size() < 4) {
            System.out.println("Note: Fewer than 4 portfolio alternatives were assignment-distinct in this run.");
        }
        System.out.println("==================================================");
    }

    private static double stabilityScore(ScoredCandidate candidate) {
        return candidate.score.deltaTransitNorm / (1.0 + candidate.score.nonTargetDeterioration);
    }

    private static boolean sameScenario(ScoredCandidate a, ScoredCandidate b) {
        return Math.abs(a.relaxationPercent - b.relaxationPercent) < 1e-9
                && Math.abs(a.gamma - b.gamma) < 1e-9;
    }

    private static ScoredCandidate pickDistinctCandidate(
            List<ScoredCandidate> candidates,
            Comparator<ScoredCandidate> comparator,
            List<ScoredCandidate> alreadyPicked) {
        return candidates.stream()
                .filter(candidate -> alreadyPicked.stream().noneMatch(picked -> sameAssignmentPattern(candidate, picked)))
                .max(comparator)
                .orElse(null);
    }

    private static boolean sameAssignmentPattern(ScoredCandidate a, ScoredCandidate b) {
        return a.assignmentSignature.equals(b.assignmentSignature);
    }

    private static void printPortfolioCandidate(String label, ScoredCandidate candidate) {
        OptimizationModels.EpsilonResult result = candidate.result;
        System.out.println(label + " alternative:");
        System.out.println("  Relaxation percent: " + candidate.relaxationPercent);
        System.out.println("  Gamma used: " + candidate.gamma);
        System.out.println("  Delta metrics:");
        System.out.println("    deltaTransitNorm: " + candidate.score.deltaTransitNorm);
        System.out.println("    deltaCostNorm: " + candidate.score.deltaCostNorm);
        System.out.println("    deltaClaimsNorm: " + candidate.score.deltaClaimsNorm);
        System.out.println("    deltaOffTimeNorm: " + candidate.score.deltaOffTimeNorm);
        System.out.println("    nonTargetDeterioration: " + candidate.score.nonTargetDeterioration);
        System.out.println("    deltaNetNorm: " + candidate.score.deltaNetNorm);
        System.out.println("  Normalized KPIs:");
        System.out.println("    costNorm: " + candidate.score.candidateNormalized.costNorm);
        System.out.println("    transitNorm: " + candidate.score.candidateNormalized.transitNorm);
        System.out.println("    claimsNorm: " + candidate.score.candidateNormalized.claimsNorm);
        System.out.println("    offTimeNorm: " + candidate.score.candidateNormalized.offTimeNorm);
        System.out.println("  Raw KPIs:");
        System.out.println("    Objective value: " + result.objectiveValue());
        System.out.println("    Total cost: " + result.kpis().getTotalCost());
        System.out.println("    Average transit time: " + result.kpis().getAverageTransitTime());
        System.out.println("    Average claims: " + result.kpis().getAverageClaims());
        System.out.println("    Average off-time delivery: " + result.kpis().getAverageOffTimeDelivery());
        System.out.println("  Epsilons and feasibility:");
        System.out.println("    Cost epsilon: " + result.epsilonValues().costEpsilon());
        System.out.println("    Claims epsilon: " + result.epsilonValues().claimsEpsilon());
        System.out.println("    Off-time epsilon: " + result.epsilonValues().offTimeEpsilon());
        System.out.println("    Shipment change share: " + result.shipmentChangeShare());
        System.out.println("    Relative improvement: " + result.relativeImprovement());
        System.out.println("    Assignments returned: " + result.assignments().size());
    }

    private static final class ScoredCandidate {
        private final OptimizationModels.EpsilonResult result;
        private final double relaxationPercent;
        private final double gamma;
        private final ScoreBreakdown score;
        private final String assignmentSignature;

        private ScoredCandidate(
                OptimizationModels.EpsilonResult result,
                double relaxationPercent,
                double gamma,
                ScoreBreakdown score) {
            this.result = result;
            this.relaxationPercent = relaxationPercent;
            this.gamma = gamma;
            this.score = score;
            this.assignmentSignature = buildAssignmentSignature(result);
        }
    }

    private static String buildAssignmentSignature(OptimizationModels.EpsilonResult result) {
        StringBuilder signature = new StringBuilder();
        for (AssignmentRecord assignment : result.assignments()) {
            signature.append(assignment.getShipmentId())
                    .append('=')
                    .append(assignment.getCarrierId())
                    .append(';');
        }
        return signature.toString();
    }

    private static final class ScoreBreakdown {
        private final NormalizedKpis candidateNormalized;
        private final double deltaTransitNorm;
        private final double deltaCostNorm;
        private final double deltaClaimsNorm;
        private final double deltaOffTimeNorm;
        private final double nonTargetDeterioration;
        private final double deltaNetNorm;

        private ScoreBreakdown(
                NormalizedKpis candidateNormalized,
                double deltaTransitNorm,
                double deltaCostNorm,
                double deltaClaimsNorm,
                double deltaOffTimeNorm,
                double nonTargetDeterioration,
                double deltaNetNorm) {
            this.candidateNormalized = candidateNormalized;
            this.deltaTransitNorm = deltaTransitNorm;
            this.deltaCostNorm = deltaCostNorm;
            this.deltaClaimsNorm = deltaClaimsNorm;
            this.deltaOffTimeNorm = deltaOffTimeNorm;
            this.nonTargetDeterioration = nonTargetDeterioration;
            this.deltaNetNorm = deltaNetNorm;
        }
    }

    private static final class NormalizedKpis {
        private final double costNorm;
        private final double transitNorm;
        private final double claimsNorm;
        private final double offTimeNorm;

        private NormalizedKpis(
                double costNorm,
                double transitNorm,
                double claimsNorm,
                double offTimeNorm) {
            this.costNorm = costNorm;
            this.transitNorm = transitNorm;
            this.claimsNorm = claimsNorm;
            this.offTimeNorm = offTimeNorm;
        }
    }
}
