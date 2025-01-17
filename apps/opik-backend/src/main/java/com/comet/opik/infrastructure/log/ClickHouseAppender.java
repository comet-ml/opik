package com.comet.opik.infrastructure.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.comet.opik.utils.TemplateUtils;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Statement;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.utils.TemplateUtils.getQueryItemPlaceHolder;

@RequiredArgsConstructor(access = lombok.AccessLevel.PRIVATE)
@Slf4j
class ClickHouseAppender extends AppenderBase<ILoggingEvent> {

    private static final String INSERT_STATEMENT = """
            INSERT INTO automation_rule_evaluator_logs (timestamp, level, workspace_id, rule_id, message, extra)
            VALUES <items:{item |
                        (
                            parseDateTime64BestEffort(:timestamp<item.index>, 9),
                            :level<item.index>,
                            :workspace_id<item.index>,
                            :rule_id<item.index>,
                            :message<item.index>,
                            mapFromArrays(:extra_keys<item.index>, :extra_values<item.index>)
                        )
                        <if(item.hasNext)>,<endif>
                    }>
            ;
            """;

    private static ClickHouseAppender instance;

    public static synchronized void init(@NonNull ConnectionFactory connectionFactory, int batchSize,
            @NonNull Duration flushIntervalDuration) {

        if (instance == null) {
            setInstance(new ClickHouseAppender(connectionFactory, batchSize, flushIntervalDuration));
            instance.start();
        }
    }

    public static ClickHouseAppender getInstance() {
        if (instance == null) {
            throw new IllegalStateException("ClickHouseAppender is not initialized");
        }
        return instance;
    }

    private static synchronized void setInstance(ClickHouseAppender instance) {
        ClickHouseAppender.instance = instance;
    }

    private final ConnectionFactory connectionFactory;
    private final int batchSize;
    private final Duration flushIntervalDuration;
    private volatile boolean running = true;

    private BlockingQueue<ILoggingEvent> logQueue;
    private ScheduledExecutorService scheduler;

    @Override
    public void start() {
        if (connectionFactory == null) {
            log.error("ClickHouse connection factory is not set");
            return;
        }

        logQueue = new LinkedBlockingQueue<>();
        scheduler = Executors.newSingleThreadScheduledExecutor();

        // Background flush thread
        scheduler.scheduleAtFixedRate(this::flushLogs, flushIntervalDuration.toMillis(),
                flushIntervalDuration.toMillis(), TimeUnit.MILLISECONDS);

        super.start();
    }

    private void flushLogs() {
        if (logQueue.isEmpty()) return;

        List<ILoggingEvent> batch = new ArrayList<>(logQueue.size());
        logQueue.drainTo(batch, logQueue.size());

        if (batch.isEmpty()) return;

        Mono.from(connectionFactory.create())
                .flatMapMany(conn -> {

                    var template = new ST(INSERT_STATEMENT);
                    List<TemplateUtils.QueryItem> queryItems = getQueryItemPlaceHolder(batch.size());

                    template.add("items", queryItems);

                    Statement statement = conn.createStatement(template.render());

                    for (int i = 0; i < batch.size(); i++) {
                        ILoggingEvent event = batch.get(i);

                        String logLevel = event.getLevel().toString();
                        String workspaceId = Optional.ofNullable(event.getMDCPropertyMap().get("workspace_id"))
                                .orElseThrow(() -> failWithMessage("workspace_id is not set"));
                        String traceId = Optional.ofNullable(event.getMDCPropertyMap().get("trace_id"))
                                .orElseThrow(() -> failWithMessage("trace_id is not set"));
                        String ruleId = Optional.ofNullable(event.getMDCPropertyMap().get("rule_id"))
                                .orElseThrow(() -> failWithMessage("rule_id is not set"));

                        statement
                                .bind("timestamp" + i, event.getInstant().toString())
                                .bind("level" + i, logLevel)
                                .bind("workspace_id" + i, workspaceId)
                                .bind("rule_id" + i, ruleId)
                                .bind("message" + i, event.getFormattedMessage())
                                .bind("extra_keys" + i, new String[]{"trace_id"})
                                .bind("extra_values" + i, new String[]{traceId});
                    }

                    return statement.execute();
                })
                .subscribe(
                        noop -> {
                        },
                        e -> log.error("Failed to insert logs", e));
    }

    private IllegalStateException failWithMessage(String message) {
        log.error(message);
        return new IllegalStateException(message);
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!running) return;

        boolean added = logQueue.offer(event);
        if (!added) {
            log.warn("Log queue is full, dropping log: {}", event.getFormattedMessage());
        }

        if (logQueue.size() >= batchSize) {
            scheduler.execute(this::flushLogs);
        }
    }

    @Override
    public void stop() {
        running = false;
        super.stop();
        flushLogs();
        setInstance(null);
        scheduler.shutdown();
    }
}
