package com.guanxian.platform.storage;

interface ObjectStorage {
    void put(String objectKey, String mediaType, byte[] content);

    byte[] get(String objectKey);

    void delete(String objectKey);
}
