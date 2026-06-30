ALTER TABLE connected_calendar ADD COLUMN account_email VARCHAR(256);
ALTER TABLE connected_calendar ADD COLUMN external_calendar_id VARCHAR(256);
ALTER TABLE connected_calendar ADD COLUMN refresh_token VARCHAR(512);
ALTER TABLE connected_calendar ADD COLUMN is_booking_target BOOLEAN NOT NULL DEFAULT FALSE;
