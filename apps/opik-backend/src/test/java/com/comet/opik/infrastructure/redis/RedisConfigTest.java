package com.comet.opik.infrastructure.redis;

import com.comet.opik.infrastructure.RedisConfig;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.ResourceConfigurationSourceProvider;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.core.Configuration;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.jersey.validation.Validators;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Redis Config Test")
class RedisConfigTest {

    private static final String MASTER_NAME = "mymaster";

    private static RedisConfig newSentinelConfig(String singleNodeUrl) {
        var redisConfig = new RedisConfig();
        redisConfig.setSingleNodeUrl(singleNodeUrl);
        var sentinel = new RedisConfig.SentinelConfig();
        sentinel.setEnabled(true);
        sentinel.setMasterName(MASTER_NAME);
        redisConfig.setSentinel(sentinel);
        return redisConfig;
    }

    @Nested
    @DisplayName("Single node mode")
    class SingleNodeMode {

        @Test
        @DisplayName("Should build single node config when sentinel is disabled")
        void shouldBuildSingleNodeConfigWhenSentinelDisabled() {
            var redisConfig = new RedisConfig();
            redisConfig.setSingleNodeUrl("redis://localhost:6379/0");

            var config = redisConfig.build();

            assertThat(config.useSingleServer().getAddress()).isEqualTo("redis://localhost:6379");
            assertThat(config.useSingleServer().getDatabase()).isZero();
        }

