package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.client.JerseyClientConfiguration;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.jobs.JobConfiguration;
import io.dropwizard.util.Duration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;

import java.util.stream.Stream;

@Getter
@Setter
public class OpikConfiguration extends JobConfiguration {

    @Valid @NotNull @JsonProperty
    private DataSourceFactory database = new DataSourceFactory();

    @Valid @NotNull @JsonProperty
    private DataSourceFactory databaseAnalyticsMigrations = new DataSourceFactory();

    @Valid @NotNull @JsonProperty
    private DatabaseAnalyticsFactory databaseAnalytics = new DatabaseAnalyticsFactory();

    @Valid @NotNull @JsonProperty
    private DatabaseAnalyticsDataModelConfig databaseAnalyticsDataModel = DatabaseAnalyticsDataModelConfig.builder()
            .build();

    @Valid @NotNull @JsonProperty
    private DatabaseAnalyticsReadOnlyFreeFormSqlConfig databaseAnalyticsReadOnlyFreeFormSql = new DatabaseAnalyticsReadOnlyFreeFormSqlConfig();

    @Valid @NotNull @JsonProperty
    private DatabaseAnalyticsReadOnlyFreeFormSqlConfig databaseAnalyticsReadOnlyFreeFormExtendedSql = new DatabaseAnalyticsReadOnlyFreeFormSqlConfig();

    @Valid @NotNull @JsonProperty
    private CustomChartsConfig customCharts = new CustomChartsConfig();

    @Valid @NotNull @JsonProperty
    private FreeFormSqlPostRunCheckConfig freeFormSqlPostRunCheck = new FreeFormSqlPostRunCheckConfig();

    @Valid @NotNull @JsonProperty
    private UuidValidationConfig uuidValidation = UuidValidationConfig.builder().build();

    @Valid @NotNull @JsonProperty
    private AuthenticationConfig authentication = new AuthenticationConfig();

    @Valid @NotNull @JsonProperty
    private SharedHttpClientHealthCheckConfig sharedHttpClientHealthCheck = new SharedHttpClientHealthCheckConfig();

    @Valid @NotNull @JsonProperty
    private McpOAuthConfig mcpOAuth = new McpOAuthConfig();

    @Valid @NotNull @JsonProperty
    private CipxTokenValidationConfig cipxTokenValidation = new CipxTokenValidationConfig();

    @Valid @NotNull @JsonProperty
    private RedisConfig redis = new RedisConfig();

    @Valid @NotNull @JsonProperty
    private DistributedLockConfig distributedLock = new DistributedLockConfig();

    @Valid @NotNull @JsonProperty
    private RateLimitConfig rateLimit = new RateLimitConfig();

    @Valid @NotNull @JsonProperty
    private UsageLimitConfig usageLimit = new UsageLimitConfig();

    @Valid @NotNull @JsonProperty
    private MetadataConfig metadata = new MetadataConfig();

    @Valid @NotNull @JsonProperty
    private UsageReportConfig usageReport = new UsageReportConfig();

    @Valid @NotNull @JsonProperty
    private AnalyticsConfig analytics = new AnalyticsConfig();

    @Valid @NotNull @JsonProperty
    private CorsConfig cors = new CorsConfig();

    @Valid @NotNull @JsonProperty
    private BatchOperationsConfig batchOperations = new BatchOperationsConfig();

    @Valid @NotNull @JsonProperty
    @ToString.Exclude
    private EncryptionConfig encryption = new EncryptionConfig();

    @Valid @NotNull @JsonProperty
    private LlmProviderClientConfig llmProviderClient = new LlmProviderClientConfig();

    @Valid @NotNull @JsonProperty
    private LlmProviderTokenAuthConfig llmProviderTokenAuth = new LlmProviderTokenAuthConfig();

    @Valid @NotNull @JsonProperty
    private CacheConfiguration cacheManager = new CacheConfiguration();

    @Valid @NotNull @JsonProperty
    private OnlineScoringConfig onlineScoring = new OnlineScoringConfig();

