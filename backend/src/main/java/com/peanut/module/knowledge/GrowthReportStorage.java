package com.peanut.module.knowledge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.regex.Pattern;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.ObjectMetadata;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class GrowthReportStorage {
    private static final Pattern OSS_HOST = Pattern.compile("oss-([a-z0-9-]+?)(?:-internal)?\\.aliyuncs\\.com");
    private static final Duration DOWNLOAD_VALIDITY = Duration.ofDays(7);
    private final Path local;
    private final String bucket;
    private final OSS cloud;

    GrowthReportStorage(@Value("${growth-report.directory}") String directory,
            @Value("${growth-report.oss.endpoint:}") String endpoint,
            @Value("${growth-report.oss.region:}") String region,
            @Value("${growth-report.oss.bucket:}") String bucket,
            @Value("${growth-report.oss.access-key-id:}") String key,
            @Value("${growth-report.oss.access-key-secret:}") String secret) {
        local = Path.of(directory);
        this.bucket = bucket;
        if (endpoint.isBlank() && region.isBlank() && bucket.isBlank() && key.isBlank() && secret.isBlank()) {
            cloud = null;
        } else {
            if (endpoint.isBlank() || bucket.isBlank() || key.isBlank() || secret.isBlank()) {
                throw new IllegalArgumentException("Growth report OSS requires endpoint, bucket and credentials");
            }
            ClientBuilderConfiguration config = new ClientBuilderConfiguration();
            config.setSignatureVersion(SignVersion.V4);
            cloud = OSSClientBuilder.create().endpoint(endpoint).region(resolveRegion(endpoint, region))
                    .credentialsProvider(new DefaultCredentialProvider(key, secret)).clientConfiguration(config).build();
        }
    }

    private static String resolveRegion(String endpoint, String region) {
        if (!region.isBlank()) {
            return region;
        }
        String host = URI.create(endpoint.contains("://") ? endpoint : "https://" + endpoint).getHost();
        var match = OSS_HOST.matcher(host == null ? "" : host);
        if (!match.matches()) {
            throw new IllegalArgumentException("Set GROWTH_REPORT_OSS_REGION to the bucket's region ID");
        }
        return match.group(1);
    }

    boolean hasCloudStorage() {
        return cloud != null;
    }

    String signedDownloadUrl(String key) {
        return cloud.generatePresignedUrl(bucket, key, Date.from(Instant.now().plus(DOWNLOAD_VALIDITY))).toString();
    }

    void save(String key, byte[] bytes) throws IOException {
        if (cloud != null) {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType("application/pdf");
            metadata.setContentLength(bytes.length);
            cloud.putObject(bucket, key, new ByteArrayInputStream(bytes), metadata);
            return;
        }
        Path path = local.resolve(key);
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "growth-", ".pdf");
        try {
            Files.write(temp, bytes);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    byte[] read(String key) throws IOException {
        if (cloud == null) {
            return Files.readAllBytes(local.resolve(key));
        }
        try (var stream = cloud.getObject(bucket, key).getObjectContent()) {
            return stream.readAllBytes();
        }
    }

    @PreDestroy
    void close() {
        if (cloud != null) {
            cloud.shutdown();
        }
    }
}
