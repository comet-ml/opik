package com.comet.opik.domain.mcpoauth;

import com.comet.opik.infrastructure.OpikConfiguration;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    @Test
    @DisplayName("a DAO failure is swallowed so a transient DB error never kills the scheduler")
    void swallowsRuntimeException() {
        when(tokenDAO.deleteExpiredAndRevoked(any())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> job.doJob(null)).doesNotThrowAnyException();
    }
}
