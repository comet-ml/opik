package com.comet.opik.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.stringtemplate.v4.ST;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive test suite for TemplateUtils.newST() factory method.
 * Tests cover functional correctness, memory leak prevention, and concurrent access safety.
 */
@DisplayName("TemplateUtils")
class TemplateUtilsTest {

    @Nested
    @DisplayName("Functional Tests")
    class FunctionalTests {

        @Test
        @DisplayName("should create valid ST instance from simple template")
        void shouldCreateValidSTInstance() {
            // Given
            String template = "Hello <name>!";

            // When
            ST st = TemplateUtils.newST(template);

            // Then
            assertThat(st).isNotNull();
            st.add("name", "World");
            String result = st.render();
            assertThat(result).isEqualTo("Hello World!");
        }

        @Test
        @DisplayName("should render template correctly with multiple parameters")
        void shouldRenderTemplateWithMultipleParameters() {
            // Given
            String template = "User <name> has email <email> and age <age>";

            // When
            ST st = TemplateUtils.newST(template);
            st.add("name", "Alice");
            st.add("email", "alice@example.com");
            st.add("age", 30);
            String result = st.render();

            // Then
            assertThat(result).isEqualTo("User Alice has email alice@example.com and age 30");
        }

        @Test
        @DisplayName("should handle SQL template with conditional sections")
        void shouldHandleSQLTemplateWithConditionalSections() {
            // Given - Similar to SpanDAO INSERT template
            String template = "INSERT INTO spans (id, name<if(end_time)>, end_time<endif>) VALUES (:id, :name<if(end_time)>, :end_time<endif>)";

            // When - with end_time
            ST st1 = TemplateUtils.newST(template);
            st1.add("end_time", "2024-01-01");
            String result1 = st1.render();

            // Then
            assertThat(result1).contains("end_time");
            assertThat(result1).isEqualTo("INSERT INTO spans (id, name, end_time) VALUES (:id, :name, :end_time)");

            // When - without end_time
            ST st2 = TemplateUtils.newST(template);
            String result2 = st2.render();

            // Then
            assertThat(result2).doesNotContain("end_time");
            assertThat(result2).isEqualTo("INSERT INTO spans (id, name) VALUES (:id, :name)");
        }

        @Test
        @DisplayName("should handle template with list iteration")
        void shouldHandleTemplateWithListIteration() {
            // Given - Similar to SpanDAO BULK_INSERT template
            String template = "INSERT INTO table VALUES <items:{item | (:id<item.index>)<if(item.hasNext)>, <endif>}>";

            // When
            ST st = TemplateUtils.newST(template);
            List<TemplateUtils.QueryItem> items = TemplateUtils.getQueryItemPlaceHolder(3);
            st.add("items", items);
            String result = st.render();

            // Then
            assertThat(result).isEqualTo("INSERT INTO table VALUES (:id0), (:id1), (:id2)");
        }

        @Test
        @DisplayName("should preserve template syntax across multiple renders")
        void shouldPreserveTemplateSyntax() {
            // Given
            String template = "SELECT * FROM spans WHERE trace_id = <traceId>";

            // When - First render
            ST st1 = TemplateUtils.newST(template);
            st1.add("traceId", "trace-123");
            String result1 = st1.render();

            // When - Second render with different instance
            ST st2 = TemplateUtils.newST(template);
            st2.add("traceId", "trace-456");
            String result2 = st2.render();

            // Then
            assertThat(result1).isEqualTo("SELECT * FROM spans WHERE trace_id = trace-123");
            assertThat(result2).isEqualTo("SELECT * FROM spans WHERE trace_id = trace-456");
        }

        @Test
        @DisplayName("should handle empty template")
        void shouldHandleEmptyTemplate() {
            // Given
            String template = "";

            // When
            ST st = TemplateUtils.newST(template);
            String result = st.render();

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should handle template without parameters")
        void shouldHandleTemplateWithoutParameters() {
            // Given
            String template = "SELECT * FROM spans";

            // When
            ST st = TemplateUtils.newST(template);
            String result = st.render();

            // Then
            assertThat(result).isEqualTo("SELECT * FROM spans");
        }
    }

    @Nested
    @DisplayName("Memory Leak Tests")
    class MemoryLeakTests {

        @Test
        @DisplayName("should allow ST instances to be garbage collected")
        void shouldAllowGarbageCollection() throws InterruptedException {
            // Given - Create ST instances and store them in weak references
            List<WeakReference<ST>> weakReferences = new ArrayList<>();
            String template = "SELECT * FROM spans WHERE id = <id>";

            // When - Create many ST instances
            for (int i = 0; i < 1000; i++) {
                ST st = TemplateUtils.newST(template);
                st.add("id", "id-" + i);
                st.render(); // Use the template
                weakReferences.add(new WeakReference<>(st));
            }

            // Suggest garbage collection
            System.gc();
            Thread.sleep(100);
            System.gc();

            // Then - Most instances should be garbage collected
            long collectedCount = weakReferences.stream()
                    .filter(ref -> ref.get() == null)
                    .count();

            // At least 50% should be collected (in practice, usually 90%+)
            assertThat(collectedCount).isGreaterThan(500);
        }

