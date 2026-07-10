CREATE TABLE IF NOT EXISTS notification_schedules (created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, notification_schedules_uuid uuid PRIMARY KEY, user_uuid uuid NOT NULL, event_uuid uuid NOT NULL, notificate_time TIMESTAMP WITH TIME ZONE NOT NULL, kind TEXT NOT NULL, status TEXT NOT NULL);
CREATE INDEX notification_schedules_status_notificate_time ON notification_schedules (status, notificate_time);
CREATE INDEX notification_schedules_event_uuid ON notification_schedules (event_uuid);
