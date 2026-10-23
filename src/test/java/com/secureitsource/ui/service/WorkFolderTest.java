package com.secureitsource.ui.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.secureitsource.ui.config.AlmsProperties;

import io.isc.runner.DriverRunner;
import io.util.exception.ServiceErrorException;

class WorkFolderTest {

    @TempDir
    Path dir;

    private Path root;
    private WorkFolder work;

    private Path write(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(dir.resolve("work"));
        work = new WorkFolder(new AlmsProperties(dir.resolve("creds"), root));
        write("repo/src/main/resources/demo/roles/driver.csv", "﻿Notes,Execute,operation,tenant,pathToInput\n");
        write("repo/src/main/resources/demo/roles/RoleOps.csv", "Notes,Execute,Operation Type\n");
        write("repo/src/main/resources/demo/fixtures/people.csv", "Notes,Execute\n");
        write("repo/notes.txt", "x");
        write("repo/.hidden.csv", "x");
        Files.writeString(dir.resolve("outside.csv"), "x");
    }

    @Test
    void list_showsFoldersThenCsvFiles_markingDrivers() throws Exception {
        WorkFolder.Listing roles = work.list("repo/src/main/resources/demo/roles");

        assertEquals("repo/src/main/resources/demo/roles", roles.path());
        assertEquals("repo/src/main/resources/demo", roles.parent());
        assertEquals(List.of(new WorkFolder.Entry("driver.csv", "repo/src/main/resources/demo/roles/driver.csv", false, true),
                new WorkFolder.Entry("RoleOps.csv", "repo/src/main/resources/demo/roles/RoleOps.csv", false, false)), roles.entries());

        WorkFolder.Listing repo = work.list("repo");
        assertEquals(List.of("src"), repo.entries().stream().map(WorkFolder.Entry::name).toList(), "other files and hidden ones are left out");
        assertNull(work.list("").parent());
    }

    @Test
    void outsideTheWorkFolder_isRefused() {
        assertThrows(ServiceErrorException.class, () -> work.list(".."));
        assertThrows(ServiceErrorException.class, () -> work.csv("../outside.csv"));
        assertThrows(ServiceErrorException.class, () -> work.csv("repo/notes.txt"), "only CSV files");
    }

    @Test
    void sheets_areFoundFromTheDriversFolderOrTheFoldersAboveIt() throws Exception {
        Path driver = root.resolve("repo/src/main/resources/demo/roles/driver.csv");
        DriverRunner.PathResolver sheets = work.sheetsFor(driver);

        assertEquals(root.resolve("repo/src/main/resources/demo/roles/RoleOps.csv").toString(), sheets.resolve("RoleOps.csv"));
        assertEquals(root.resolve("repo/src/main/resources/demo/fixtures/people.csv").toString(), sheets.resolve("src/main/resources/demo/fixtures/people.csv"),
                "a path written from the repository's root");
        assertEquals(root.resolve("repo/src/main/resources/demo/fixtures/people.csv").toString(), sheets.resolve("..\\fixtures\\people.csv"));
        assertEquals(root.resolve("repo/src/main/resources/demo/roles/RoleOps.csv").toString(),
                sheets.resolve(root.resolve("repo/src/main/resources/demo/roles/RoleOps.csv").toString()), "absolute inside the work folder");
    }

    @Test
    void sheets_missingOrOutside_fail() {
        DriverRunner.PathResolver sheets = work.sheetsFor(root.resolve("repo/src/main/resources/demo/roles/driver.csv"));

        ServiceErrorException missing = assertThrows(ServiceErrorException.class, () -> sheets.resolve("nope.csv"));
        assertEquals("Sheet \"nope.csv\" not found from the driver's folder or the folders above it.", missing.getMessage());
        assertThrows(ServiceErrorException.class, () -> sheets.resolve(dir.resolve("outside.csv").toString()));
        assertThrows(ServiceErrorException.class, () -> sheets.resolve("../../../../../../outside.csv"));
    }

    @Test
    void written_listsTheCsvFilesARunAddedOrChanged() throws Exception {
        Path folder = root.resolve("repo/src/main/resources/demo/roles");
        Path sheet = folder.resolve("RoleOps.csv");
        Files.setLastModifiedTime(sheet, FileTime.fromMillis(1_000_000));
        Map<Path, Long> before = work.snapshot(Set.of(folder));

        write("repo/src/main/resources/demo/roles/RoleOutput.csv", "y");
        write("repo/src/main/resources/demo/roles/notes.txt", "y");

        assertEquals(List.of("repo/src/main/resources/demo/roles/RoleOutput.csv"), work.written(Set.of(folder), before));
        Files.setLastModifiedTime(sheet, FileTime.fromMillis(2_000_000));
        assertTrue(work.written(Set.of(folder), before).contains("repo/src/main/resources/demo/roles/RoleOps.csv"));
    }

    @Test
    void isDriver_needsOperationAndPathToInput() throws Exception {
        assertTrue(WorkFolder.isDriver(root.resolve("repo/src/main/resources/demo/roles/driver.csv")));
        assertFalse(WorkFolder.isDriver(root.resolve("repo/src/main/resources/demo/roles/RoleOps.csv")));
    }
}
