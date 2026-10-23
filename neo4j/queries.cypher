// Run the sections one at a time in Neo4j Browser, or run the whole file with
// cypher-shell after seed.cypher. Each section uses only synthetic test data.

// 1. Doctors by specialization.
MATCH (doctor:Doctor)-[:SPECIALIZES_IN]->(specialization:Specialization {code: 'CARDIOLOGIST'})
RETURN doctor.doctorId AS doctorId, doctor.name AS doctor, specialization.name AS specialization
ORDER BY doctor.name;

// 2. A patient's appointment history and completed-consultation history.
MATCH (patient:Patient {patientId: 1})-[:BOOKED]->(appointment:Appointment)-[:`WITH`]->(doctor:Doctor)
OPTIONAL MATCH (patient)-[consulted:CONSULTED]->(doctor)
WHERE consulted.appointmentId = appointment.appointmentId
RETURN patient.patientId AS patientId,
       appointment.appointmentId AS appointmentId,
       appointment.appointmentDate AS appointmentDate,
       appointment.status AS status,
       appointment.mode AS mode,
       doctor.name AS doctor,
       consulted.on AS consultedOn
ORDER BY appointment.appointmentDate;

// 3. Doctors sharing a specialization with a doctor this patient has consulted.
MATCH (:Patient {patientId: 1})-[:CONSULTED]->(previousDoctor:Doctor)-[:SPECIALIZES_IN]->(specialization:Specialization)<-[:SPECIALIZES_IN]-(peer:Doctor)
WHERE peer <> previousDoctor
RETURN DISTINCT previousDoctor.name AS previouslyConsultedDoctor,
       specialization.name AS sharedSpecialization,
       peer.doctorId AS peerDoctorId,
       peer.name AS peerDoctor
ORDER BY sharedSpecialization, peerDoctor;

// 4. Referral paths. In Browser, this returns a drawable path.
MATCH path = (:Doctor {doctorId: 101})-[:REFERRED_TO*1..3]->(:Doctor {doctorId: 105})
RETURN path;

// 5a. CREATE (safe to repeat because MERGE reuses the same test node).
MERGE (testPatient:Patient {patientId: 9999})
ON CREATE SET testPatient.name = 'Test-only Patient', testPatient.city = 'Test City', testPatient.synthetic = true
ON MATCH SET testPatient.name = 'Test-only Patient', testPatient.city = 'Test City', testPatient.synthetic = true
RETURN testPatient;

// 5b. RETRIEVE the test record.
MATCH (testPatient:Patient {patientId: 9999})
RETURN testPatient;

// 5c. UPDATE the test record.
MATCH (testPatient:Patient {patientId: 9999})
SET testPatient.city = 'Updated Test City'
RETURN testPatient;

// 5d. DELETE the test record. DETACH DELETE is deliberate so the test remains
// safe even if a relationship was added during manual experimentation.
MATCH (testPatient:Patient {patientId: 9999})
DETACH DELETE testPatient
RETURN 1 AS deletedTestPatient;

// 6. Graph visualization: execute this query in Neo4j Browser and switch the
// result to graph view if Browser did not select it automatically.
MATCH graph = (:Patient)-[:BOOKED]->(:Appointment)-[:`WITH`]->(:Doctor)-[:SPECIALIZES_IN]->(:Specialization)
RETURN graph
LIMIT 50;
