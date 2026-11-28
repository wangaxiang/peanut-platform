package com.peanut.module.iot;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
class IotInflux {
    record Point(Instant time, String source, Map<String,Double> values, Map<String,String> quality) {}
    private final RestClient client;
    private final String org;
    private final String bucket;

    IotInflux(@Value("${influxdb.url}") String url, @Value("${influxdb.token}") String token,
            @Value("${influxdb.org}") String org, @Value("${influxdb.bucket}") String bucket) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        this.client=RestClient.builder().baseUrl(url).requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION,"Token " + token).build();
        this.org=org;
        this.bucket=bucket;
    }

    void write(long id, String serial, Long field, String type, String source, Instant time,
            Map<String,Double> metrics, Map<String,String> quality) {
        var line = new StringBuilder("device_telemetry,device_id=").append(id).append(",device_sn=").append(serial);
        if (field != null) line.append(",field_id=").append(field);
        line.append(",device_type=").append(type).append(",source=").append(source).append(' ');
        metrics.forEach((key,value) -> line.append(key).append('=').append(value).append(',')
                .append("quality_").append(key).append('=').append(literal(quality.get(key))).append(','));
        String summary = quality.containsValue("bad") ? "bad" : quality.containsValue("suspect") ? "suspect" : "good";
        line.append("quality_code=").append(literal(summary)).append(' ')
                .append(Math.addExact(Math.multiplyExact(time.getEpochSecond(),1_000_000_000L),time.getNano()));
        try {
            client.post().uri(uri -> uri.path("/api/v2/write").queryParam("org",org).queryParam("bucket",bucket)
                    .queryParam("precision","ns").build()).contentType(MediaType.TEXT_PLAIN)
                    .body(line.toString()).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw unavailable();
        }
    }

    List<Point> recent(long id) {
        String flux = "from(bucket: " + literal(bucket) + ")\n" + """
                  |> range(start:-7d)
                  |> filter(fn:(r) => r._measurement == "device_telemetry" and r.device_id == "%d" and r.source == "simulated")
                  |> group(columns:["_field"])
                  |> sort(columns:["_time"],desc:true)
                  |> limit(n:60)
                  |> keep(columns:["_time","_field","_value"])
                """.formatted(id);
        try {
            String csv = client.post().uri(uri -> uri.path("/api/v2/query").queryParam("org",org).build())
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.valueOf("text/csv"))
                    .body(Map.of("query",flux,"dialect",Map.of("annotations",List.of(),"header",true)))
                    .retrieve().body(String.class);
            return parse(csv);
        } catch (IOException | IllegalArgumentException | RestClientException e) {
            throw unavailable();
        }
    }

    private static List<Point> parse(String csv) throws IOException {
        if (csv == null || csv.isBlank()) return List.of();
        var result = new TreeMap<Instant,Point>();
        Map<String,Integer> header = new LinkedHashMap<>();
        try (var parser = CSVParser.parse(csv,CSVFormat.DEFAULT)) {
            for (var row : parser) {
                if (row.toList().contains("_time")) {
                    header.clear();
                    for (int i=0;i<row.size();i++) header.put(row.get(i),i);
                } else if (!header.isEmpty() && row.size()>header.get("_time")) {
                    Instant time = Instant.parse(row.get(header.get("_time")));
                    String key = row.get(header.get("_field"));
                    String value = row.get(header.get("_value"));
                    var point = result.computeIfAbsent(time,t -> new Point(t,"simulated",new LinkedHashMap<>(),new LinkedHashMap<>()));
                    if (key.startsWith("quality_") && !"quality_code".equals(key)) {
                        point.quality().put(key.substring("quality_".length()),value);
                    } else if (!"quality_code".equals(key)) {
                        point.values().put(key,Double.parseDouble(value));
                    }
                }
            }
        }
        var rows = new ArrayList<>(result.values());
        return rows.subList(Math.max(0,rows.size()-60),rows.size());
    }

    private static String literal(String value) {
        return "\"" + value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")
                .replace("$"+"{","\\$"+"{") + "\"";
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"InfluxDB 暂不可用，遥测可重试");
    }
}
