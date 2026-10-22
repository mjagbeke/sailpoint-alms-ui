package com.secureitsource.ui.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.secureitsource.ui.config.LocalOnlyFilter;

import io.isc.util.TenantInfo;

@SpringBootTest
@AutoConfigureMockMvc
class ApiControllerTest {

    private static final Path ROOT = createRoot();

    @Autowired
    MockMvc mvc;

    private final TenantInfo.KeyReader original = TenantInfo.reader;

    private static Path createRoot() {
        try {
            Path root = Files.createTempDirectory("alms-ui-test");
            Files.createDirectories(root.resolve("credentials"));
            Files.writeString(root.resolve("credentials/acme.yaml"), "x");
            return root;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void folders(DynamicPropertyRegistry registry) {
        registry.add("alms.credentials-dir", () -> ROOT.resolve("credentials").toString());
        registry.add("alms.workspace-dir", () -> ROOT.resolve("work").toString());
    }

    @BeforeEach
    void tenant() {
        TenantInfo.reader = (path, key) -> ("company".equals(key) ? "acme" : "demo").toCharArray();
    }

    @AfterEach
    void restore() {
        TenantInfo.reader = original;
    }

    @Test
    void operations_listEveryOperationWithItsColumns() throws Exception {
        mvc.perform(get("/api/operations").header("Host", "localhost:8080"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(14)))
                .andExpect(jsonPath("$[0].name").value("sources"))
                .andExpect(jsonPath("$[0].versions[0]").value("beta"))
                .andExpect(jsonPath("$[0].columns[0]").value("Notes"));
    }

    @Test
    void template_isTheSheetsHeaderRow() throws Exception {
        mvc.perform(get("/api/operations/transforms/template").header("Host", "localhost"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Notes,Execute,Operation Type,Transform by Name")));
    }

    @Test
    void otherHosts_areRefused() throws Exception {
        mvc.perform(get("/api/operations").header("Host", "evil.example:8080")).andExpect(status().isForbidden());
    }

    @Test
    void postWithoutTheRequestHeader_isRefused() throws Exception {
        mvc.perform(post("/api/workspaces/x/check").header("Host", "localhost").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void credentials_listTheFolder() throws Exception {
        mvc.perform(get("/api/credentials").header("Host", "127.0.0.1:8080"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].name").value("acme.yaml"))
                .andExpect(jsonPath("$.files[0].company").value("acme"));
    }

    @Test
    void driverFolder_isCheckedAndRun_badRowsAreSkipped() throws Exception {
        String driver = "Notes,Execute,operation,credentialsFilePath,pathToInput,apiVersion\n"
                + "Roles,X,roles,somewhere/acme.yaml,src/main/resources/demo/roles/RoleOps.csv,\n"
                + "Typo,X,rolez,acme.yaml,roles/RoleOps.csv,\n"
                + "Version,X,roles,acme.yaml,roles/RoleOps.csv,v2025\n";

        String upload = mvc.perform(multipart("/api/workspaces")
                        .file(new MockMultipartFile("files", "driver.csv", "text/csv", driver.getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "RoleOps.csv", "text/csv", "Notes,Execute,Operation Type\n".getBytes(StandardCharsets.UTF_8)))
                        .param("paths", "demo/driver.csv", "demo/roles/RoleOps.csv")
                        .header("Host", "localhost").header(LocalOnlyFilter.REQUEST_HEADER, "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drivers[0]").value("demo/driver.csv"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(upload, "$.id");

        mvc.perform(post("/api/workspaces/" + id + "/check").header("Host", "localhost").header(LocalOnlyFilter.REQUEST_HEADER, "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"driver\":\"demo/driver.csv\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runnable").value(true))
                .andExpect(jsonPath("$[0].tenant").value("acme (demo)"))
                .andExpect(jsonPath("$[0].sheets[0]").value("demo/roles/RoleOps.csv"))
                .andExpect(jsonPath("$[1].error").value(containsString("Unknown operation \"rolez\"")))
                .andExpect(jsonPath("$[2].error").value("roles doesn't support apiVersion \"v2025\". Supported: beta."));

        // Only the bad rows: the run logs why they're skipped without calling a tenant
        String onlyBadRows = "Notes,Execute,operation,credentialsFilePath,pathToInput\nTypo,X,rolez,acme.yaml,roles/RoleOps.csv\n";
        String second = mvc.perform(multipart("/api/workspaces")
                        .file(new MockMultipartFile("files", "driver.csv", "text/csv", onlyBadRows.getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "RoleOps.csv", "text/csv", "Notes,Execute,Operation Type\n".getBytes(StandardCharsets.UTF_8)))
                        .param("paths", "driver.csv", "roles/RoleOps.csv").header("Host", "localhost").header(LocalOnlyFilter.REQUEST_HEADER, "1"))
                .andReturn().getResponse().getContentAsString();
        String secondId = JsonPath.read(second, "$.id");

        mvc.perform(post("/api/workspaces/" + id + "/check").header("Host", "localhost").header(LocalOnlyFilter.REQUEST_HEADER, "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"driver\":\"demo/driver.csv\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("no longer available")));

        mvc.perform(post("/api/workspaces/" + secondId + "/runs/driver").header("Host", "localhost").header(LocalOnlyFilter.REQUEST_HEADER, "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"driver\":\"driver.csv\"}"))
                .andExpect(status().isOk());

        String snapshot = waitForRun();
        assertEquals("DONE", JsonPath.read(snapshot, "$.state"));
        assertEquals(1, (int) JsonPath.read(snapshot, "$.errors"));
        String line = JsonPath.read(snapshot, "$.lines[0]");
        assertEquals(true, line.startsWith("ERROR Driver row 1 (rolez) skipped: Unknown operation"), line);
    }

    @Test
    void sheetRun_unknownCredentialsFile_isRefusedBeforeRunning() throws Exception {
        String upload = mvc.perform(multipart("/api/workspaces")
                        .file(new MockMultipartFile("files", "t.csv", "text/csv", "Notes,Execute\n".getBytes(StandardCharsets.UTF_8)))
                        .header("Host", "localhost").header(LocalOnlyFilter.REQUEST_HEADER, "1"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(upload, "$.id");

        mvc.perform(post("/api/workspaces/" + id + "/runs/sheet").header("Host", "localhost").header(LocalOnlyFilter.REQUEST_HEADER, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"transforms\",\"apiVersion\":\"beta\",\"credentials\":\"../acme.yaml\",\"sheet\":\"t.csv\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("\"../acme.yaml\" isn't a credentials file name."));
    }

    private String waitForRun() throws Exception {
        for (int i = 0; i < 100; i++) {
            String body = mvc.perform(get("/api/runs/current").header("Host", "localhost")).andReturn().getResponse().getContentAsString();
            if (!body.isEmpty() && !"RUNNING".equals(JsonPath.read(body, "$.state"))) {
                return body;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the run didn't finish");
    }
}
