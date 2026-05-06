package com.k8sgovernor.util;

import com.k8sgovernor.config.AppConfig;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class GitLabUtils {

    private final AppConfig appConfig;

    private GitLabSession session;

    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\{\\{\\s*\\.Values\\.([\\w\\.]+)\\s*}}");

    private static final Pattern JOB_NAME_PATTERN =
            Pattern.compile("\\{\\{\\s*\\.Values\\.job\\.([\\w]+)\\.name\\s*}}");

    public record GitLabSession(
            String baseUrl,
            String projectPath,
            String branch,
            String directory,
            RestClient client,
            String repositoryTreeUrl,
            String repositoryRawFileUrl
    ) {}

    @PostConstruct
    void initSession() {
        try {
            this.session = buildSession();
        } catch (URISyntaxException e) {
            log.error("Invalid repository URL: {}", e.getMessage());
            throw new IllegalStateException("Invalid GitLab config", e);
        }
    }

    public GitLabSession getSession() throws URISyntaxException {
        if (session == null) {
            session = buildSession();
        }
        return session;
    }

    private GitLabSession buildSession() throws URISyntaxException {
        var helm = appConfig.getKubernetes().getHelm();
        var api = appConfig.getGitLab().getApi();
        var auth = appConfig.getGitLab().getAuth();

        URI uri = new URI(helm.getRepository());

        String baseUrl = uri.getScheme() + "://" + uri.getHost();
        String projectPath = uri.getPath()
                .replaceFirst("^/", "")
                .replaceFirst("\\.git$", "");

        RestClient client = RestClient.builder()
                .defaultHeader(auth.getHeaderName(), auth.getToken())
                .build();

        String apiBase = baseUrl + api.getBasePath() + "/" + api.getVersion()
                + "/projects/" + projectPath;

        String repositoryTreeUrl =
                apiBase + api.getRepositoryTreePath() + "?path={path}&ref={ref}";

        String repositoryRawFileUrl =
                apiBase + api.getRepositoryFilesPath() + "/{filePath}/raw?ref={ref}";

        return new GitLabSession(
                baseUrl,
                projectPath,
                helm.getBranch(),
                helm.getDirectory(),
                client,
                repositoryTreeUrl,
                repositoryRawFileUrl
        );
    }

    public String fetchTemplate(String templateName) {
        if (templateName == null || templateName.contains("..") || templateName.contains("/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid template name: must not contain path separators or traversal sequences");
        }

        try {
            GitLabSession ctx = getSession();
            String filePath = ctx.directory() + "/" + templateName;
            return fetchRawFileContent(ctx, filePath, "Template", false);

        } catch (URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid repository URL: " + e.getMessage());
        }
    }

    private Map<String, Object> fetchYamlFile(String filename, boolean optional) {
        try {
            GitLabSession ctx = getSession();
            String content = fetchRawFileContent(ctx, filename, "Value file", optional);

            if (content == null) {
                return Collections.emptyMap();
            }

            return parseYamlToMap(content);

        } catch (URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid repository URL: " + e.getMessage());
        }
    }

    private String fetchRawFileContent(
            GitLabSession ctx,
            String filePath,
            String resourceType,
            boolean optional
    ) {
        try {
            return ctx.client()
                    .get()
                    .uri(ctx.repositoryRawFileUrl(), Map.of("filePath", filePath, "ref", ctx.branch()))
                    .retrieve()
                    .body(String.class);

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                if (optional) {
                    log.info("{} '{}' not found (optional)", resourceType, filePath);
                    return null;
                }
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        resourceType + " " + filePath + " not found");
            }
            throw new ResponseStatusException(e.getStatusCode(),
                    "GitLab API error: " + e.getMessage());
        }
    }

    private Map<String, Object> parseYamlToMap(String content) {
        if (!StringUtils.hasText(content)) {
            return Collections.emptyMap();
        }

        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object parsed = yaml.load(content);

        if (parsed instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) map;
            return result;
        }

        return Collections.emptyMap();
    }

    public Map<String, Object> fetchAndMergeValues(String env, String region) {
        Map<String, Object> merged = new HashMap<>();

        merged.putAll(fetchYamlFile("values.yaml", false));

        if (StringUtils.hasText(env)) {
            mergeLayer(merged, "values-" + env + ".yaml", true);
        }

        if (StringUtils.hasText(env) && StringUtils.hasText(region)) {
            mergeLayer(merged, "values-" + env + "-" + region + ".yaml", true);
        }

        return merged;
    }

    private void mergeLayer(Map<String, Object> merged, String file, boolean optional) {
        Map<String, Object> layer = fetchYamlFile(file, optional);
        if (!layer.isEmpty()) {
            merged.putAll(layer);
        }
    }

    public String renderTemplate(
            String template,
            Map<String, Object> mergedValues,
            Map<String, Object> overrides
    ) {
        Map<String, Object> allValues = new HashMap<>(mergedValues);

        if (overrides != null) {
            allValues.putAll(overrides);
        }

        injectGeneratedJobNames(template, allValues);

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String keyPath = matcher.group(1);
            Object value = resolveValue(keyPath, allValues);
            String replacement = (value != null) ? value.toString() : "";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(result);
        return result.toString();
    }

    private void injectGeneratedJobNames(String template, Map<String, Object> values) {
        Matcher matcher = JOB_NAME_PATTERN.matcher(template);
        Set<String> processed = new HashSet<>();

        while (matcher.find()) {
            String jobType = matcher.group(1);

            if (processed.contains(jobType)) continue;
            processed.add(jobType);

            Object base = resolveValue("job." + jobType + ".name", values);

            if (base != null) {
                String finalName = base + "-" + JobUtils.generateJobNameSuffix();
                setNestedValue(values, "job." + jobType + ".name", finalName);
            }
        }
    }

    private void setNestedValue(Map<String, Object> map, String keyPath, Object value) {
        String[] parts = keyPath.split("\\.");
        Map<String, Object> current = map;

        for (int i = 0; i < parts.length - 1; i++) {
            current = (Map<String, Object>)
                    current.computeIfAbsent(parts[i], k -> new HashMap<>());
        }

        current.put(parts[parts.length - 1], value);
    }

    private Object resolveValue(String keyPath, Map<String, Object> values) {
        String[] parts = keyPath.split("\\.");
        Object current = values;

        for (String part : parts) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
            if (current == null) {
                return null;
            }
        }

        return current;
    }
}
