package com.archiver.connect.transforms;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.transforms.Transformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

/**
 * Kafka Connect Single Message Transform (SMT) that converts a schemaless
 * JSON audit record (Map) into a schema-aware {@link Struct} so that the
 * JDBC Sink Connector can persist it to PostgreSQL.
 *
 * <p>Records with invalid or missing required fields are logged and skipped
 * (filtered out) to prevent the connector from failing on malformed data.
 *
 * <p>Expected input JSON structure:
 * <pre>{
 *   "messageId":  "uuid-string",
 *   "timestamp":  1771138537,
 *   "requester":  "MyApplicationName",
 *   "direction":  "REQUEST" | "RESPONSE",
 *   "metadata":   "{ \"key1\":\"value1\" }",
 *   "format":     "application/xml"
 * }</pre>
 */
public class AuditRecordSchemaTransform<R extends ConnectRecord<R>> implements Transformation<R> {

    private static final Logger LOG = LoggerFactory.getLogger(AuditRecordSchemaTransform.class);

    /** Valid values for the direction field. */
    private static final Set<String> VALID_DIRECTIONS = Set.of("REQUEST", "RESPONSE");

    /** Fixed schema that mirrors the PostgreSQL audit_records table. */
    private static final Schema VALUE_SCHEMA = SchemaBuilder.struct()
            .name("AuditRecord")
            .field("messageId",  Schema.STRING_SCHEMA)
            .field("timestamp",  Schema.INT64_SCHEMA)
            .field("requester",  Schema.STRING_SCHEMA)
            .field("direction",  Schema.STRING_SCHEMA)
            .field("metadata",   Schema.OPTIONAL_STRING_SCHEMA)
            .field("format",     Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    @Override
    @SuppressWarnings("unchecked")
    public R apply(R record) {
        if (record.value() == null) {
            return null;  // Skip null records
        }

        final Map<String, Object> value;
        try {
            value = (Map<String, Object>) record.value();
        } catch (ClassCastException e) {
            LOG.warn("Skipping record: value is not a Map. Topic={}, Partition={}",
                    record.topic(), record.kafkaPartition());
            return null;
        }

        // Validate required fields
        String validationError = validateRecord(value);
        if (validationError != null) {
            LOG.warn("Skipping invalid record: {}. Topic={}, Partition={}, Value={}",
                    validationError, record.topic(), record.kafkaPartition(), value);
            return null;  // Skip invalid records
        }

        try {
            Struct struct = new Struct(VALUE_SCHEMA);
            struct.put("messageId",  requireString(value, "messageId"));
            struct.put("timestamp",  requireLong(value, "timestamp"));
            struct.put("requester",  requireString(value, "requester"));
            struct.put("direction",  requireString(value, "direction"));
            struct.put("metadata",   optionalString(value, "metadata"));
            struct.put("format",     optionalString(value, "format"));

            return record.newRecord(
                    record.topic(),
                    record.kafkaPartition(),
                    record.keySchema(),
                    record.key(),
                    VALUE_SCHEMA,
                    struct,
                    record.timestamp()
            );
        } catch (Exception e) {
            LOG.error("Skipping record due to transformation error: {}. Topic={}, Partition={}, Value={}",
                    e.getMessage(), record.topic(), record.kafkaPartition(), value);
            return null;  // Skip records that fail transformation
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Validation methods                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Validates the record and returns an error message if invalid, or null if valid.
     */
    private static String validateRecord(Map<String, Object> value) {
        // Check required fields
        if (value.get("messageId") == null) {
            return "Missing required field: messageId";
        }
        if (value.get("timestamp") == null) {
            return "Missing required field: timestamp";
        }
        if (value.get("requester") == null) {
            return "Missing required field: requester";
        }
        if (value.get("direction") == null) {
            return "Missing required field: direction";
        }

        // Validate direction enum
        String direction = value.get("direction").toString();
        if (!VALID_DIRECTIONS.contains(direction)) {
            return "Invalid direction value: '" + direction + "'. Must be one of: " + VALID_DIRECTIONS;
        }

        // Validate timestamp is a valid number
        Object timestamp = value.get("timestamp");
        if (!(timestamp instanceof Number)) {
            try {
                Long.parseLong(timestamp.toString());
            } catch (NumberFormatException e) {
                return "Invalid timestamp value: '" + timestamp + "'. Must be a numeric value";
            }
        }

        return null;  // Valid
    }

    /* ------------------------------------------------------------------ */
    /*  Helper methods                                                     */
    /* ------------------------------------------------------------------ */

    private static String requireString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return v.toString();
    }

    private static long requireLong(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        return Long.parseLong(v.toString());
    }

    private static String optionalString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    /* ------------------------------------------------------------------ */
    /*  Boilerplate required by the Transformation interface               */
    /* ------------------------------------------------------------------ */

    @Override
    public ConfigDef config() {
        return new ConfigDef();   // no custom configuration needed
    }

    @Override
    public void close() {
        // nothing to release
    }

    @Override
    public void configure(Map<String, ?> configs) {
        // no configuration to process
    }
}
