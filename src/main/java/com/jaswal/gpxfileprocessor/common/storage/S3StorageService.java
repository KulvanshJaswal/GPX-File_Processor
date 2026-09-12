package com.jaswal.gpxfileprocessor.common.storage;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketLocationConstraint;
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;

// Credentials are never configured here on purpose — no S3Client.builder().credentialsProvider(...)
// call means the SDK's DefaultCredentialsProvider chain is used, which picks up the IAM role
// attached to the EC2/Beanstalk instance at runtime. No access keys anywhere.
@Service
@Profile("prod")
public class S3StorageService implements FileStorageService {

    @Value("${s3.bucket-name}")
    private String bucketName;

    @Value("${s3.region}")
    private String region;

    private S3Client s3Client;

    @PostConstruct
    public void init() {
        s3Client = S3Client.builder()
                .region(Region.of(region))
                .build();
    }

    @Override
    public void upload(String objectKey, InputStream data, long size, String contentType) throws Exception {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(objectKey)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromInputStream(data, size));
    }

    @Override
    public InputStream download(String objectKey) throws Exception {
        return s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .build());
    }

    @Override
    public void ensureBucketExists() throws Exception {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
        } catch (NoSuchBucketException e) {
            s3Client.createBucket(CreateBucketRequest.builder()
                    .bucket(bucketName)
                    .createBucketConfiguration(CreateBucketConfiguration.builder()
                            .locationConstraint(BucketLocationConstraint.fromValue(region))
                            .build())
                    .build());
        }
    }
}
