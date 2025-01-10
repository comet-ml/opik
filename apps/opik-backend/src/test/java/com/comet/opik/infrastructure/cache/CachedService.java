package com.comet.opik.infrastructure.cache;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Slf4j
public class CachedService {

    public record DTO(String id, String workspaceId, String value) {
    }

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

    @Cacheable(name = CacheManagerTest.CACHE_NAME_1, key = "$id-$workspaceId", returnType = DTO.class)
    public DTO getWithKeyInvalidExpression(String id, String workspaceId) {
        return new DTO(id, workspaceId, UUID.randomUUID().toString());
    }

    @Cacheable(name = CacheManagerTest.CACHE_NAME_1, key = "$id + '-' + $workspaceId", returnType = DTO.class)
    public DTO getWithException(String id, String workspaceId) {
        throw new IndexOutOfBoundsException("Simulate runtime exception");
    }

    @Cacheable(name = CacheManagerTest.CACHE_NAME_2, key = "$id-$workspaceId", returnType = DTO.class)
    public Mono<DTO> get2WithInvalidKeyExpression(String id, String workspaceId) {
        return Mono.just(new DTO(id, workspaceId, UUID.randomUUID().toString()));
    }

    @Cacheable(name = CacheManagerTest.CACHE_NAME_2, key = "$id +'-'+ $workspaceId", returnType = DTO.class)
    public Mono<DTO> get2WithException(String id, String workspaceId) {
        return Mono.error(new IndexOutOfBoundsException("Simulate runtime exception"));
    }

}
