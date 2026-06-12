package com.comet.opik.domain.mcpoauth;

import com.comet.opik.infrastructure.OpikConfiguration;
import io.dropwizard.jobs.Job;
import io.dropwizard.jobs.annotations.On;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@Singleton
@Slf4j
@DisallowConcurrentExecution
@On(value = "0 0/5 * * * ?", timeZone = "UTC") // every 5 minutes
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class McpOAuthScrubJob extends Job {

    private final @NonNull TransactionTemplate template;
    private final @NonNull OpikConfiguration opikConfig;

    /**
     * Revoked tokens within the rotation grace window must remain queryable, so a duplicate refresh
     * request (RFC 6749 §6 token rotation grace) returns invalid_grant instead of triggering reuse detection.
     */
    @Override
    public void doJob(JobExecutionContext context) {
        Instant now = Instant.now();
        Instant tokenThreshold = now.minus(opikConfig.getMcpOAuth().getRefreshRotationGrace());

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
        log.info("MCP OAuth scrub: removed '{}' expired codes, '{}' expired/revoked tokens", codes, tokens);
    }
}
