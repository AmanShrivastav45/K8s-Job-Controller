package com.k8sgovernor.config;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.util.Config;

import java.io.IOException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class K8sConfig {

    private final AppConfig appConfig;

    @Bean
    public ApiClient kubernetesApiClient() throws IOException {
        var k8s = appConfig.getKubernetes();

        log.info("=== Kubernetes Runtime Config ===");
        log.info("Environment:     {}", k8s.getEnvironment());
        log.info("Region:          {}", k8s.getRegion());
        log.info("Namespace:       {}", k8s.getNamespace());
        log.info("Service Account: {}", k8s.getServiceAccount());
        log.info("=================================");

        ApiClient client = Config.defaultClient();
        client.setConnectTimeout(k8s.getConnectTimeoutMs());
        client.setReadTimeout(k8s.getReadTimeoutMs());
        client.setWriteTimeout(k8s.getWriteTimeoutMs());

        io.kubernetes.client.openapi.Configuration.setDefaultApiClient(client);

        return client;
    }

    @Bean
    public BatchV1Api batchV1Api(ApiClient apiClient) {
        return new BatchV1Api(apiClient);
    }
}
