CREATE TABLE settings (
    id INT PRIMARY KEY,
    timezone VARCHAR(64) NOT NULL,
    granularity_minutes INT NOT NULL,
    minimum_notice_minutes INT NOT NULL
);
CREATE TABLE working_hours (
    id UUID PRIMARY KEY, day_of_week VARCHAR(9) NOT NULL,
    start_time VARCHAR(5) NOT NULL, end_time VARCHAR(5) NOT NULL
);
CREATE TABLE date_override (
    id UUID PRIMARY KEY, override_date VARCHAR(10) NOT NULL,
    start_time VARCHAR(5), end_time VARCHAR(5)
);
CREATE TABLE event_type (
    id UUID PRIMARY KEY, slug VARCHAR(128) NOT NULL UNIQUE, name VARCHAR(256) NOT NULL,
    duration_minutes INT NOT NULL, buffer_before_minutes INT NOT NULL,
    buffer_after_minutes INT NOT NULL, status VARCHAR(16) NOT NULL
);
CREATE TABLE connected_calendar (
    id UUID PRIMARY KEY, name VARCHAR(256) NOT NULL, provider VARCHAR(32) NOT NULL, created_at VARCHAR(64) NOT NULL
);
INSERT INTO settings (id, timezone, granularity_minutes, minimum_notice_minutes) VALUES (1, 'Europe/Paris', 30, 0);
INSERT INTO connected_calendar (id, name, provider, created_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'Default', 'IN_MEMORY', '2026-06-29T00:00:00Z');
