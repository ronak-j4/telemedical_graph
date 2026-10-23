package com.hospital.dashboard.repository.graph;

import com.hospital.dashboard.exception.GraphUnavailableException;
import com.hospital.dashboard.model.graph.GraphModels.DoctorDiscovery;
import com.hospital.dashboard.model.graph.GraphModels.GraphData;
import com.hospital.dashboard.model.graph.GraphModels.GraphNode;
import com.hospital.dashboard.model.graph.GraphModels.GraphRelationship;
import com.hospital.dashboard.model.graph.GraphModels.PatientJourneyItem;
import com.hospital.dashboard.model.graph.GraphModels.Referral;
import com.hospital.dashboard.model.graph.GraphModels.ReferralPath;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;
import org.neo4j.driver.exceptions.AuthenticationException;
import org.neo4j.driver.exceptions.Neo4jException;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Parameterized Cypher access only. This repository never reads or writes the
 * Oracle datasource and is deliberately separate from the JdbcTemplate repositories.
 */
@Repository
public class Neo4jGraphRepository {

    private static final Logger log = LoggerFactory.getLogger(Neo4jGraphRepository.class);

    private final ObjectProvider<Driver> driverProvider;
    private final String database;

    public Neo4jGraphRepository(ObjectProvider<Driver> driverProvider,
                                @org.springframework.beans.factory.annotation.Value("${app.neo4j.database}") String database) {
        this.driverProvider = driverProvider;
        this.database = database;
    }

    public boolean patientExists(Long patientId) {
        return nodeExists("Patient", "patientId", patientId);
    }

    public boolean doctorExists(Long doctorId) {
        return nodeExists("Doctor", "doctorId", doctorId);
    }

    public List<DoctorDiscovery> findDoctorsBySpecialization(String specialization, Long excludeDoctorId) {
        String cypher = """
                MATCH (doctor:Doctor)-[:SPECIALIZES_IN]->(specialization:Specialization {code: $specialization})
                WHERE $excludeDoctorId IS NULL OR doctor.doctorId <> $excludeDoctorId
                RETURN DISTINCT doctor.doctorId AS doctorId,
                       doctor.name AS doctorName,
                       specialization.name AS specialization
                ORDER BY doctorName
                """;
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("specialization", specialization);
        parameters.put("excludeDoctorId", excludeDoctorId);
        return read(session -> session.run(cypher, parameters).list(record -> new DoctorDiscovery(
                record.get("doctorId").asLong(),
                stringValue(record.get("doctorName")),
                stringValue(record.get("specialization"))
        )));
    }

    public List<PatientJourneyItem> findPatientJourney(Long patientId) {
        String cypher = """
                MATCH (patient:Patient {patientId: $patientId})-[:BOOKED]->(appointment:Appointment)-[:`WITH`]->(doctor:Doctor)
                OPTIONAL MATCH (patient)-[consulted:CONSULTED {appointmentId: appointment.appointmentId}]->(doctor)
                RETURN appointment.appointmentId AS appointmentId,
                       appointment.appointmentDate AS appointmentDate,
                       appointment.status AS status,
                       appointment.mode AS mode,
                       doctor.doctorId AS doctorId,
                       doctor.name AS doctorName,
                       consulted IS NOT NULL AS consulted
                ORDER BY appointmentDate, appointmentId
                """;
        return read(session -> session.run(cypher, Map.of("patientId", patientId)).list(record -> new PatientJourneyItem(
                record.get("appointmentId").asLong(),
                dateValue(record.get("appointmentDate")),
                stringValue(record.get("status")),
                stringValue(record.get("mode")),
                record.get("doctorId").asLong(),
                stringValue(record.get("doctorName")),
                record.get("consulted").asBoolean()
        )));
    }

    public List<Referral> findOutgoingReferrals(Long doctorId) {
        String cypher = """
                MATCH (source:Doctor {doctorId: $doctorId})-[referral:REFERRED_TO]->(target:Doctor)
                RETURN source.doctorId AS fromDoctorId,
                       source.name AS fromDoctorName,
                       target.doctorId AS toDoctorId,
                       target.name AS toDoctorName,
                       referral.reason AS reason,
                       referral.referredOn AS referredOn,
                       referral.synthetic AS synthetic
                ORDER BY referredOn, toDoctorName
                """;
        return read(session -> session.run(cypher, Map.of("doctorId", doctorId)).list(this::toReferral));
    }

