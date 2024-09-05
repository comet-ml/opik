package com.comet.opik.api.resources.utils;

import com.comet.opik.OpikApplication;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.auth.TestHttpClientUtils;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import ru.vyarus.dropwizard.guice.hook.GuiceyConfigurationHook;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.util.ArrayList;

public class TestDropwizardAppExtensionUtils {

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

        var list = new ArrayList<String>();
        list.add("database.url: " + jdbcUrl);

        if (databaseAnalyticsFactory != null) {
            list.add("databaseAnalytics.port: " + databaseAnalyticsFactory.getPort());
            list.add("databaseAnalytics.username: " + databaseAnalyticsFactory.getUsername());
            list.add("databaseAnalytics.password: " + databaseAnalyticsFactory.getPassword());
        }

        if (runtimeInfo != null) {
            list.add("authentication.enabled: true");
            list.add("authentication.sdk.url: " + "%s/opik/auth".formatted(runtimeInfo.getHttpsBaseUrl()));
            list.add("authentication.ui.url: " + "%s/opik/auth-session".formatted(runtimeInfo.getHttpsBaseUrl()));

            if (cacheTtlInSeconds != null) {
                list.add("authentication.apiKeyResolutionCacheTTLInSec: " + cacheTtlInSeconds);
            }
        }

        GuiceyConfigurationHook hook = injector -> {
            injector.modulesOverride(TestHttpClientUtils.testAuthModule());
        };

        if (redisUrl != null) {
            list.add("redis.singleNodeUrl: %s".formatted(redisUrl));
            list.add("redis.sentinelMode: false");
            list.add("redis.lockTimeout: 500");
        }

        return TestDropwizardAppExtension.forApp(OpikApplication.class)
                .config("src/test/resources/config-test.yml")
                .configOverrides(list.toArray(new String[0]))
                .randomPorts()
                .hooks(hook)
                .create();
    }
}
