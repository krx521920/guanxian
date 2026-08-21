package com.guanxian.platform.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@ConditionalOnProperty(name = "guanxian.storage.backend", havingValue = "memory", matchIfMissing = true)
final class InMemoryObjectStorage implements ObjectStorage {
    private final ConcurrentMap<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String objectKey, String mediaType, byte[] content) {
        byte[] copy = Arrays.copyOf(content, content.length);
        if (objects.putIfAbsent(objectKey, copy) != null) {
            throw new IllegalStateException("duplicate object key");
        }
    }

    @Override
    public byte[] get(String objectKey) {
        byte[] content = objects.get(objectKey);
        if (content == null) {
            throw new StorageUnavailableException("stored object is missing");
        }
        return Arrays.copyOf(content, content.length);
    }
}
