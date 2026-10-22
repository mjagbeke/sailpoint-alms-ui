package com.secureitsource.ui.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.secureitsource.ui.config.AlmsProperties;

import jakarta.annotation.PreDestroy;
import io.util.exception.ServiceErrorException;

// Keeps one workspace: a new upload replaces the last one (and its OUTPUT files), and nothing is kept after the UI stops
@Service
public class WorkspaceService {

    private final Path folder;
    private final RunService runs;
    private Workspace current;

    public WorkspaceService(AlmsProperties properties, RunService runs) {
        this.folder = properties.workspaceDir().toAbsolutePath().normalize();
        this.runs = runs;
    }

    // paths.get(i) is files.get(i)'s path inside the uploaded folder (or just its name)
    public synchronized Workspace create(List<MultipartFile> files, List<String> paths) throws IOException, ServiceErrorException {
        if (files == null || files.isEmpty()) {
            throw new ServiceErrorException("Choose the files to upload.");
        }
        if (paths != null && paths.size() != files.size()) {
            throw new ServiceErrorException("Each uploaded file needs its path.");
        }
        if (runs.isRunning()) {
            throw new IllegalStateException("A run is in progress; wait for it to finish before uploading.");
        }

        clear();
        String id = UUID.randomUUID().toString();
        Workspace workspace = new Workspace(id, folder.resolve(id));
        Files.createDirectories(workspace.root());

        try {
            for (int i = 0; i < files.size(); i++) {
                MultipartFile file = files.get(i);
                String path = paths == null ? file.getOriginalFilename() : paths.get(i);
                try (InputStream content = file.getInputStream()) {
                    workspace.add(path, content);
                }
            }
        } catch (IOException | ServiceErrorException e) {
            workspace.delete();
            throw e;
        }

        current = workspace;
        return workspace;
    }

    public synchronized Workspace get(String id) throws ServiceErrorException {
        if (current == null || !current.id().equals(id)) {
            throw new ServiceErrorException("That upload is no longer available; upload the files again.");
        }
        return current;
    }

    @PreDestroy
    public synchronized void clear() throws IOException {
        if (current != null) {
            current.delete();
            current = null;
        }
    }
}
