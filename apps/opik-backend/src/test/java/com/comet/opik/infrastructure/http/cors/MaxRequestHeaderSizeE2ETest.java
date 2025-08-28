package com.comet.opik.infrastructure.http.cors;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.google.common.net.HttpHeaders;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.util.Random;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Max request header size configuration test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class MaxRequestHeaderSizeE2ETest {

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

    // Default maxRequestHeaderSize from config.yml: 16000 bytes (16KB)
    private static final int DEFAULT_MAX_HEADER_SIZE = 16000;
    
    // Test values: below default, at default, and above default
    private static final int BELOW_DEFAULT_SIZE = DEFAULT_MAX_HEADER_SIZE - 1000;  // 15KB
    private static final int AT_DEFAULT_SIZE = DEFAULT_MAX_HEADER_SIZE;            // 16KB
    private static final int ABOVE_DEFAULT_SIZE = DEFAULT_MAX_HEADER_SIZE + 1000;  // 17KB

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(MYSQL, CLICKHOUSE, REDIS, ZOOKEEPER_CONTAINER).join();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICKHOUSE, DATABASE_NAME);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .corsEnabled(true)
                        .build());
    }

    private String baseURI;
    private ClientSupport client;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;
        ClientSupportUtils.config(client);
    }

    /**
     * Generates a header value of the specified size.
     * Creates a header that will be exactly the specified size when encoded.
     */
    private String generateHeaderValue(int targetSize) {
        // Account for header name and encoding overhead
        // A simple approach: create a string that will result in the target size
        int contentSize = targetSize - 100; // Leave room for header name and encoding
        if (contentSize <= 0) {
            contentSize = 100; // Minimum size
        }
        
        // Generate random content to reach target size
        Random random = new Random(42); // Fixed seed for reproducible tests
        StringBuilder sb = new StringBuilder();
        
        while (sb.length() < contentSize) {
            sb.append("test_value_").append(random.nextInt(1000)).append("_");
        }
        
        // Trim to exact size
        return sb.substring(0, contentSize);
    }

    @Test
    @DisplayName("Should accept request with header size below default limit")
    void testRequestWithHeaderBelowDefaultLimit() {
        // Create a header value that's below the default limit
        String headerValue = generateHeaderValue(BELOW_DEFAULT_SIZE);
        
        var response = client.target("%s/is-alive/ping".formatted(baseURI))
                .request()
                .header("X-Test-Header-Below-Limit", headerValue)
                .get();

        // Should succeed with 200 status
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeaders()).containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    @DisplayName("Should accept request with header size at default limit")
    void testRequestWithHeaderAtDefaultLimit() {
        // Create a header value that's exactly at the default limit
        String headerValue = generateHeaderValue(AT_DEFAULT_SIZE);
        
        var response = client.target("%s/is-alive/ping".formatted(baseURI))
                .request()
                .header("X-Test-Header-At-Limit", headerValue)
                .get();

        // Should succeed with 200 status
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeaders()).containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    @DisplayName("Should reject request with header size above default limit")
    void testRequestWithHeaderAboveDefaultLimit() {
        // Create a header value that's above the default limit
        String headerValue = generateHeaderValue(ABOVE_DEFAULT_SIZE);
        
        try (var response = client.target("%s/is-alive/ping".formatted(baseURI))
                .request()
                .header("X-Test-Header-Above-Limit", headerValue)
                .get()) {
            
            // Should fail with 431 Request Header Fields Too Large
            assertThat(response.getStatus()).isEqualTo(431);
        }
    }

    @Test
    @DisplayName("Should handle multiple large headers within limit")
    void testRequestWithMultipleHeadersWithinLimit() {
        // Create multiple headers that together stay within the limit
        String header1 = generateHeaderValue(5000);  // 5KB
        String header2 = generateHeaderValue(5000);  // 5KB
        String header3 = generateHeaderValue(5000);  // 5KB
        // Total: 15KB, which is below the 16KB limit
        
        var response = client.target("%s/is-alive/ping".formatted(baseURI))
                .request()
                .header("X-Test-Header-1", header1)
                .header("X-Test-Header-2", header2)
                .header("X-Test-Header-3", header3)
                .get();

        // Should succeed with 200 status
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeaders()).containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    @DisplayName("Should handle complex query parameters within header limit")
    void testRequestWithComplexQueryParameters() {
        // Create a URL with many query parameters that should stay within the header limit
        StringBuilder queryParams = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            if (i > 0) queryParams.append("&");
            queryParams.append("filter").append(i).append("=").append("value").append(i);
        }
        
        // Add some longer filter values
        for (int i = 50; i < 60; i++) {
            queryParams.append("&filter").append(i).append("=").append("very_long_filter_value_").append(i).append("_with_additional_content");
        }
        
        String url = "%s/is-alive/ping?%s".formatted(baseURI, queryParams.toString());
        
        var response = client.target(url)
                .request()
                .get();

        // Should succeed with 200 status
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeaders()).containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    @DisplayName("Should handle extremely long single header value")
    void testRequestWithExtremelyLongHeaderValue() {
        // Create a single header with a very long value that's just within the limit
        String extremelyLongValue = generateHeaderValue(DEFAULT_MAX_HEADER_SIZE - 200);
        
        var response = client.target("%s/is-alive/ping".formatted(baseURI))
                .request()
                .header("X-Extremely-Long-Header", extremelyLongValue)
                .get();

        // Should succeed with 200 status
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeaders()).containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    @DisplayName("Should handle mixed header sizes within limit")
    void testRequestWithMixedHeaderSizes() {
        // Mix of small, medium, and large headers that stay within the limit
        String smallHeader = "small_value";
        String mediumHeader = generateHeaderValue(2000);   // 2KB
        String largeHeader = generateHeaderValue(10000);  // 10KB
        // Total: ~12KB, which is within the 16KB limit
        
        var response = client.target("%s/is-alive/ping".formatted(baseURI))
                .request()
                .header("X-Small-Header", smallHeader)
                .header("X-Medium-Header", mediumHeader)
                .header("X-Large-Header", largeHeader)
                .get();

        // Should succeed with 200 status
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeaders()).containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }
}