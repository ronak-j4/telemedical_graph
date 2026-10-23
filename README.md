# MediCore — Telemedicine Consultation & Records System

MediCore is a full-stack telemedicine prototype that combines a conventional
Oracle-backed healthcare records application with a Neo4j graph database for
relationship-oriented features. The existing relational workflows remain
available; Neo4j adds doctor discovery, patient care journeys, referral
exploration, and an interactive graph view.

## What the application provides

- Existing telemedicine management screens for patients, doctors, hospitals,
  consultations, prescriptions, medicines, tests, and payments.
- Oracle-backed REST APIs and CRUD operations that retain the original project
  behavior.
- A **Healthcare Graph** screen powered by real Neo4j API responses:
  - find doctors by specialization;
  - view a selected patient's linked appointments and doctors;
  - inspect synthetic, graph-only doctor referral connections and paths;
  - view returned nodes and relationships in a bounded interactive SVG graph.
- Repeatable Neo4j constraints and synthetic demo data that use the same stable
  IDs as the repository's Oracle demo records.

## Technology stack

| Area | Technology | Role in this project |
| --- | --- | --- |
| Frontend | React 18, TypeScript, Vite 5 | Single-page management dashboard and Healthcare Graph UI. |
| UI | Tailwind CSS, Lucide React, React Router, Recharts | Styling, icons, navigation, and existing dashboard charts. |
| Backend | Java 17, Spring Boot 3.3.4, Maven | REST API, validation, error handling, and integration boundary. |
| Relational persistence | Spring JDBC / `JdbcTemplate`, Oracle JDBC driver | Original CRUD and operational healthcare records. |
| Relational database | Oracle Database | Primary source of truth for the existing DBMS project. |
| Graph persistence | Official Neo4j Java Driver 6.1.0 over Bolt | Parameterized Cypher queries in a dedicated graph repository. |
| Graph database | Neo4j Desktop or Neo4j Community Edition | Local graph database and Neo4j Browser/Bloom visualization. |

## Two-database architecture

```text
                         HTTP / JSON
┌───────────────────────────────────────────────────────────────────┐
│ React + Vite frontend (http://localhost:5173)                      │
│ Existing management pages + Healthcare Graph                       │
└──────────────────────────────┬────────────────────────────────────┘
                               │ /api
┌──────────────────────────────▼────────────────────────────────────┐
│ Spring Boot backend (http://localhost:8080)                        │
│                                                                     │
│  Existing services/repositories       Dedicated graph repository   │
│  Spring JDBC / JdbcTemplate           Official Neo4j Java Driver   │
└──────────────────┬───────────────────────────────┬────────────────┘
                   │ JDBC                          │ Bolt
        ┌──────────▼──────────┐          ┌─────────▼──────────┐
        │ Oracle Database     │          │ Neo4j database     │
        │ Original records    │          │ Graph projection   │
        │ Source of truth     │          │ Traversals / paths │
        └─────────────────────┘          └────────────────────┘
```

Only the Spring Boot backend contacts either database. Browser code has no
database credentials and communicates solely through `/api` endpoints.

### Data ownership and availability

- **Oracle owns the original data.** Existing entities, CRUD endpoints,
  database scripts, and `JdbcTemplate` repositories are not replaced by Neo4j.
- **Neo4j stores only graph data needed for the new features.** It deliberately
  omits unrelated Oracle tables such as payments, prescriptions, medicines,
  tests, phones, feedback, and medical records.
- **No cross-database transaction is claimed.** Oracle and Neo4j changes are
  not atomic together.
- **Graph data is manually seeded for this assignment.** A relational record
  does not automatically receive a corresponding Neo4j node. Rerun the graph
  seed after changing the supplied Oracle demo data, or build a separately
  approved synchronization process for a production system.
- **Neo4j is optional.** `NEO4J_ENABLED` defaults to `false`, so the original
  Oracle-backed application can still operate if Neo4j is not configured or is
  unavailable. Graph requests then return a safe HTTP `503` response.

## Repository layout

```text
telemedical_platform_graph/
├── backend/                  Spring Boot API and Oracle + Neo4j integration
├── frontend/                 React/Vite web application
├── database/                 Oracle schema, constraints, seeds, queries, PL/SQL
├── neo4j/                    Cypher constraints, repeatable seed, graph queries
└── README.md                 This project overview
```

More focused documentation is available in:

- [Backend guide](backend/README.md)
- [Oracle database guide](database/README.md)
- [Neo4j graph guide and scripts](neo4j/README.md)

## Relational source and graph model

The graph design was derived from the implemented Oracle schema and backend,
not invented from the UI. The backend uses Spring JDBC rather than JPA; its Java
model is named `Consultation`, while the graph uses the clearer `Appointment`
label for the corresponding `CONSULTATION` row.

