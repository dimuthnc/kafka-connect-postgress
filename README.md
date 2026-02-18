# Kafka Connect with PostgreSQL – Audit Records Archiver

This project provides a complete Docker-based setup for archiving JSON audit records from a Kafka topic into a PostgreSQL database using Kafka Connect with the JDBC Sink Connector.

## Features

- **Apache Kafka 4.1.1** running in KRaft mode (no ZooKeeper required)
- **PostgreSQL 14.21** for persistent storage of audit records
- **Kafka Connect** with Aiven JDBC Connector (v6.10.0)
- **Custom SMT (Single Message Transform)** to convert schemaless JSON to schema-aware Struct
- **Kafka UI** for monitoring topics, consumers, and connectors
- Automatic topic creation (`Audit-Records.JSON`)
- Upsert mode with `messageId` as primary key

## Project Structure

```
kafka-connect-postgres/
├── docker-compose.yml                 # All services: Kafka, PostgreSQL, Kafka Connect, Kafka UI
├── Dockerfile.connect                 # Multi-stage build for Kafka Connect with plugins
├── README.md                          # This documentation
├── config/
│   └── connect-distributed.properties # Kafka Connect distributed mode configuration
├── connectors/
│   └── sink-connector.json            # JDBC Sink Connector configuration
├── kafka-connect-smt/
│   ├── pom.xml                        # Maven build for custom SMT
│   └── src/main/java/com/archiver/connect/transforms/
│       └── AuditRecordSchemaTransform.java  # Custom SMT implementation
├── postgres-init/
│   └── init.sql                       # PostgreSQL schema initialization
└── .env                               # Environment variables (optional)
```

## Services

| Service         | Port  | Description                                      |
|-----------------|-------|--------------------------------------------------|
| Kafka           | 9092  | Internal broker communication                    |
| Kafka           | 29092 | External (host) access via `localhost:29092`     |
| PostgreSQL      | 5432  | Database server                                  |
| Kafka Connect   | 8083  | REST API for connector management                |
| Kafka UI        | 8080  | Web interface for monitoring Kafka               |

## Prerequisites

- Docker and Docker Compose installed
- Maven 3.9+ (only if building SMT locally outside Docker)
- Java 17+ (only if building SMT locally outside Docker)

## Quick Start

### 1. Start All Services

```bash
docker-compose up -d --build
```

This will:
- Build the custom Kafka Connect image with the JDBC connector and SMT
- Start Kafka in KRaft mode
- Create the `Audit-Records.JSON` topic with 3 partitions
- Start PostgreSQL with the `audit_records` table
- Start Kafka Connect and Kafka UI

### 2. Verify Services Are Running

```bash
docker-compose ps
```

Wait for all services to be healthy:
```bash
docker-compose logs -f kafka-connect
```

### 3. Register the Sink Connector

**PowerShell:**
```powershell
Invoke-RestMethod -Uri "http://localhost:8083/connectors" -Method Post -ContentType "application/json" -Body (Get-Content -Raw connectors/sink-connector.json)
```

**Bash / curl:**
```bash
curl -X POST -H "Content-Type: application/json" --data @connectors/sink-connector.json http://localhost:8083/connectors
```

### 4. Verify Connector Status

```bash
curl http://localhost:8083/connectors/audit-records-jdbc-sink/status
```

### 5. Access Kafka UI

Open your browser and navigate to: **http://localhost:8080**

From here you can:
- View topics and messages
- Monitor consumer groups
- Manage connectors (under "Kafka Connect" tab)

## Message Format

The connector expects JSON messages in the following format:

```json
{
  "messageId": "d1c452a2-7ba4-4e18-8366-7ffafaed3bed",
  "timestamp": 1771138537,
  "requester": "MyApplicationName",
  "direction": "REQUEST",
  "metadata": "{\"key1\":\"value1\",\"key2\":\"value2\"}",
  "format": "application/xml"
}
```

**Important:** The `metadata` field must be a valid JSON **string** (escaped), not a nested JSON object.

