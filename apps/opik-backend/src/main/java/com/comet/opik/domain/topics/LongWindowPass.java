package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.lock.LockService.Lock;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * When the daily long-window pass is due: once per {@code longWindowInterval}, cluster-wide, a tick reads the long
 * window in place of the short one, for rows the short window missed. A pass that could not read everything runs again
 * after one normal tick, until one drains it.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class LongWindowPass {

    /**
     * Present while the pass is not due. A tick that finds it absent sets it for {@link #hold()} and runs the pass;
     * {@link #finish} sets when it is due next. A leader that dies mid-pass leaves it to expire.
     */
    static final Lock LOCK = new Lock("topics:vectors:long_window");

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull LockService lockService;

    /** True when the pass is due; it is then held by this tick. Redis down: not due. */
    Mono<Boolean> startIfDue() {
        return lockService.lockUsingToken(LOCK, hold())
                .onErrorResume(exception -> {
                    log.warn("Topic vectors long-window lock unavailable, running the short window", exception);
                    return Mono.just(false);
                });
    }

    /**
     * Drained (the tick read every workspace, each under the cap, and left nothing for want of time, a read, an embed
     * call or an insert): the pass is next due {@code longWindowInterval} from now.
     *
     * <p>Not drained: it is due again 1.5 tick intervals after this tick's {@code start}, counted from the start so the
     * pace does not depend on how long the tick ran. The next tick reads the short window and the one after runs the
     * pass, which keeps the short window served when the pass can't drain, for example when one workspace's long read
     * fails every time. A tick that ends past the 1.5 intervals leaves the pass due at once.
     */
    Mono<Void> finish(@NonNull Instant start, boolean drained) {
        return Mono.defer(() -> {
            var vectors = config.getVectors();
            var notDueFor = drained
                    ? vectors.getLongWindowInterval().toJavaDuration()
                    : Duration.between(Clock.systemUTC().instant(),
                            start.plus(vectors.getTickInterval().toJavaDuration().multipliedBy(3).dividedBy(2)));
            if (!drained) {
                log.info("Topic vectors long-window pass not drained, running it again after one normal tick");
            }
            var finish = lockService.unlockUsingToken(LOCK);
            // Redis takes a TTL in whole milliseconds, above zero.
            return notDueFor.toMillis() > 0 ? finish.then(lockService.lockUsingToken(LOCK, notDueFor)).then() : finish;
        }).onErrorResume(exception -> {
            log.warn("Topic vectors long-window lock update failed, drained '{}'", drained, exception);
            return Mono.empty();
        });
    }

    /**
     * Longer than any tick: the budget is under one interval, and every read and embed call ends within the budget. A
     * pass that dies with its leader is due again within two intervals.
     */
    private Duration hold() {
        return config.getVectors().getTickInterval().toJavaDuration().multipliedBy(2);
    }
}
