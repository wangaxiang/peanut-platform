package com.peanut.module.patrol;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
class SensorHistory {
    private static final Logger LOG = LoggerFactory.getLogger(SensorHistory.class);
    private static final List<String> METRICS = List.of("soil_temp", "soil_moisture", "soil_n", "soil_p", "soil_k");
    private static final String MEASUREMENT = """
              |> filter(fn: (r) => r._measurement == "soil_sensor")
              |> filter(fn: (r) => r._field == "soil_temp" or r._field == "soil_moisture"
                      or r._field == "soil_n" or r._field == "soil_p" or r._field == "soil_k")
            """;
    private final RestClient influx;
    private final String org;
    private final String bucket;

    SensorHistory(@Value("${influxdb.url}") String url, @Value("${influxdb.token}") String token,
            @Value("${influxdb.org}") String org, @Value("${influxdb.bucket}") String bucket) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        influx = RestClient.builder().baseUrl(url).requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Token " + token).build();
        this.org = org;
        this.bucket = bucket;
    }

    List<Map<String, Object>> history(String serial, Instant from, Instant to, String interval) {
        String query = """
                from(bucket: params.bucket)
                  |> range(start: time(v: params.start), stop: time(v: params.end))
                """ + MEASUREMENT + """
                  |> filter(fn: (r) => r.device_sn == params.serial)
                  |> group(columns: ["_field"])
                  |> aggregateWindow(every: duration(v: params.interval), fn: mean, timeSrc: "_start", createEmpty: false)
                  |> keep(columns: ["_time", "_field", "_value"])
                """;
        var rows = new TreeMap<Instant, Map<String, Object>>();
        query(query, Map.of("start", from.toString(), "end", to.toString(), "serial", serial, "interval", interval),
                (csv, headers) -> {
                    Instant time = Instant.parse(value(csv, headers, "_time"));
                    Map<String, Object> row = rows.computeIfAbsent(time, this::emptyReading);
                    row.put(value(csv, headers, "_field"), Double.valueOf(value(csv, headers, "_value")));
                });
        return List.copyOf(rows.values());
    }

    List<Map<String, Object>> latest(Long fieldId) {
        String query = """
                from(bucket: params.bucket)
                  |> range(start: 0)
                """ + MEASUREMENT
                + (fieldId == null ? "" : "  |> filter(fn: (r) => r.field_id == params.fieldId)\n")
                + """
                  |> last()
                  |> keep(columns: ["_time", "_field", "_value", "device_sn", "field_id"])
                """;
        var rows = new TreeMap<String, Map<String, Object>>();
        query(query, fieldId == null ? Map.of() : Map.of("fieldId", fieldId.toString()), (csv, headers) -> {
            String serial = value(csv, headers, "device_sn");
            Long field = Long.valueOf(value(csv, headers, "field_id"));
            Instant time = Instant.parse(value(csv, headers, "_time"));
            Map<String, Object> row = rows.computeIfAbsent(serial + ":" + field, key -> emptyReading(time));
            row.put("deviceSn", serial);
            row.put("fieldId", field);
            if (time.isAfter((Instant) row.get("time"))) {
                row.put("time", time);
            }
            row.put(value(csv, headers, "_field"), Double.valueOf(value(csv, headers, "_value")));
        });
        return List.copyOf(rows.values());
    }

    private Map<String, Object> emptyReading(Instant time) {
        var reading = new LinkedHashMap<String, Object>();
        reading.put("time", time);
        METRICS.forEach(metric -> reading.put(metric, null));
        return reading;
    }

    private void query(String flux, Map<String, String> arguments, BiConsumer<CSVRecord, Map<String, Integer>> consume) {
        var params = new HashMap<>(arguments);
        params.put("bucket", bucket);
        // InfluxDB OSS needs the parameters declared in Flux itself.
        String bindings = params.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + fluxString(entry.getValue()))
                .collect(Collectors.joining(", ", "params = {", "}\n"));
        try {
            String body = influx.post().uri(uri -> uri.path("/api/v2/query").queryParam("org", org).build())
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.valueOf("text/csv"))
                    .body(Map.of("query", bindings + flux, "dialect", Map.of("annotations", List.of())))
                    .retrieve().body(String.class);
            if (body == null || body.isBlank()) {
                return;
            }
            var headers = new HashMap<String, Integer>();
            try (var records = CSVParser.parse(body, CSVFormat.DEFAULT)) {
                for (CSVRecord record : records) {
                    if (record.toList().contains("_time")) {
                        headers.clear();
                        for (int i = 0; i < record.size(); i++) {
                            headers.put(record.get(i), i);
                        }
                    } else if (record.size() > 1 && headers.containsKey("_time")) {
                        consume.accept(record, headers);
                    }
                }
            }
        } catch (IOException | RestClientException | IllegalArgumentException e) {
            LOG.warn("InfluxDB sensor query failed", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "传感器数据暂时不可用");
        }
    }

    private static String fluxString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                .replace("$" + "{", "\\" + "$" + "{") + "\"";
    }

    private static String value(CSVRecord row, Map<String, Integer> headers, String column) {
        Integer index = headers.get(column);
        if (index == null) {
            throw new IllegalArgumentException("Missing InfluxDB CSV column: " + column);
        }
        return row.get(index);
    }
}
