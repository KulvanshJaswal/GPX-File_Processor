package com.jaswal.gpxfileprocessor.upload;

import com.jaswal.gpxfileprocessor.common.config.RabbitMQConfig;
import com.jaswal.gpxfileprocessor.common.entity.JobEntity;
import com.jaswal.gpxfileprocessor.common.entity.JobStatus;
import com.jaswal.gpxfileprocessor.common.exception.FileStorageException;
import com.jaswal.gpxfileprocessor.common.exception.InvalidGpxFileException;
import com.jaswal.gpxfileprocessor.common.repository.JobRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.Getter;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

@Service
public class UploadService {
    
    private final JobRepository jobRepository;

    @Autowired
    private MinioClient minioClient;

    @Value("${minio.bucket-name}")
    private String bucketName;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    public UploadService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }


    public void validateFile(MultipartFile file) {
        if(file == null) {
            throw new InvalidGpxFileException("GPX file does not exist");
        }

        String fileName = file.getOriginalFilename();
        if(!fileName.endsWith(".gpx")) {
            throw new InvalidGpxFileException("File is not a GPX file");
        }

        if(file.isEmpty()) {
            throw new InvalidGpxFileException("File is empty");
        }
    }

    public String saveToMinio(MultipartFile file) {
        String objectName = UUID.randomUUID().toString().substring(0,8) + "_" + file.getOriginalFilename();

        try(InputStream inputStream = file.getInputStream()){
            PutObjectArgs putObjectArgs = PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(inputStream, file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build();

            minioClient.putObject(putObjectArgs);
        } catch (Exception e) {
            throw new FileStorageException("Failed to upload GPX file to MinIO storage: " + e.getMessage(), e);
        }

        return objectName;
    }

    public JobEntity createJob(String objectName) {

        JobEntity job = new JobEntity();

        job.setName(objectName);
        job.setStatus(JobStatus.QUEUED);

        JobEntity savedJob = jobRepository.save(job);

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.MAIN_EXCHANGE,
                RabbitMQConfig.INGEST_ROUTING_KEY,
                String.valueOf(savedJob.getId())
        );

        return savedJob;
    }
}
