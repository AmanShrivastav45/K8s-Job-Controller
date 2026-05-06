package com.k8sgovernor.gitlab;

import org.springframework.web.client.RestClient;

public record GitLabSession(
        String baseUrl,
        String projectPath,
        String branch,
        String directory,
        RestClient client,
        String repositoryTreeUrl,
        String repositoryRawFileUrl
) {}
