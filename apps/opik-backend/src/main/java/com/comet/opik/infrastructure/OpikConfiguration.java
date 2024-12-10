package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.jobs.JobConfiguration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.ToString;

@Getter
public class OpikConfiguration extends JobConfiguration {

    @Valid
    @NotNull @JsonProperty
    private DataSourceFactory database = new DataSourceFactory();

    @Valid
    @NotNull @JsonProperty
    private DataSourceFactory databaseAnalyticsMigrations = new DataSourceFactory();

    @Valid
    @NotNull @JsonProperty
    private DatabaseAnalyticsFactory databaseAnalytics = new DatabaseAnalyticsFactory();

    @Valid
    @NotNull @JsonProperty
    private AuthenticationConfig authentication = new AuthenticationConfig();

    @Valid
    @NotNull @JsonProperty
    private RedisConfig redis = new RedisConfig();

    @Valid
    @NotNull @JsonProperty
    private DistributedLockConfig distributedLock = new DistributedLockConfig();

    @Valid
    @NotNull @JsonProperty
    private RateLimitConfig rateLimit = new RateLimitConfig();

    @Valid
    @NotNull @JsonProperty
    private MetadataConfig metadata = new MetadataConfig();

    @Valid
    @NotNull @JsonProperty
    private UsageReportConfig usageReport = new UsageReportConfig();

    @Valid
    @NotNull @JsonProperty
    private CorsConfig cors = new CorsConfig();

    @Valid
    @NotNull @JsonProperty
    private BatchOperationsConfig batchOperations = new BatchOperationsConfig();

    @Valid
    @NotNull @JsonProperty
    @ToString.Exclude
    private EncryptionConfig encryption = new EncryptionConfig();
}
