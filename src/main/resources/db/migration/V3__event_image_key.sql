-- Object-storage key (S3/R2) for event covers. NULL = cover stored in event_images (Postgres) or none.
ALTER TABLE events ADD COLUMN image_key VARCHAR(200);