| Oracle source | Relationship evidenced by the existing schema | Neo4j representation |
| --- | --- | --- |
| `PATIENT` | A patient can have zero or more `CONSULTATION` rows. | `(:Patient)-[:BOOKED]->(:Appointment)` |
| `CONSULTATION` | Each usable consultation links one patient and one doctor. | `(:Appointment)` with the consultation ID, date, status, and mode. |
| `DOCTOR` | A doctor can have zero or more consultations. | `(:Appointment)-[:WITH]->(:Doctor)` |
| `DOCTOR_SPECIALIZATION` | `DOCTOR_ID` is both the PK and FK, so a doctor has zero or one specialization row; a specialization value may belong to many doctors. | `(:Doctor)-[:SPECIALIZES_IN]->(:Specialization)` |

Two additional edges are intentionally documented as graph features:

| Graph relationship | Direction | Meaning |
| --- | --- | --- |
| `CONSULTED` | `Patient → Doctor` | A derived convenience edge for a **completed** appointment. Its relationship data includes the source appointment ID. It is not an Oracle table. |
| `REFERRED_TO` | `Doctor → Doctor` | A small, synthetic doctor referral network added only for graph traversal demonstrations. The relational project has no referral table, so it must never be represented as an Oracle clinical record. |

### Nodes and stable identifiers

| Label | Identifier | Prototype properties kept |
| --- | --- | --- |
| `Patient` | `patientId` from `PATIENT.PATIENT_ID` | ID and minimal city data only. |
| `Doctor` | `doctorId` from `DOCTOR.DOCTOR_ID` | ID and display name. |
| `Appointment` | `appointmentId` from `CONSULTATION.CONSULTATION_ID` | Date, status, and mode. |
| `Specialization` | `code` | Stable specialization code and display name. |

The constraints in [neo4j/constraints.cypher](neo4j/constraints.cypher) enforce
uniqueness for these identifiers. The repeatable seed in
[neo4j/seed.cypher](neo4j/seed.cypher) uses `MERGE`, so rerunning it does not
duplicate matching nodes or relationships.

The supplied sample projection contains 6 patients, 5 doctors, 5
specializations, 7 appointments, 4 derived `CONSULTED` edges, and 3 synthetic
`REFERRED_TO` edges.

## Healthcare Graph API

All graph endpoints are served through the existing backend at
`http://localhost:8080/api`. Queries are parameterized in a dedicated Neo4j
repository; they are separate from the Oracle repositories.

| Endpoint | Purpose |
| --- | --- |
| `GET /api/graph/doctors?specialization={code}&excludeDoctorId={id}` | Finds doctors by graph specialization and can exclude the current doctor. |
| `GET /api/graph/patients/{patientId}/journey` | Returns the selected patient's linked appointments and doctors without exposing unrelated patient data. |
| `GET /api/graph/referrals/{doctorId}` | Retrieves direct outgoing `REFERRED_TO` records. These are explicitly graph-only synthetic data. |
| `GET /api/graph/referrals/path?fromDoctorId={id}&toDoctorId={id}&maxDepth=3` | Finds directed referral paths; the API limits traversal depth to five hops. |
| `GET /api/graph/visualization?patientId={id}&maxNodes=24` | Returns a bounded set of patient-journey nodes and edges for the UI graph. |
| `GET /api/graph/visualization?doctorId={id}&maxNodes=24` | Returns a bounded referral graph for a doctor. |

The visualization response contains stable node IDs, node types, display
labels, relationship types, and source/target IDs. Invalid IDs or limits return
`400`, a missing graph node returns `404`, and unavailable/disabled Neo4j
returns `503`. Graph responses are capped at 50 nodes to keep payloads and UI
rendering controlled.

## Local setup

### Prerequisites

- JDK 17 and Maven
- Node.js with npm
- An Oracle database/schema for the original project
- Neo4j Desktop or Neo4j Community Edition for the graph feature

Docker is not required.

### 1. Prepare the Oracle database

Use Oracle SQL Developer or SQL*Plus and run the scripts in the order described
in [database/README.md](database/README.md): schema, constraints, indexes,
seed data, queries, PL/SQL, then sequences.

Set the Oracle connection settings before starting the backend. Adapt the values
to your local Oracle installation and do not commit passwords.

```powershell
$env:DB_URL = 'jdbc:oracle:thin:@//localhost:1521/XEPDB1'
$env:DB_USERNAME = 'your_oracle_user'
$env:DB_PASSWORD = 'your_oracle_password'
```

### 2. Start Neo4j and import the graph projection

1. Open Neo4j Desktop, create a local instance/database if needed, choose an
   initial password, and start it.
2. Select the database named `neo4j` (or use the name you chose below).
3. In Neo4j Query/Browser, open and execute
   [neo4j/constraints.cypher](neo4j/constraints.cypher) first, then
   [neo4j/seed.cypher](neo4j/seed.cypher). Running the statements in each file
   separately is convenient in Neo4j Desktop and does not require `cypher-shell`.
4. Set the following values in the **same terminal session** used to start the
   backend. Replace the password with the one created in Neo4j Desktop.

```powershell
$env:NEO4J_ENABLED = 'true'
$env:NEO4J_URI = 'neo4j://127.0.0.1:7687'
$env:NEO4J_USERNAME = 'neo4j'
$env:NEO4J_PASSWORD = 'your_local_neo4j_password'
$env:NEO4J_DATABASE = 'neo4j'
```

