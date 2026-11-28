-- Profile photos (any role), kept out of users so loading accounts never loads images.
ALTER TABLE users ADD COLUMN avatar_updated_at TIMESTAMP(6) WITH TIME ZONE;
CREATE TABLE user_avatars (
    user_id      BIGINT      PRIMARY KEY REFERENCES users (id),
    content_type VARCHAR(20) NOT NULL,
    data         BYTEA       NOT NULL
);

-- Staff invites can be accepted or declined from inside the app (no email needed).
ALTER TABLE staff_invites ADD COLUMN declined_at TIMESTAMP(6) WITH TIME ZONE;
CREATE INDEX ix_staff_invites_email ON staff_invites (email);
