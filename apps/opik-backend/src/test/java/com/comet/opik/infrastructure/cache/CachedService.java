package com.comet.opik.infrastructure.cache;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@Slf4j
public class CachedService {

    public record DTO(String id, String workspaceId, String value) {
    }

    // Non-reactive methods

    @Cacheable(name = CacheManagerTest.CACHE_NAME_1, key = "$id +'-'+ $workspaceId", returnType = DTO.class)
    public DTO get(String id, String workspaceId) {
        return new DTO(id, workspaceId, UUID.randomUUID().toString());
    }

    @CachePut(name = CacheManagerTest.CACHE_NAME_1, key = "$id +'-'+ $workspaceId")
    public DTO update(String id, String workspaceId, DTO dto) {
        return new DTO(id, workspaceId, dto.value());
    }

    @CacheEvict(name = CacheManagerTest.CACHE_NAME_1, key = "$id +'-'+ $workspaceId")
    public void evict(String id, String workspaceId) {
    }

    // Reactive methods (Mono)

    @Cacheable(name = CacheManagerTest.CACHE_NAME_2, key = "$id +'-'+ $workspaceId", returnType = DTO.class)
    public Mono<DTO> get2(String id, String workspaceId) {
        return Mono.just(new DTO(id, workspaceId, UUID.randomUUID().toString()));
    }

    @CachePut(name = CacheManagerTest.CACHE_NAME_2, key = "$id +'-'+ $workspaceId")
    public Mono<DTO> update2(String id, String workspaceId, DTO dto) {
        return Mono.just(new DTO(id, workspaceId, dto.value()));
    }

    @CacheEvict(name = CacheManagerTest.CACHE_NAME_2, key = "$id +'-'+ $workspaceId")
    public Mono<Void> evict2(String id, String workspaceId) {
        return Mono.empty();
    }

    @CacheEvict(name = CacheManagerTest.CACHE_NAME_2, key = "$id +'-'+ $workspaceId")
    public Mono<DTO> evict2WithValue(String id, String workspaceId) {
        return Mono.just(new DTO(id, workspaceId, UUID.randomUUID().toString()));
    }

    // Exception handling methods

    @Cacheable(name = CacheManagerTest.CACHE_NAME_1, key = "$id-$workspaceId", returnType = DTO.class)
    public DTO getWithKeyInvalidExpression(String id, String workspaceId) {
        return new DTO(id, workspaceId, UUID.randomUUID().toString());
    }

    @Cacheable(name = CacheManagerTest.CACHE_NAME_1, key = "$id + '-' + $workspaceId", returnType = DTO.class)
    public DTO getWithException(String id, String workspaceId) {
        throw new IndexOutOfBoundsException("Simulate runtime exception");
    }

    @CachePut(name = CacheManagerTest.CACHE_NAME_1, key = "$id +'-'+ $workspaceId")
    public DTO updateWithException(String id, String workspaceId, DTO dto) {
        throw new IllegalStateException("Simulate runtime exception on update");
    }

    @CacheEvict(name = CacheManagerTest.CACHE_NAME_1, key = "$id +'-'+ $workspaceId")
    public void evictWithException(String id, String workspaceId) {
        throw new UnsupportedOperationException("Simulate runtime exception on evict");
    }

    @Cacheable(name = CacheManagerTest.CACHE_NAME_2, key = "$id-$workspaceId", returnType = DTO.class)
    public Mono<DTO> get2WithInvalidKeyExpression(String id, String workspaceId) {
        return Mono.just(new DTO(id, workspaceId, UUID.randomUUID().toString()));
    }

    @Cacheable(name = CacheManagerTest.CACHE_NAME_2, key = "$id +'-'+ $workspaceId", returnType = DTO.class)
    public Mono<DTO> get2WithException(String id, String workspaceId) {
        return Mono.error(new IndexOutOfBoundsException("Simulate runtime exception"));
    }

    @Cacheable(name = CacheManagerTest.CACHE_NAME_1, key = "$id +'-'+ $workspaceId", returnType = DTO.class)
    public Flux<DTO> getFluxWithException(String id, String workspaceId) {
        return Flux.error(new IndexOutOfBoundsException("Simulate runtime exception in Flux"));
    }

    @CachePut(name = CacheManagerTest.CACHE_NAME_2, key = "$id +'-'+ $workspaceId")
    public Mono<DTO> update2WithException(String id, String workspaceId, DTO dto) {
        return Mono.error(new IllegalStateException("Simulate runtime exception on update"));
    }

    @CacheEvict(name = CacheManagerTest.CACHE_NAME_2, key = "$id +'-'+ $workspaceId")
    public Mono<Void> evict2WithException(String id, String workspaceId) {
        return Mono.error(new UnsupportedOperationException("Simulate runtime exception on evict"));
    }

    // Collection methods

