package com.secureitsource.ui.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.secureitsource.ui.config.AlmsProperties;

import io.isc.util.CredentialsFolder;
import io.isc.util.TenantInfo;
import io.util.exception.ServiceErrorException;

// The tenants in the credentials folder. The page only ever names a tenant; its secrets stay in its credentials file
// and are decrypted by the library when it requests a token.
@Service
public class TenantsService {

    // A tenant and its company and tenant type, or why its credentials file can't be read
    public record Tenant(String name, String company, String tenantType, String error) {}

    private final CredentialsFolder folder;

    public TenantsService(AlmsProperties properties) {
        this.folder = new CredentialsFolder(properties.credentialsDir());
    }

    public CredentialsFolder folder() {
        return folder;
    }

    public List<Tenant> list() throws IOException {
        List<Tenant> tenants = new ArrayList<>();

        for (String name : folder.tenants()) {
            try {
                TenantInfo.Tenant tenant = TenantInfo.resolve(folder.resolve(name));
                tenants.add(new Tenant(name, tenant.company(), tenant.tenantType(), null));
            } catch (ServiceErrorException | IOException e) {
                tenants.add(new Tenant(name, null, null, e.getMessage()));
            }
        }
        return tenants;
    }
}
