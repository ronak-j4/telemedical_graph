package com.hospital.dashboard.controller;

import com.hospital.dashboard.model.graph.GraphModels.DoctorDiscovery;
import com.hospital.dashboard.model.graph.GraphModels.GraphData;
import com.hospital.dashboard.model.graph.GraphModels.PatientJourneyItem;
import com.hospital.dashboard.model.graph.GraphModels.Referral;
import com.hospital.dashboard.model.graph.GraphModels.ReferralPath;
import com.hospital.dashboard.service.GraphService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only API over the separate Neo4j graph database. */
@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final GraphService service;

    public GraphController(GraphService service) {
        this.service = service;
    }

    @GetMapping("/doctors")
    public List<DoctorDiscovery> discoverDoctors(
            @RequestParam String specialization,
            @RequestParam(required = false) Long excludeDoctorId) {
        return service.discoverDoctors(specialization, excludeDoctorId);
    }

    @GetMapping("/patients/{patientId}/journey")
    public List<PatientJourneyItem> patientJourney(@PathVariable Long patientId) {
        return service.patientJourney(patientId);
    }

    @GetMapping("/referrals/{doctorId}")
    public List<Referral> outgoingReferrals(@PathVariable Long doctorId) {
        return service.outgoingReferrals(doctorId);
    }

    @GetMapping("/referrals/path")
    public List<ReferralPath> referralPaths(
            @RequestParam Long fromDoctorId,
            @RequestParam Long toDoctorId,
            @RequestParam(defaultValue = "3") Integer maxDepth) {
        return service.referralPaths(fromDoctorId, toDoctorId, maxDepth);
    }

    @GetMapping("/visualization")
    public GraphData visualization(
            @RequestParam(required = false) Long patientId,
            @RequestParam(required = false) Long doctorId,
            @RequestParam(defaultValue = "24") Integer maxNodes) {
        return service.visualization(patientId, doctorId, maxNodes);
    }
}
