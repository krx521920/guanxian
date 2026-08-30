package com.guanxian.platform.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Arrays;

@Component
@ConditionalOnProperty(name = "guanxian.storage.backend", havingValue = "minio")
final class MinioObjectStorage implements ObjectStorage, HealthIndicator {
    private final MinioClient client;
    private final String bucket;
    private final boolean production;
    private volatile boolean bucketReady;

    MinioObjectStorage(StorageProperties properties, Environment environment) {
        validate(properties, environment.getActiveProfiles());
        this.bucket = properties.getBucket().trim();
        this.production = isProduction(environment.getActiveProfiles());
        this.client = MinioClient.builder()
                .endpoint(properties.getEndpoint().trim())
                .credentials(properties.getAccessKey().trim(), properties.getSecretKey())
                .build();
    }

    @PostConstruct
    void initializeBucket() {
        try {
            ensureBucket();
        } catch (Exception exception) {
            throw new IllegalStateException("MinIO bucket initialization failed", exception);
        }
    }

    @Override
    public void put(String objectKey, String mediaType, byte[] content) {
        try {
            ensureBucket();
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .contentType(mediaType)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .build());
        } catch (Exception exception) {
            throw new StorageUnavailableException("object upload failed", exception);
        }
    }

    @Override
    public byte[] get(String objectKey) {
        try (var stream = client.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            return stream.readAllBytes();
        } catch (Exception exception) {
            throw new StorageUnavailableException("object download failed", exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception exception) {
            throw new StorageUnavailableException("object cleanup failed", exception);
        }
    }

    @Override
    public Health health() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                bucketReady = false;
                return Health.down().build();
            }
            return Health.up().build();
        } catch (Exception exception) {
            return Health.down(exception).build();
        }
    }

    private synchronized void ensureBucket() throws Exception {
        if (bucketReady) {
            return;
        }
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            if (production) {
                throw new IllegalStateException(
                        "production MinIO bucket must be provisioned before server startup");
            }
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        bucketReady = true;
    }

    private static void validate(StorageProperties properties, String[] activeProfiles) {
        if (!"minio".equalsIgnoreCase(properties.getBackend())) {
            throw new IllegalStateException("MinIO adapter requires guanxian.storage.backend=minio");
        }
        if (properties.getBucket() == null
                || !properties.getBucket().matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
            throw new IllegalStateException("MinIO bucket name is invalid");
        }
        try {
            URI endpoint = URI.create(properties.getEndpoint().trim());
            boolean https = "https".equalsIgnoreCase(endpoint.getScheme());
            boolean http = "http".equalsIgnoreCase(endpoint.getScheme());
            if ((!https && !http) || endpoint.getHost() == null || endpoint.getUserInfo() != null
                    || endpoint.getQuery() != null || endpoint.getFragment() != null) {
                throw new IllegalStateException(
                        "MinIO endpoint must be an HTTP(S) origin without credentials");
            }
            if (isProduction(activeProfiles) && !https) {
                throw new IllegalStateException("MinIO endpoint must use HTTPS in production");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("MinIO endpoint is invalid", exception);
        }
        if (properties.getAccessKey() == null || properties.getAccessKey().isBlank()
                || properties.getSecretKey() == null || properties.getSecretKey().length() < 16) {
            throw new IllegalStateException("MinIO credentials must be configured");
        }
    }

    private static boolean isProduction(String[] activeProfiles) {
        return Arrays.stream(activeProfiles).anyMatch(profile ->
                "prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile));
    }
}
