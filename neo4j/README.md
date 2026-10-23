# Neo4j graph prototype

This folder contains the Phase 1 graph prototype scripts and the Phase 2
integration notes. The Phase 2 backend adds an optional Neo4j driver while
leaving the Oracle schema and relational application behavior unchanged.

## What was inspected

The backend uses Spring JDBC (`JdbcTemplate`), not JPA/Hibernate, so there are
no `@Entity` annotations or JPA repositories. The Java model is named
`Consultation`; there is no Java `Appointment` or `Specialization` model.
`Doctor.specialization` is populated from the supporting
`DOCTOR_SPECIALIZATION` table. The relational source of truth is
`database/01_schema.sql` and `database/02_constraints.sql`:

| Relational source | Cardinality evidenced by foreign keys / PKs | Graph representation |
| --- | --- | --- |
| `PATIENT` | One patient has zero or many `CONSULTATION` rows. | `(:Patient)-[:BOOKED]->(:Appointment)` |
| `DOCTOR` | One doctor has zero or many `CONSULTATION` rows. | `(:Appointment)-[:`WITH`]->(:Doctor)` |
| `CONSULTATION` | Each row has `patient_id` and `doctor_id` foreign keys. The code calls this entity `Consultation`; `Appointment` is the graph label used in this prototype. | `:Appointment` with the consultation date, status, and mode only |
| `DOCTOR_SPECIALIZATION` | `doctor_id` is both PK and FK, so each doctor has zero or one specialization row. A specialization value can belong to many doctors. | `(:Doctor)-[:SPECIALIZES_IN]->(:Specialization)` |

The database foreign-key columns on `CONSULTATION` are nullable at the DDL
level, but the REST `ConsultationService` requires both `patientId` and
`doctorId` for every create/update operation. The graph sample therefore gives
each `Appointment` exactly one `Patient` and one `Doctor`.

`CONSULTED` is not a relational table: it is a derived graph convenience edge
created only for completed `Appointment` nodes, with the source appointment ID
on the relationship. `REFERRED_TO` is a **new, graph-only feature**; the
relational project has no referral table. The seed is a repeatable projection
of the repository's synthetic Oracle demo rows, using the same stable primary
keys. Referral records remain synthetic graph-only data.

The graph intentionally excludes unrelated relational tables (hospital,
payments, prescriptions, medicines, tests, phone numbers, feedback, and
medical records) because they are not necessary for the requested graph
queries.

## Existing application (unchanged)

The existing application remains a separate Oracle-backed Spring Boot API on
port 8080 with a Vite/React frontend. The frontend's `VITE_API_URL` defaults to
`http://localhost:8080/api`; the backend reads its Oracle connection settings
from `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`.

## Phase 2 application integration

The backend uses the official Neo4j Java Driver in a dedicated graph repository;
it does not alter `JdbcTemplate`, Oracle repositories, or relational CRUD.
Neo4j is opt-in: `NEO4J_ENABLED` defaults to `false`, so existing Oracle
features remain available when Neo4j is offline. Graph requests return HTTP 503
with a safe message when the graph driver is disabled or cannot connect.

The frontend has a **Healthcare Graph** navigation item. It obtains graph data
only through the backend APIs, and the small interactive SVG view renders the
structured graph response rather than direct database data. It intentionally
shows patient nodes as ID-only labels.

### Two-database boundary and seed process

Oracle remains the source of truth for the original records. Neo4j contains the
traversal-oriented projection only. The bundled seed uses the existing demo
primary keys (`PATIENT` 1–6, `DOCTOR` 101–105, and `CONSULTATION` 701–707), but
there is no automatic synchronization and no cross-database transaction.

After any change to the Oracle demo records, rerun `constraints.cypher` and
`seed.cypher` to refresh the graph prototype. For real data, implement a
separate approved synchronization process; do not assume a relational record
automatically has a graph node. `REFERRED_TO` remains synthetic graph-only
prototype data and is identified as such by the API/UI.

### Graph API

| Endpoint | Purpose |
| --- | --- |
| `GET /api/graph/doctors?specialization=&excludeDoctorId=` | Parameterized doctor discovery, optionally excluding a current doctor. |
| `GET /api/graph/patients/{patientId}/journey` | ID-only patient journey with linked appointments and doctors. |
| `GET /api/graph/referrals/{doctorId}` | Direct, graph-only outgoing referral edges. |
| `GET /api/graph/referrals/path?fromDoctorId=&toDoctorId=&maxDepth=3` | Directed referral paths, up to five hops. |
| `GET /api/graph/visualization?patientId=&maxNodes=24` | Bounded nodes and relationships for the patient graph. |
| `GET /api/graph/visualization?doctorId=&maxNodes=24` | Bounded doctor referral graph. |

Invalid IDs and limits return HTTP 400; absent graph nodes return HTTP 404;
and disabled/unreachable Neo4j returns HTTP 503. The graph endpoint accepts a
maximum of 50 nodes.

## Minimal local setup (no Docker required)

1. Install [Neo4j Desktop or Neo4j Community Edition](https://neo4j.com/download/),
   create a local DBMS/database, set its initial password, and start it.
2. Copy the values from `.env.example` into your shell environment. Do not save
   the real password in the repository. The usual local Browser URL is
   `http://localhost:7474`; Bolt is normally `bolt://localhost:7687`.
3. In PowerShell, from the project root, set the connection values:

   ```powershell
   $env:NEO4J_URI = 'bolt://localhost:7687'
   $env:NEO4J_USERNAME = 'neo4j'
   $env:NEO4J_PASSWORD = 'your-local-neo4j-password'
   $env:NEO4J_DATABASE = 'neo4j'
   $env:NEO4J_ENABLED = 'true'
   ```

## Import the sample data

Use the built-in `cypher-shell` that comes with Neo4j. Run the constraints
first, then the seed script:

```powershell
cypher-shell -a $env:NEO4J_URI -d $env:NEO4J_DATABASE --file neo4j\constraints.cypher
cypher-shell -a $env:NEO4J_URI -d $env:NEO4J_DATABASE --file neo4j\seed.cypher
```

Both scripts are repeatable: constraints use `IF NOT EXISTS`, and the seed uses
`MERGE` on the unique identifiers. As an alternative, open each file in Neo4j
Browser and run its statements in that order.

With Oracle and Neo4j running, start the integrated application in two shells:

```powershell
# backend/
mvn spring-boot:run

# frontend/
npm run dev
```

## Run and visualize the queries

Execute individual sections of `queries.cypher` in Neo4j Browser, or run:

```powershell
cypher-shell -a $env:NEO4J_URI -d $env:NEO4J_DATABASE --file neo4j\queries.cypher
```

For an interactive graph, open `http://localhost:7474`, authenticate with the
same credentials, run query 4 or query 6 from `queries.cypher`, and use the
Graph result view. The scripts include working Cypher for specialization lookup,
patient history, shared-specialization discovery, referral paths, CRUD, and a
connected-node visualization.

## Verification status

This checkout has no `neo4j` or `cypher-shell` executable and nothing is
listening on local ports 7474 or 7687. The scripts have therefore been
reviewed for Neo4j 5 Cypher syntax and repeatability but are **not yet executed
against a running Neo4j instance**. After following the import commands above,
run `queries.cypher`; its expected cardinalities are 6 patients, 5 doctors, 5
specializations, 7 appointments, 4 `CONSULTED` edges (for completed
appointments), and 3 `REFERRED_TO` edges.
