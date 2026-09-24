package com.gendaz.leads.service;

import com.gendaz.leads.entity.OsmSyncRun;
import com.gendaz.leads.service.provider.GeoScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.Map;

@Service
public class GitHubOsmSyncDispatcher {

    private static final Logger log = LoggerFactory.getLogger(GitHubOsmSyncDispatcher.class);

    private final RestClient restClient;
    private final String repository;
    private final String workflow;
    private final String ref;
    private final String token;

    public GitHubOsmSyncDispatcher(
            RestClient.Builder builder,
            @Value("${app.osm-catalog.github.repository:DevViniciusAlves/gendaz-leads}") String repository,
            @Value("${app.osm-catalog.github.workflow:osm-catalog-sync.yml}") String workflow,
            @Value("${app.osm-catalog.github.ref:main}") String ref,
            @Value("${app.osm-catalog.github.token:}") String token
    ) {
        this.restClient = builder
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader(HttpHeaders.USER_AGENT, "GendazLeads/1.0")
                .build();
        this.repository = repository;
        this.workflow = workflow;
        this.ref = ref;
        this.token = token;
    }

    public void dispatch(OsmSyncRun syncRun, GeoScope scope, String geofabrikRegion) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("OSM_SYNC_GITHUB_NOT_CONFIGURED");
        }

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("sync_run_id", syncRun.getId().toString());
        inputs.put("region_id", syncRun.getRegion().getId().toString());
        inputs.put("city", scope.city());
        inputs.put("state", scope.state());
        inputs.put("country_code", scope.countryCode());
        inputs.put("osm_type", scope.osmType());
        inputs.put("osm_id", String.valueOf(scope.osmId()));
        inputs.put("geofabrik_region", geofabrikRegion);
        inputs.put("requested_niche", syncRun.getRequestedNiche());
        inputs.put("canonical_niche", syncRun.getCanonicalNiche());
        inputs.put("target_valid", syncRun.getTargetValid().toString());
        inputs.put("niche_strategy_json", syncRun.getNicheStrategyJson());

        Map<String, Object> body = new HashMap<>();
        body.put("ref", ref);
        body.put("inputs", inputs);

        String url = "/repos/" + repository + "/actions/workflows/" + workflow + "/dispatches";

        log.info("[osm-catalog] sync_dispatching syncRunId={} url={}", syncRun.getId(), url);

        try {
            restClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        String errorBody = new String(res.getBody().readAllBytes());
                        throw new RestClientException("GitHub API error: " + res.getStatusCode() + " - " + errorBody);
                    })
                    .toBodilessEntity();

            log.info("[osm-catalog] sync_dispatched syncRunId={}", syncRun.getId());
        } catch (RestClientException e) {
            log.error("[osm-catalog] sync_dispatch_exception syncRunId={} error={}", syncRun.getId(), sanitize(e.getMessage()));
            throw e;
        }
    }

    private String sanitize(String msg) {
        if (msg == null) return "";
        return msg.replaceAll("(?i)(password|token|secret|url|database_url|key)=[^\\s&]+", "$1=***");
    }
}