    @Cacheable(name = CacheManagerTest.CACHE_NAME_1, key = "$id +'-'+ $workspaceId", returnType = DTO.class, wrapperType = List.class)
    public List<DTO> getCollection(String id, String workspaceId) {
        return List.of(new DTO(id, workspaceId, UUID.randomUUID().toString()));
    }

    @Cacheable(name = CacheManagerTest.CACHE_NAME_2, key = "$id +'-'+ $workspaceId", returnType = DTO.class, wrapperType = List.class)
    public Mono<List<DTO>> getCollection2(String id, String workspaceId) {
        return Mono.just(List.of(new DTO(id, workspaceId, UUID.randomUUID().toString())));
    }

    // Reactive methods (Flux)

    @Cacheable(name = CacheManagerTest.CACHE_NAME_1, key = "$id +'-'+ $workspaceId", returnType = DTO.class)
    public Flux<DTO> getFlux(String id, String workspaceId) {
        String value = UUID.randomUUID().toString();
        String value2 = UUID.randomUUID().toString();
        log.info("getFlux: id={}, workspaceId={}, value={}, value2={}", id, workspaceId, value, value2);
        return Flux.just(new DTO(id, workspaceId, value), new DTO(id, workspaceId, value2));
    }

    @Cacheable(name = CacheManagerTest.CACHE_NAME_2, key = "$id +'-'+ $workspaceId", returnType = DTO.class, wrapperType = List.class)
    public Flux<List<DTO>> getFlux2(String id, String workspaceId) {
        return Flux.just(
                List.of(new DTO(id, workspaceId, UUID.randomUUID().toString())),
                List.of(new DTO(id, workspaceId, UUID.randomUUID().toString())));
    }

    // Methods to test null/empty values are not cached

    @Cacheable(name = CacheManagerTest.CACHE_NAME_1, key = "$id +'-'+ $workspaceId", returnType = DTO.class)
    public DTO getWithNullValue(String id, String workspaceId) {
        log.info("getWithNullValue called for id={}, workspaceId={}", id, workspaceId);
        return null;
    }

    @CachePut(name = CacheManagerTest.CACHE_NAME_1, key = "$id +'-'+ $workspaceId")
    public DTO updateWithNullValue(String id, String workspaceId, DTO dto) {
        log.info("updateWithNullValue called for id={}, workspaceId={}", id, workspaceId);
        return null;
    }

    @Cacheable(name = CacheManagerTest.CACHE_NAME_2, key = "$id +'-'+ $workspaceId", returnType = DTO.class)
    public Mono<DTO> get2WithEmptyValue(String id, String workspaceId) {
        log.info("get2WithEmptyValue called for id={}, workspaceId={}", id, workspaceId);
        return Mono.empty();
    }

    @CachePut(name = CacheManagerTest.CACHE_NAME_2, key = "$id +'-'+ $workspaceId")
    public Mono<DTO> update2WithEmptyValue(String id, String workspaceId, DTO dto) {
        log.info("update2WithEmptyValue called for id={}, workspaceId={}", id, workspaceId);
        return Mono.empty();
    }

    @Cacheable(name = CacheManagerTest.CACHE_NAME_1, key = "$id +'-'+ $workspaceId", returnType = DTO.class)
    public Flux<DTO> getFluxWithEmptyValue(String id, String workspaceId) {
        log.info("getFluxWithEmptyValue called for id={}, workspaceId={}", id, workspaceId);
        return Flux.empty();
    }

    // Methods to test nested cache annotation (overloaded methods both with @Cacheable)
    // This regresses a fixed bug found in AutomationRuleEvaluatorService

    /**
     * 2-parameter overloaded method with @Cacheable that delegates to 3-parameter version.
     * This creates a nested cache operation that previously caused Redis timeout/deadlock.
     * Generally, it's not desirable to have @Cacheable annotation when delegating to another cached method.
     */
    @Cacheable(name = CacheManagerTest.CACHE_NAME_1, key = "$id +'-'+ $workspaceId", returnType = DTO.class, wrapperType = List.class)
    public List<DTO> getOverloadedWithNestedCache(String id, String workspaceId) {
        return getOverloadedWithNestedCache(id, workspaceId, null);
    }

    /**
     * 3-parameter overloaded method with @Cacheable that has the actual implementation.
     * This is the method that actually does the work.
     */
    @Cacheable(name = CacheManagerTest.CACHE_NAME_1, key = "$id +'-'+ $workspaceId + '-' + ($type != null ? $type : 'all')", returnType = DTO.class, wrapperType = List.class)
    public List<DTO> getOverloadedWithNestedCache(String id, String workspaceId, String type) {
        log.info("getOverloadedWithNestedCache: id={}, workspaceId={}, type={}", id, workspaceId, type);
        return List.of(new DTO(id, workspaceId, UUID.randomUUID().toString()));
    }
}
