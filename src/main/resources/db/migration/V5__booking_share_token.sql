-- Random token for the public "shared ticket" page. Unguessable (128 bits), unlike the short ticket code.
ALTER TABLE bookings ADD COLUMN share_token VARCHAR(32);
CREATE UNIQUE INDEX ux_bookings_share_token ON bookings (share_token) WHERE share_token IS NOT NULL;
