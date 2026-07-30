package com.secureitsource.ui.controller;

import ch.qos.logback.classic.Logger;
import com.secureitsource.ui.log.QueueAppender;
import com.secureitsource.ui.service.OperationRunner;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class RunController {

    private final OperationRunner operationRunner;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public RunController(OperationRunner operationRunner) {
        this.operationRunner = operationRunner;
    }

    @PostMapping(value = "/run", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseBodyEmitter run(
            @RequestParam String company,
            @RequestParam String tenantType,
            @RequestParam String authType,
            @RequestParam String clientId,
            @RequestParam String clientSecret,
            @RequestParam String apiVersion,
            @RequestParam String operation,
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {

        ResponseBodyEmitter emitter = new ResponseBodyEmitter(600_000L);

        executor.submit(() -> {
            List<Path> tempFiles = new ArrayList<>();
            BlockingQueue<String> logQueue = new LinkedBlockingQueue<>();
            QueueAppender appender = new QueueAppender(logQueue);
            Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            root.addAppender(appender);

            // Drainer thread owns the emitter lifecycle
            Thread drainer = new Thread(() -> {
                try {
                    while (true) {
                        String msg = logQueue.take();
                        if ("__DONE__".equals(msg)) {
                            emitter.complete();
                            break;
                        }
                        emitter.send(msg + "\n");
                    }
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            });
            drainer.setDaemon(true);
            drainer.start();

            try {
                if (files != null) {
                    for (MultipartFile file : files) {
                        if (!file.isEmpty()) {
                            Path tmp = Files.createTempFile("alms-", "-" + file.getOriginalFilename());
                            file.transferTo(tmp);
                            tempFiles.add(tmp);
                        }
                    }
                }

                List<String> paths = tempFiles.isEmpty()
                        ? Collections.singletonList("") // generatetransform needs one iteration
                        : tempFiles.stream().map(Path::toString).collect(Collectors.toList());

                operationRunner.execute(company, tenantType, authType,
                        clientId, clientSecret, apiVersion, operation, paths);

            } catch (Exception e) {
                logQueue.offer("[ERROR] " + e.getMessage());
            } finally {
                root.detachAppender(appender);
                appender.markDone();
                tempFiles.forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        });

        return emitter;
    }
}
