package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.resources.oauth.OAuthAuthorizeResource;
import com.comet.opik.api.resources.oauth.OAuthMetadataResource;
import com.comet.opik.api.resources.oauth.OAuthRegisterResource;
import com.comet.opik.api.resources.oauth.OAuthTokenResource;
import com.comet.opik.api.resources.oauth.OAuthValidateTokenResource;
import com.comet.opik.api.resources.v1.internal.AgentInsightsEnrollmentResource;
import com.comet.opik.api.resources.v1.internal.AnalyticsQueriesResource;
import com.comet.opik.api.resources.v1.internal.UsageResource;
import com.comet.opik.api.resources.v1.session.RedirectResource;
import com.comet.opik.domain.mcpoauth.McpOAuthService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.health.IsAliveResource;
import com.comet.opik.infrastructure.web.InstantParamConverter;
import com.comet.opik.infrastructure.web.LocalDateParamConverter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.glassfish.jersey.server.model.Resource;
import org.glassfish.jersey.server.model.ResourceMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(DropwizardExtensionsSupport.class)
@DisplayName("AuthFilter coverage of every resource method")
class AuthFilterCoverageTest {

    private enum Expectation {
        AUTHENTICATED,
        SESSION,
        PUBLIC
    }

    private static final String PRIVATE_PACKAGE = "com.comet.opik.api.resources.v1.priv";

    private static final Set<Class<?>> AUTHENTICATED_RESOURCES = Set.of(AnalyticsQueriesResource.class);

    private static final Set<Class<?>> SESSION_RESOURCES = Set.of(RedirectResource.class);

    private static final Set<Class<?>> PUBLIC_RESOURCES = Set.of(
            UsageResource.class,
            AgentInsightsEnrollmentResource.class,
            OAuthAuthorizeResource.class,
            OAuthRegisterResource.class,
            OAuthTokenResource.class,
            OAuthMetadataResource.class,
            OAuthValidateTokenResource.class,
            IsAliveResource.class);

    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{[^}]+}");

    private static final List<Class<?>> RESOURCE_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.comet.opik")
            .stream()
            .filter(javaClass -> javaClass.isAnnotatedWith(Path.class))
            .<Class<?>>map(javaClass -> javaClass.reflect())
            .sorted(Comparator.comparing(Class::getName))
            .toList();

    private static final AuthService AUTH_SERVICE = mock(AuthService.class);

    private static final ResourceExtension EXT;

    static {
        var builder = ResourceExtension.builder()
                .addProvider(new AuthFilter(AUTH_SERVICE, mock(McpOAuthService.class),
                        mock(CipxTokenValidationService.class), new OpikConfiguration(), RequestContext::new))
                .addProvider(new AbortAfterMatching())
                .addProvider(InstantParamConverter.class)
                .addProvider(LocalDateParamConverter.class);
        RESOURCE_CLASSES.forEach(resourceClass -> builder.addResource(mock(resourceClass)));
        EXT = builder.build();
    }

    @Priority(Priorities.USER + 1000)
    private static class AbortAfterMatching implements ContainerRequestFilter {

        @Override
        public void filter(ContainerRequestContext context) {
            context.abortWith(Response.noContent().build());
        }
    }

    private record Endpoint(Class<?> resourceClass, String httpMethod, String template, MediaType consumes) {

        String requestPath() {
            return TEMPLATE_VARIABLE.matcher(template).replaceAll(UUID.randomUUID().toString());
        }

        @Override
        public String toString() {
            return httpMethod + " " + template;
        }
    }

    @TestFactory
    Stream<DynamicTest> everyResourceMethodTakesTheExpectedBranch() {
        assertThat(RESOURCE_CLASSES).isNotEmpty();
        return RESOURCE_CLASSES.stream()
                .flatMap(AuthFilterCoverageTest::endpoints)
                .map(endpoint -> DynamicTest.dynamicTest(endpoint + " -> " + expected(endpoint.resourceClass()),
                        () -> assertBranch(endpoint)));
    }

    private static Stream<Endpoint> endpoints(Class<?> resourceClass) {
        Resource resource = Resource.from(resourceClass);
        Stream<Endpoint> own = resource.getResourceMethods().stream()
                .map(method -> new Endpoint(resourceClass, method.getHttpMethod(), resource.getPath(),
                        consumes(method)));
        Stream<Endpoint> children = resource.getChildResources().stream()
                .flatMap(child -> child.getResourceMethods().stream()
                        .map(method -> new Endpoint(resourceClass, method.getHttpMethod(),
                                join(resource.getPath(), child.getPath()), consumes(method))));
        return Stream.concat(own, children);
    }

    private static MediaType consumes(ResourceMethod method) {
        return method.getConsumedTypes().isEmpty()
                ? MediaType.APPLICATION_JSON_TYPE
                : method.getConsumedTypes().getFirst();
    }

    private static String join(String parent, String child) {
        return (parent + "/" + child).replaceAll("/{2,}", "/");
    }

    private static Expectation expected(Class<?> resourceClass) {
        if (PUBLIC_RESOURCES.contains(resourceClass)) {
            return Expectation.PUBLIC;
        }
        if (SESSION_RESOURCES.contains(resourceClass)) {
            return Expectation.SESSION;
        }
        if (AUTHENTICATED_RESOURCES.contains(resourceClass)
                || resourceClass.getPackageName().startsWith(PRIVATE_PACKAGE)) {
            return Expectation.AUTHENTICATED;
        }
        throw new AssertionError("Resource '%s' is not classified: add it to AUTHENTICATED_RESOURCES, or make the"
                .formatted(resourceClass.getName())
                + " deliberate decision to list it in SESSION_RESOURCES or PUBLIC_RESOURCES");
    }

    private static void assertBranch(Endpoint endpoint) {
        clearInvocations(AUTH_SERVICE);

        var request = EXT.target(endpoint.requestPath()).request();
        try (var response = Set.of("PUT", "PATCH").contains(endpoint.httpMethod())
                ? request.method(endpoint.httpMethod(), Entity.entity("{}", endpoint.consumes()))
                : request.method(endpoint.httpMethod())) {
            assertThat(response.getStatus())
                    .as("the request must be routed to the resource before it is aborted")
                    .isEqualTo(204);
        }

        switch (expected(endpoint.resourceClass())) {
            case AUTHENTICATED -> verify(AUTH_SERVICE).authenticate(any(), any(), any());
            case SESSION -> verify(AUTH_SERVICE).authenticateSession(any());
            case PUBLIC -> {
            }
        }
        verifyNoMoreInteractions(AUTH_SERVICE);
    }
}
