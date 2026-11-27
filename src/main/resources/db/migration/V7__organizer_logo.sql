-- Optional organizer logo, shown in the "Hosted by" card. Same storage as event covers: an object-storage key
-- when S3/R2 is configured, otherwise the bytes in organizer_logos (kept out of organizers, so listing never loads them).
ALTER TABLE organizers ADD COLUMN logo_key VARCHAR(200);
ALTER TABLE organizers ADD COLUMN logo_updated_at TIMESTAMP(6) WITH TIME ZONE;

CREATE TABLE organizer_logos (
    organizer_id BIGINT      PRIMARY KEY REFERENCES organizers (id),
    content_type VARCHAR(20) NOT NULL,
    data         BYTEA       NOT NULL
);