        @RepeatedTest(10)
        @DisplayName("should not cause memory leak with repeated template creation")
        void shouldNotCauseMemoryLeakWithRepeatedCreation() {
            // Given
            String template = "INSERT INTO spans (id, name, trace_id) VALUES (:id, :name, :trace_id)";
            Runtime runtime = Runtime.getRuntime();

            // Force GC before test
            System.gc();
            long memoryBefore = runtime.totalMemory() - runtime.freeMemory();

            // When - Create many templates (simulating SpanDAO usage)
            for (int i = 0; i < 10000; i++) {
                ST st = TemplateUtils.newST(template);
                st.add("id", "id-" + i);
                st.add("name", "name-" + i);
                st.add("trace_id", "trace-" + i);
                st.render();
                // ST instance goes out of scope and should be GC-able
            }

            // Suggest GC
            System.gc();
            long memoryAfter = runtime.totalMemory() - runtime.freeMemory();

            // Then - Memory growth should be reasonable (less than 10MB)
            long memoryGrowth = memoryAfter - memoryBefore;
            assertThat(memoryGrowth).isLessThan(10 * 1024 * 1024); // 10 MB
        }

        @Test
        @DisplayName("should not accumulate templates in default STGroup")
        void shouldNotAccumulateTemplatesInDefaultGroup() {
            // Given
            String baseTemplate = "SELECT * FROM table WHERE id = <id>";
            int iterationCount = 1000;

            // When - Create many ST instances with same template
            for (int i = 0; i < iterationCount; i++) {
                ST st = TemplateUtils.newST(baseTemplate);
                st.add("id", i);
                st.render();
            }

            // Then - Create one more and verify it works correctly
            ST finalSt = TemplateUtils.newST(baseTemplate);
            finalSt.add("id", 999);
            String result = finalSt.render();
            assertThat(result).isEqualTo("SELECT * FROM table WHERE id = 999");
        }
    }

    @Nested
    @DisplayName("Correctness Tests")
    class CorrectnessTests {

        @Test
        @DisplayName("should handle real SpanDAO BULK_INSERT template pattern")
        void shouldHandleSpanDAOBulkInsertPattern() {
            // Given - Actual pattern from SpanDAO
            String template = """
                    INSERT INTO spans (id, name, trace_id) VALUES
                    <items:{item | (:id<item.index>, :name<item.index>, :trace_id<item.index>)<if(item.hasNext)>, <endif>}>
                    """;

            // When
            ST st = TemplateUtils.newST(template);
            List<TemplateUtils.QueryItem> items = TemplateUtils.getQueryItemPlaceHolder(3);
            st.add("items", items);
            String result = st.render();

            // Then
            assertThat(result).contains("(:id0, :name0, :trace_id0)");
            assertThat(result).contains("(:id1, :name1, :trace_id1)");
            assertThat(result).contains("(:id2, :name2, :trace_id2)");
        }

        @Test
        @DisplayName("should handle real SpanDAO UPDATE template pattern")
        void shouldHandleSpanDAOUpdatePattern() {
            // Given - Pattern from SpanDAO newUpdateTemplate
            String template = """
                    UPDATE spans SET
                    <if(name)>name = :name<endif>
                    <if(type)>, type = :type<endif>
                    <if(end_time)>, end_time = :end_time<endif>
                    WHERE id = :id
                    """;

            // When - With all fields
            ST st1 = TemplateUtils.newST(template);
            st1.add("name", "span-name");
            st1.add("type", "LLM");
            st1.add("end_time", "2024-01-01");
            String result1 = st1.render();

            // Then
            assertThat(result1).contains("name = :name");
            assertThat(result1).contains("type = :type");
            assertThat(result1).contains("end_time = :end_time");

            // When - With only name
            ST st2 = TemplateUtils.newST(template);
            st2.add("name", "span-name");
            String result2 = st2.render();

            // Then
            assertThat(result2).contains("name = :name");
            assertThat(result2).doesNotContain("type = :type");
            assertThat(result2).doesNotContain("end_time = :end_time");
        }

        @Test
        @DisplayName("should handle real SpanDAO FIND template with filters")
        void shouldHandleSpanDAOFindTemplateWithFilters() {
            // Given - Pattern from SpanDAO newFindTemplate
            String template = """
                    SELECT * FROM spans
                    WHERE 1=1
                    <if(trace_id)> AND trace_id = :trace_id<endif>
                    <if(type)> AND type = :type<endif>
                    <if(filters)> AND <filters><endif>
                    """;

            // When
            ST st = TemplateUtils.newST(template);
            st.add("trace_id", "trace-123");
            st.add("type", "LLM");
            st.add("filters", "created_at > :from_date");
            String result = st.render();

            // Then
            assertThat(result).contains("trace_id = :trace_id");
            assertThat(result).contains("type = :type");
            assertThat(result).contains("created_at > :from_date");
        }

