package com.secureitsource.ui.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.secureitsource.ui.config.LocalOnlyFilter;

import io.isc.util.TenantInfo;

@SpringBootTest
@AutoConfigureMockMvc
class ApiControllerTest {

    private static final Path ROOT = createRoot();
    private static final String DEMO = "repo/src/main/resources/demo/";

    @Autowired
    MockMvc mvc;

    private final TenantInfo.KeyReader original = TenantInfo.reader;

    // A work folder holding a repository whose driver names its sheets from the repository's root, as the demos do
    private static Path createRoot() {
        try {
            Path root = Files.createTempDirectory("alms-ui-test");
            Files.createDirectories(root.resolve("credentials"));
            Files.writeString(root.resolve("credentials/acme.yaml"), "x");
            Path demo = Files.createDirectories(root.resolve("work/" + DEMO + "roles"));
            Files.createDirectories(root.resolve("work/" + DEMO + "fixtures"));
            Files.writeString(demo.resolve("RoleOps.csv"), "Notes,Execute,Operation Type\n");
            Files.writeString(root.resolve("work/" + DEMO + "fixtures/people.csv"), "Notes,Execute\n");
            Files.writeString(demo.resolve("driver.csv"), "Notes,Execute,operation,tenant,pathToInput,apiVersion\n"
                    + "Setup,X,sources,acme,src/main/resources/demo/fixtures/people.csv,\n"
                    + "Roles,X,roles,acme,roles/RoleOps.csv,\n"
                    + "Typo,X,rolez,acme,roles/RoleOps.csv,\n"
                    + "Version,X,roles,acme,roles/RoleOps.csv,v2025\n"
                    + "No file,X,roles,nobody,roles/RoleOps.csv,\n"
                    + "Missing sheet,X,roles,acme,roles/Nope.csv,\n");
            Files.writeString(demo.resolve("bad-only.csv"), "Notes,Execute,operation,tenant,pathToInput\nTypo,X,rolez,acme,roles/RoleOps.csv\n");
            return root;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void folders(DynamicPropertyRegistry registry) {
        registry.add("alms.credentials-dir", () -> ROOT.resolve("credentials").toString());
        registry.add("alms.work-dir", () -> ROOT.resolve("work").toString());
    }

    @BeforeEach
    void tenant() {
        TenantInfo.reader = (path, key) -> ("company".equals(key) ? "acme" : "demo").toCharArray();
    }

    @AfterEach
    void restore() {
        TenantInfo.reader = original;
    }

    private static MockHttpServletRequestBuilder postJson(String url, String json) {
        return post(url).header("Host", "localhost").header(LocalOnlyFilter.REQUEST_HEADER, "1").contentType(MediaType.APPLICATION_JSON).content(json);
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
        mvc.perform(post("/api/drivers/check").header("Host", "localhost").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void tenants_listTheCredentialsFolder() throws Exception {
        mvc.perform(get("/api/tenants").header("Host", "127.0.0.1:8080"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenants[0].name").value("acme"))
                .andExpect(jsonPath("$.tenants[0].company").value("acme"));
    }

    @Test
    void files_browseTheWorkFolderOnly() throws Exception {
        mvc.perform(get("/api/files").param("path", DEMO + "roles").header("Host", "localhost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parent").value("repo/src/main/resources/demo"))
                .andExpect(jsonPath("$.entries[?(@.name == 'driver.csv')].driver").value(true))
                .andExpect(jsonPath("$.entries[?(@.name == 'RoleOps.csv')].driver").value(false));

        mvc.perform(get("/api/files").param("path", "..").header("Host", "localhost"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("is outside")));
    }

    @Test
    void check_findsSheetsFromTheRepositoryRoot_andSaysWhyRowsWontRun() throws Exception {
        mvc.perform(postJson("/api/drivers/check", "{\"driver\":\"" + DEMO + "roles/driver.csv\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[0].runnable").value(true))
                .andExpect(jsonPath("$[0].sheets[0]").value(DEMO + "fixtures/people.csv"))
                .andExpect(jsonPath("$[1].tenant").value("acme — acme (demo)"))
                .andExpect(jsonPath("$[2].error").value(containsString("Unknown operation \"rolez\"")))
                .andExpect(jsonPath("$[3].error").value("roles doesn't support apiVersion \"v2025\". Supported: beta."))
                .andExpect(jsonPath("$[4].error").value(containsString("Tenant \"nobody\" has no credentials file")))
                .andExpect(jsonPath("$[5].error").value("Sheet \"roles/Nope.csv\" not found from the driver's folder or the folders above it."));
    }

    @Test
    void check_aSheetThatIsntADriver_isRefused() throws Exception {
        mvc.perform(postJson("/api/drivers/check", "{\"driver\":\"" + DEMO + "roles/RoleOps.csv\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("isn't a driver")));
    }

    @Test
    void driverRun_logsSkippedRows_andOnlyItsOutputsCanBeDownloaded() throws Exception {
        String started = mvc.perform(postJson("/api/runs/driver", "{\"driver\":\"" + DEMO + "roles/bad-only.csv\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(started, "$.runId");

        String snapshot = waitForRun();
        assertEquals("DONE", JsonPath.read(snapshot, "$.state"));
        assertEquals(1, (int) JsonPath.read(snapshot, "$.errors"));
        String line = JsonPath.read(snapshot, "$.lines[0]");
        assertTrue(line.startsWith("ERROR Driver row 1 (rolez) skipped: Unknown operation"), line);

        mvc.perform(get("/api/runs/" + runId + "/files").param("path", DEMO + "roles/RoleOps.csv").header("Host", "localhost"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("isn't an OUTPUT file of that run")));
    }

    @Test
    void sheetRun_unknownTenantOrSheetOutside_isRefusedBeforeRunning() throws Exception {
        mvc.perform(postJson("/api/runs/sheet", "{\"operation\":\"roles\",\"apiVersion\":\"beta\",\"tenant\":\"../acme\",\"sheet\":\"" + DEMO + "roles/RoleOps.csv\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("not a path")));

        mvc.perform(postJson("/api/runs/sheet", "{\"operation\":\"roles\",\"apiVersion\":\"beta\",\"tenant\":\"acme\",\"sheet\":\"../credentials/acme.yaml\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("is outside")));
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
