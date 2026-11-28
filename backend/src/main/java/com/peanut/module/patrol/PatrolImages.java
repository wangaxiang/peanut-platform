package com.peanut.module.patrol;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
class PatrolImages {
    private static final Logger LOG = LoggerFactory.getLogger(PatrolImages.class);
    private final PatrolRepository repository;
    private final PatrolService patrol;
    private final RestClient ai;
    private final Path directory;

    PatrolImages(PatrolRepository repository, PatrolService patrol, @Value("${ai-service.base-url}") String aiUrl,
            @Value("${patrol.image-directory}") String directory) {
        this.repository = repository;
        this.patrol = patrol;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build());
        factory.setReadTimeout(Duration.ofMinutes(4));
        ai = RestClient.builder().baseUrl(aiUrl).requestFactory(factory).build();
    }

    PatrolFlight attach(long id, MultipartFile image) throws IOException {
        patrol.flight(id);
        if (image.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择多光谱 GeoTIFF");
        }
        Path uploads = Files.createDirectories(directory.resolve("uploads"));
        Path staged = Files.createTempFile(uploads, "flight-" + id + "-", ".part");
        Path destination = uploads.resolve(id + "-" + UUID.randomUUID() + ".tif");
        boolean attached = false;
        try {
            try (var input = image.getInputStream()) {
                Files.copy(input, staged, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE);
            attached = repository.attachImage(id, "uploads/" + destination.getFileName());
            if (!attached) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前飞行状态不支持上传或更换影像");
            }
        } finally {
            Files.deleteIfExists(staged);
            if (!attached) {
                Files.deleteIfExists(destination);
            }
        }
        return patrol.flight(id);
    }

    @Scheduled(fixedDelay = 2000)
    void processNextImage() {
        repository.claimImage().ifPresent(job -> {
            JsonNode result;
            try {
                result = ai.post().uri("/ndvi/process")
                        .body(Map.of("tif_path", job.rawPath(), "flight_id", job.id()))
                        .retrieve().body(JsonNode.class);
                if (result == null || !result.path("stats").isObject() || !result.path("ndviPath").isTextual()) {
                    throw new IllegalStateException("Invalid NDVI response");
                }
            } catch (Exception e) {
                LOG.warn("NDVI processing failed for flight {}", job.id(), e);
                String error = e instanceof RestClientResponseException response && response.getStatusCode().is4xxClientError()
                        ? "NDVI 影像无法处理，请检查波段、坐标系及有效像元"
                        : "NDVI 服务暂时不可用，请重新提交影像";
                repository.finishImage(job, null, error);
                return;
            }
            repository.finishImage(job, result, null);
        });
    }

    Map<String, Object> tileJson(long id) {
        String imagePath = ndviPath(id);
        JsonNode metadata = ai.get().uri(uri -> uri.path("/ndvi/metadata").queryParam("path", imagePath).build())
                .retrieve().body(JsonNode.class);
        return Map.of("tilejson", "3.0.0", "scheme", "xyz", "minzoom", 0, "maxzoom", 22,
                "bounds", metadata.get("bounds"),
                "tiles", List.of("/api/patrol/flights/" + id + "/ndvi/tiles/{z}/{x}/{y}.png"));
    }

    byte[] tile(long id, int z, int x, int y) {
        if (z < 0 || z > 22 || x < 0 || y < 0 || x >= (1 << z) || y >= (1 << z)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "切片不存在");
        }
        String imagePath = ndviPath(id);
        return ai.get().uri(uri -> uri.path("/ndvi/tile/{z}/{x}/{y}.png").queryParam("path", imagePath)
                .build(z, x, y)).retrieve().body(byte[].class);
    }

    private String ndviPath(long id) {
        PatrolFlight flight = patrol.flight(id);
        if (!"done".equals(flight.status()) || flight.ndviImagePath() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "NDVI 影像尚未生成");
        }
        return flight.ndviImagePath();
    }
}
