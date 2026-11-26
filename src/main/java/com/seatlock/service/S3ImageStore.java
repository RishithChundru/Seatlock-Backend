package com.seatlock.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.util.UUID;

/**
 * Event covers in S3-compatible object storage (Cloudflare R2, S3Mock locally, AWS S3), served straight from
 * the bucket's public URL / CDN, so image traffic never touches the API or the database.
 * Enabled only when S3_BUCKET is set; otherwise isEnabled() is false and covers stay in Postgres (EventService).
 * (Always a bean, deciding at construction, rather than a conditional bean: conditions run before some
 * property sources exist, which silently disabled it in tests.)
 */
@Service
public class S3ImageStore implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(S3ImageStore.class);

    private final S3Client s3; // null when disabled
    private final String bucket;
    private final String publicUrl;

    public S3ImageStore(@Value("${app.images.s3.bucket}") String bucket, @Value("${app.images.s3.endpoint}") String endpoint,
                        @Value("${app.images.s3.region}") String region, @Value("${app.images.s3.access-key}") String accessKey,
                        @Value("${app.images.s3.secret-key}") String secretKey, @Value("${app.images.s3.public-url}") String publicUrl) {
        this.bucket = bucket;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        if (bucket.isBlank()) {
            this.s3 = null;
            return;
        }
        if (publicUrl.isBlank()) throw new IllegalStateException("S3_PUBLIC_URL is required when S3_BUCKET is set");
        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                // Path-style (endpoint/bucket/key) works on R2, MinIO and S3 alike.
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        this.s3 = builder.build();
        log.info("Event covers are stored in object storage bucket '{}'", bucket);
    }

    public boolean isEnabled() { return s3 != null; }

    /** Stores the image under a fresh key (new upload = new URL, so it can be cached forever). Returns the key. */
    public String put(Long eventId, String contentType, byte[] data) {
        String ext = switch (contentType) { case "image/png" -> "png"; case "image/webp" -> "webp"; default -> "jpg"; };
        String key = "events/" + eventId + "/" + UUID.randomUUID() + "." + ext;
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
                .cacheControl("public, max-age=31536000, immutable").build(), RequestBody.fromBytes(data));
        return key;
    }

    /** Best-effort: a leftover object only costs storage, so a failed delete never fails the request. */
    public void delete(String key) {
        if (key == null || s3 == null) return;
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (RuntimeException e) {
            log.warn("Could not delete {} from {}: {}", key, bucket, e.getMessage());
        }
    }

    public String urlFor(String key) { return publicUrl + "/" + key; }

    @Override
    public void close() { if (s3 != null) s3.close(); }
}
