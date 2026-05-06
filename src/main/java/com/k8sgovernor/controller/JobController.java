package com.k8sgovernor.controller;

import com.k8sgovernor.model.Job;
import com.k8sgovernor.model.JobCreateRequest;
import com.k8sgovernor.model.JobCreateResponse;
import com.k8sgovernor.service.JobService;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;

    /**
     * curl http://localhost:9663/governor/jobs
     */
    @GetMapping
    public ResponseEntity<List<Job>> getAllJobs() {
        return ResponseEntity.ok(jobService.getAllJobs());
    }

    /**
     * curl http://localhost:9663/governor/jobs/{jobName}
     */
    @GetMapping("/{jobName}")
    public ResponseEntity<Job> getJobByName(@PathVariable String jobName) {
        return ResponseEntity.ok(jobService.getJobByName(jobName));
    }

    /**
     * curl http://localhost:9663/governor/jobs/templates
     */
    @GetMapping("/templates")
    public ResponseEntity<List<String>> getJobTemplateNames() {
        return ResponseEntity.ok(jobService.getJobTemplateNames());
    }

    /**
     * curl -X POST http://localhost:9663/governor/jobs \
     *   -H 'Content-Type: application/json' \
     *   -d '{"templateName": "example.yaml"}'
     */
    @PostMapping
    public ResponseEntity<JobCreateResponse> createJob(@RequestBody JobCreateRequest request) {
        log.info("Create request received for '{}'", request.getTemplateName());
        String jobName = jobService.createJob(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(new JobCreateResponse(jobName));
    }

    /**
     * curl -X DELETE http://localhost:9663/governor/jobs/{jobName}
     */
    @DeleteMapping("/{jobName}")
    public ResponseEntity<Void> deleteJobByName(@PathVariable String jobName) {
        log.info("Delete request received for job '{}'", jobName);
        boolean deleted = jobService.deleteJobByName(jobName);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
