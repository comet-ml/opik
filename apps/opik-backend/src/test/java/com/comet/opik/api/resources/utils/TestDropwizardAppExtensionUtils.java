package com.comet.opik.api.resources.utils;

import com.comet.opik.OpikApplication;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.events.EventModule;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.google.common.eventbus.EventBus;
import lombok.Builder;
import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.CollectionUtils;
import ru.vyarus.dropwizard.guice.hook.GuiceyConfigurationHook;
import ru.vyarus.dropwizard.guice.module.installer.bundle.GuiceyBundle;
import ru.vyarus.dropwizard.guice.module.installer.bundle.GuiceyEnvironment;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.comet.opik.infrastructure.RateLimitConfig.LimitConfig;

@UtilityClass
public class TestDropwizardAppExtensionUtils {

    public record CustomConfig(String key, String value) {
    }

    @Builder
    public record AppContextConfig(
            String jdbcUrl,
            DatabaseAnalyticsFactory databaseAnalyticsFactory,
            WireMockRuntimeInfo runtimeInfo,
            String redisUrl,
            Integer cacheTtlInSeconds,
            boolean rateLimitEnabled,
            Long limit,
            Long limitDurationInSeconds,
            Map<String, LimitConfig> customLimits,
            List<Object> customBeans,
            String jdbcUserName,
            String jdbcDriverClass,
            String awsJdbcDriverPlugins,
            boolean usageReportEnabled,
            String usageReportUrl,
            String metadataVersion,
            EventBus mockEventBus,
            boolean corsEnabled,
            List<CustomConfig> customConfigs) {
    }

    public static TestDropwizardAppExtension newTestDropwizardAppExtension(String jdbcUrl,
            WireMockRuntimeInfo runtimeInfo) {
        return newTestDropwizardAppExtension(jdbcUrl, null, runtimeInfo);
    }

    public static TestDropwizardAppExtension newTestDropwizardAppExtension(String jdbcUrl,
            DatabaseAnalyticsFactory databaseAnalyticsFactory) {
        return newTestDropwizardAppExtension(jdbcUrl, databaseAnalyticsFactory, null);
    }

    public static TestDropwizardAppExtension newTestDropwizardAppExtension(
            String jdbcUrl, DatabaseAnalyticsFactory databaseAnalyticsFactory, WireMockRuntimeInfo runtimeInfo) {
        return newTestDropwizardAppExtension(jdbcUrl, databaseAnalyticsFactory, runtimeInfo, null);
    }

    public static TestDropwizardAppExtension newTestDropwizardAppExtension(
            String jdbcUrl,
            DatabaseAnalyticsFactory databaseAnalyticsFactory,
            WireMockRuntimeInfo runtimeInfo,
            String redisUrl) {
        return newTestDropwizardAppExtension(jdbcUrl, databaseAnalyticsFactory, runtimeInfo, redisUrl, null);
    }

