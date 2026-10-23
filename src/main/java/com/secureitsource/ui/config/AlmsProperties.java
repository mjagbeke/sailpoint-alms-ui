package com.secureitsource.ui.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

// alms.credentials-dir: the folder of tenants' credentials files (<tenant>.yaml).
// alms.work-dir: the folder the UI may read drivers and sheets from, and that its file browser shows.
@ConfigurationProperties("alms")
public record AlmsProperties(Path credentialsDir, Path workDir) {
}
