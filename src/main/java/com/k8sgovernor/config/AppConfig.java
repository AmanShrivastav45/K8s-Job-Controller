package com.k8sgovernor.config;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "config")
public class AppConfig {

    private Kubernetes kubernetes = new Kubernetes();
    private GitLab gitLab = new GitLab();
    private Security security = new Security();

    @Getter
    @Setter
    public static class Kubernetes {
        private String environment;
        private String region;
        private String namespace;
        private String serviceAccount;
        private int connectTimeoutMs = 10000;
        private int readTimeoutMs = 30000;
        private int writeTimeoutMs = 30000;
        private Helm helm = new Helm();

        @Getter
        @Setter
        public static class Helm {
            private String repository;
            private String branch;
            private String directory;
        }
    }

    @Getter
    @Setter
    public static class GitLab {
        private Api api = new Api();
        private Auth auth = new Auth();

        @Getter
        @Setter
        public static class Api {
            private String version = "v4";
            private String basePath = "/api";
            private String repositoryTreePath = "/repository/tree";
            private String repositoryFilesPath = "/repository/files";
        }

        @Getter
        @Setter
        public static class Auth {
            private String headerName = "PRIVATE-TOKEN";
            private String token;
        }
    }

    @Getter
    @Setter
    public static class Security {
        private String apiKey;
        private String apiKeyHeader = "X-API-Key";
    }
}
