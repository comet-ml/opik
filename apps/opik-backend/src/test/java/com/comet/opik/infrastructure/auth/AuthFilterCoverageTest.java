package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.resources.oauth.OAuthAuthorizeResource;
import com.comet.opik.api.resources.oauth.OAuthMetadataResource;
import com.comet.opik.api.resources.oauth.OAuthRegisterResource;
import com.comet.opik.api.resources.oauth.OAuthTokenResource;
import com.comet.opik.api.resources.oauth.OAuthValidateTokenResource;
import com.comet.opik.api.resources.v1.internal.AgentInsightsEnrollmentResource;
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
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.glassfish.jersey.server.model.Resource;
import org.glassfish.jersey.server.model.ResourceMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(DropwizardExtensionsSupport.class)
@DisplayName("AuthFilter coverage of every resource method")
class AuthFilterCoverageTest {

    private enum Expectation {
        AUTHENTICATED,
        SESSION,
        PUBLIC
    }

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
        var authFilter = new AuthFilter(AUTH_SERVICE, mock(McpOAuthService.class),
                mock(CipxTokenValidationService.class), new OpikConfiguration(), RequestContext::new);
        var builder = ResourceExtension.builder()
                .addProvider(new AuthDynamicFeature(authFilter))
                .addProvider(new AbortAfterMatching())
                .addProvider(InstantParamConverter.class)
                .addProvider(LocalDateParamConverter.class);
        RESOURCE_CLASSES.forEach(resourceClass -> builder.addResource(mock(resourceClass, RETURNS_MOCKS)));
        EXT = builder.build();
    }

    @Priority(Priorities.USER + 1000)
    private static class AbortAfterMatching implements ContainerRequestFilter {

        @Override
        public void filter(ContainerRequestContext context) {
            context.abortWith(Response.noContent().build());
        }
    }

    private record Endpoint(Class<?> resourceClass, String httpMethod, String template, MediaType consumes,
            boolean generated, List<String> requiredPermissions) {

        String requestPath() {
            return TEMPLATE_VARIABLE.matcher(template).replaceAll(UUID.randomUUID().toString());
        }

        @Override
        public String toString() {
            return httpMethod + " " + template;
        }
    }

    @TestFactory
    Stream<DynamicTest> authenticatedEndpointsAuthenticate() {
        return tests(Expectation.AUTHENTICATED, endpoint -> {
            send(endpoint);

            var expectedContextInfo = ContextInfoHolder.builder()
                    .uriInfo(mock(UriInfo.class))
                    .method(endpoint.httpMethod())
                    .requiredPermissions(endpoint.requiredPermissions())
                    .build();
            var contextInfo = ArgumentCaptor.forClass(ContextInfoHolder.class);
            verify(AUTH_SERVICE).authenticate(any(), any(), contextInfo.capture());
            assertThat(contextInfo.getValue())
                    .usingRecursiveComparison()
                    .ignoringFields("uriInfo")
                    .isEqualTo(expectedContextInfo);
            verifyNoMoreInteractions(AUTH_SERVICE);
        });
    }

    @TestFactory
    Stream<DynamicTest> sessionEndpointsCheckTheSessionCookie() {
        return tests(Expectation.SESSION, endpoint -> {
            send(endpoint);

            verify(AUTH_SERVICE).authenticateSession(any());
            verifyNoMoreInteractions(AUTH_SERVICE);
        });
    }

    @TestFactory
    Stream<DynamicTest> publicEndpointsSkipAuthentication() {
        return tests(Expectation.PUBLIC, endpoint -> {
            send(endpoint);

            verifyNoInteractions(AUTH_SERVICE);
        });
    }

    private static Stream<DynamicTest> tests(Expectation expectation, Consumer<Endpoint> assertion) {
        var endpoints = RESOURCE_CLASSES.stream()
                .flatMap(AuthFilterCoverageTest::endpoints)
                .filter(endpoint -> expected(endpoint.resourceClass()) == expectation)
                .toList();
        assertThat(endpoints).isNotEmpty();
        return endpoints.stream()
                .map(endpoint -> DynamicTest.dynamicTest(endpoint.toString(), () -> assertion.accept(endpoint)));
    }

    private static void send(Endpoint endpoint) {
        clearInvocations(AUTH_SERVICE);
        var request = EXT.target(endpoint.requestPath()).request();
        try (var response = Set.of("PUT", "PATCH").contains(endpoint.httpMethod())
                ? request.method(endpoint.httpMethod(), Entity.entity("{}", endpoint.consumes()))
                : request.method(endpoint.httpMethod())) {
            assertThat(response.getStatus())
                    .as("%s must be routed to the resource before it is aborted", endpoint)
                    .isEqualTo(204);
        }
    }

    private static Stream<Endpoint> endpoints(Class<?> resourceClass) {
        return endpoints(resourceClass, resourceClass, Resource.from(resourceClass), "");
    }

    private static Stream<Endpoint> endpoints(Class<?> resourceClass, Class<?> handlerClass, Resource resource,
            String parentPath) {
        String path = join(parentPath, resource.getPath());
        Stream<Endpoint> own = resource.getResourceMethods().stream()
                .map(method -> new Endpoint(resourceClass, method.getHttpMethod(), path, consumes(method), false,
                        requiredPermissions(handlerClass, method)));
        Stream<Endpoint> children = resource.getChildResources().stream()
                .flatMap(child -> child.getResourceMethods().stream()
                        .map(method -> new Endpoint(resourceClass, method.getHttpMethod(),
                                join(path, child.getPath()), consumes(method), false,
                                requiredPermissions(handlerClass, method))));
        Stream<Endpoint> generatedOptions = Stream.concat(
                resource.getResourceMethods().isEmpty() ? Stream.empty() : Stream.of(path),
                resource.getChildResources().stream()
                        .filter(child -> !child.getResourceMethods().isEmpty())
                        .map(child -> join(path, child.getPath())))
                .distinct()
                .map(optionsPath -> new Endpoint(resourceClass, HttpMethod.OPTIONS, optionsPath,
                        MediaType.APPLICATION_JSON_TYPE, true, List.of()));
        Stream<Endpoint> located = resource.getChildResources().stream()
                .filter(child -> child.getResourceLocator() != null)
                .flatMap(child -> {
                    Class<?> locatedClass = child.getResourceLocator().getInvocable().getRawResponseType();
                    return endpoints(resourceClass, locatedClass, Resource.from(locatedClass),
                            join(path, child.getPath()));
                });
        return Stream.of(own, children, generatedOptions, located).flatMap(stream -> stream);
    }

    private static List<String> requiredPermissions(Class<?> resourceClass, ResourceMethod method) {
        return RequiredPermissionsResolver.getRequiredPermissions(new ResourceInfo() {
            @Override
            public Method getResourceMethod() {
                return method.getInvocable().getHandlingMethod();
            }

            @Override
            public Class<?> getResourceClass() {
                return resourceClass;
            }
        });
    }

    private static MediaType consumes(ResourceMethod method) {
        return method.getConsumedTypes().isEmpty()
                ? MediaType.APPLICATION_JSON_TYPE
                : method.getConsumedTypes().getFirst();
    }

    private static String join(String parent, String child) {
        return (parent + "/" + (child == null ? "" : child)).replaceAll("/{2,}", "/");
    }

    private static Expectation expected(Class<?> resourceClass) {
        if (PUBLIC_RESOURCES.contains(resourceClass)) {
            return Expectation.PUBLIC;
        }
        if (SESSION_RESOURCES.contains(resourceClass)) {
            return Expectation.SESSION;
        }
        return Expectation.AUTHENTICATED;
    }

}
