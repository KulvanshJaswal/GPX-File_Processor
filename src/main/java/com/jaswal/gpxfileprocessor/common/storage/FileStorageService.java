package com.jaswal.gpxfileprocessor.common.storage;

import java.io.InputStream;

public interface FileStorageService {
    void upload(String objectKey, InputStream data, long size, String contentType) throws Exception;
    InputStream download(String objectKey) throws Exception;
    void ensureBucketExists() throws Exception;
}
