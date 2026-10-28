package com.comet.opik.infrastructure.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BodyDeadlineTest {

    @Test
    @DisplayName("a body whose close blocks doesn't delay another body's deadline")
    void aBlockingCloseDoesNotDelayOtherDeadlines() throws Exception {
        var release = new CountDownLatch(1);
        var blockingCloseStarted = new CountDownLatch(1);
        Closeable blockingBody = () -> {
            blockingCloseStarted.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
        var otherClosed = new CountDownLatch(1);
        Closeable otherBody = otherClosed::countDown;

        try (var blocking = BodyDeadline.closing(blockingBody, Duration.ofMillis(50));
                var other = BodyDeadline.closing(otherBody, Duration.ofMillis(200))) {
            assertThat(blockingCloseStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // the first close is still blocked while the second deadline passes
            assertThat(otherClosed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(other.expired()).isTrue();
            assertThat(blocking.expired()).isTrue();
        } finally {
            release.countDown();
        }
    }
}
