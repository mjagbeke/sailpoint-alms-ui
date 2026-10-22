package com.secureitsource.ui.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

class CredentialsServiceTest {

    @TempDir
    Path dir;

    private final TenantInfo.KeyReader original = TenantInfo.reader;
    private CredentialsService service;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(dir.resolve("acme.yaml"), "x");
        Files.writeString(dir.resolve("broken.yml"), "x");
        Files.writeString(dir.resolve("notes.txt"), "x");
        Files.writeString(dir.resolve(".decrypted~acme.yaml"), "x");
        TenantInfo.reader = (path, key) -> {
            if (path.endsWith("broken.yml")) {
                throw new ServiceErrorException("SOPS file \"" + path + "\" has no \"" + key + "\" key");
            }
            return ("company".equals(key) ? "acme" : "demo").toCharArray();
        };
        service = new CredentialsService(new AlmsProperties(dir, dir.resolve("work")));
    }

    @AfterEach
    void restore() {
        TenantInfo.reader = original;
    }

    @Test
    void list_showsEachYamlFilesTenant_orWhyItCantBeRead() throws Exception {
        List<CredentialsService.Credential> files = service.list();

        assertEquals(2, files.size());
        assertEquals(new CredentialsService.Credential("acme.yaml", "acme", "demo", null), files.get(0));
        assertEquals("broken.yml", files.get(1).name());
        assertEquals("Credentials file \"" + dir.resolve("broken.yml") + "\" has no company. Add it with: --set-credential " + dir.resolve("broken.yml")
                + " company", files.get(1).error());
    }

    @Test
    void resolve_onlyBareNamesInTheFolder() throws Exception {
        assertEquals(dir.resolve("acme.yaml").toString(), service.resolve("acme.yaml"));

        for (String bad : List.of("../acme.yaml", "sub/acme.yaml", "..\\acme.yaml", "..", "", "missing.yaml", ".decrypted~acme.yaml")) {
            assertThrows(ServiceErrorException.class, () -> service.resolve(bad), bad);
        }
    }

    @Test
    void resolveByFileName_ignoresTheDriversFolder() throws Exception {
        assertEquals(dir.resolve("acme.yaml").toString(), service.resolveByFileName("src/main/resources/demo/credentials/acme.yaml"));
        assertEquals(dir.resolve("acme.yaml").toString(), service.resolveByFileName("C:\\creds\\acme.yaml"));
    }

    @Test
    void list_missingFolder_isEmpty() throws Exception {
        assertEquals(List.of(), new CredentialsService(new AlmsProperties(dir.resolve("none"), dir)).list());
    }
}
