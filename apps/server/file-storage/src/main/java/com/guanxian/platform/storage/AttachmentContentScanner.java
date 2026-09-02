package com.guanxian.platform.storage;

/** Security boundary invoked before attachment metadata becomes available. */
public interface AttachmentContentScanner {
    void assertClean(byte[] content);
    String scannerName();
}
