package com.secureitsource.ui.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import io.util.exception.ServiceErrorException;

// One upload: a folder holding the files as they were uploaded (sub-folders kept), so sheets find their data files
// next to them and write their OUTPUT files there. Paths are relative to the root and use "/".
public final class Workspace {

    private final String id;
    private final Path root;
    private final Set<String> uploaded = new TreeSet<>();

    Workspace(String id, Path root) {
        this.id = id;
        this.root = root;
    }

    public String id() {
        return id;
    }

    public Path root() {
        return root;
    }

    public Set<String> uploaded() {
        return Set.copyOf(uploaded);
    }

    void add(String relativePath, InputStream content) throws IOException, ServiceErrorException {
        String clean = clean(relativePath);
        Path target = file(clean);
        Files.createDirectories(target.getParent());
        Files.copy(content, target);
        uploaded.add(clean);
    }

    // The file at a relative path inside the workspace; a path that would leave it is refused
    public Path file(String relativePath) throws ServiceErrorException {
        Path file = root.resolve(clean(relativePath)).normalize();

        if (!file.startsWith(root)) {
            throw new ServiceErrorException("\"" + relativePath + "\" is outside the upload.");
        }
        return file;
    }

    static String clean(String relativePath) throws ServiceErrorException {
        String path = relativePath == null ? "" : relativePath.replace('\\', '/').trim();

        while (path.startsWith("/")) {
            path = path.substring(1);
        }

        if (path.isEmpty() || path.matches("^[A-Za-z]:.*") || List.of(path.split("/")).stream().anyMatch(part -> part.equals("..") || part.isEmpty())) {
            throw new ServiceErrorException("\"" + relativePath + "\" isn't a relative file path.");
        }
        return path;
    }

    // The uploaded CSV files that are drivers: their header has both an operation and a pathToInput column
    public List<String> drivers() throws IOException {
        List<String> drivers = new ArrayList<>();

        for (String path : uploaded) {
            if (path.toLowerCase().endsWith(".csv")) {
                String header = firstLine(root.resolve(path));
                List<String> columns = List.of(header.replace("﻿", "").replace("\"", "").split(","));
                if (columns.stream().map(String::trim).anyMatch("operation"::equals) && columns.stream().map(String::trim).anyMatch("pathToInput"::equals)) {
                    drivers.add(path);
                }
            }
        }
        return drivers;
    }

    // A sheet a driver row names. Paths are tried relative to the driver's folder first. Failing that, a path written
    // for another machine (e.g. src/main/resources/demo/roles/RoleOps.csv) matches the uploaded file whose path is the
    // longest tail of it, so a driver works unchanged when its folder is uploaded on its own.
    public String resolveSheet(String driver, String sheetPath) throws ServiceErrorException {
        String wanted = sheetPath.replace('\\', '/').trim();
        Path driverFolder = file(driver).getParent();

        boolean absolute = wanted.startsWith("/") || wanted.matches("^[A-Za-z]:.*");

        if (!absolute) {
            Path relative = driverFolder.resolve(wanted).normalize();
            if (relative.startsWith(root) && Files.isRegularFile(relative)) {
                return relative.toString();
            }
        }

        String best = null;
        int bestLength = 0;
        boolean tie = false;

        for (String path : uploaded) {
            int length = matchingTail(wanted, path);
            if (length > bestLength) {
                best = path;
                bestLength = length;
                tie = false;
            } else if (length == bestLength && length > 0) {
                tie = true;
            }
        }

        if (best == null) {
            throw new ServiceErrorException("Sheet \"" + sheetPath + "\" isn't in the uploaded folder.");
        }
        if (tie) {
            throw new ServiceErrorException("Sheet \"" + sheetPath + "\" matches more than one uploaded file; give its path from the driver's folder.");
        }
        return root.resolve(best).toString();
    }

    // How many trailing path segments two paths share, counting only whole segments
    static int matchingTail(String a, String b) {
        String[] left = a.split("/");
        String[] right = b.split("/");
        int count = 0;

        while (count < left.length && count < right.length
                && left[left.length - 1 - count].equalsIgnoreCase(right[right.length - 1 - count])) {
            count++;
        }
        return count;
    }

    // Files the run wrote: everything in the workspace that wasn't uploaded
    public List<String> outputs() throws IOException {
        List<String> outputs = new ArrayList<>();

        if (!Files.isDirectory(root)) {
            return outputs;
        }

        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile).map(file -> root.relativize(file).toString().replace('\\', '/'))
                    .filter(path -> !uploaded.contains(path)).sorted().forEach(outputs::add);
        }
        return outputs;
    }

    void delete() throws IOException {
        if (Files.exists(root)) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private static String firstLine(Path file) throws IOException {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            return line == null ? "" : line;
        } catch (java.nio.charset.MalformedInputException e) {
            return "";
        }
    }
}
