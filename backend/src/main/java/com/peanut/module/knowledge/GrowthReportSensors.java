package com.peanut.module.knowledge;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
class GrowthReportSensors {
    private static final Logger LOG = LoggerFactory.getLogger(GrowthReportSensors.class);
    private final RestClient influx;
    private final String org;

    GrowthReportSensors(@Value("${influxdb.url}") String url, @Value("${influxdb.token}") String token,
            @Value("${influxdb.org}") String org) {
        this.org = org;
        if (token.isBlank()) {
            influx = null;
            return;
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build());
        factory.setReadTimeout(Duration.ofSeconds(8));
        influx = RestClient.builder().baseUrl(url).requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Token " + token).build();
    }

    Map<String, Double> weeklyAverages(long fieldId, Instant from, Instant to) {
        if (influx == null) {
            return Map.of();
        }
        String query = """
                from(bucket: params.bucket)
                  |> range(start: time(v: params.from), stop: time(v: params.to))
                  |> filter(fn: (r) => r._measurement == "soil_sensor" and r.field_id == params.fieldId)
                  |> filter(fn: (r) => r._field == "soil_temp" or r._field == "soil_moisture")
                  |> group(columns: ["_field"])
                  |> mean()
                """;
        try {
            String body = influx.post().uri(uri -> uri.path("/api/v2/query").queryParam("org", org).build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.valueOf("text/csv"))
                    .body(Map.of("query", query, "dialect", Map.of("annotations", java.util.List.of()),
                            "params", Map.of("bucket", bucket, "from", from.toString(),
                                    "to", to.toString(), "fieldId", Long.toString(fieldId))))
                    .retrieve().body(String.class);
            if (body == null || body.isBlank()) {
                return Map.of();
            }
            Map<String, Double> result = new HashMap<>();
            CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                    .setAllowMissingColumnNames(true).build();
            try (var records = CSVParser.parse(body, format)) {
                for (var record : records) {
                    String field = record.get("_field");
                    if ("soil_temp".equals(field) || "soil_moisture".equals(field)) {
                        result.put(field, Double.valueOf(record.get("_value")));
                    }
                }
            }
            return result;
        } catch (RestClientException | IOException | IllegalArgumentException error) {
            LOG.warn("Could not read sensor averages for field {}", fieldId, error);
            return Map.of();
        }
    }

    @Value("${influxdb.bucket}")
    private String bucket;
}
