package com.comet.opik.utils.template;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.stringtemplate.v4.ST;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.withinPercentage;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@Slf4j
class TemplateUtilsTest {

    private static final int BYTES_PER_MB = 1024 * 1024;

    private static final int TOTAL_TEMPLATES = 50_000;

    private static final String BATCH_INSERT_TEMPLATE = """
            INSERT INTO spans(
                id,
                project_id,
                workspace_id,
                trace_id,
                parent_span_id,
                name,
                type,
                start_time,
                end_time,
                input,
                output,
                metadata,
                model,
                provider,
                total_estimated_cost,
                total_estimated_cost_version,
                tags,
                usage,
                last_updated_at,
                error_info,
                created_by,
                last_updated_by
            ) VALUES
                <items:{item |
                    (
                        :id<item.index>,
                        :project_id<item.index>,
                        :workspace_id,
                        :trace_id<item.index>,
                        :parent_span_id<item.index>,
                        :name<item.index>,
                        :type<item.index>,
                        parseDateTime64BestEffort(:start_time<item.index>, 9),
                        if(:end_time<item.index> IS NULL, NULL, parseDateTime64BestEffort(:end_time<item.index>, 9)),
                        :input<item.index>,
                        :output<item.index>,
                        :metadata<item.index>,
                        :model<item.index>,
                        :provider<item.index>,
                        toDecimal128(:total_estimated_cost<item.index>, 12),
                        :total_estimated_cost_version<item.index>,
                        :tags<item.index>,
                        mapFromArrays(:usage_keys<item.index>, :usage_values<item.index>),
                        if(:last_updated_at<item.index> IS NULL, NULL, parseDateTime64BestEffort(:last_updated_at<item.index>, 6)),
                        :error_info<item.index>,
                        :created_by<item.index>,
                        :last_updated_by<item.index>
                    )
                    <if(item.hasNext)>,<endif>
                }>
            ;
            """;

    @Nested
    class GetQueryItemPlaceHolderTests {

        @Test
        void handleItems() {
            var expectedRendered = """
                    INSERT INTO spans (id, name) VALUES
                    (:id0, :name0), (:id1, :name1), (:id2, :name2)
                    """;

            var template = """
                    INSERT INTO spans (id, name) VALUES
                    <items:{item | (:id<item.index>, :name<item.index>)<if(item.hasNext)>, <endif>}>
                    """;
            var items = TemplateUtils.getQueryItemPlaceHolder(3);
            var st = TemplateUtils.newST(template);
            st.add("items", items);
            var actualRendered = st.render();

            assertThat(actualRendered).isEqualTo(expectedRendered);
        }
    }

    @Nested
    class NewSTTests {

        @Test
        void createNewST() {
            var name = RandomStringUtils.secure().nextAlphanumeric(10);
            var email = RandomStringUtils.secure().nextAlphanumeric(10);
            var age = RandomUtils.secure().randomInt();
            var expectedRendered = "User %s has email %s and age %d".formatted(name, email, age);

            var template = "User <name> has email <email> and age <age>";
            var st = TemplateUtils.newST(template);
            st.add("name", name);
            st.add("email", email);
            st.add("age", age);
            var actualRendered = st.render();

            assertThat(actualRendered).isEqualTo(expectedRendered);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        void handleBlankTemplate(String template) {
            var st = TemplateUtils.newST(template);
            String result = st.render();

            assertThat(result).isEqualTo(template);
        }

        @Test
        void throwNullPointerExceptionOnNullTemplate() {
            assertThatThrownBy(() -> TemplateUtils.newST(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class NewSTMemoryLeakTests {

        static Stream<Arguments> verifyMemory() {
            Function<String, ST> factoryMethod = TemplateUtils::newST;
            Function<String, ST> directConstructor = ST::new;
            return Stream.of(
                    arguments(
                            "allowGCAndNotCauseMemoryLeak",
                            factoryMethod,
                            // The majority of ST should be collected
                            TOTAL_TEMPLATES,
                            // Growth is about 5MB in local isolated runs
                            // But allowing 1 to 100 MB range for other environments and running with other tests
                            1,
                            100),
                    arguments(
                            "preventGCAndCauseMemoryLeak",
                            directConstructor,
                            // Growth is about 2650 MB in local isolated runs
                            // But allowing 2 to 3 GB range for other environments and running with other tests
                            TOTAL_TEMPLATES,
                            2000,
                            3000));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource
        @Disabled("This is a non functional test for template memory leak investigation, no need to run in CI")
        void verifyMemory(
                String testName,
                Function<String, ST> templateFactory,
                long expectedGCCount,
                long expectedUsedMemoryGrowthInMBMin,
                long expectedUsedMemoryGrowthInMBMax) {
            var weakReferences = new ArrayList<WeakReference<ST>>();

            // Suggest GC before test to start in a clean state
            System.gc();
            var usedMemoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            logMemoryUsage(testName, "before template creation");
            for (var i = 0; i < TOTAL_TEMPLATES; i++) {
                var st = templateFactory.apply(BATCH_INSERT_TEMPLATE);
                st.add("items", List.of());
                st.render();
                // ST instance goes out of scope and should be GC-able, as weak reference doesn't prevent GC
                weakReferences.add(new WeakReference<>(st));
            }

            // Suggest GC after test
            logMemoryUsage(testName, "after template creation");
            System.gc();
            logMemoryUsage(testName, "after GC");

            // Wait for GC to actually collect the objects and verify memory assertions
            Awaitility.await()
                    .atMost(10, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        var actualGCCount = weakReferences.stream()
                                .filter(ref -> ref.get() == null)
                                .count();
                        var usedMemoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                        var actualUsedMemoryGrowthInMB = (usedMemoryAfter - usedMemoryBefore) / BYTES_PER_MB;

                        log.info("{} - GC collected: {}", testName, actualGCCount);
                        log.info("{} - Memory growth: {} MB", testName, actualUsedMemoryGrowthInMB);

                        // The amount of GC-ed instances shouldn't vary much
                        assertThat(actualGCCount).isCloseTo(expectedGCCount, withinPercentage(10));
                        // Using a range, as it may vary based on the environment or if running test in isolation or with others
                        assertThat(actualUsedMemoryGrowthInMB)
                                .isBetween(expectedUsedMemoryGrowthInMBMin, expectedUsedMemoryGrowthInMBMax);
                    });
        }

    }

    private void logMemoryUsage(String testName, String header) {
        log.info("== {}: {} ==", testName, header);
        var totalMemory = Runtime.getRuntime().totalMemory();
        var freeMemory = Runtime.getRuntime().freeMemory();
        var usedMemory = totalMemory - freeMemory;
        log.info("Total Memory (MB): {}", totalMemory / BYTES_PER_MB);
        log.info("Free Memory (MB): {}", freeMemory / BYTES_PER_MB);
        log.info("Used Memory (MB): {}", usedMemory / BYTES_PER_MB);
    }
}