`bolt://localhost:7687` is also valid for a typical local installation. Use the
connection URI shown for the running instance in Neo4j Desktop. Do not put the
actual password in `neo4j/.env.example`; it is a template only.

Optional command-line import, when `cypher-shell` is installed and on `PATH`:

```powershell
cypher-shell -a $env:NEO4J_URI -d $env:NEO4J_DATABASE --file neo4j\constraints.cypher
cypher-shell -a $env:NEO4J_URI -d $env:NEO4J_DATABASE --file neo4j\seed.cypher
```

### 3. Start the backend

Open a PowerShell terminal at the repository root. The following uses a local
Maven cache inside `backend` and avoids depending on a user-level Maven cache.

```powershell
Set-Location backend
$projectMavenRepo = Join-Path (Get-Location) '.m2'
mvn "-Dmaven.repo.local=$projectMavenRepo" spring-boot:run
```

The API starts at `http://localhost:8080/api`. Keep this terminal open while
using the website. A successful startup with Neo4j enabled logs that a driver
instance was created for the configured URI; that alone does not expose the
password.

### 4. Start the frontend

In a second PowerShell terminal:

```powershell
Set-Location frontend
npm install
npm run dev
```

Open `http://localhost:5173`. The default frontend API target is
`http://localhost:8080/api`; set `VITE_API_URL` only if the backend uses a
different address. If you use a different frontend origin, set `FRONTEND_ORIGIN`
before starting Spring Boot so CORS allows it.

## Demonstrating the graph

### In the MediCore website

1. Open **Healthcare Graph** in the left navigation.
2. Choose a specialization to run Doctor Discovery.
3. Select a patient to see the Patient Care Journey.
4. Select a doctor to inspect outgoing referrals or choose a destination doctor
   to find a referral path.
5. The interactive graph renders the exact bounded response returned by
   `/api/graph/visualization`. Select a node to inspect its label, type, and
   stable ID.

The UI intentionally uses patient ID-only graph labels where possible to avoid
showing more patient data than the feature requires.

### In Neo4j Browser or Bloom

Open the local Neo4j database from Neo4j Desktop and execute sections from
[neo4j/queries.cypher](neo4j/queries.cypher). They demonstrate:

1. doctors by specialization;
2. a patient's appointment and consultation history;
3. doctors sharing a specialization with a previously consulted doctor;
4. direct and multi-hop referral paths;
5. safe test CRUD using a disposable graph record; and
6. a connected-node query that Neo4j Browser can render visually.

For a compact graph visualization in Browser, run this query:

```cypher
MATCH (p:Patient)-[booked:BOOKED]->(a:Appointment)-[with:`WITH`]->(d:Doctor)
OPTIONAL MATCH (p)-[consulted:CONSULTED]->(d)
OPTIONAL MATCH (d)-[specializes:SPECIALIZES_IN]->(s:Specialization)
OPTIONAL MATCH (d)-[referred:REFERRED_TO]->(next:Doctor)
RETURN p, booked, a, with, d, consulted, specializes, s, referred, next;
```

## Verification recorded for this prototype

The local demonstration was manually verified on **23 October 2026**:

- Neo4j Desktop showed the `TelemedicineGraph` local instance running and its
  `neo4j` database connected.
- Neo4j Bloom displayed the seeded patients, doctors, appointments,
  specializations, and their graph relationships.
- The running web application's Healthcare Graph successfully loaded the care
  journey for sample patient `Aarav Sharma (#1)`, including appointment `#701`
  with Dr. Meera Nair and appointment `#707` with Dr. Vikram Rao.
- The website's interactive graph rendered the `BOOKED`, `WITH`, and
  `SPECIALIZES_IN` links returned by the backend.
- `mvn clean test` completed successfully after the Neo4j integration build;
  this repository currently has no Java test source classes, so Maven reported
  no automated tests to run.

Use the commands below whenever you change code:

```powershell
# Backend validation
Set-Location backend
$projectMavenRepo = Join-Path (Get-Location) '.m2'
mvn "-Dmaven.repo.local=$projectMavenRepo" clean test

# Frontend production bundle
Set-Location ..\frontend
npm run build
```

## Security and prototype limits

- Never commit Oracle or Neo4j passwords. The included
  [neo4j/.env.example](neo4j/.env.example) is deliberately non-secret.
- The graph seed uses synthetic data for demonstration only; it is not real
  patient information.
- `REFERRED_TO` is synthetic graph data, not a clinical referral record.
- The graph feature validates IDs and bounds path depth/result size, but it is
  an academic prototype and does not provide authentication, authorization,
  auditing, or production data synchronization.
- Keep Neo4j access on the backend. The frontend must never use Bolt directly
  or receive database credentials.

## Further documentation

- Oracle installation, table list, and SQL execution order:
  [database/README.md](database/README.md)
- Backend endpoints and existing Oracle API details:
  [backend/README.md](backend/README.md)
- Graph model rationale, Cypher scripts, and Neo4j-specific setup:
  [neo4j/README.md](neo4j/README.md)