    public List<ReferralPath> findReferralPaths(Long fromDoctorId, Long toDoctorId, int maxDepth) {
        String cypher = """
                MATCH path = (source:Doctor {doctorId: $fromDoctorId})-[:REFERRED_TO*1..5]->(target:Doctor {doctorId: $toDoctorId})
                WHERE length(path) <= $maxDepth
                RETURN nodes(path) AS doctors, relationships(path) AS referrals
                LIMIT 10
                """;
        Map<String, Object> parameters = Map.of(
                "fromDoctorId", fromDoctorId,
                "toDoctorId", toDoctorId,
                "maxDepth", maxDepth
        );
        return read(session -> session.run(cypher, parameters).list(this::toReferralPath));
    }

    public GraphData patientGraph(Long patientId, int maxNodes) {
        String cypher = """
                MATCH (patient:Patient {patientId: $patientId})-[booked:BOOKED]->(appointment:Appointment)-[withRelation:`WITH`]->(doctor:Doctor)
                OPTIONAL MATCH (doctor)-[specializes:SPECIALIZES_IN]->(specialization:Specialization)
                OPTIONAL MATCH (patient)-[consulted:CONSULTED {appointmentId: appointment.appointmentId}]->(doctor)
                RETURN patient, appointment, doctor, specialization, booked, withRelation, specializes, consulted
                ORDER BY appointment.appointmentDate, appointment.appointmentId
                LIMIT $rowLimit
                """;
        List<Record> records = read(session -> session.run(cypher, Map.of(
                "patientId", patientId,
                "rowLimit", maxNodes
        )).list());
        return graphFromPatientRecords(records, maxNodes);
    }

    public GraphData doctorReferralGraph(Long doctorId, int maxNodes) {
        String cypher = """
                MATCH (doctor:Doctor {doctorId: $doctorId})
                OPTIONAL MATCH (doctor)-[outgoing:REFERRED_TO]->(outgoingDoctor:Doctor)
                OPTIONAL MATCH (incomingDoctor:Doctor)-[incoming:REFERRED_TO]->(doctor)
                OPTIONAL MATCH (doctor)-[specializes:SPECIALIZES_IN]->(specialization:Specialization)
                RETURN doctor, outgoing, outgoingDoctor, incoming, incomingDoctor, specializes, specialization
                LIMIT $rowLimit
                """;
        List<Record> records = read(session -> session.run(cypher, Map.of(
                "doctorId", doctorId,
                "rowLimit", maxNodes
        )).list());
        return graphFromDoctorRecords(records, maxNodes);
    }

    private boolean nodeExists(String label, String idProperty, Long id) {
        String cypher = "MATCH (node:" + label + " {" + idProperty + ": $id}) RETURN count(node) > 0 AS exists";
        return read(session -> session.run(cypher, Map.of("id", id)).single().get("exists").asBoolean());
    }

