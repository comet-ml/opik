package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.core.Configuration;
import io.dropwizard.db.DataSourceFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class OpikConfiguration extends Configuration {

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
}