    @Valid @NotNull @JsonProperty
    private DatasetExportConfig datasetExport = new DatasetExportConfig();

    @Valid @NotNull @JsonProperty
    private AgentInsightsReportConfig agentInsightsReport = new AgentInsightsReportConfig();

    @Valid @NotNull @JsonProperty
    private AnnotationQueueRoutingConfig annotationQueueRouting = new AnnotationQueueRoutingConfig();

    @Valid @NotNull @JsonProperty
    private ClickHouseLogAppenderConfig clickHouseLogAppender = new ClickHouseLogAppenderConfig();

    @Valid @NotNull @JsonProperty
    private OpenTelemetryConfig openTelemetry = new OpenTelemetryConfig();

    @Valid @NotNull @JsonProperty
    private WorkspaceSettings workspaceSettings = WorkspaceSettings.builder().build();

    @Valid @NotNull @JsonProperty
    private S3Config s3Config = new S3Config();

    @Valid @NotNull @JsonProperty
    private GuardrailsConfig guardrails = new GuardrailsConfig();

    @Valid @NotNull @JsonProperty
    private PythonEvaluatorConfig pythonEvaluator = new PythonEvaluatorConfig();

    @Valid @NotNull @JsonProperty
    private JerseyClientConfiguration jerseyClient = new JerseyClientConfiguration();

    @Valid @NotNull @JsonProperty
    private ServiceTogglesConfig serviceToggles = new ServiceTogglesConfig();

    @Valid @NotNull @JsonProperty
    private TraceThreadConfig traceThreadConfig = new TraceThreadConfig();

    @Valid @NotNull @JsonProperty
    private JobTimeoutConfig jobTimeout = new JobTimeoutConfig();

    @Valid @NotNull @JsonProperty
    private ResponseFormattingConfig responseFormatting = new ResponseFormattingConfig();

    @Valid @NotNull @JsonProperty
    private WebhookConfig webhook = new WebhookConfig();

    @Valid @NotNull @JsonProperty
    private AlertsEventBridgeConfig alertsEventBridge = new AlertsEventBridgeConfig();

    @Valid @JsonProperty
    private QueuesConfig queues = new QueuesConfig();

    @Valid @NotNull @JsonProperty
    private JacksonConfig jacksonConfig = new JacksonConfig();

    @Valid @NotNull @JsonProperty
    private AttachmentsConfig attachmentsConfig = new AttachmentsConfig();

    @Valid @NotNull @JsonProperty
    private FreeModelConfig freeModel = new FreeModelConfig();

    @Valid @NotNull @JsonProperty
    private OptimizationLogsConfig optimizationLogs = new OptimizationLogsConfig();

    @Valid @NotNull @JsonProperty
    private OptimizationStalledReaperConfig optimizationStalledReaper = OptimizationStalledReaperConfig.builder()
            .build();

    @Valid @NotNull @JsonProperty
    private RetentionConfig retention = new RetentionConfig();

    @Valid @NotNull @JsonProperty
    private PartitionMetricsConfig partitionMetrics = new PartitionMetricsConfig();

    @Valid @NotNull @JsonProperty
    private SpanWeeksBackfillConfig spanWeeksBackfill = SpanWeeksBackfillConfig.builder().build();

    @Valid @NotNull @JsonProperty
    private DatasetVersioningMigrationConfig datasetVersioningMigration = new DatasetVersioningMigrationConfig();

    @Valid @NotNull @JsonProperty
    private DatasetVersioningConfig datasetVersioning = DatasetVersioningConfig.builder().build();

    @Valid @NotNull @JsonProperty
    private LocalRunnerConfig localRunner = new LocalRunnerConfig();

    @Valid @NotNull @JsonProperty
    private StreamConsumerReaperConfig streamConsumerReaper = StreamConsumerReaperConfig.builder().build();

    @Valid @NotNull @JsonProperty
    private ExperimentAggregatesConfig experimentAggregates = new ExperimentAggregatesConfig();