    private <T> T read(Function<Session, T> work) {
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null) {
            throw new GraphUnavailableException("Healthcare Graph is disabled. Set NEO4J_ENABLED=true and configure Neo4j credentials.");
        }
        try (Session session = driver.session(SessionConfig.forDatabase(database))) {
            return work.apply(session);
        } catch (AuthenticationException ex) {
            log.warn("Neo4j authentication failed for configured user and database '{}'.", database);
            throw new GraphUnavailableException("Neo4j rejected the configured username or password.", ex);
        } catch (ServiceUnavailableException ex) {
            log.warn("Neo4j server is unreachable at the configured URI for database '{}'.", database);
            throw new GraphUnavailableException("Neo4j is not reachable at the configured URI.", ex);
        } catch (Neo4jException | IllegalStateException ex) {
            log.warn("Neo4j graph query failed for database '{}': {}", database, ex.getMessage(), ex);
            throw new GraphUnavailableException("Neo4j rejected the graph request. Verify its database name and configuration.", ex);
        }
    }

    private Referral toReferral(Record record) {
        return new Referral(
                record.get("fromDoctorId").asLong(),
                stringValue(record.get("fromDoctorName")),
                record.get("toDoctorId").asLong(),
                stringValue(record.get("toDoctorName")),
                stringValue(record.get("reason")),
                dateValue(record.get("referredOn")),
                booleanValue(record.get("synthetic"))
        );
    }

    private ReferralPath toReferralPath(Record record) {
        List<Node> doctors = record.get("doctors").asList(Value::asNode);
        List<Relationship> referrals = record.get("referrals").asList(Value::asRelationship);
        List<Referral> steps = new ArrayList<>();
        for (int index = 0; index < referrals.size(); index++) {
            Node from = doctors.get(index);
            Node to = doctors.get(index + 1);
            Relationship referral = referrals.get(index);
            steps.add(new Referral(
                    from.get("doctorId").asLong(),
                    stringValue(from.get("name")),
                    to.get("doctorId").asLong(),
                    stringValue(to.get("name")),
                    stringValue(referral.get("reason")),
                    dateValue(referral.get("referredOn")),
                    booleanValue(referral.get("synthetic"))
            ));
        }
        return new ReferralPath(steps);
    }

    private GraphData graphFromPatientRecords(List<Record> records, int maxNodes) {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        Map<String, GraphRelationship> relationships = new LinkedHashMap<>();
        for (Record record : records) {
            GraphNode patient = addNode(nodes, nodeValue(record, "patient"));
            GraphNode appointment = addNode(nodes, nodeValue(record, "appointment"));
            GraphNode doctor = addNode(nodes, nodeValue(record, "doctor"));
            GraphNode specialization = addNode(nodes, nodeValue(record, "specialization"));
            addRelationship(relationships, "BOOKED", patient, appointment, "");
            addRelationship(relationships, "WITH", appointment, doctor, "");
            addRelationship(relationships, "SPECIALIZES_IN", doctor, specialization, "");
            if (!record.get("consulted").isNull()) {
                addRelationship(relationships, "CONSULTED", patient, doctor,
                        String.valueOf(record.get("appointment").asNode().get("appointmentId").asLong()));
            }
        }
        return boundedGraph(nodes, relationships, maxNodes);
    }

    private GraphData graphFromDoctorRecords(List<Record> records, int maxNodes) {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        Map<String, GraphRelationship> relationships = new LinkedHashMap<>();
        for (Record record : records) {
            GraphNode doctor = addNode(nodes, nodeValue(record, "doctor"));
            GraphNode outgoingDoctor = addNode(nodes, nodeValue(record, "outgoingDoctor"));
            GraphNode incomingDoctor = addNode(nodes, nodeValue(record, "incomingDoctor"));
            GraphNode specialization = addNode(nodes, nodeValue(record, "specialization"));
            addRelationship(relationships, "REFERRED_TO", doctor, outgoingDoctor, "outgoing");
            addRelationship(relationships, "REFERRED_TO", incomingDoctor, doctor, "incoming");
            addRelationship(relationships, "SPECIALIZES_IN", doctor, specialization, "");
        }
        return boundedGraph(nodes, relationships, maxNodes);
    }

    private GraphData boundedGraph(Map<String, GraphNode> nodeMap,
                                   Map<String, GraphRelationship> relationshipMap,
                                   int maxNodes) {
        List<GraphNode> nodes = nodeMap.values().stream().limit(maxNodes).toList();
        Map<String, Boolean> retainedNodeIds = new LinkedHashMap<>();
        nodes.forEach(node -> retainedNodeIds.put(node.id(), true));
        List<GraphRelationship> relationships = relationshipMap.values().stream()
                .filter(relationship -> retainedNodeIds.containsKey(relationship.source())
                        && retainedNodeIds.containsKey(relationship.target()))
                .toList();
        return new GraphData(nodes, relationships);
    }

    private GraphNode addNode(Map<String, GraphNode> nodes, Node node) {
        if (node == null) return null;
        String type = node.labels().iterator().next();
        String id = switch (type) {
            case "Patient" -> "Patient:" + node.get("patientId").asLong();
            case "Doctor" -> "Doctor:" + node.get("doctorId").asLong();
            case "Appointment" -> "Appointment:" + node.get("appointmentId").asLong();
            case "Specialization" -> "Specialization:" + stringValue(node.get("code"));
            default -> type + ":" + node.elementId();
        };
        String label = switch (type) {
            case "Patient" -> "Patient #" + node.get("patientId").asLong();
            case "Doctor" -> stringValue(node.get("name"));
            case "Appointment" -> "Appointment #" + node.get("appointmentId").asLong();
            case "Specialization" -> stringValue(node.get("name"));
            default -> type;
        };
        GraphNode graphNode = new GraphNode(id, type, label);
        nodes.putIfAbsent(id, graphNode);
        return graphNode;
    }

    private void addRelationship(Map<String, GraphRelationship> relationships,
                                 String type,
                                 GraphNode source,
                                 GraphNode target,
                                 String suffix) {
        if (source == null || target == null) return;
        String id = type + ":" + source.id() + ":" + target.id() + ":" + suffix;
        relationships.putIfAbsent(id, new GraphRelationship(id, type, source.id(), target.id()));
    }

    private Node nodeValue(Record record, String key) {
        Value value = record.get(key);
        return value == null || value.isNull() ? null : value.asNode();
    }

    private static String stringValue(Value value) {
        return value == null || value.isNull() ? null : value.asString();
    }

    private static String dateValue(Value value) {
        return value == null || value.isNull() ? null : value.asLocalDate().toString();
    }

    private static boolean booleanValue(Value value) {
        return value != null && !value.isNull() && value.asBoolean();
    }
}
