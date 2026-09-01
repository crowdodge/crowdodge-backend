CREATE TABLE IF NOT EXISTS event_destinations (
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    event_destination_uuid UUID PRIMARY KEY,
    recurring_event_id TEXT NULL,
    destination TEXT NOT NULL,
    destination_point geography(Point,4326) NOT NULL,
    route_duration INTERVAL NOT NULL,
    route_information JSONB NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS event_destinations_recurring_event_id_unique
    ON event_destinations (recurring_event_id);

CREATE TABLE IF NOT EXISTS event_destination_links (
    event_uuid UUID PRIMARY KEY,
    event_destination_uuid UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_event_destination_links_event_destination_uuid__event_destin
        FOREIGN KEY (event_destination_uuid)
        REFERENCES event_destinations(event_destination_uuid)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT
);
