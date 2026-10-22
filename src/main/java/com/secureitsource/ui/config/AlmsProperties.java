package com.secureitsource.ui.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

// alms.credentials-dir: the folder of credentials files the UI offers.
// alms.workspace-dir: where uploads are kept while they run, with their OUTPUT files.
@ConfigurationProperties("alms")
public record AlmsProperties(Path credentialsDir, Path workspaceDir) {
}
