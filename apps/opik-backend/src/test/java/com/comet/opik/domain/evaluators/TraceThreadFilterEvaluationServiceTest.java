package com.comet.opik.domain.evaluators;

import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.domain.threads.TraceThreadModel;
import com.comet.opik.podam.PodamFactoryUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;

@DisplayName("Trace Thread Filter Evaluation Service Test")
class TraceThreadFilterEvaluationServiceTest {

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private final TraceThreadFilterEvaluationService traceThreadFilterEvaluationService = new TraceThreadFilterEvaluationService(
            mock(ObjectMapper.class));

    static Stream<Arguments> statusStartsOrEndsWithTestCases() {
        return Stream.of(
                arguments(TraceThreadStatus.INACTIVE, Operator.STARTS_WITH, "in", true),
                arguments(TraceThreadStatus.ACTIVE, Operator.STARTS_WITH, "in", false),
                arguments(TraceThreadStatus.ACTIVE, Operator.ENDS_WITH, "Tive", true),
                arguments(TraceThreadStatus.ACTIVE, Operator.ENDS_WITH, "act", false));
    }

    @ParameterizedTest
    @MethodSource("statusStartsOrEndsWithTestCases")
    void matchesFilterWithStatusStartsOrEndsWith(TraceThreadStatus status, Operator operator, String filterValue,
            boolean expectedResult) {
        // Given
        var thread = podamFactory.manufacturePojo(TraceThreadModel.class).toBuilder()
                .status(status)
                .build();
        var filter = TraceThreadFilter.builder()
                .field(TraceThreadField.STATUS)
                .operator(operator)
                .value(filterValue)
                .build();

        // When
        var result = traceThreadFilterEvaluationService.matchesFilter(filter, thread);

        // Then
        assertThat(result).isEqualTo(expectedResult);
    }

    static Stream<Arguments> tagsStartsOrEndsWithTestCases() {
        return Stream.of(
                arguments(Operator.STARTS_WITH, "VIP", true),
                arguments(Operator.STARTS_WITH, "customer", false),
                arguments(Operator.ENDS_WITH, "Customer", true),
                arguments(Operator.ENDS_WITH, "vip", false));
    }

    @ParameterizedTest
    @MethodSource("tagsStartsOrEndsWithTestCases")
    void matchesFilterWithTagsStartsOrEndsWith(Operator operator, String filterValue, boolean expectedResult) {
        // Given
        var thread = podamFactory.manufacturePojo(TraceThreadModel.class).toBuilder()
                .tags(Set.of("vip-customer", "support"))
                .build();
        var filter = TraceThreadFilter.builder()
                .field(TraceThreadField.TAGS)
                .operator(operator)
                .value(filterValue)
                .build();

        // When
        var result = traceThreadFilterEvaluationService.matchesFilter(filter, thread);

        // Then
        assertThat(result).isEqualTo(expectedResult);
    }
}