    @Valid @NotNull @JsonProperty
    private ExperimentDenormalizationConfig experimentDenormalization = new ExperimentDenormalizationConfig();

    @Valid @NotNull @JsonProperty
    private ProjectLastUpdatedFlushConfig projectLastUpdatedFlush = new ProjectLastUpdatedFlushConfig();

    @Valid @NotNull @JsonProperty
    private LlmModelRegistryConfig llmModelRegistry = new LlmModelRegistryConfig();

    @Valid @NotNull @JsonProperty
    private OllieStateConfig ollieStateConfig = new OllieStateConfig();

    @Valid @NotNull @JsonProperty
    private TestSuiteConfig testSuite = new TestSuiteConfig();

    @Valid @NotNull @JsonProperty
    private ExperimentExecutionConfig experimentExecution = new ExperimentExecutionConfig();

    @Valid @NotNull @JsonProperty
    private AgentConfigConfiguration agentConfig = new AgentConfigConfiguration();

    @Valid @NotNull @JsonProperty
    private EnvironmentConfig environment = new EnvironmentConfig();

    @Valid @NotNull @JsonProperty
    private ReportGenerationConfig reportGeneration = new ReportGenerationConfig();

    @Valid @NotNull @JsonProperty
    private RedactionConfig redaction = new RedactionConfig();

    @Valid @NotNull @JsonProperty
    private AiLabelsConfig aiLabels = new AiLabelsConfig();

    @Valid @NotNull @JsonProperty
    private InferenceConfig inference = new InferenceConfig();

    @Valid @NotNull @JsonProperty
    private TopicsConfig topics = new TopicsConfig();

    @Valid @NotNull @JsonProperty
    private WorkspaceFeaturesConfig workspaceFeatures = new WorkspaceFeaturesConfig();

    @JsonIgnore
    @AssertTrue(message = "inference.url (OPIK_INFERENCE_URL) and inference.credential (OPIK_INFERENCE_CREDENTIAL) must be set when serviceToggles.guardrailsValidationsEnabled (TOGGLE_GUARDRAILS_VALIDATIONS_ENABLED) is true: the backend runs guardrail checks on opik-inference")
    public boolean isGuardrailsInferenceConfigured() {
        return !serviceToggles.isGuardrailsValidationsEnabled()
                || (StringUtils.isNotBlank(inference.getUrl()) && StringUtils.isNotBlank(inference.getCredential()));
    }

    @JsonIgnore
    @AssertTrue(message = "inference.url (OPIK_INFERENCE_URL) and inference.credential (OPIK_INFERENCE_CREDENTIAL) must be set when aiLabels.enabled (AI_LABELS_ENABLED) is true")
    public boolean isAiLabelsInferenceConfigured() {
        return !aiLabels.isEnabled()
                || (StringUtils.isNotBlank(inference.getUrl()) && StringUtils.isNotBlank(inference.getCredential()));
    }

    @Valid @NotNull @JsonProperty
    private AdminServerConfig adminServer;

    @JsonIgnore
    @AssertTrue(message = "alertsEventBridge.eventBus (ALERTS_EVENTBRIDGE_EVENT_BUS) must be set when serviceToggles.eventBridgeAlertsEnabled (ALERTS_EVENTBRIDGE_ENABLED) is true")
    public boolean isAlertsEventBridgeBusConfigured() {
        return !serviceToggles.isEventBridgeAlertsEnabled() || StringUtils.isNotBlank(alertsEventBridge.getEventBus());
    }

    // ai-labels reads traces only in their post-cutover layout; spans it reads in either (it runs before that cutover).
    @JsonIgnore
    @AssertTrue(message = "aiLabels.enabled (AI_LABELS_ENABLED) requires databaseAnalyticsDataModel.traceColumnsNonNullable (ANALYTICS_DB_DATA_MODEL_TRACE_COLUMNS_NON_NULLABLE): ai-labels reads only the cut-over traces table")
    public boolean isAiLabelsDataModelSupported() {
        return !aiLabels.isEnabled() || databaseAnalyticsDataModel.traceColumnsNonNullable();
    }

