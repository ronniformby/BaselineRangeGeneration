package com.arcbest.optimization;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DataLoader {
    public ModelData load(
            String shipmentCsvPath,
            String carrierMetricsCsvPath,
            String transitTimeCsvPath) throws IOException {

        Map<String, Carrier> carriers = loadCarriers(Path.of(carrierMetricsCsvPath));
        Map<String, Map<String, Double>> transitTimesByShipment =
                loadTransitTimes(Path.of(transitTimeCsvPath));
        List<Shipment> shipments =
                loadShipments(Path.of(shipmentCsvPath), carriers, transitTimesByShipment);

        GlobalParameters globalParameters = new GlobalParameters(
                3,
                8,
                0.60,
                10_000.0
        );

        return new ModelData(shipments, carriers, globalParameters);
    }

    private Map<String, Carrier> loadCarriers(Path carrierMetricsPath) throws IOException {
        Map<String, Carrier> carriers = new LinkedHashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(carrierMetricsPath, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IOException("Carrier metrics file is empty.");
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                String[] parts = splitCsv(line);
                String carrierId = cleanText(parts[0]);
                double claimsRate = parsePercent(parts[1]);
                boolean retailCompliant = parseYesNo(parts[2]);
                boolean liftgateCapable = parseYesNo(parts[3]);
                double onTimeRate = parsePercent(parts[4]);
                double offTimeDeliveryRate = 1.0 - onTimeRate;

                Carrier carrier = new Carrier(
                        carrierId,
                        claimsRate,
                        offTimeDeliveryRate,
                        retailCompliant,
                        liftgateCapable
                );

                carriers.put(carrierId, carrier);
            }
        }

        return carriers;
    }

    private Map<String, Map<String, Double>> loadTransitTimes(Path transitTimePath) throws IOException {
        Map<String, Map<String, Double>> transitTimesByShipment = new LinkedHashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(transitTimePath, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IOException("Transit time file is empty.");
            }

            String[] headers = splitCsv(headerLine);
            headers[0] = removeBom(headers[0]);

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                String[] parts = splitCsv(line);
                String shipmentId = cleanText(parts[0]);
                Map<String, Double> shipmentTransitMap = new LinkedHashMap<>();

                for (int i = 1; i < headers.length && i < parts.length; i++) {
                    String carrierId = cleanText(headers[i]);
                    double transitTime = parseNumber(parts[i]);
                    shipmentTransitMap.put(carrierId, transitTime);
                }

                transitTimesByShipment.put(shipmentId, shipmentTransitMap);
            }
        }

        return transitTimesByShipment;
    }

    private List<Shipment> loadShipments(
            Path shipmentPath,
            Map<String, Carrier> carriers,
            Map<String, Map<String, Double>> transitTimesByShipment) throws IOException {

        List<Shipment> shipments = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(shipmentPath, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IOException("Shipment file is empty.");
            }

            String[] headers = splitCsv(headerLine);
            headers[0] = removeBom(headers[0]);

            int shipmentIdIndex = findIndex(headers, "SHIPMENT ID");
            int retailFlagIndex = findIndex(headers, "RETAIL SHIPMENT FLAG");
            int liftgateFlagIndex = findIndex(headers, "LIFTGATE FLAG");
            int firstCarrierColumnIndex = findIndex(headers, "Carrier 1");

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                String[] parts = splitCsv(line);
                String shipmentId = cleanText(parts[shipmentIdIndex]);
                boolean retailRequired = parseRetailFlag(parts[retailFlagIndex]);
                boolean liftgateRequired = parseLiftgateFlag(parts[liftgateFlagIndex]);

                Map<String, Double> shipmentTransitMap = transitTimesByShipment.get(shipmentId);
                List<ShipmentOption> options = new ArrayList<>();

                for (int i = firstCarrierColumnIndex; i < headers.length && i < parts.length; i++) {
                    String carrierId = cleanText(headers[i]);
                    if (carrierId.isBlank() || !carriers.containsKey(carrierId)) {
                        continue;
                    }

                    double cost = parseCurrency(parts[i]);
                    double transitTime = shipmentTransitMap != null
                            ? shipmentTransitMap.getOrDefault(carrierId, 0.0)
                            : 0.0;

                    Carrier carrier = carriers.get(carrierId);

                    boolean feasible = cost > 0.0
                            && transitTime > 0.0
                            && (!retailRequired || carrier.isRetailCompliant())
                            && (!liftgateRequired || carrier.isLiftgateCapable());

                    options.add(new ShipmentOption(carrierId, cost, transitTime, feasible));
                }

                shipments.add(new Shipment(shipmentId, retailRequired, liftgateRequired, options));
            }
        }

        return shipments;
    }

    private int findIndex(String[] headers, String target) {
        for (int i = 0; i < headers.length; i++) {
            if (cleanText(headers[i]).equalsIgnoreCase(target)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Missing required column: " + target);
    }

    private String[] splitCsv(String line) {
        return line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
    }

    private String cleanText(String value) {
        return removeBom(value).replace("\"", "").trim();
    }

    private String removeBom(String value) {
        return value == null ? "" : value.replace("\uFEFF", "");
    }

    private double parseCurrency(String value) {
        String cleaned = cleanText(value).replace("$", "").replace(",", "");
        if (cleaned.isBlank()) {
            return 0.0;
        }
        return Double.parseDouble(cleaned);
    }

    private double parseNumber(String value) {
        String cleaned = cleanText(value).replace(",", "");
        if (cleaned.isBlank()) {
            return 0.0;
        }
        return Double.parseDouble(cleaned);
    }

    private double parsePercent(String value) {
        String cleaned = cleanText(value).replace("%", "");
        if (cleaned.isBlank()) {
            return 0.0;
        }
        return Double.parseDouble(cleaned) / 100.0;
    }

    private boolean parseYesNo(String value) {
        return cleanText(value).equalsIgnoreCase("Yes");
    }

    private boolean parseRetailFlag(String value) {
        return cleanText(value).equalsIgnoreCase("Retail");
    }

    private boolean parseLiftgateFlag(String value) {
        return cleanText(value).equalsIgnoreCase("Liftgate Needed");
    }
}
