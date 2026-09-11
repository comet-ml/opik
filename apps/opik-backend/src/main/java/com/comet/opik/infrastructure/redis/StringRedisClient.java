package com.comet.opik.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import org.redisson.api.BatchOptions;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBatch;
import org.redisson.api.RBlockingDeque;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RBucket;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

@RequiredArgsConstructor
public class StringRedisClient {

    private final RedissonClient syncClient;

    public RBucket<String> getBucket(String name) {
        return syncClient.getBucket(name, StringCodec.INSTANCE);
    }

    public <K, V> RMap<K, V> getMap(String name) {
        return syncClient.getMap(name, StringCodec.INSTANCE);
    }

    public <V> RList<V> getList(String name) {
        return syncClient.getList(name, StringCodec.INSTANCE);
    }

    public <V> RSet<V> getSet(String name) {
        return syncClient.getSet(name, StringCodec.INSTANCE);
    }

    public <V> RScoredSortedSet<V> getScoredSortedSet(String name) {
        return syncClient.getScoredSortedSet(name, StringCodec.INSTANCE);
    }

    public <V> RBlockingDeque<V> getBlockingDeque(String name) {
        return syncClient.getBlockingDeque(name, StringCodec.INSTANCE);
    }

    public <V> RBlockingQueue<V> getBlockingQueue(String name) {
        return syncClient.getBlockingQueue(name, StringCodec.INSTANCE);
    }

    public RAtomicLong getAtomicLong(String name) {
        return syncClient.getAtomicLong(name);
    }

    public RBatch createBatch() {
        return syncClient.createBatch(BatchOptions.defaults());
    }

    public long deleteKeys(String... keys) {
        return syncClient.getKeys().delete(keys);
    }
}
