package com.secureitsource.ui.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

import com.secureitsource.ui.service.RunService;
import com.secureitsource.ui.service.TenantsService;
import com.secureitsource.ui.service.WorkFolder;

import io.isc.runner.DriverRunner;
import io.isc.runner.Operation;
import io.isc.runner.OperationRegistry;
import io.util.exception.ServiceErrorException;

@RestController
@RequestMapping("/api")
public class ApiController {

    public record OperationInfo(String name, String group, String label, List<String> versions, List<String> columns) {}

    public record TenantsInfo(String folder, List<TenantsService.Tenant> tenants) {}

    public record CheckedRow(long number, String notes, String operation, boolean runnable, String error, String apiVersion, String tenant,
            List<String> sheets) {}

    public record SheetRun(String operation, String apiVersion, String tenant, String sheet) {}

    public record DriverRun(String driver) {}

    public record Started(String runId) {}

    private final TenantsService tenants;
    private final WorkFolder work;
    private final RunService runs;

    public ApiController(TenantsService tenants, WorkFolder work, RunService runs) {
        this.tenants = tenants;
        this.work = work;
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

    @GetMapping("/tenants")
    public TenantsInfo tenants() throws IOException {
        return new TenantsInfo(tenants.folder().folder().toString(), tenants.list());
    }

    @GetMapping("/files")
    public WorkFolder.Listing files(@RequestParam(defaultValue = "") String path) throws IOException, ServiceErrorException {
        return work.list(path);
    }

    @PostMapping("/drivers/check")
    public List<CheckedRow> check(@RequestBody DriverRun request) throws IOException, ServiceErrorException {
        List<CheckedRow> rows = new ArrayList<>();

        for (DriverRunner.Checked checked : runner(driver(request)).check(driver(request).toString())) {
            DriverRunner.Row row = checked.row();
            rows.add(new CheckedRow(checked.number(), checked.notes(), checked.operation(), checked.runnable(), checked.error(),
                    row == null ? null : row.apiVersion(),
                    row == null ? null : row.tenantName() + " — " + row.tenant().company() + " (" + row.tenant().tenantType() + ")",
                    row == null ? List.of() : row.sheets().stream().map(sheet -> work.relative(Path.of(sheet))).toList()));
        }
        return rows;
    }

    @PostMapping("/runs/driver")
    public Started runDriver(@RequestBody DriverRun request) throws IOException, ServiceErrorException {
        Path driver = driver(request);
        DriverRunner runner = runner(driver);

        // The OUTPUT files are written next to the sheets of the rows that will run
        Set<Path> folders = new LinkedHashSet<>();
        for (DriverRunner.Checked checked : runner.check(driver.toString())) {
            if (checked.runnable()) {
                checked.row().sheets().forEach(sheet -> folders.add(Path.of(sheet).getParent()));
            }
        }
        Map<Path, Long> before = work.snapshot(folders);

        return new Started(runs.start("Driver " + work.relative(driver), () -> runner.run(driver.toString()), () -> work.written(folders, before)));
    }

    @PostMapping("/runs/sheet")
    public Started runSheet(@RequestBody SheetRun request) throws IOException, ServiceErrorException {
        Path sheet = work.csv(request.sheet() == null ? "" : request.sheet());

        // Checked now, so a bad choice is reported here rather than in the log
        DriverRunner.Row row = DriverRunner.row(1, "", request.operation(), null, request.apiVersion(), tenants.folder(), request.tenant(),
                List.of(sheet.toString()));
        Set<Path> folders = Set.of(sheet.getParent());
        Map<Path, Long> before = work.snapshot(folders);

        return new Started(runs.start(row.operation().label() + ": " + work.relative(sheet), () -> DriverRunner.run(row), () -> work.written(folders, before)));
    }

    @GetMapping("/runs/current")
    public ResponseEntity<RunService.Snapshot> currentRun(@RequestParam(defaultValue = "0") int from) {
        return runs.current(from).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    // Only the files the run wrote can be downloaded
    @GetMapping("/runs/{id}/files")
    public ResponseEntity<Resource> output(@PathVariable String id, @RequestParam String path) throws ServiceErrorException {
        if (!runs.wrote(id, path)) {
            throw new ServiceErrorException("\"" + path + "\" isn't an OUTPUT file of that run.");
        }
        Path file = work.csv(path);
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

    private Path driver(DriverRun request) throws ServiceErrorException {
        Path driver = work.csv(request.driver() == null ? "" : request.driver());

        if (!WorkFolder.isDriver(driver)) {
            throw new ServiceErrorException("\"" + request.driver() + "\" isn't a driver: it needs operation and pathToInput columns.");
        }
        return driver;
    }

    private DriverRunner runner(Path driver) {
        return new DriverRunner(work.sheetsFor(driver), tenants.folder());
    }

    private static ResponseEntity<Resource> download(Resource body, String fileName) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(body);
    }
}
