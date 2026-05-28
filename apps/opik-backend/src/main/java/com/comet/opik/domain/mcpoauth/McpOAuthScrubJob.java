package com.comet.opik.domain.mcpoauth;

import com.comet.opik.infrastructure.McpOAuthConfig;
import io.dropwizard.jobs.Job;
import io.dropwizard.jobs.annotations.On;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@Singleton
@Slf4j
@DisallowConcurrentExecution
@On(value = "${mcpOAuthScrubCron}", timeZone = "UTC")
public class McpOAuthScrubJob extends Job {

    private final TransactionTemplate template;
    private final McpOAuthConfig config;

    @Inject
    public McpOAuthScrubJob(@NonNull TransactionTemplate template,
            @NonNull @Config("mcpOAuth") McpOAuthConfig config) {
        this.template = template;
        this.config = config;
    }

    @Override
    public void doJob(JobExecutionContext context) {
        Instant now = Instant.now();
        // Revoked tokens within the rotation grace window must remain queryable, so a duplicate refresh
        // request (RFC 6749 §6 token rotation grace) returns invalid_grant instead of triggering reuse detection.
        Instant tokenThreshold = now.minus(config.getRefreshRotationGrace());

        int codes;
        int tokens;
        try {
            codes = template.inTransaction(WRITE,
                    handle -> handle.attach(McpOAuthCodeDAO.class).deleteExpired(now));
            tokens = template.inTransaction(WRITE,
                    handle -> handle.attach(McpOAuthTokenDAO.class).deleteExpiredAndRevoked(tokenThreshold));
        } catch (RuntimeException e) {
            log.error("MCP OAuth scrub failed", e);
            return;
        }

        if (codes > 0 || tokens > 0) {
            log.info("MCP OAuth scrub: removed {} expired codes, {} expired/revoked tokens", codes, tokens);
        }
    }
}
