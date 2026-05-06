package com.k8sgovernor.kubernetes;

import com.k8sgovernor.model.Job;

import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobCondition;
import io.kubernetes.client.openapi.models.V1JobStatus;
import io.kubernetes.client.openapi.models.V1ObjectMeta;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

@Component
public class JobMapper {

    public Job toJob(V1Job v1Job) {
        V1ObjectMeta meta = v1Job.getMetadata();
        V1JobStatus jobStatus = v1Job.getStatus();

        OffsetDateTime created = (meta != null && meta.getCreationTimestamp() != null)
                ? meta.getCreationTimestamp()
                : null;

        OffsetDateTime completed = (jobStatus != null && jobStatus.getCompletionTime() != null)
                ? jobStatus.getCompletionTime()
                : null;

        return Job.builder()
                .id(meta != null ? meta.getUid() : null)
                .name(meta != null ? meta.getName() : null)
                .status(deriveStatus(jobStatus))
                .created(created)
                .completed(completed)
                .build();
    }

    private String deriveStatus(V1JobStatus jobStatus) {
        if (jobStatus == null) {
            return "PENDING";
        }

        if (jobStatus.getConditions() != null) {
            for (V1JobCondition condition : jobStatus.getConditions()) {
                if (condition == null) continue;

                if ("True".equalsIgnoreCase(condition.getStatus())) {
                    if ("Complete".equals(condition.getType())) return "COMPLETED";
                    if ("Failed".equals(condition.getType())) return "FAILED";
                }
            }
        }

        if (jobStatus.getActive() != null && jobStatus.getActive() > 0) {
            return "RUNNING";
        }

        return "PENDING";
    }
}
