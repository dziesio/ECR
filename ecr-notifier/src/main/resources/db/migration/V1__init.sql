CREATE TABLE notifications (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id    UUID        NOT NULL,
    entity_type  VARCHAR(20) NOT NULL,
    notified_at  TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uq_entity UNIQUE (entity_id, entity_type)
);

CREATE INDEX idx_notifications_entity ON notifications (entity_id, entity_type);