### Field Specifications

| Field       | Type    | Required | Description                          |
|-------------|---------|----------|--------------------------------------|
| messageId   | String  | Yes      | UUID, primary key                    |
| timestamp   | Long    | Yes      | Unix timestamp (seconds)             |
| requester   | String  | Yes      | Application name                     |
| direction   | String  | Yes      | `REQUEST` or `RESPONSE`              |
| metadata    | String  | No       | JSON string with additional data     |
| format      | String  | No       | Content type (e.g., `application/xml`) |

## Publish Test Messages

**Using Kafka Console Producer:**
```bash
docker exec -it kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic Audit-Records.JSON
```

Then paste a valid JSON message (single line):
```json
{"messageId":"test-001","timestamp":1771138537,"requester":"TestApp","direction":"REQUEST","metadata":"{\"key\":\"value\"}","format":"application/json"}
```

## PostgreSQL Connection

| Property | Value                |
|----------|----------------------|
| Host     | `localhost`          |
| Port     | `5432`               |
| Database | `kafka_sink`         |
| Username | `postgres`           |
| Password | `postgres`           |

**Note:** If you previously ran this setup and cannot connect with the default credentials, the password may have been set differently during the first initialization. To reset:

```bash
docker-compose down -v   # Removes volumes (deletes all data!)
docker-compose up -d --build
```

### Query Audit Records

```sql
SELECT * FROM audit_records;
```

## Custom SMT: AuditRecordSchemaTransform

The custom Single Message Transform (`com.archiver.connect.transforms.AuditRecordSchemaTransform`) converts schemaless JSON messages (Map) into schema-aware Struct objects required by the JDBC Sink Connector.

### Building the SMT Manually

```bash
cd kafka-connect-smt
mvn clean package
```

The JAR file will be created at `target/audit-record-smt-1.0.0.jar`.

## Connector Configuration

The sink connector (`connectors/sink-connector.json`) is configured with:

- **Connector:** `io.aiven.connect.jdbc.JdbcSinkConnector` (Aiven JDBC Connector)
- **Insert Mode:** `upsert` (insert or update based on primary key)
- **Primary Key:** `messageId` (from record value)
- **Auto Create/Evolve:** Enabled for schema flexibility
- **Error Tolerance:** `all` (logs errors but continues processing)

## Troubleshooting

### Connector Not Starting

Check connector status:
```bash
curl http://localhost:8083/connectors/audit-records-jdbc-sink/status | jq
```

### JSON Parsing Errors

Ensure your JSON message:
1. Is valid JSON (use a validator)
2. Has the `metadata` field as a **string**, not a nested object
3. Is sent as a single line (no newlines within the JSON)

**Correct:**
```json
{"messageId":"123","timestamp":1234567890,"requester":"App","direction":"REQUEST","metadata":"{\"key\":\"value\"}","format":"text/plain"}
```

**Incorrect:**
```json
{"metadata": { "key": "value" }}  // metadata should be a string, not an object
```

### Database Connection Issues

If authentication fails after changing environment variables:
```bash
docker-compose down -v
docker-compose up -d --build
```

### View Kafka Connect Logs

```bash
docker logs -f kafka-connect
```

### Reset Everything

```bash
docker-compose down -v
docker-compose up -d --build
```

## Environment Variables

You can override defaults by creating a `.env` file:

```env
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres
POSTGRES_DB=kafka_sink
```

## Architecture

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   Producer      │────▶│  Kafka Broker   │────▶│  Kafka Connect  │
│  (Your App)     │     │  (KRaft mode)   │     │  (JDBC Sink)    │
└─────────────────┘     └─────────────────┘     └────────┬────────┘
                                                         │
                        ┌─────────────────┐              │
                        │   Kafka UI      │              │
                        │  (Port 8080)    │              ▼
                        └─────────────────┘     ┌─────────────────┐
                                                │   PostgreSQL    │
                                                │  (audit_records)│
                                                └─────────────────┘
```

## License

This project is provided as-is for demonstration and development purposes.
