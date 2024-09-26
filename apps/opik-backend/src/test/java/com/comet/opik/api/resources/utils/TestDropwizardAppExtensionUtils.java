package com.comet.opik.api.resources.utils;

import com.comet.opik.OpikApplication;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.auth.TestHttpClientUtils;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
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
            String awsJdbcDriverPlugins) {
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

        var list = new ArrayList<String>();
        list.add("database.url: " + appContextConfig.jdbcUrl());

        if (appContextConfig.jdbcUserName() != null) {
            list.add("database.user: " + appContextConfig.jdbcUserName());
        }

        if (appContextConfig.jdbcDriverClass() != null) {
            list.add("database.driverClass: " + appContextConfig.jdbcDriverClass());
        }

        if (appContextConfig.awsJdbcDriverPlugins() != null) {
            list.add("database.properties.wrapperPlugins: " + appContextConfig.awsJdbcDriverPlugins());
        }

        if (appContextConfig.databaseAnalyticsFactory() != null) {
            list.add("databaseAnalytics.port: " + appContextConfig.databaseAnalyticsFactory().getPort());
            list.add("databaseAnalytics.username: " + appContextConfig.databaseAnalyticsFactory().getUsername());
            list.add("databaseAnalytics.password: " + appContextConfig.databaseAnalyticsFactory().getPassword());
        }

        if (appContextConfig.runtimeInfo() != null) {
            list.add("authentication.enabled: true");
            list.add("authentication.sdk.url: "
                    + "%s/opik/auth".formatted(appContextConfig.runtimeInfo().getHttpsBaseUrl()));
            list.add("authentication.ui.url: "
                    + "%s/opik/auth-session".formatted(appContextConfig.runtimeInfo().getHttpsBaseUrl()));

            if (appContextConfig.cacheTtlInSeconds() != null) {
                list.add("authentication.apiKeyResolutionCacheTTLInSec: " + appContextConfig.cacheTtlInSeconds());
            }
        }

        GuiceyConfigurationHook hook = injector -> {
            injector.modulesOverride(TestHttpClientUtils.testAuthModule());

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
            list.add("redis.singleNodeUrl: %s".formatted(appContextConfig.redisUrl()));
            list.add("redis.sentinelMode: false");
            list.add("redis.lockTimeout: 500");
        }

        if (appContextConfig.rateLimitEnabled()) {
            list.add("rateLimit.enabled: true");
            list.add("rateLimit.generalLimit.limit: %d".formatted(appContextConfig.limit()));
            list.add("rateLimit.generalLimit.durationInSeconds: %d"
                    .formatted(appContextConfig.limitDurationInSeconds()));

            if (appContextConfig.customLimits() != null) {
                appContextConfig.customLimits()
                        .forEach((bucket, limitConfig) -> {
                            list.add("rateLimit.customLimits.%s.limit: %d".formatted(bucket, limitConfig.limit()));
                            list.add("rateLimit.customLimits.%s.durationInSeconds: %d".formatted(bucket,
                                    limitConfig.durationInSeconds()));
                        });
            }
        }

        return TestDropwizardAppExtension.forApp(OpikApplication.class)
                .config("src/test/resources/config-test.yml")
                .configOverrides(list.toArray(new String[0]))
                .randomPorts()
                .hooks(hook)
                .create();
    }
}
