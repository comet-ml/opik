package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AutomationRuleEvaluatorType;
import com.comet.opik.api.events.ScoringMessage;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.OnlineScoringConfig.StreamConfiguration;
import io.dropwizard.lifecycle.Managed;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.Codec;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * This service listens a Redis stream for Traces to be scored in a LLM provider. It will prepare the LLM request
 * by rendering message templates using values from the Trace and prepare the schema for the return (structured output).
 */
@EagerSingleton
@Slf4j
public abstract class OnlineScoringBaseScorer<M extends ScoringMessage> implements Managed {

    private final OnlineScoringConfig config;
    private final String consumerId;
    private final StreamReadGroupArgs redisReadConfig;
    private final RedissonReactiveClient redisson;
    private final AutomationRuleEvaluatorType type;

    private RStreamReactive<String, M> stream;
    private Disposable streamSubscription; // Store the subscription reference

    @Inject
    public OnlineScoringBaseScorer(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull AutomationRuleEvaluatorType type) {
        this.config = config;
        this.redisReadConfig = StreamReadGroupArgs.neverDelivered().count(config.getConsumerBatchSize());
        this.redisson = redisson;
        this.consumerId = "consumer-" + config.getConsumerGroupName() + "-" + UUID.randomUUID();
        this.type = type;
        // as we are a LLM consumer, lets check only LLM stream
        initStream(config, redisson);
    }
    @Override
    public void start() {
        if (stream != null) {
            log.warn("OnlineScoringLlmAsJudgeScorer already started. Ignoring start request.");
            return;
        }

        // as we are a LLM consumer, lets check only LLM stream
        stream = initStream(config, redisson);
        log.info("OnlineScoringLlmAsJudgeScorer started.");
    }

    @Override
    public void stop() {
        log.info("Shutting down OnlineScoringLlmAsJudgeScorer and closing stream.");
        if (stream != null) {
            if (streamSubscription != null && !streamSubscription.isDisposed()) {
                log.info("Waiting for last messages to be processed before shutdown...");

                try {
                    // Read any remaining messages before stopping
                    stream.readGroup(config.getConsumerGroupName(), consumerId, redisReadConfig)
                            .flatMap(messages -> {
                                if (!messages.isEmpty()) {
                                    log.info("Processing last {} messages before shutdown.", messages.size());

                                    return Flux.fromIterable(messages.entrySet())
                                            .publishOn(Schedulers.boundedElastic())
                                            .doOnNext(entry -> processReceivedMessages(stream, entry))
                                            .collectList()
                                            .then(Mono.fromRunnable(() -> streamSubscription.dispose()));
                                }

                                return Mono.fromRunnable(() -> streamSubscription.dispose());
                            })
                            .block(Duration.ofSeconds(2));
                } catch (Exception e) {
                    log.error("Error processing last messages before shutdown: {}", e.getMessage(), e);
                }
            } else {
                log.info("No active subscription, deleting Redis stream.");
            }

            stream.delete().doOnTerminate(() -> log.info("Redis Stream deleted")).subscribe();
        }
    }

    private RStreamReactive<String, M> initStream(OnlineScoringConfig config,
            RedissonReactiveClient redisson) {
        Optional<StreamConfiguration> configuration = config.getStreams().stream()
                .filter(this::isLlmAsJudge)
                .findFirst();

        if (configuration.isEmpty()) {
            this.logIfEmpty();
            return null;
        }

        return setupListener(redisson, configuration.get());
    }

    private void logIfEmpty() {
        log.warn("No '{}' redis stream config found. Online Scoring consumer won't start.", type.name());
    }

    private RStreamReactive<String, M> setupListener(RedissonReactiveClient redisson,
            StreamConfiguration llmConfig) {
        var scoringCodecs = OnlineScoringCodecs.fromString(llmConfig.getCodec());
        String streamName = llmConfig.getStreamName();
        Codec codec = scoringCodecs.getCodec();

        RStreamReactive<String, M> stream = redisson.getStream(streamName, codec);

        log.info("OnlineScoring Scorer listening for events on stream {}", streamName);

        enforceConsumerGroup(stream);
        setupStreamListener(stream);

        return stream;
    }

    private boolean isLlmAsJudge(StreamConfiguration streamConfiguration) {
        return type.name().equalsIgnoreCase(streamConfiguration.getScorer());
    }

    private void enforceConsumerGroup(RStreamReactive<String, M> stream) {
        // make sure the stream and the consumer group exists
        StreamCreateGroupArgs args = StreamCreateGroupArgs.name(config.getConsumerGroupName()).makeStream();

        stream.createGroup(args)
                .onErrorResume(err -> {
                    if (err.getMessage().contains("BUSYGROUP")) {
                        log.info("Consumer group already exists: {}", config.getConsumerGroupName());
                        return Mono.empty();
                    }
                    return Mono.error(err);
                })
                .subscribe();
    }

    private void setupStreamListener(RStreamReactive<String, M> stream) {
        // Listen for messages
        this.streamSubscription = Flux.interval(config.getPoolingInterval().toJavaDuration())
                .flatMap(i -> stream.readGroup(config.getConsumerGroupName(), consumerId, redisReadConfig))
                .flatMap(messages -> Flux.fromIterable(messages.entrySet()))
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(entry -> processReceivedMessages(stream, entry))
                .subscribe();
    }

    private void processReceivedMessages(RStreamReactive<String, M> stream,
            Map.Entry<StreamMessageId, Map<String, M>> entry) {
        var messageId = entry.getKey();

        try {
            var message = entry.getValue().get(OnlineScoringConfig.PAYLOAD_FIELD);

            log.info("Message received [{}]: traceId '{}' from user '{}'",
                    messageId, message.trace().id(), message.userName());

            score(message);

            // remove messages from Redis pending list
            stream.ack(config.getConsumerGroupName(), messageId).subscribe();
            stream.remove(messageId).subscribe();
        } catch (Exception e) {
            log.error("Error processing message [{}]: {}", messageId, e.getMessage(), e);
        }
    }
    /**
     * Use AI Proxy to score the trace and store it as a FeedbackScore.
     * If the evaluator has multiple score definitions, it calls the LLM once per score definition.
     *
     * @param message a Redis message with Trace to score with an Evaluator code, workspace and username
     */
    protected abstract void score(M message);
}