    // Topics embeds ai-labels' summaries through ai-labels' opik-inference deployment: without labels there is nothing.
    @JsonIgnore
    @AssertTrue(message = "topics.enabled (TOPICS_ENABLED) requires aiLabels.enabled (AI_LABELS_ENABLED) and inference.url (OPIK_INFERENCE_URL)")
    public boolean isTopicsAiLabelsEnabled() {
        return !topics.isEnabled() || (aiLabels.isEnabled() && StringUtils.isNotBlank(inference.getUrl()));
    }

    @JsonIgnore
    @AssertTrue(message = "inference.maxConcurrentDecides (OPIK_INFERENCE_MAX_CONCURRENT_DECIDES) + aiLabels.loadConcurrency (AI_LABELS_LOAD_CONCURRENCY, when ai-labels is on) + topics.vectors.embedConcurrency (TOPICS_VECTORS_EMBED_CONCURRENCY) + inference.maxConcurrentGenerates (OPIK_INFERENCE_MAX_CONCURRENT_GENERATES), the last two when Topics is on, must be below jerseyClient.maxConnectionsPerRoute (JERSEY_CLIENT_MAX_CONNECTIONS_PER_ROUTE) when serviceToggles.guardrailsValidationsEnabled (TOGGLE_GUARDRAILS_VALIDATIONS_ENABLED) is true: guardrail decide calls share that pool with the other calls to opik-inference")
    public boolean isInferenceCallsWithinConnectionPool() {
        if (!serviceToggles.isGuardrailsValidationsEnabled() || inference == null || jerseyClient == null) {
            return true;
        }
        // Only the features that are on call opik-inference. Summed as long so a value near Integer.MAX_VALUE can't
        // overflow past the check.
        long loadConcurrency = aiLabels.isEnabled() ? aiLabels.getLoadConcurrency() : 0;
        long embedConcurrency = topics.isEnabled() && topics.getVectors() != null
                ? topics.getVectors().getEmbedConcurrency()
                : 0;
        long generateConcurrency = topics.isEnabled() ? inference.getMaxConcurrentGenerates() : 0;
        return inference.getMaxConcurrentDecides() + loadConcurrency + embedConcurrency
                + generateConcurrency < jerseyClient.getMaxConnectionsPerRoute();
    }

    @JsonIgnore
    @AssertTrue(message = "topics.vectors.embedBatchSize must not be above inference.limits.maxEmbedInputs") public boolean isTopicsEmbedBatchWithinLimits() {
        return topics.getVectors() == null || inference.getLimits() == null
                || topics.getVectors().getEmbedBatchSize() <= inference.getLimits().getMaxEmbedInputs();
    }

    @JsonIgnore
    @AssertTrue(message = "topics.vectors.embedTimeout must not be above inference.limits.maxEmbedDeadline") public boolean isTopicsEmbedTimeoutWithinLimits() {
        var vectors = topics.getVectors();
        var limits = inference.getLimits();
        return vectors == null || limits == null || notAbove(vectors.getEmbedTimeout(), limits.getMaxEmbedDeadline());
    }

    /** A bound left unset is checked by its own field's validation, not here. */
    private static boolean notAbove(Duration value, Duration limit) {
        return value == null || limit == null || value.toMilliseconds() <= limit.toMilliseconds();
    }

    @JsonIgnore
    @AssertTrue(message = "topics.build.llmDeadline, namingDeadline and verifyDeadline must not be above inference.limits.maxGenerateBatchDeadline") public boolean isTopicsLlmDeadlinesWithinLimits() {
        var build = topics.getBuild();
        var limits = inference.getLimits();
        return build == null || limits == null
                || Stream.of(build.getLlmDeadline(), build.getNamingDeadline(), build.getVerifyDeadline())
                        .allMatch(deadline -> notAbove(deadline, limits.getMaxGenerateBatchDeadline()));
    }
}
