package com.gendaz.leads.service;

import com.gendaz.leads.entity.OsmSyncRun;
import com.gendaz.leads.service.provider.GeoScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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
            throw new IllegalStateException("OSM_SYNC_GITHUB_TOKEN não configurado");
        }

        Map<String, Object> inputs = Map.of(
                "sync_run_id", syncRun.getId().toString(),
                "region_id", syncRun.getRegion().getId().toString(),
                "city", scope.city(),
                "state", scope.state(),
                "country_code", scope.countryCode(),
                "osm_type", scope.osmType(),
                "osm_id", String.valueOf(scope.osmId()),
                "geofabrik_region", geofabrikRegion
        );

        Map<String, Object> body = Map.of(
                "ref", ref,
                "inputs", inputs
        );

        String url = "/repos/" + repository + "/actions/workflows/" + workflow + "/dispatches";

        log.info("[osm-catalog] sync_dispatching syncRunId={} url={}", syncRun.getId(), url);

        try {
            String response = restClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

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