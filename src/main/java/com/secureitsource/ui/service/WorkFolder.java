package com.secureitsource.ui.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.secureitsource.ui.config.AlmsProperties;

import io.isc.runner.DriverRunner;
import io.util.exception.ServiceErrorException;

// The folder the UI works in. Drivers and sheets are read where they are, as on the command line, and runs write
// their OUTPUT files next to their sheets. Nothing outside the folder is listed, read or run. Paths given to and
// returned by the page are relative to it and use "/".
@Service
public class WorkFolder {

    // One entry in a folder listing; a driver is a CSV with operation and pathToInput columns
    public record Entry(String name, String path, boolean folder, boolean driver) {}

    public record Listing(String root, String path, String parent, List<Entry> entries) {}

    private final Path root;

    public WorkFolder(AlmsProperties properties) {
        this.root = properties.workDir().toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    // The folders and CSV files in a folder; hidden ones are left out
    public Listing list(String relativePath) throws IOException, ServiceErrorException {
        Path folder = inside(relativePath == null || relativePath.isBlank() ? "" : relativePath);

        if (!Files.isDirectory(folder)) {
            throw new ServiceErrorException("\"" + relativePath + "\" isn't a folder in " + root + ".");
        }

        List<Entry> folders = new ArrayList<>();
        List<Entry> files = new ArrayList<>();

        try (Stream<Path> children = Files.list(folder)) {
            for (Path child : children.sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString())).toList()) {
                String name = child.getFileName().toString();

                if (name.startsWith(".")) {
                    continue;
                }
                if (Files.isDirectory(child)) {
                    folders.add(new Entry(name, relative(child), true, false));
                } else if (name.toLowerCase().endsWith(".csv")) {
                    files.add(new Entry(name, relative(child), false, isDriver(child)));
                }
            }
        }

        folders.addAll(files);
        String path = relative(folder);
        return new Listing(root.toString(), path, folder.equals(root) ? null : relative(folder.getParent()), folders);
    }

    // A CSV file inside the work folder
    public Path csv(String relativePath) throws ServiceErrorException {
        Path file = inside(relativePath);

        if (!Files.isRegularFile(file) || !file.getFileName().toString().toLowerCase().endsWith(".csv")) {
            throw new ServiceErrorException("\"" + relativePath + "\" isn't a CSV file in " + root + ".");
        }
        return file;
    }

    // Finds the sheets a driver names. An absolute path is used as written; a relative one is looked up from the
    // driver's folder, then from each folder above it up to the work folder, so a path written from a repository's
    // root (src/main/resources/demo/...) is found as it is on the command line.
    public DriverRunner.PathResolver sheetsFor(Path driver) {
        return written -> {
            Path path = Path.of(written.trim());

            if (path.isAbsolute()) {
                Path file = path.normalize();
                if (!file.startsWith(root)) {
                    throw new ServiceErrorException("Sheet \"" + written + "\" is outside " + root + ".");
                }
                if (!Files.isRegularFile(file)) {
                    throw new ServiceErrorException("Sheet \"" + written + "\" not found.");
                }
                return file.toString();
            }

            for (Path from = driver.getParent(); from != null && from.startsWith(root); from = from.getParent()) {
                Path file = from.resolve(path).normalize();
                if (file.startsWith(root) && Files.isRegularFile(file)) {
                    return file.toString();
                }
            }
            throw new ServiceErrorException("Sheet \"" + written + "\" not found from the driver's folder or the folders above it.");
        };
    }

    public String relative(Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    // The CSV files in some folders and when they last changed, to tell afterwards which ones a run wrote
    public Map<Path, Long> snapshot(Collection<Path> folders) throws IOException {
        Map<Path, Long> files = new HashMap<>();

        for (Path folder : folders) {
            for (Path file : csvFiles(folder)) {
                files.put(file, Files.getLastModifiedTime(file).toMillis());
            }
        }
        return files;
    }

    // The CSV files in those folders that are new or changed since the snapshot
    public List<String> written(Collection<Path> folders, Map<Path, Long> before) throws IOException {
        List<String> written = new ArrayList<>();

        for (Path folder : folders) {
            for (Path file : csvFiles(folder)) {
                Long modified = before.get(file);
                if (modified == null || modified != Files.getLastModifiedTime(file).toMillis()) {
                    written.add(relative(file));
                }
            }
        }
        written.sort(null);
        return written;
    }

    private List<Path> csvFiles(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(Files::isRegularFile).filter(file -> file.getFileName().toString().toLowerCase().endsWith(".csv")).toList();
        }
    }

    private Path inside(String relativePath) throws ServiceErrorException {
        Path path = root.resolve(relativePath.replace('\\', '/')).normalize();

        if (!path.startsWith(root)) {
            throw new ServiceErrorException("\"" + relativePath + "\" is outside " + root + ".");
        }
        return path;
    }

    public static boolean isDriver(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                return false;
            }
            List<String> columns = List.of(header.replace("﻿", "").replace("\"", "").split(",")).stream().map(String::trim).toList();
            return columns.contains("operation") && columns.contains("pathToInput");
        } catch (MalformedInputException e) {
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
