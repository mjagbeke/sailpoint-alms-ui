package com.secureitsource.ui.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.secureitsource.ui.config.AlmsProperties;

import io.isc.util.TenantInfo;
import io.util.exception.ServiceErrorException;

// The credentials files the UI can use: the .yaml files directly in the credentials folder, except hidden ones. The
// page only ever names a file; the secrets stay in it and are decrypted by the library when it requests a token.
@Service
public class CredentialsService {

    // A file and the tenant it's for, or why its tenant can't be read
    public record Credential(String name, String company, String tenantType, String error) {}

    private final Path folder;

    public CredentialsService(AlmsProperties properties) {
        this.folder = properties.credentialsDir().toAbsolutePath().normalize();
    }

    public Path folder() {
        return folder;
    }

    public List<Credential> list() throws IOException {
        List<Credential> credentials = new ArrayList<>();

        if (!Files.isDirectory(folder)) {
            return credentials;
        }

        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(CredentialsService::isCredentialsFile).sorted().toList()) {
                String name = file.getFileName().toString();
                try {
                    TenantInfo.Tenant tenant = TenantInfo.resolve(file.toString());
                    credentials.add(new Credential(name, tenant.company(), tenant.tenantType(), null));
                } catch (ServiceErrorException | IOException e) {
                    credentials.add(new Credential(name, null, null, e.getMessage()));
                }
            }
        }
        return credentials;
    }

    // The file with this name in the credentials folder; a name is a bare file name, never a path
    public String resolve(String name) throws ServiceErrorException {
        String trimmed = name == null ? "" : name.trim();

        if (trimmed.isEmpty() || trimmed.startsWith(".") || trimmed.contains("/") || trimmed.contains("\\")) {
            throw new ServiceErrorException("\"" + trimmed + "\" isn't a credentials file name.");
        }

        Path file = folder.resolve(trimmed);
        if (!Files.isRegularFile(file)) {
            throw new ServiceErrorException("Credentials file \"" + trimmed + "\" isn't in " + folder + ".");
        }
        return file.toString();
    }

    // A driver row's credentialsFilePath, found by its file name in the credentials folder
    public String resolveByFileName(String path) throws ServiceErrorException {
        Path fileName = Path.of(path.replace('\\', '/')).getFileName();
        return resolve(fileName == null ? "" : fileName.toString());
    }

    // Hidden files are left out: sops edit leaves a decrypted ".decrypted~<name>" copy if it's interrupted
    private static boolean isCredentialsFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return Files.isRegularFile(file) && !name.startsWith(".") && (name.endsWith(".yaml") || name.endsWith(".yml"));
    }
}
