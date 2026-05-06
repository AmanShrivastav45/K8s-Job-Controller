package com.k8sgovernor.dto;

import com.k8sgovernor.model.Job;

import java.time.OffsetDateTime;

public record JobDto(
        String id,
        String name,
        String status,
        OffsetDateTime created,
        OffsetDateTime completed
) {
    public static JobDto from(Job job) {
        return new JobDto(job.getId(), job.getName(), job.getStatus(), job.getCreated(), job.getCompleted());
    }
}
