package com.comet.opik.infrastructure.ratelimit;

import com.comet.opik.infrastructure.RateLimitConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Provider;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitInterceptor Tests")
class RateLimitInterceptorTest {

    @Mock
    private Provider<RequestContext> requestContextProvider;

    @Mock
    private Provider<RateLimitService> rateLimitServiceProvider;

    @Mock
    private RateLimitConfig rateLimitConfig;

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private RequestContext requestContext;

    @Mock
    private MethodInvocation methodInvocation;

    private RateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new RateLimitInterceptor(requestContextProvider, rateLimitServiceProvider, rateLimitConfig);
    }

    @Nested
    @DisplayName("HTTP Route Extraction Tests")
    class HttpRouteExtractionTests {

        @Test
        void extractHttpRoute_whenClassAndMethodHavePaths_shouldCombineCorrectly() throws Exception {
            // Given
            Method method = TestResource.class.getMethod("testMethod");
            when(methodInvocation.getMethod()).thenReturn(method);

            // When
            String route = extractHttpRoute(methodInvocation);

            // Then
            assertThat(route).isEqualTo("/v1/private/test/method");
        }

        @Test
        void extractHttpRoute_whenClassPathEndsWithSlash_shouldNotAddExtraSlash() throws Exception {
            // Given
            Method method = TestResourceWithTrailingSlash.class.getMethod("testMethod");
            when(methodInvocation.getMethod()).thenReturn(method);

            // When
            String route = extractHttpRoute(methodInvocation);

            // Then
            assertThat(route).isEqualTo("/v1/private/test/method");
        }

        @Test
        void extractHttpRoute_whenMethodPathStartsWithSlash_shouldNotAddExtraSlash() throws Exception {
            // Given
            Method method = TestResourceWithLeadingSlash.class.getMethod("testMethod");
            when(methodInvocation.getMethod()).thenReturn(method);

            // When
            String route = extractHttpRoute(methodInvocation);

            // Then
            assertThat(route).isEqualTo("/v1/private/test/method");
        }

        @Test
        void extractHttpRoute_whenOnlyClassHasPath_shouldReturnClassPath() throws Exception {
            // Given
            Method method = TestResource.class.getMethod("methodWithoutPath");
            when(methodInvocation.getMethod()).thenReturn(method);

            // When
            String route = extractHttpRoute(methodInvocation);

            // Then
            assertThat(route).isEqualTo("/v1/private/test");
        }

        @Test
        void extractHttpRoute_whenOnlyMethodHasPath_shouldReturnMethodPath() throws Exception {
            // Given
            Method method = TestResourceWithoutClassPath.class.getMethod("testMethod");
            when(methodInvocation.getMethod()).thenReturn(method);

            // When
            String route = extractHttpRoute(methodInvocation);

            // Then
            assertThat(route).isEqualTo("/v1/private/test/method");
        }

        @Test
        void extractHttpRoute_whenNoPaths_shouldReturnUnknown() throws Exception {
            // Given
            Method method = TestResourceWithoutPaths.class.getMethod("testMethod");
            when(methodInvocation.getMethod()).thenReturn(method);

            // When
            String route = extractHttpRoute(methodInvocation);

            // Then
            assertThat(route).isEqualTo("unknown");
        }

        @Test
        void extractHttpRoute_whenExceptionOccurs_shouldReturnUnknown() throws Exception {
            // Given
            when(methodInvocation.getMethod()).thenThrow(new RuntimeException("Test exception"));

            // When
            String route = extractHttpRoute(methodInvocation);

            // Then
            assertThat(route).isEqualTo("unknown");
        }
    }

    @Nested
    @DisplayName("HTTP Method Extraction Tests")
    class HttpMethodExtractionTests {

        @ParameterizedTest
        @MethodSource("httpMethodProvider")
        void extractHttpMethod_whenMethodHasHttpAnnotation_shouldReturnCorrectMethod(
                Class<?> resourceClass, String methodName, String expectedMethod) throws Exception {
            // Given
            Method method = resourceClass.getMethod(methodName);
            when(methodInvocation.getMethod()).thenReturn(method);

            // When
            String httpMethod = extractHttpMethod(methodInvocation);

            // Then
            assertThat(httpMethod).isEqualTo(expectedMethod);
        }

        @Test
        void extractHttpMethod_whenNoHttpAnnotation_shouldReturnUnknown() throws Exception {
            // Given
            Method method = TestResourceWithoutHttpMethod.class.getMethod("testMethod");
            when(methodInvocation.getMethod()).thenReturn(method);

            // When
            String httpMethod = extractHttpMethod(methodInvocation);

            // Then
            assertThat(httpMethod).isEqualTo("unknown");
        }

        @Test
        void extractHttpMethod_whenExceptionOccurs_shouldReturnUnknown() throws Exception {
            // Given
            when(methodInvocation.getMethod()).thenThrow(new RuntimeException("Test exception"));

            // When
            String httpMethod = extractHttpMethod(methodInvocation);

            // Then
            assertThat(httpMethod).isEqualTo("unknown");
        }

        static Stream<Arguments> httpMethodProvider() {
            return Stream.of(
                    Arguments.of(TestResource.class, "getMethod", "GET"),
                    Arguments.of(TestResource.class, "postMethod", "POST"),
                    Arguments.of(TestResource.class, "putMethod", "PUT"),
                    Arguments.of(TestResource.class, "deleteMethod", "DELETE"),
                    Arguments.of(TestResource.class, "patchMethod", "PATCH"),
                    Arguments.of(TestResource.class, "headMethod", "HEAD"),
                    Arguments.of(TestResource.class, "optionsMethod", "OPTIONS"));
        }
    }

    // Helper method to access private extractHttpRoute method
    private String extractHttpRoute(MethodInvocation invocation) throws Exception {
        Method method = RateLimitInterceptor.class.getDeclaredMethod("extractHttpRoute", MethodInvocation.class);
        method.setAccessible(true);
        return (String) method.invoke(interceptor, invocation);
    }

    // Helper method to access private extractHttpMethod method
    private String extractHttpMethod(MethodInvocation invocation) throws Exception {
        Method method = RateLimitInterceptor.class.getDeclaredMethod("extractHttpMethod", MethodInvocation.class);
        method.setAccessible(true);
        return (String) method.invoke(interceptor, invocation);
    }

    // Test resource classes for different scenarios
    @Path("/v1/private/test")
    static class TestResource {
        @GET
        @Path("/method")
        public void getMethod() {
        }

        @POST
        @Path("/post")
        public void postMethod() {
        }

        @PUT
        @Path("/put")
        public void putMethod() {
        }

        @DELETE
        @Path("/delete")
        public void deleteMethod() {
        }

        @PATCH
        @Path("/patch")
        public void patchMethod() {
        }

        @HEAD
        @Path("/head")
        public void headMethod() {
        }

        @OPTIONS
        @Path("/options")
        public void optionsMethod() {
        }

        @POST
        @Path("/method")
        public void testMethod() {
        }

        @POST
        public void methodWithoutPath() {
        }
    }

    @Path("/v1/private/test/")
    static class TestResourceWithTrailingSlash {
        @POST
        @Path("method")
        public void testMethod() {
        }
    }

    @Path("/v1/private/test")
    static class TestResourceWithLeadingSlash {
        @POST
        @Path("/method")
        public void testMethod() {
        }
    }

    @Path("/v1/private/test")
    static class TestResourceWithoutClassPath {
        @POST
        @Path("/method")
        public void testMethod() {
        }
    }

    static class TestResourceWithoutPaths {
        public void testMethod() {
        }
    }

    static class TestResourceWithoutHttpMethod {
        public void testMethod() {
        }
    }
}