    public static TestDropwizardAppExtension newTestDropwizardAppExtension(
            String jdbcUrl,
            DatabaseAnalyticsFactory databaseAnalyticsFactory,
            WireMockRuntimeInfo runtimeInfo,
            String redisUrl,
            Integer cacheTtlInSeconds) {
        return newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(jdbcUrl)
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(runtimeInfo)
                        .redisUrl(redisUrl)
                        .cacheTtlInSeconds(cacheTtlInSeconds)
                        .build());
    }

    public static TestDropwizardAppExtension newTestDropwizardAppExtension(AppContextConfig appContextConfig) {

        var configs = new ArrayList<String>();
        configs.add("database.url: " + appContextConfig.jdbcUrl());

        if (appContextConfig.jdbcUserName() != null) {
            configs.add("database.user: " + appContextConfig.jdbcUserName());
        }

        if (appContextConfig.jdbcDriverClass() != null) {
            configs.add("database.driverClass: " + appContextConfig.jdbcDriverClass());
        }

        if (appContextConfig.awsJdbcDriverPlugins() != null) {
            configs.add("database.properties.wrapperPlugins: " + appContextConfig.awsJdbcDriverPlugins());
        }

        if (appContextConfig.databaseAnalyticsFactory() != null) {
            configs.add("databaseAnalytics.port: " + appContextConfig.databaseAnalyticsFactory().getPort());
            configs.add("databaseAnalytics.username: " + appContextConfig.databaseAnalyticsFactory().getUsername());
            configs.add("databaseAnalytics.password: " + appContextConfig.databaseAnalyticsFactory().getPassword());
        }

        if (appContextConfig.runtimeInfo() != null) {
            configs.add("authentication.enabled: true");
            configs.add("authentication.sdk.url: "
                    + "%s/opik/auth".formatted(appContextConfig.runtimeInfo().getHttpsBaseUrl()));
            configs.add("authentication.ui.url: "
                    + "%s/opik/auth-session".formatted(appContextConfig.runtimeInfo().getHttpsBaseUrl()));

            if (appContextConfig.cacheTtlInSeconds() != null) {
                configs.add("authentication.apiKeyResolutionCacheTTLInSec: " + appContextConfig.cacheTtlInSeconds());
            }
        }

        GuiceyConfigurationHook hook = injector -> {
            injector.modulesOverride(TestHttpClientUtils.testAuthModule());

            if (appContextConfig.mockEventBus() != null) {
                injector.modulesOverride(new EventModule() {
                    @Override
                    public EventBus getEventBus() {
                        return appContextConfig.mockEventBus();
                    }
                });
            }

            injector.bundles(new GuiceyBundle() {

                @Override
                public void run(GuiceyEnvironment environment) {

                    if (CollectionUtils.isNotEmpty(appContextConfig.customBeans())) {
                        appContextConfig.customBeans()
                                .forEach(environment::register);
                    }
                }
            });

        };

        if (appContextConfig.redisUrl() != null) {
            configs.add("redis.singleNodeUrl: %s".formatted(appContextConfig.redisUrl()));
            configs.add("redis.sentinelMode: false");
            configs.add("redis.lockTimeout: 500");
        }

        if (appContextConfig.rateLimitEnabled()) {
            configs.add("rateLimit.enabled: true");
            configs.add("rateLimit.generalLimit.limit: %d".formatted(appContextConfig.limit()));
            configs.add("rateLimit.generalLimit.durationInSeconds: %d"
                    .formatted(appContextConfig.limitDurationInSeconds()));

            if (appContextConfig.customLimits() != null) {
                appContextConfig.customLimits()
                        .forEach((bucket, limitConfig) -> {
                            configs.add("rateLimit.customLimits.%s.limit: %d".formatted(bucket, limitConfig.limit()));
                            configs.add("rateLimit.customLimits.%s.durationInSeconds: %d".formatted(bucket,
                                    limitConfig.durationInSeconds()));
                        });
            }
        }

        if (appContextConfig.metadataVersion() != null) {
            configs.add("metadata.version: %s".formatted(appContextConfig.metadataVersion()));
        }

        if (appContextConfig.usageReportEnabled()) {
            configs.add("usageReport.enabled: %s".formatted(true));

            if (appContextConfig.usageReportUrl() != null) {
                configs.add("usageReport.url: %s".formatted(appContextConfig.usageReportUrl()));
            }
        }

        if (appContextConfig.corsEnabled) {
            configs.add("cors.enabled: true");
        }

        if (CollectionUtils.isNotEmpty(appContextConfig.customConfigs())) {
            appContextConfig
                    .customConfigs()
                    .stream()
                    .filter(customConfig -> configs.stream().noneMatch(s -> s.contains(customConfig.key())))
                    .forEach(customConfig -> {
                        configs.add("%s: %s".formatted(customConfig.key(), customConfig.value()));
                    });
        }

        return TestDropwizardAppExtension.forApp(OpikApplication.class)
                .config("src/test/resources/config-test.yml")
                .configOverrides(configs.toArray(new String[0]))
                .randomPorts()
                .hooks(hook)
                .create();
    }

}
