package com.jaswal.gpxfileprocessor.upload;

import com.jaswal.gpxfileprocessor.common.config.RabbitMQConfig;
import com.jaswal.gpxfileprocessor.common.entity.JobEntity;
import com.jaswal.gpxfileprocessor.common.entity.JobStatus;
import com.jaswal.gpxfileprocessor.common.exception.FileStorageException;
import com.jaswal.gpxfileprocessor.common.exception.InvalidGpxFileException;
import com.jaswal.gpxfileprocessor.common.repository.JobRepository;
import com.jaswal.gpxfileprocessor.common.storage.FileStorageService;
import lombok.Getter;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

@Service
public class UploadService {
    
    private final JobRepository jobRepository;

    @Autowired
    private FileStorageService fileStorageService;

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

    public String storeFile(MultipartFile file) {
        String objectName = UUID.randomUUID().toString().substring(0,8) + "_" + file.getOriginalFilename();

        try (InputStream inputStream = file.getInputStream()) {
            fileStorageService.upload(objectName, inputStream, file.getSize(), file.getContentType());
        } catch (Exception e) {
            throw new FileStorageException("Failed to upload GPX file to storage: " + e.getMessage(), e);
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
