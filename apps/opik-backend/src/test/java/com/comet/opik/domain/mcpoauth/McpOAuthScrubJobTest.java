package com.comet.opik.domain.mcpoauth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.comet.opik.infrastructure.OpikConfiguration;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpOAuthScrubJob")
class McpOAuthScrubJobTest {

    private static final Duration ROTATION_GRACE = Duration.ofMinutes(5);

    @Mock
    private TransactionTemplate template;
    @Mock
    private Handle handle;
    @Mock
    private McpOAuthCodeDAO codeDAO;
    @Mock
    private McpOAuthTokenDAO tokenDAO;

    private McpOAuthScrubJob job;
    private Logger jobLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        var config = new OpikConfiguration();
        config.getMcpOAuth().setRefreshRotationGrace(ROTATION_GRACE);

        job = new McpOAuthScrubJob(template, config);

        lenient().when(template.inTransaction(any(), any())).thenAnswer(invocation -> {
            TxAction<?> callback = invocation.getArgument(1);
            return callback.execute(handle);
        });
        lenient().when(handle.attach(McpOAuthCodeDAO.class)).thenReturn(codeDAO);
        lenient().when(handle.attach(McpOAuthTokenDAO.class)).thenReturn(tokenDAO);

        logAppender = new ListAppender<>();
        logAppender.start();
        jobLogger = (Logger) LoggerFactory.getLogger(McpOAuthScrubJob.class);
        jobLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        jobLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("scrubs expired codes and trails the token threshold behind now by the rotation grace")
    void appliesRotationGraceToTokenThreshold() {
        var codeNow = ArgumentCaptor.forClass(Instant.class);
        var tokenThreshold = ArgumentCaptor.forClass(Instant.class);

        job.doJob(null);

        verify(codeDAO).deleteExpired(codeNow.capture());
        verify(tokenDAO).deleteExpiredAndRevoked(tokenThreshold.capture());

        // Revoked tokens must remain queryable for the grace window, so their cutoff trails "now" by exactly the grace.
        assertThat(tokenThreshold.getValue()).isEqualTo(codeNow.getValue().minus(ROTATION_GRACE));
    }

    @ParameterizedTest(name = "failure in {0} transaction is swallowed")
    @ValueSource(strings = {"codes", "tokens"})
    @DisplayName("a DAO failure in either transaction is swallowed so a transient DB error never kills the scheduler")
    void swallowsRuntimeExceptionFromEitherTransaction(String failing) {
        if ("codes".equals(failing)) {
            when(codeDAO.deleteExpired(any())).thenThrow(new RuntimeException("db down"));
        } else {
            when(tokenDAO.deleteExpiredAndRevoked(any())).thenThrow(new RuntimeException("db down"));
        }

        assertThatCode(() -> job.doJob(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("logs the removed counts when anything was scrubbed")
    void logsSummaryWhenSomethingRemoved() {
        when(codeDAO.deleteExpired(any())).thenReturn(3);
        when(tokenDAO.deleteExpiredAndRevoked(any())).thenReturn(5);

        job.doJob(null);

        assertThat(logAppender.list)
                .filteredOn(event -> event.getLevel() == Level.INFO)
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .isEqualTo("MCP OAuth scrub: removed '3' expired codes, '5' expired/revoked tokens"));
    }

    @Test
    @DisplayName("stays silent when nothing was scrubbed")
    void doesNotLogWhenNothingRemoved() {
        job.doJob(null);

        assertThat(logAppender.list).noneMatch(event -> event.getLevel() == Level.INFO);
    }
}
