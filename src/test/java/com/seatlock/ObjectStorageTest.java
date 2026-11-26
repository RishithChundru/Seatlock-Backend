package com.seatlock;

import com.seatlock.api.Dtos.CreateEventRequest;
import com.seatlock.domain.Repositories.EventImageRepository;
import com.seatlock.service.EventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers go to S3-compatible storage (S3Mock here, Cloudflare R2 in production) when a bucket is configured. */
@SpringBootTest
@Import({TestcontainersConfig.class, ObjectStorageTest.MinioConfig.class})
class ObjectStorageTest {
    static final String BUCKET = "covers-test";

    @TestConfiguration(proxyBeanMethods = false)
    static class MinioConfig {
        /** Adobe S3Mock: an S3-compatible server (stand-in for Cloudflare R2). Accepts any credentials. */
        @Bean
        GenericContainer<?> minio() {
            return new GenericContainer<>("adobe/s3mock:latest").withExposedPorts(9090);
        }

        @Bean
        DynamicPropertyRegistrar s3Properties(GenericContainer<?> minio) {
            return registry -> {
                registry.add("app.images.s3.bucket", () -> BUCKET);
                registry.add("app.images.s3.endpoint", () -> endpoint(minio));
                registry.add("app.images.s3.region", () -> "us-east-1");
                registry.add("app.images.s3.access-key", () -> "test");
                registry.add("app.images.s3.secret-key", () -> "test-secret");
                registry.add("app.images.s3.public-url", () -> endpoint(minio) + "/" + BUCKET);
            };
        }

        static String endpoint(GenericContainer<?> minio) { return "http://" + minio.getHost() + ":" + minio.getMappedPort(9090); }
    }

    @Autowired EventService events;
    @Autowired EventImageRepository dbImages;
    @Autowired GenericContainer<?> minio;

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    @Test
    void coversRoundTripThroughObjectStorage() {
        S3Client s3 = S3Client.builder().region(Region.US_EAST_1)
                .endpointOverride(URI.create(MinioConfig.endpoint(minio)))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test-secret")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
        s3.createBucket(b -> b.bucket(BUCKET));

        var ev = events.create(new CreateEventRequest("Covers " + UUID.randomUUID(), "Hall, Test City", "Concert",
                Instant.now().plus(Duration.ofDays(5)), 1000, 1, 2, null));

        var first = events.setImage(ev.id(), PNG);
        String prefix = MinioConfig.endpoint(minio) + "/" + BUCKET + "/";
        assertThat(first.imageUrl()).startsWith(prefix + "events/" + ev.id() + "/").endsWith(".png");
        String firstKey = first.imageUrl().substring(prefix.length());
        var stored = s3.getObject(b -> b.bucket(BUCKET).key(firstKey));
        assertThat(stored.response().contentType()).isEqualTo("image/png");
        assertThat(stored.response().cacheControl()).contains("immutable");
        assertThat(dbImages.existsById(ev.id())).as("not stored in Postgres").isFalse();

        // Replacing gets a new key (fresh URL for caches) and cleans up the old object.
        var second = events.setImage(ev.id(), PNG);
        assertThat(second.imageUrl()).isNotEqualTo(first.imageUrl());
        assertThatThrownBy(() -> s3.getObject(b -> b.bucket(BUCKET).key(firstKey))).isInstanceOf(NoSuchKeyException.class);

        String secondKey = second.imageUrl().substring(prefix.length());
        assertThat(events.removeImage(ev.id()).imageUrl()).isNull();
        assertThatThrownBy(() -> s3.getObject(b -> b.bucket(BUCKET).key(secondKey))).isInstanceOf(NoSuchKeyException.class);
    }
}
