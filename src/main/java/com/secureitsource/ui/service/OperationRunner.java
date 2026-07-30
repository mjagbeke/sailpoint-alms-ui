package com.secureitsource.ui.service;

import io.isc.constants.JsonPathConstants;
import io.isc.operations.*;
import io.util.config.OperationConfig;
import io.util.exception.ServiceErrorException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class OperationRunner {

    public void execute(String company, String tenantType, String authType,
                        String clientId, String clientSecret, String apiVersion,
                        String operation, List<String> inputPaths)
            throws IOException, ServiceErrorException {

        Map<String, String> operationProperties = new HashMap<>();
        operationProperties.put("operation", operation);

        Map<String, String> targetEndpointProps = new HashMap<>();
        targetEndpointProps.put("company", company);
        targetEndpointProps.put("tenantType", tenantType);
        targetEndpointProps.put("authType", authType);
        targetEndpointProps.put("clientId", clientId);
        targetEndpointProps.put("clientSecret", clientSecret);
        targetEndpointProps.put("apiVersion", apiVersion);
        targetEndpointProps.put("tokenField", JsonPathConstants.ACCESS_TOKEN_PATH);

        Map<String, Map<String, String>> driver = new HashMap<>();
        driver.put("operationProperties", operationProperties);
        driver.put("targetEndpointProps", targetEndpointProps);

        OperationConfig config = new OperationConfig(driver, inputPaths);

        for (String path : config.getPathToInput()) {
            switch (operation.toLowerCase().trim()) {
                case "generategovernancegroups":
                case "deletegovernancegroups":
                case "outputgovernancegroups":
                    new GovernanceGroupsOps(config).processGovernanceGroups(path);
                    break;
                case "generateaccessprofiles":
                case "deleteaccessprofiles":
                case "outputaccessprofiles":
                    new AccessProfilesOps(config).processAccessProfiles(path);
                    break;
                case "generateapplications":
                    new ApplicationOps(config).generateApplications(path);
                    break;
                case "generatesources":
                    new SourceOps(config).generateSources(path);
                    break;
                case "deletesources":
                    new SourceOps(config).deleteSources(path);
                    break;
                case "importentitlements":
                    new SourceOps(config).importEntitlements(path);
                    break;
                case "importaccounts":
                    new SourceOps(config).importAccounts(path);
                    break;
                case "importspconfig":
                    new SPConfigOps(config).importSPConfiguration(path);
                    break;
                case "reassignowner":
                    new SPConfigScenarios(config).reassignOwner(path);
                    break;
                case "swapcorrelationconfig":
                    new SPConfigScenarios(config).swapCorrelationConfig(path);
                    break;
                case "swapmanagementworkgroup":
                    new SPConfigScenarios(config).swapManagementWorkgroup(path);
                    break;
                case "swapcluster":
                    new SPConfigScenarios(config).swapCluster(path);
                    break;
                case "migratesodpolicies":
                    new SPConfigScenarios(config).migrateSodPolicies(path);
                    break;
                case "promoteroles":
                    new SPConfigScenarios(config).promoteRoles(path);
                    break;
                case "generateaccessrequest":
                    new AccessRequestOps(config).generateAccessRequests(path);
                    break;
                case "cancelaccessrequest":
                    new AccessRequestOps(config).cancelAccessRequests(path);
                    break;
                case "applicationoutput":
                    new ApplicationOps(config).generateOutput(path);
                    break;
                case "applicationdelete":
                    new ApplicationOps(config).deleteApplications(path);
                    break;
                case "outputsources":
                    new SourceOps(config).outputSources(path);
                    break;
                default:
                    throw new ServiceErrorException("Unknown operation: " + operation);
            }
        }
    }
}
