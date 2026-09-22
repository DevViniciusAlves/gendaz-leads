package com.gendaz.leads.controller;

import com.gendaz.leads.dto.osm.OsmCatalogRegionResponse;
import com.gendaz.leads.dto.osm.OsmSyncRequest;
import com.gendaz.leads.dto.osm.OsmSyncResponse;
import com.gendaz.leads.dto.osm.OsmSyncRunResponse;
import com.gendaz.leads.entity.OsmSyncRun;
import com.gendaz.leads.entity.User;
import com.gendaz.leads.repository.UserRepository;
import com.gendaz.leads.security.SecurityService;
import com.gendaz.leads.service.OsmCatalogSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/osm-catalog")
public class OsmCatalogController {

    private final OsmCatalogSyncService syncService;
    private final SecurityService securityService;
    private final UserRepository userRepository;

    public OsmCatalogController(OsmCatalogSyncService syncService, SecurityService securityService, UserRepository userRepository) {
        this.syncService = syncService;
        this.securityService = securityService;
        this.userRepository = userRepository;
    }

    @GetMapping("/regions")
    public ResponseEntity<List<OsmCatalogRegionResponse>> listRegions() {
        List<OsmCatalogRegionResponse> regions = syncService.listRegions().stream()
                .map(OsmCatalogRegionResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(regions);
    }

    @PostMapping("/sync")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<OsmSyncResponse> requestSync(@Valid @RequestBody OsmSyncRequest request) {
        String email = securityService.currentEmail();
        User currentUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new com.gendaz.leads.exception.ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Não autenticado"));
        OsmSyncRun syncRun = syncService.requestSync(request.city(), request.country(), currentUser);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(OsmSyncResponse.from(syncRun));
    }

    @GetMapping("/sync/{id}")
    public ResponseEntity<OsmSyncRunResponse> getSyncRun(@PathVariable Long id) {
        return syncService.getSyncRun(id)
                .map(run -> ResponseEntity.ok(OsmSyncRunResponse.from(run)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/regions/{regionId}/sync-runs")
    public ResponseEntity<List<OsmSyncRunResponse>> getSyncRunsForRegion(@PathVariable Long regionId) {
        List<OsmSyncRunResponse> runs = syncService.getSyncRunsForRegion(regionId).stream()
                .map(OsmSyncRunResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(runs);
    }
}