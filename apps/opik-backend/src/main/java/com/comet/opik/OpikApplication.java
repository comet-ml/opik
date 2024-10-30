package com.comet.opik;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.AuthModule;
import com.comet.opik.infrastructure.bi.OpikGuiceyLifecycleEventListener;
import com.comet.opik.infrastructure.bundle.LiquibaseBundle;
import com.comet.opik.infrastructure.db.DatabaseAnalyticsModule;
import com.comet.opik.infrastructure.db.IdGeneratorModule;
import com.comet.opik.infrastructure.db.NameGeneratorModule;
import com.comet.opik.infrastructure.http.HttpModule;
import com.comet.opik.infrastructure.ratelimit.RateLimitModule;
import com.comet.opik.infrastructure.redis.RedisModule;
import com.comet.opik.utils.JsonBigDecimalDeserializer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import org.glassfish.jersey.server.ServerProperties;
import org.jdbi.v3.jackson2.Jackson2Plugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import ru.vyarus.dropwizard.guice.GuiceBundle;
import ru.vyarus.guicey.jdbi3.JdbiBundle;

import java.math.BigDecimal;

import static com.comet.opik.infrastructure.bundle.LiquibaseBundle.DB_APP_ANALYTICS_MIGRATIONS_FILE_NAME;
import static com.comet.opik.infrastructure.bundle.LiquibaseBundle.DB_APP_ANALYTICS_NAME;
import static com.comet.opik.infrastructure.bundle.LiquibaseBundle.DB_APP_STATE_MIGRATIONS_FILE_NAME;
import static com.comet.opik.infrastructure.bundle.LiquibaseBundle.DB_APP_STATE_NAME;

public class OpikApplication extends Application<OpikConfiguration> {

    public static void main(String[] args) throws Exception {
        new OpikApplication().run(args);
    }

    @Override
    public String getName() {
        return "Opik";
    }

    @Override
    public void initialize(Bootstrap<OpikConfiguration> bootstrap) {
        var substitutor = new EnvironmentVariableSubstitutor(false);
        var provider = new SubstitutingSourceProvider(bootstrap.getConfigurationSourceProvider(), substitutor);
        bootstrap.setConfigurationSourceProvider(provider);
        bootstrap.addBundle(LiquibaseBundle.builder()
                .name(DB_APP_STATE_NAME)
                .migrationsFileName(DB_APP_STATE_MIGRATIONS_FILE_NAME)
                .dataSourceFactoryFunction(OpikConfiguration::getDatabase)
                .build());
        bootstrap.addBundle(LiquibaseBundle.builder()
                .name(DB_APP_ANALYTICS_NAME)
                .migrationsFileName(DB_APP_ANALYTICS_MIGRATIONS_FILE_NAME)
                .dataSourceFactoryFunction(OpikConfiguration::getDatabaseAnalyticsMigrations)
                .build());
        bootstrap.addBundle(GuiceBundle.builder()
                .bundles(JdbiBundle.<OpikConfiguration>forDatabase((conf, env) -> conf.getDatabase())
                        .withPlugins(new SqlObjectPlugin(), new Jackson2Plugin()))
                .modules(new DatabaseAnalyticsModule(), new IdGeneratorModule(), new AuthModule(), new RedisModule(),
                        new RateLimitModule(), new NameGeneratorModule(), new HttpModule())
                .listen(new OpikGuiceyLifecycleEventListener())
                .enableAutoConfig()
                .build());
    }

    @Override
    public void run(OpikConfiguration configuration, Environment environment) {
        // Resources
        var jersey = environment.jersey();

        environment.getObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        // Naming strategy, this is the default for all objects serving as a fallback.
        // However, it does not apply to OpenAPI documentation.
        environment.getObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SnakeCaseStrategy.INSTANCE);
        environment.getObjectMapper().configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        environment.getObjectMapper()
                .registerModule(new SimpleModule().addDeserializer(BigDecimal.class, new JsonBigDecimalDeserializer()));

        jersey.property(ServerProperties.RESPONSE_SET_STATUS_OVER_SEND_ERROR, true);
    }
}