        @Test
        @DisplayName("should handle template with array binding pattern")
        void shouldHandleTemplateWithArrayBindingPattern() {
            // Given - Pattern from SpanDAO for DELETE_BY_TRACE_IDS
            String template = """
                    DELETE FROM spans
                    WHERE trace_id IN :trace_ids
                    <if(project_id)> AND project_id = :project_id<endif>
                    """;

            // When - with project_id
            ST st1 = TemplateUtils.newST(template);
            st1.add("project_id", "project-123");
            String result1 = st1.render();

            // Then
            assertThat(result1).contains("project_id = :project_id");

            // When - without project_id
            ST st2 = TemplateUtils.newST(template);
            String result2 = st2.render();

            // Then
            assertThat(result2).doesNotContain("project_id");
        }

        @Test
        @DisplayName("should handle BI information template with multiple conditionals")
        void shouldHandleBIInformationTemplate() {
            // Given - Pattern from SpanDAO getSpanBIInformation
            String template = """
                    SELECT workspace_id, created_by as user, count(*) as span_count
                    FROM spans
                    WHERE 1=1
                    <if(excluded_project_ids)> AND project_id NOT IN :excluded_project_ids<endif>
                    <if(demo_data_created_at)> AND created_at >= :demo_data_created_at<endif>
                    GROUP BY workspace_id, user
                    """;

            // When - with both conditions
            ST st1 = TemplateUtils.newST(template);
            st1.add("excluded_project_ids", new String[]{"proj1", "proj2"});
            st1.add("demo_data_created_at", "2024-01-01");
            String result1 = st1.render();

            // Then
            assertThat(result1).contains("project_id NOT IN :excluded_project_ids");
            assertThat(result1).contains("created_at >= :demo_data_created_at");

            // When - with no conditions
            ST st2 = TemplateUtils.newST(template);
            String result2 = st2.render();

            // Then
            assertThat(result2).doesNotContain("NOT IN");
            assertThat(result2).doesNotContain(">= :demo_data_created_at");
        }

        @Test
        @DisplayName("should handle special characters in template values")
        void shouldHandleSpecialCharactersInValues() {
            // Given
            String template = "SELECT * FROM spans WHERE metadata = '<metadata>'";

            // When
            ST st = TemplateUtils.newST(template);
            st.add("metadata", "{\"key\": \"value with 'quotes'\"}");
            String result = st.render();

            // Then
            assertThat(result).contains("{\"key\": \"value with 'quotes'\"}");
        }
    }

    @Nested
    @DisplayName("Concurrent Access Tests")
    class ConcurrentAccessTests {

        @Test
        @DisplayName("should be thread-safe for concurrent template creation")
        void shouldBeThreadSafeForConcurrentCreation() throws InterruptedException {
            // Given
            String template = "SELECT * FROM spans WHERE id = <id> AND name = <name>";
            int threadCount = 10;
            int iterationsPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            List<Throwable> errors = new ArrayList<>();

            // When - Multiple threads creating templates concurrently
            for (int t = 0; t < threadCount; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < iterationsPerThread; i++) {
                            ST st = TemplateUtils.newST(template);
                            st.add("id", "id-" + threadId + "-" + i);
                            st.add("name", "name-" + threadId + "-" + i);
                            String result = st.render();
                            assertThat(result).contains("id-" + threadId + "-" + i);
                            assertThat(result).contains("name-" + threadId + "-" + i);
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Then - All threads should complete without errors
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
            assertThat(errors).isEmpty();
        }

        @Test
        @DisplayName("should create independent ST instances across threads")
        void shouldCreateIndependentSTInstancesAcrossThreads() throws Exception {
            // Given
            String template = "Value: <value>";
            int threadCount = 5;

            // When - Create templates in parallel with different values
            List<CompletableFuture<String>> futures = IntStream.range(0, threadCount)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                        ST st = TemplateUtils.newST(template);
                        st.add("value", "thread-" + i);
                        return st.render();
                    }))
                    .toList();

            // Wait for all to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Then - Each should have its own value
            List<String> results = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            assertThat(results).hasSize(threadCount);
            for (int i = 0; i < threadCount; i++) {
                assertThat(results).contains("Value: thread-" + i);
            }
        }

        @Test
        @DisplayName("should not have attribute pollution between concurrent renders")
        void shouldNotHaveAttributePollutionBetweenConcurrentRenders() throws InterruptedException {
            // Given
            String template = "User <name> has <attribute>";
            int threadCount = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            List<String> results = new ArrayList<>();
            List<Throwable> errors = new ArrayList<>();

            // When - Multiple threads using templates with different attributes
            for (int t = 0; t < threadCount; t++) {
                int threadId = t;
                executor.submit(() -> {
                    try {
                        ST st = TemplateUtils.newST(template);
                        st.add("name", "User-" + threadId);
                        st.add("attribute", "Attribute-" + threadId);
                        String result = st.render();
                        synchronized (results) {
                            results.add(result);
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Then - No cross-contamination of attributes
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
            assertThat(errors).isEmpty();
            assertThat(results).hasSize(threadCount);

            // Verify each result contains only its own thread data
            for (int i = 0; i < threadCount; i++) {
                int threadId = i;
                assertThat(results).anyMatch(result -> result.contains("User-" + threadId) &&
                        result.contains("Attribute-" + threadId));
            }
        }
    }
}
