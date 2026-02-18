-- Table to store audit records from Kafka topic "Audit-Records.JSON"
CREATE TABLE IF NOT EXISTS audit_records (
    "messageId"   VARCHAR(36)   NOT NULL PRIMARY KEY,
    "timestamp"   BIGINT        NOT NULL,
    "requester"   VARCHAR(255)  NOT NULL,
    "direction"   VARCHAR(10)   NOT NULL CHECK ("direction" IN ('REQUEST', 'RESPONSE')),
    "metadata"    JSONB,
    "format"      VARCHAR(255)
);
