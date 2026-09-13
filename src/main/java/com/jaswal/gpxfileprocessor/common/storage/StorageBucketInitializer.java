package com.jaswal.gpxfileprocessor.common.storage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class StorageBucketInitializer implements CommandLineRunner {

    @Autowired
    private FileStorageService fileStorageService;

    @Override
    public void run(String... args) throws Exception {
        fileStorageService.ensureBucketExists();
    }
}
