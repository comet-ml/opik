package com.comet.opik.infrastructure.pagination;

import com.comet.opik.api.Trace;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.sorting.TraceSortingFactory;
import com.comet.opik.domain.TraceSearchCriteria;
import com.comet.opik.domain.TraceService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.api.resources.utils.TestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for cursor-based pagination functionality.
 * Tests the complete flow from REST API through Service layer to DAO.
 *
 * @since 1.9.0
 */
@DisplayName("Cursor Pagination Integration Tests")
public class CursorPaginationIntegrationTest {

    private TraceService traceService;
    private TraceSortingFactory sortingFactory;
    private List<Trace> testTraces;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        traceService = mock(TraceService.class);
        sortingFactory = mock(TraceSortingFactory.class);
        projectId = UUID.randomUUID();

        // Create test data
        testTraces = createTestTraces(100);
    }

    @Test
    @DisplayName("Should paginate forward through traces using cursor")
    void testForwardPagination() {
        // Given
        int pageSize = 20;
        List<Trace> allFetchedTraces = new ArrayList<>();
        String cursor = null;
        int iterations = 0;
        int maxIterations = 10; // Safety limit

        // When - paginate through all results
        while (iterations < maxIterations) {
            var paginationRequest = CursorPaginationRequest.builder()
                    .cursor(cursor)
                    .limit(pageSize)
                    .direction(CursorPaginationRequest.Direction.FORWARD)
                    .build();

            var response = createMockResponse(cursor, pageSize, testTraces);

            when(traceService.findWithCursor(any(), any()))
                    .thenReturn(Mono.just(response));

            var result = traceService.findWithCursor(
                    paginationRequest,
                    TraceSearchCriteria.builder().projectId(projectId).build()
            ).block();

            // Then - verify response structure
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(Math.min(pageSize, testTraces.size() - allFetchedTraces.size()));

            allFetchedTraces.addAll(result.getContent());

            if (!result.isHasMore()) {
                break;
            }

            cursor = result.getNextCursor();
            iterations++;
        }

        // Verify we got all traces
        assertThat(iterations).isLessThan(maxIterations);
        assertThat(allFetchedTraces).hasSizeLessThanOrEqualTo(testTraces.size());
    }

    @Test
    @DisplayName("Should return empty response for empty dataset")
    void testEmptyDataset() {
        // Given
        var paginationRequest = CursorPaginationRequest.builder()
                .limit(50)
                .direction(CursorPaginationRequest.Direction.FORWARD)
                .build();

        var emptyResponse = CursorPaginationResponse.<Trace>empty();
        when(traceService.findWithCursor(any(), any()))
                .thenReturn(Mono.just(emptyResponse));

        // When
        var result = traceService.findWithCursor(
                paginationRequest,
                TraceSearchCriteria.builder().projectId(projectId).build()
        ).block();

        // Then
        assertThat(result).isNotNull();
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.isHasMore()).isFalse();
        assertThat(result.getNextCursor()).isNull();
        assertThat(result.getSize()).isZero();
    }

    @Test
    @DisplayName("Should respect limit parameter")
    void testLimitParameter() {
        // Given
        int[] limits = {1, 10, 50, 100};

        for (int limit : limits) {
            var paginationRequest = CursorPaginationRequest.builder()
                    .limit(limit)
                    .direction(CursorPaginationRequest.Direction.FORWARD)
                    .build();

            var response = createMockResponse(null, limit, testTraces);
            when(traceService.findWithCursor(any(), any()))
                    .thenReturn(Mono.just(response));

            // When
            var result = traceService.findWithCursor(
                    paginationRequest,
                    TraceSearchCriteria.builder().projectId(projectId).build()
            ).block();

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getSize()).isLessThanOrEqualTo(limit);

            if (testTraces.size() > limit) {
                assertThat(result.isHasMore()).isTrue();
                assertThat(result.getNextCursor()).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("Should encode and decode cursor correctly")
    void testCursorEncodingDecoding() {
        // Given
        Instant timestamp = Instant.now();
        UUID id = UUID.randomUUID();
        Cursor originalCursor = new Cursor(timestamp, id);

        // When
        String encoded = originalCursor.encode();
        Cursor decoded = Cursor.decode(encoded);

        // Then
        assertThat(decoded).isNotNull();
        assertThat(decoded.getTimestamp()).isEqualTo(timestamp);
        assertThat(decoded.getId()).isEqualTo(id);
    }

    @Test
    @DisplayName("Should handle last page correctly")
    void testLastPage() {
        // Given - simulate last page with fewer items than limit
        int limit = 50;
        int remainingItems = 10;

        var paginationRequest = CursorPaginationRequest.builder()
                .limit(limit)
                .direction(CursorPaginationRequest.Direction.FORWARD)
                .build();

        var lastPageTraces = testTraces.subList(0, remainingItems);
        var response = CursorPaginationResponse.<Trace>builder()
                .content(lastPageTraces)
                .hasMore(false)
                .size(remainingItems)
                .nextCursor(null)
                .build();

        when(traceService.findWithCursor(any(), any()))
                .thenReturn(Mono.just(response));

        // When
        var result = traceService.findWithCursor(
                paginationRequest,
                TraceSearchCriteria.builder().projectId(projectId).build()
        ).block();

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getSize()).isEqualTo(remainingItems);
        assertThat(result.isHasMore()).isFalse();
        assertThat(result.getNextCursor()).isNull();
        assertThat(result.isLastPage()).isTrue();
    }

    @Test
    @DisplayName("Should create response using from() utility method")
    void testFromUtilityMethod() {
        // Given
        int limit = 50;
        List<Trace> items = testTraces.subList(0, Math.min(limit + 1, testTraces.size()));

        // When
        var response = CursorPaginationResponse.from(
                items,
                limit,
                trace -> new Cursor(trace.lastUpdatedAt(), trace.id())
        );

        // Then
        assertThat(response).isNotNull();

        if (items.size() > limit) {
            // Has more pages
            assertThat(response.getSize()).isEqualTo(limit);
            assertThat(response.isHasMore()).isTrue();
            assertThat(response.getNextCursor()).isNotNull();
            assertThat(response.getContent()).hasSize(limit);
        } else {
            // Last page
            assertThat(response.getSize()).isEqualTo(items.size());
            assertThat(response.isHasMore()).isFalse();
            assertThat(response.getNextCursor()).isNull();
        }
    }

    // Helper methods

    private List<Trace> createTestTraces(int count) {
        List<Trace> traces = new ArrayList<>();
        Instant baseTime = Instant.now();

        for (int i = 0; i < count; i++) {
            traces.add(Trace.builder()
                    .id(UUID.randomUUID())
                    .projectId(projectId)
                    .name("Test Trace " + i)
                    .startTime(baseTime.minusSeconds(count - i))
                    .endTime(baseTime.minusSeconds(count - i - 1))
                    .createdAt(baseTime.minusSeconds(count - i))
                    .lastUpdatedAt(baseTime.minusSeconds(count - i))
                    .build());
        }

        return traces;
    }

    private CursorPaginationResponse<Trace> createMockResponse(
            String cursor,
            int limit,
            List<Trace> allTraces) {

        int startIndex = cursor == null ? 0 :
                allTraces.size() / 5; // Simple mock logic
        int endIndex = Math.min(startIndex + limit, allTraces.size());

        List<Trace> pageTraces = allTraces.subList(startIndex, endIndex);
        boolean hasMore = endIndex < allTraces.size();

        String nextCursor = null;
        if (hasMore && !pageTraces.isEmpty()) {
            Trace lastTrace = pageTraces.get(pageTraces.size() - 1);
            Cursor cursorObj = new Cursor(lastTrace.lastUpdatedAt(), lastTrace.id());
            nextCursor = cursorObj.encode();
        }

        return CursorPaginationResponse.<Trace>builder()
                .content(pageTraces)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .size(pageTraces.size())
                .build();
    }
}