        @Test
        @DisplayName("Should set username, password and database from url when sentinel is disabled")
        void shouldSetCredentialsAndDatabaseFromUrlWhenSentinelDisabled() {
            var redisConfig = new RedisConfig();
            redisConfig.setSingleNodeUrl("redis://myuser:mypassword@localhost:6379/2");

            var config = redisConfig.build();

            assertThat(config.useSingleServer().getUsername()).isEqualTo("myuser");
            assertThat(config.useSingleServer().getPassword()).isEqualTo("mypassword");
            assertThat(config.useSingleServer().getDatabase()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should keep the rediss scheme when sentinel is disabled")
        void shouldKeepSslSchemeWhenSentinelDisabled() {
            var redisConfig = new RedisConfig();
            redisConfig.setSingleNodeUrl("rediss://secure.redis.com:6380/1");

            var config = redisConfig.build();

            assertThat(config.useSingleServer().getAddress()).isEqualTo("rediss://secure.redis.com:6380");
            assertThat(config.useSingleServer().getDatabase()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Sentinel mode")
    class SentinelMode {

        @Test
        @DisplayName("Should preserve Sentinel node order when loading environment-substituted YAML")
        void shouldPreserveSentinelNodeOrderWhenLoadingEnvironmentSubstitutedYaml() throws Exception {
            var environment = Map.of(
                    "OPIK_REDIS_SENTINEL_NODES",
                    "redis://sentinel-2:26379,redis://sentinel-3:26379");
            var substitutor = new EnvironmentVariableSubstitutor(false);
            substitutor.setVariableResolver(environment::get);
            var sourceProvider = new SubstitutingSourceProvider(new ResourceConfigurationSourceProvider(), substitutor);

            var config = new YamlConfigurationFactory<>(
                    SentinelTestConfiguration.class,
                    Validators.newValidator(),
                    Jackson.newObjectMapper(),
                    "dw")
                    .build(sourceProvider, "redis/sentinel-config-test.yml");

            assertThat(config.getSentinel().getNodes())
                    .containsExactly("redis://sentinel-2:26379", "redis://sentinel-3:26379");
        }

        @Test
        @DisplayName("Should build sentinel config with the master name and the seed address from the url")
        void shouldBuildSentinelConfigWithMasterNameAndSeedAddressFromUrl() {
            var config = newSentinelConfig("redis://localhost:26379/0").build();

            assertThat(config.useSentinelServers().getMasterName()).isEqualTo(MASTER_NAME);
            assertThat(config.useSentinelServers().getSentinelAddresses())
                    .containsExactly("redis://localhost:26379");
            assertThat(config.useSentinelServers().getDatabase()).isZero();
        }

        @Test
        @DisplayName("Should preserve the rediss scheme for all sentinel addresses")
        void shouldPreserveRedissSchemeForAllSentinelAddresses() {
            var redisConfig = newSentinelConfig("rediss://sentinel-1:26379/0");
            redisConfig.getSentinel().setNodes("rediss://sentinel-2:26379");
            var config = redisConfig.build();

            assertThat(config.useSentinelServers().getSentinelAddresses())
                    .containsExactly("rediss://sentinel-1:26379", "rediss://sentinel-2:26379");
        }

        @Test
        @DisplayName("Should reject unsupported schemes when sentinel is enabled")
        void shouldRejectUnsupportedSchemesWhenSentinelIsEnabled() {
            var redisConfig = newSentinelConfig("http://sentinel-1:26379/0");

            assertThatThrownBy(redisConfig::build)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("singleNodeUrl must use the redis or rediss scheme");
        }

        @Test
        @DisplayName("Should reject mixed sentinel schemes")
        void shouldRejectMixedSentinelSchemes() {
            var redisConfig = newSentinelConfig("rediss://sentinel-1:26379/0");
            redisConfig.getSentinel().setNodes("rediss://sentinel-2:26379,redis://sentinel-3:26379");

            assertThatThrownBy(redisConfig::build)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must use the same scheme as singleNodeUrl");
        }

        @Test
        @DisplayName("Should reject malformed extra sentinel addresses")
        void shouldRejectMalformedExtraSentinelAddresses() {
            var redisConfig = newSentinelConfig("redis://sentinel-1:26379/0");
            redisConfig.getSentinel().setNodes("redis://sentinel-2:26379,redis://bad host:26379");

            assertThatThrownBy(redisConfig::build)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sentinel.nodes entries must be valid Redis URLs");
        }

        @Test
        @DisplayName("Should reject extra sentinel addresses without a port")
        void shouldRejectExtraSentinelAddressesWithoutPort() {
            var redisConfig = newSentinelConfig("redis://sentinel-1:26379/0");
            redisConfig.getSentinel().setNodes("redis://sentinel-2");

            assertThatThrownBy(redisConfig::build)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must include a valid host and port");
        }

        @Test
        @DisplayName("Should append the extra seed sentinels after the one derived from the url")
        void shouldAppendExtraSeedSentinelsAfterTheOneDerivedFromTheUrl() {
            var redisConfig = newSentinelConfig("redis://sentinel-1:26379/0");
            redisConfig.getSentinel().setNodes(" redis://sentinel-2:26379 , ,redis://sentinel-3:26379 ");

            var config = redisConfig.build();

            assertThat(config.useSentinelServers().getSentinelAddresses()).containsExactly(
                    "redis://sentinel-1:26379", "redis://sentinel-2:26379", "redis://sentinel-3:26379");
        }

        @Test
        @DisplayName("Should not duplicate a extra seed sentinel that matches the url")
        void shouldNotDuplicateExtraSeedSentinelThatMatchesTheUrl() {
            var redisConfig = newSentinelConfig("redis://sentinel-1:26379/0");
            redisConfig.getSentinel().setNodes("redis://sentinel-1:26379,redis://sentinel-2:26379");

            var config = redisConfig.build();

            assertThat(config.useSentinelServers().getSentinelAddresses())
                    .containsExactly("redis://sentinel-1:26379", "redis://sentinel-2:26379");
        }

        @Test
        @DisplayName("Should set the sentinel credentials when provided")
        void shouldSetSentinelCredentialsWhenProvided() {
            var redisConfig = newSentinelConfig("redis://localhost:26379/0");
            redisConfig.getSentinel().setUsername("sentinel-user");
            redisConfig.getSentinel().setPassword("sentinel-secret");

            var config = redisConfig.build();

            assertThat(config.useSentinelServers().getSentinelUsername()).isEqualTo("sentinel-user");
            assertThat(config.useSentinelServers().getSentinelPassword()).isEqualTo("sentinel-secret");
        }

        @Test
        @DisplayName("Should not set the sentinel credentials when blank")
        void shouldNotSetSentinelCredentialsWhenBlank() {
            var redisConfig = newSentinelConfig("redis://localhost:26379/0");
            redisConfig.getSentinel().setUsername("");
            redisConfig.getSentinel().setPassword("  ");

            var config = redisConfig.build();

            assertThat(config.useSentinelServers().getSentinelUsername()).isNull();
            assertThat(config.useSentinelServers().getSentinelPassword()).isNull();
        }

        @Test
        @DisplayName("Should apply the url credentials and database to the master data nodes")
        void shouldApplyUrlCredentialsAndDatabaseToMasterDataNodes() {
            var config = newSentinelConfig("redis://datauser:datapassword@localhost:26379/3").build();

            assertThat(config.useSentinelServers().getUsername()).isEqualTo("datauser");
            assertThat(config.useSentinelServers().getPassword()).isEqualTo("datapassword");
            assertThat(config.useSentinelServers().getDatabase()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should apply the configured timeouts, retries and scan interval")
        void shouldApplyConfiguredTimeoutsRetriesAndScanInterval() {
            var redisConfig = newSentinelConfig("redis://localhost:26379/0");
            redisConfig.getSentinel().setRetryAttempts(7);
            redisConfig.getSentinel().setConnectTimeout(Duration.seconds(11));
            redisConfig.getSentinel().setTimeout(Duration.seconds(6));
            redisConfig.getSentinel().setScanInterval(Duration.milliseconds(1500));

            var config = redisConfig.build();

            assertThat(config.useSentinelServers().getRetryAttempts()).isEqualTo(7);
            assertThat(config.useSentinelServers().getConnectTimeout()).isEqualTo(11_000);
            assertThat(config.useSentinelServers().getTimeout()).isEqualTo(6_000);
            assertThat(config.useSentinelServers().getScanInterval()).isEqualTo(1_500);
        }

        @Test
        @DisplayName("Should accept the maximum Redisson duration")
        void shouldAcceptMaximumRedissonDuration() {
            var redisConfig = newSentinelConfig("redis://localhost:26379/0");
            var maximumDuration = Duration.milliseconds(Integer.MAX_VALUE);
            redisConfig.getSentinel().setConnectTimeout(maximumDuration);
            redisConfig.getSentinel().setTimeout(maximumDuration);
            redisConfig.getSentinel().setScanInterval(maximumDuration);

            var config = redisConfig.build();

            assertThat(config.useSentinelServers().getConnectTimeout()).isEqualTo(Integer.MAX_VALUE);
            assertThat(config.useSentinelServers().getTimeout()).isEqualTo(Integer.MAX_VALUE);
            assertThat(config.useSentinelServers().getScanInterval()).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("Should allow relaxing the minimum sentinel quorum check")
        void shouldAllowRelaxingMinimumSentinelQuorumCheck() {
            var redisConfig = newSentinelConfig("redis://localhost:26379/0");
            redisConfig.getSentinel().setCheckSentinelsList(false);

            var config = redisConfig.build();

            assertThat(config.useSentinelServers().isCheckSentinelsList()).isFalse();
        }

        @Test
        @DisplayName("Should reject Sentinel durations above the Redisson integer limit")
        void shouldRejectSentinelDurationsAboveRedissonIntegerLimit() {
            var sentinel = new RedisConfig.SentinelConfig();
            var oversizedDuration = Duration.milliseconds((long) Integer.MAX_VALUE + 1);
            sentinel.setConnectTimeout(oversizedDuration);
            sentinel.setTimeout(oversizedDuration);
            sentinel.setScanInterval(oversizedDuration);

            var violations = Validators.newValidator().validate(sentinel);

            assertThat(violations)
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("connectTimeout", "timeout", "scanInterval");
        }

        @Test
        @DisplayName("Should reject Sentinel duration overflow during direct builds")
        void shouldRejectSentinelDurationOverflowDuringDirectBuilds() {
            var oversizedDuration = Duration.milliseconds((long) Integer.MAX_VALUE + 1);

            var connectTimeoutConfig = newSentinelConfig("redis://localhost:26379/0");
            connectTimeoutConfig.getSentinel().setConnectTimeout(oversizedDuration);
            assertThatThrownBy(connectTimeoutConfig::build)
                    .isInstanceOf(ArithmeticException.class)
                    .hasMessage("integer overflow");

            var timeoutConfig = newSentinelConfig("redis://localhost:26379/0");
            timeoutConfig.getSentinel().setTimeout(oversizedDuration);
            assertThatThrownBy(timeoutConfig::build)
                    .isInstanceOf(ArithmeticException.class)
                    .hasMessage("integer overflow");

            var scanIntervalConfig = newSentinelConfig("redis://localhost:26379/0");
            scanIntervalConfig.getSentinel().setScanInterval(oversizedDuration);
            assertThatThrownBy(scanIntervalConfig::build)
                    .isInstanceOf(ArithmeticException.class)
                    .hasMessage("integer overflow");
        }
    }

    @Nested
    @DisplayName("Defaults")
    class Defaults {

        @Test
        @DisplayName("Should disable sentinel by default")
        void shouldDisableSentinelByDefault() {
            var sentinel = new RedisConfig.SentinelConfig();

            assertThat(sentinel.isEnabled()).isFalse();
            assertThat(sentinel.getMasterName()).isNull();
            assertThat(sentinel.getNodes()).isEmpty();
            assertThat(sentinel.getRetryAttempts()).isEqualTo(3);
            assertThat(sentinel.isCheckSentinelsList()).isTrue();
            assertThat(sentinel.getConnectTimeout()).isEqualTo(Duration.seconds(10));
            assertThat(sentinel.getTimeout()).isEqualTo(Duration.seconds(5));
            assertThat(sentinel.getScanInterval()).isEqualTo(Duration.seconds(2));
        }

        @Test
        @DisplayName("Should use single node mode when the sentinel config is left at its defaults")
        void shouldUseSingleNodeModeWhenSentinelConfigIsLeftAtDefaults() {
            var redisConfig = new RedisConfig();
            redisConfig.setSingleNodeUrl("redis://localhost:6379/0");

            var config = redisConfig.build();

            assertThat(config.useSingleServer().getAddress()).isEqualTo("redis://localhost:6379");
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("Should reject a null single node url")
        void shouldRejectNullSingleNodeUrl() {
            var redisConfig = new RedisConfig();

            assertThatThrownBy(redisConfig::build)
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("singleNodeUrl");
        }

        @Test
        @DisplayName("Should reject a malformed single node url")
        void shouldRejectMalformedSingleNodeUrl() {
            var redisConfig = new RedisConfig();
            redisConfig.setSingleNodeUrl("not-a-valid-url");

            assertThatThrownBy(redisConfig::build).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should reject a blank master name when sentinel is enabled")
        void shouldRejectBlankMasterNameWhenSentinelEnabled() {
            var redisConfig = newSentinelConfig("redis://localhost:26379/0");
            redisConfig.getSentinel().setMasterName("  ");

            assertThatThrownBy(redisConfig::build)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("masterName");
        }

        @Test
        @DisplayName("Should reject null Sentinel nodes during direct builds")
        void shouldRejectNullSentinelNodesDuringDirectBuilds() {
            var redisConfig = newSentinelConfig("redis://localhost:26379/0");
            redisConfig.getSentinel().setNodes(null);

            assertThatThrownBy(redisConfig::build)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("sentinel.nodes must not be null");
        }

        @Test
        @DisplayName("Should fail bean validation when the master name is missing and sentinel is enabled")
        void shouldFailBeanValidationWhenMasterNameIsMissingAndSentinelEnabled() {
            var sentinel = new RedisConfig.SentinelConfig();
            sentinel.setEnabled(true);

            assertThat(sentinel.isMasterNameProvidedWhenEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should pass bean validation when the master name is missing and sentinel is disabled")
        void shouldPassBeanValidationWhenMasterNameIsMissingAndSentinelDisabled() {
            var sentinel = new RedisConfig.SentinelConfig();

            assertThat(sentinel.isMasterNameProvidedWhenEnabled()).isTrue();
        }
    }

    public static class SentinelTestConfiguration extends Configuration {

        private RedisConfig.SentinelConfig sentinel;

        public RedisConfig.SentinelConfig getSentinel() {
            return sentinel;
        }

        public void setSentinel(RedisConfig.SentinelConfig sentinel) {
            this.sentinel = sentinel;
        }
    }
}
