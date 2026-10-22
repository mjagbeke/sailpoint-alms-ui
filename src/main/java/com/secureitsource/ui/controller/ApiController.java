package com.secureitsource.ui.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.secureitsource.ui.service.CredentialsService;
import com.secureitsource.ui.service.RunService;
import com.secureitsource.ui.service.Workspace;
import com.secureitsource.ui.service.WorkspaceService;

import io.isc.runner.DriverRunner;
import io.isc.runner.Operation;
import io.isc.runner.OperationRegistry;
import io.util.exception.ServiceErrorException;

@RestController
@RequestMapping("/api")
public class ApiController {

    public record OperationInfo(String name, String group, String label, List<String> versions, List<String> columns) {}

    public record CredentialsInfo(String folder, List<CredentialsService.Credential> files) {}

    public record WorkspaceInfo(String id, List<String> files, List<String> drivers) {}

    public record CheckedRow(long number, String notes, String operation, boolean runnable, String error, String apiVersion, String tenant,
            List<String> sheets) {}

    public record SheetRun(String operation, String apiVersion, String credentials, String sheet) {}

    public record DriverRun(String driver) {}

    public record Started(String runId) {}

    private final CredentialsService credentials;
    private final WorkspaceService workspaces;
    private final RunService runs;

    public ApiController(CredentialsService credentials, WorkspaceService workspaces, RunService runs) {
        this.credentials = credentials;
        this.workspaces = workspaces;
        this.runs = runs;
    }

    @GetMapping("/operations")
    public List<OperationInfo> operations() throws IOException {
        List<OperationInfo> operations = new ArrayList<>();
        for (Operation operation : OperationRegistry.OPERATIONS) {
            operations.add(new OperationInfo(operation.name(), operation.group(), operation.label(), List.copyOf(new TreeSet<>(operation.versions())),
                    operation.columns()));
        }
        return operations;
    }

    @GetMapping("/operations/{name}/template")
    public ResponseEntity<Resource> template(@PathVariable String name) throws IOException, ServiceErrorException {
        Operation operation = OperationRegistry.find(name).orElseThrow(() -> new ServiceErrorException("Unknown operation \"" + name + "\"."));
        InputStream template = operation.openTemplate();
        return download(new InputStreamResource(template), operation.name() + ".csv");
    }

    @GetMapping("/credentials")
    public CredentialsInfo credentials() throws IOException {
        return new CredentialsInfo(credentials.folder().toString(), credentials.list());
    }

    @PostMapping(value = "/workspaces", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public WorkspaceInfo upload(@RequestParam("files") List<MultipartFile> files, @RequestParam(value = "paths", required = false) List<String> paths)
            throws IOException, ServiceErrorException {
        Workspace workspace = workspaces.create(files, paths);
        return new WorkspaceInfo(workspace.id(), List.copyOf(workspace.uploaded()), workspace.drivers());
    }

    @PostMapping("/workspaces/{id}/check")
    public List<CheckedRow> check(@PathVariable String id, @RequestBody DriverRun request) throws IOException, ServiceErrorException {
        Workspace workspace = workspaces.get(id);
        String driver = driverIn(workspace, request.driver());
        List<CheckedRow> rows = new ArrayList<>();

        for (DriverRunner.Checked checked : runner(workspace, driver).check(workspace.file(driver).toString())) {
            DriverRunner.Row row = checked.row();
            rows.add(new CheckedRow(checked.number(), checked.notes(), checked.operation(), checked.runnable(), checked.error(),
                    row == null ? null : row.apiVersion(), row == null ? null : row.tenant().company() + " (" + row.tenant().tenantType() + ")",
                    row == null ? List.of() : row.sheets().stream().map(sheet -> relative(workspace, sheet)).toList()));
        }
        return rows;
    }

    @PostMapping("/workspaces/{id}/runs/driver")
    public Started runDriver(@PathVariable String id, @RequestBody DriverRun request) throws ServiceErrorException {
        Workspace workspace = workspaces.get(id);
        String driver = driverIn(workspace, request.driver());
        DriverRunner runner = runner(workspace, driver);
        String driverPath = workspace.file(driver).toString();
        return new Started(runs.start("Driver " + driver, workspace, () -> runner.run(driverPath)));
    }

    @PostMapping("/workspaces/{id}/runs/sheet")
    public Started runSheet(@PathVariable String id, @RequestBody SheetRun request) throws IOException, ServiceErrorException {
        Workspace workspace = workspaces.get(id);

        if (request.sheet() == null || !workspace.uploaded().contains(request.sheet())) {
            throw new ServiceErrorException("Choose the sheet to run from the uploaded files.");
        }

        // Checked now, so a bad choice is reported here rather than in the log
        DriverRunner.Row row = DriverRunner.row(1, "", request.operation(), null, request.apiVersion(), credentials.resolve(request.credentials()),
                List.of(workspace.file(request.sheet()).toString()));
        return new Started(runs.start(row.operation().label() + ": " + request.sheet(), workspace, () -> DriverRunner.run(row)));
    }

    @GetMapping("/runs/current")
    public ResponseEntity<RunService.Snapshot> currentRun(@RequestParam(defaultValue = "0") int from) {
        return runs.current(from).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/workspaces/{id}/files")
    public ResponseEntity<Resource> file(@PathVariable String id, @RequestParam String path) throws ServiceErrorException {
        Path file = workspaces.get(id).file(path);

        if (!Files.isRegularFile(file)) {
            throw new ServiceErrorException("\"" + path + "\" isn't in the upload.");
        }
        return download(new PathResource(file), file.getFileName().toString());
    }

    @ExceptionHandler(ServiceErrorException.class)
    public ResponseEntity<Map<String, String>> badRequest(ServiceErrorException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> busy(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    // Sheets are found in the upload; credentials files by name in the credentials folder
    private DriverRunner runner(Workspace workspace, String driver) {
        return new DriverRunner(path -> workspace.resolveSheet(driver, path), credentials::resolveByFileName);
    }

    private static String driverIn(Workspace workspace, String driver) throws ServiceErrorException {
        try {
            if (driver != null && workspace.drivers().contains(driver)) {
                return driver;
            }
        } catch (IOException e) {
            throw new ServiceErrorException("Couldn't read the uploaded files: " + e.getMessage());
        }
        throw new ServiceErrorException("Choose a driver from the uploaded files.");
    }

    private static String relative(Workspace workspace, String path) {
        return workspace.root().relativize(Path.of(path)).toString().replace('\\', '/');
    }

    private static ResponseEntity<Resource> download(Resource body, String fileName) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(body);
    }
}
