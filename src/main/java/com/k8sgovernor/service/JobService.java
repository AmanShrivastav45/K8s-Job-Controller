package com.k8sgovernor.service;

import com.google.gson.Gson;

import com.k8sgovernor.config.AppConfig;
import com.k8sgovernor.gitlab.GitLabClient;
import com.k8sgovernor.kubernetes.JobMapper;
import com.k8sgovernor.model.Job;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobList;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.net.URISyntaxException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private final BatchV1Api batchV1Api;
    private final AppConfig appConfig;
    private final GitLabClient gitLabClient;
    private final JobMapper jobMapper;

    public List<Job> getAllJobs() {
        String namespace = appConfig.getKubernetes().getNamespace();

        try {
            V1JobList jobList = batchV1Api.listNamespacedJob(
                    namespace, null, null, null,
                    null, null, null, null,
                    null, null, null
            );

            List<Job> result = jobList.getItems()
                    .stream()
                    .map(jobMapper::toJob)
                    .collect(Collectors.toList());

            log.info("Found {} jobs in namespace '{}'", result.size(), namespace);
            return result;

        } catch (ApiException e) {
            log.error("Failed to list jobs: HTTP {} {}", e.getCode(), e.getResponseBody());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to list jobs: " + e.getMessage());
        }
    }

    public Job getJobByName(String jobName) {
        String namespace = appConfig.getKubernetes().getNamespace();

        try {
            V1Job v1Job = batchV1Api.readNamespacedJob(jobName, namespace, null, null, null);
            return jobMapper.toJob(v1Job);

        } catch (ApiException e) {
            if (e.getCode() == 404) {
                log.warn("Job '{}' not found in namespace '{}'", jobName, namespace);
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job " + jobName + " not found");
            }
            log.error("Failed to fetch job '{}': HTTP {} {}", jobName, e.getCode(), e.getResponseBody());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to fetch job: " + e.getMessage());
        }
    }

    public List<String> getJobTemplateNames() {
        try {
            var context = gitLabClient.getSession();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = context.client()
                    .get()
                    .uri(context.repositoryTreeUrl(), Map.of("path", context.directory(), "ref", context.branch()))
                    .retrieve()
                    .body(List.class);

            if (items == null) {
                return new ArrayList<>();
            }

            List<String> templates = items.stream()
                    .filter(item -> "blob".equals(item.get("type")))
                    .map(item -> (String) item.get("name"))
                    .filter(name -> name != null && (name.endsWith(".yaml") || name.endsWith(".yml")))
                    .collect(Collectors.toList());

            log.info("Found {} job templates in directory '{}'", templates.size(), context.directory());
            return templates;

        } catch (URISyntaxException e) {
            log.error("Invalid repository URL: {}", appConfig.getKubernetes().getHelm().getRepository());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid repository URL: " + e.getMessage());

        } catch (HttpClientErrorException e) {
            log.error("GitLab API error. Status: {}", e.getStatusCode());
            throw new ResponseStatusException(e.getStatusCode(), "GitLab API error: " + e.getMessage());
        }
    }

    public String createJob(String templateName, Map<String, Object> overrides) {
        String namespace = appConfig.getKubernetes().getNamespace();
        String environment = appConfig.getKubernetes().getEnvironment();
        String region = appConfig.getKubernetes().getRegion();

        try {
            String rawYaml = gitLabClient.fetchTemplate(templateName);
            Map<String, Object> mergedValues = gitLabClient.fetchAndMergeValues(environment, region);
            String renderedTemplate = gitLabClient.renderTemplate(rawYaml, mergedValues, overrides);

            Yaml yamlParser = new Yaml(new SafeConstructor(new LoaderOptions()));
            @SuppressWarnings("unchecked")
            Map<String, Object> yamlMap = yamlParser.load(renderedTemplate);

            Gson gson = new JSON().getGson();
            V1Job v1Job = gson.fromJson(gson.toJson(yamlMap), V1Job.class);

            V1Job created = batchV1Api.createNamespacedJob(namespace, v1Job, null, null, null);

            String jobName = created.getMetadata() != null
                    ? created.getMetadata().getName()
                    : "unknown";

            log.info("Successfully deployed job '{}' in namespace '{}'", jobName, namespace);
            return jobName;

        } catch (ApiException e) {
            log.error("Kubernetes error creating job from template '{}': HTTP {} {}",
                    templateName, e.getCode(), e.getResponseBody());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Kubernetes error: " + e.getMessage());

        } catch (Exception e) {
            log.error("Failed to parse or render template '{}': {}", templateName, e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Failed to parse or create job: " + e.getMessage());
        }
    }

    public boolean deleteJobByName(String jobName) {
        String namespace = appConfig.getKubernetes().getNamespace();

        try {
            batchV1Api.deleteNamespacedJob(jobName, namespace, null, null, null, null, "Foreground", null);
            log.info("Successfully deleted job '{}'", jobName);
            return true;

        } catch (ApiException e) {
            if (e.getCode() == 404) {
                log.warn("Job '{}' not found in namespace '{}'", jobName, namespace);
                return false;
            }
            log.error("Failed to delete job '{}': HTTP {} {}", jobName, e.getCode(), e.getResponseBody());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to delete job: " + e.getMessage());
        }
    }
}
