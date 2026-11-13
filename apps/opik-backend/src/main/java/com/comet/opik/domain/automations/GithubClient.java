package com.comet.opik.domain.automations;

import com.comet.opik.infrastructure.AutomationEventsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.HashMap;
import java.util.Map;

/** Posts a commit status to GitHub, the one call {@code github.check} makes. */
@Singleton
@Slf4j
public class GithubClient {

    private final @NonNull Client httpClient;
    private final @NonNull String apiBase;

    @Inject
    public GithubClient(@NonNull Client httpClient,
            @NonNull @Config("automationEvents") AutomationEventsConfig config) {
        this.httpClient = httpClient;
        this.apiBase = config.getGithubApiUrl();
    }

    @Builder(toBuilder = true)
    public record CommitStatus(@NonNull String repository, @NonNull String sha, @NonNull String state,
            @NonNull String context, @NonNull String description, String targetUrl) {
    }

    public Mono<Void> postCommitStatus(@NonNull String token, @NonNull CommitStatus status) {
        return Mono.fromRunnable(() -> {
            Map<String, Object> body = new HashMap<>();
            body.put("state", status.state());
            body.put("context", status.context());
            body.put("description", status.description());
            if (status.targetUrl() != null) {
                body.put("target_url", status.targetUrl());
            }
            try (var response = httpClient
                    .target("%s/repos/%s/statuses/%s".formatted(apiBase, status.repository(), status.sha()))
                    .request(MediaType.APPLICATION_JSON_TYPE)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .post(Entity.json(body))) {
                if (response.getStatus() >= 300) {
                    throw new IllegalStateException("GitHub answered %d to the commit status for %s@%s"
                            .formatted(response.getStatus(), status.repository(), status.sha()));
                }
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
