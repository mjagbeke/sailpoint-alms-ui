package com.secureitsource.ui.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.secureitsource.ui.config.AlmsProperties;

import io.isc.util.TenantInfo;
import io.util.exception.ServiceErrorException;

class TenantsServiceTest {

    @TempDir
    Path dir;

    private final TenantInfo.KeyReader original = TenantInfo.reader;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(dir.resolve("acme.yaml"), "x");
        Files.writeString(dir.resolve("broken.yml"), "x");
        Files.writeString(dir.resolve(".decrypted~acme.yaml"), "x");
        Files.writeString(dir.resolve("notes.txt"), "x");
        TenantInfo.reader = (path, key) -> {
            if (path.endsWith("broken.yml")) {
                throw new ServiceErrorException("SOPS file \"" + path + "\" has no \"" + key + "\" key");
            }
            return ("company".equals(key) ? "acme" : "demo").toCharArray();
        };
    }

    @AfterEach
    void restore() {
        TenantInfo.reader = original;
    }

    @Test
    void list_showsEachTenantsCompany_orWhyItsFileCantBeRead() throws Exception {
        List<TenantsService.Tenant> tenants = new TenantsService(new AlmsProperties(dir, dir)).list();

        assertEquals(2, tenants.size(), "hidden files and other files are left out");
        assertEquals(new TenantsService.Tenant("acme", "acme", "demo", null), tenants.get(0));
        assertEquals("broken", tenants.get(1).name());
        assertTrue(tenants.get(1).error().contains("has no company"), tenants.get(1).error());
    }

    @Test
    void list_missingFolder_isEmpty() throws Exception {
        assertEquals(List.of(), new TenantsService(new AlmsProperties(dir.resolve("none"), dir)).list());
    }
}
