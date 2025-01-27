package com.comet.opik.domain;

import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.error.InvalidUUIDVersionException;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.podam.PodamFactoryUtils;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.google.common.testing.NullPointerTester;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@Disabled
class SpanServiceTest {

    private static final LockService DUMMY_LOCK_SERVICE = new DummyLockService();

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private final SpanDAO spanDAO = mock(SpanDAO.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final CommentService commentService = mock(CommentService.class);;

    private final TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator();
    private final SpanService spanService = new SpanService(spanDAO, projectService, generator::generate,
            DUMMY_LOCK_SERVICE, commentService);

    @Test
    void allPublicConstructors() {
        var nullPointerTester = new NullPointerTester();
        nullPointerTester.testAllPublicConstructors(SpanService.class);
    }

    @Test
    void allPublicInstanceMethods() {
        var nullPointerTester = new NullPointerTester();
        nullPointerTester.setDefault(SpanUpdate.class, SpanUpdate.builder().build());
        nullPointerTester.testAllPublicInstanceMethods(spanService);
    }

    @Test
    void findByTraceId() {
        var traceId = generator.generate();
        var spanModels = PodamFactoryUtils.manufacturePojoList(podamFactory, SpanModel.class)
                .stream()
                .map(span -> span.toBuilder().traceId(traceId).build())
                .toList();
        var expectedSpans = SpanMapper.INSTANCE.toSpan(spanModels);
    }

    @Test
    void getById() {
        var spanModel = podamFactory.manufacturePojo(SpanModel.class);

        var id = spanModel.id();
        var expectedSpan = SpanMapper.INSTANCE.toSpan(spanModel);

        var actualSpan = spanService.getById(id).block();

        assertThat(actualSpan).isEqualTo(expectedSpan);
        verify(spanDAO).getById(id);
    }

    @Test
    void getByIdReturnsThrowsNotFoundException() {
        var id = generator.generate();
        when(spanDAO.getById(id)).thenReturn(Mono.empty());

        assertThatThrownBy(() -> spanService.getById(id).block())
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Not found span with id '%s'", id);
        verify(spanDAO).getById(id);
    }

    @Test
    void create() {
        var expectedSpanModelInsert = podamFactory.manufacturePojo(SpanModel.class);
        var span = SpanMapper.INSTANCE.toSpan(expectedSpanModelInsert);
        when(spanDAO.insert(any())).thenReturn(Mono.empty());

        spanService.create(span).block();

        verify(spanDAO).insert(argThat(actualSpanModelInsert -> {
            assertThat(actualSpanModelInsert).usingRecursiveComparison()
                    .ignoringFields("createdAt", "lastUpdatedAt")
                    .isEqualTo(expectedSpanModelInsert);
            assertThat(actualSpanModelInsert.createdAt()).isAfter(expectedSpanModelInsert.createdAt());
            assertThat(actualSpanModelInsert.lastUpdatedAt()).isAfter(expectedSpanModelInsert.lastUpdatedAt());
            return true;
        }));
    }

    @Test
    void create__whenCreatingSpanWithUUIDVersionDifferentFrom7__thenReturnError() {
        var expectedSpanModelInsert = podamFactory.manufacturePojo(SpanModel.class);

        var span = SpanMapper.INSTANCE.toSpan(expectedSpanModelInsert);

        assertThatThrownBy(() -> spanService.create(span).block())
                .isInstanceOf(InvalidUUIDVersionException.class);

    }

    @Test
    void update() {
        var spanModelGet = podamFactory.manufacturePojo(SpanModel.class);
        var id = spanModelGet.id();
        var spanUpdate = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder().build();
        var spanModelBuilder = spanModelGet.toBuilder();
        SpanMapper.INSTANCE.updateSpanModelBuilder(spanModelBuilder, spanUpdate);
        var expectedSpanModelInsert = spanModelBuilder.build();
        when(spanDAO.insert(any())).thenReturn(Mono.empty());

        spanService.update(id, spanUpdate).block();

        verify(spanDAO).getById(id);
        verify(spanDAO).insert(argThat(actualSpanModelInsert -> {
            assertThat(actualSpanModelInsert).usingRecursiveComparison()
                    .ignoringFields("lastUpdatedAt")
                    .isEqualTo(expectedSpanModelInsert);
            assertThat(actualSpanModelInsert.name()).isEqualTo(spanModelGet.name());
            assertThat(actualSpanModelInsert.lastUpdatedAt()).isAfter(spanModelGet.lastUpdatedAt());
            return true;
        }));
    }

    @Test
    void updateThrowsNotFoundException() {
        var id = generator.generate();
        var spanUpdate = podamFactory.manufacturePojo(SpanUpdate.class);
        when(spanDAO.getById(id)).thenReturn(Mono.empty());
        when(spanDAO.insert(any())).thenReturn(Mono.empty());

        assertThatThrownBy(() -> spanService.update(id, spanUpdate).block())
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Not found span with id '%s'", id);
        verify(spanDAO).getById(id);
        verifyNoMoreInteractions(spanDAO);
    }
}
