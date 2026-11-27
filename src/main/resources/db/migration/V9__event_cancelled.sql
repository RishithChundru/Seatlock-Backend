-- A cancelled event is kept (hidden from listings and booking) so customers still see their cancelled tickets.
ALTER TABLE events ADD COLUMN cancelled_at TIMESTAMP(6) WITH TIME ZONE;
