package com.secureitsource.ui.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.util.exception.ServiceErrorException;

class WorkspaceTest {

    @TempDir
    Path dir;

    private Workspace workspace(String... pathsAndContents) throws Exception {
        Workspace workspace = new Workspace("w", dir);
        for (int i = 0; i < pathsAndContents.length; i += 2) {
            workspace.add(pathsAndContents[i], new ByteArrayInputStream(pathsAndContents[i + 1].getBytes(StandardCharsets.UTF_8)));
        }
        return workspace;
    }

    @Test
    void clean_refusesPathsThatLeaveTheUpload() {
        for (String bad : List.of("../x.csv", "a/../../x.csv", "C:/x.csv", "c:\\x.csv", "a//b.csv", "")) {
            assertThrows(ServiceErrorException.class, () -> Workspace.clean(bad), bad);
        }
    }

    @Test
    void clean_normalisesSlashes() throws Exception {
        assertEquals("demo/roles/RoleOps.csv", Workspace.clean("\\demo\\roles\\RoleOps.csv"));
    }

    @Test
    void drivers_areCsvFilesWithOperationAndPathToInput() throws Exception {
        Workspace workspace = workspace(
                "demo/driver.csv", "\uFEFFNotes,Execute,operation,credentialsFilePath,pathToInput\n",
                "demo/roles/RoleOps.csv", "Notes,Execute,Operation Type\n",
                "demo/people.txt", "operation,pathToInput\n");
        assertEquals(List.of("demo/driver.csv"), workspace.drivers());
    }

    @Test
    void resolveSheet_relativeToTheDriver() throws Exception {
        Workspace workspace = workspace("demo/driver.csv", "x", "demo/roles/RoleOps.csv", "x");
        assertEquals(dir.resolve("demo/roles/RoleOps.csv").toString(), workspace.resolveSheet("demo/driver.csv", "roles/RoleOps.csv"));
    }

    @Test
    void resolveSheet_pathFromAnotherMachine_matchesTheLongestTail() throws Exception {
        Workspace workspace = workspace("demo/driver.csv", "x", "demo/roles/RoleOps.csv", "x", "demo/other/RoleOps.csv", "x");
        assertEquals(dir.resolve("demo/roles/RoleOps.csv").toString(),
                workspace.resolveSheet("demo/driver.csv", "src/main/resources/demo/roles/RoleOps.csv"));
        assertEquals(dir.resolve("demo/roles/RoleOps.csv").toString(),
                workspace.resolveSheet("demo/driver.csv", "C:\\work\\demo\\roles\\RoleOps.csv"));
    }

    @Test
    void resolveSheet_ambiguousOrMissing_fails() throws Exception {
        Workspace workspace = workspace("demo/driver.csv", "x", "demo/a/RoleOps.csv", "x", "demo/b/RoleOps.csv", "x");

        ServiceErrorException tie = assertThrows(ServiceErrorException.class, () -> workspace.resolveSheet("demo/driver.csv", "/elsewhere/RoleOps.csv"));
        assertTrue(tie.getMessage().contains("matches more than one uploaded file"), tie.getMessage());

        ServiceErrorException missing = assertThrows(ServiceErrorException.class, () -> workspace.resolveSheet("demo/driver.csv", "nope.csv"));
        assertEquals("Sheet \"nope.csv\" isn't in the uploaded folder.", missing.getMessage());
    }

    @Test
    void outputs_areTheFilesThatWereNotUploaded() throws Exception {
        Workspace workspace = workspace("demo/roles/RoleOps.csv", "x");
        Files.writeString(dir.resolve("demo/roles/RoleOutput.csv"), "y");
        assertEquals(List.of("demo/roles/RoleOutput.csv"), workspace.outputs());
    }

    @Test
    void file_outsideTheUpload_isRefused() throws Exception {
        Workspace workspace = workspace("a.csv", "x");
        assertThrows(ServiceErrorException.class, () -> workspace.file("../secret.txt"));
    }
}
