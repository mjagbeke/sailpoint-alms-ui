package com.secureitsource.ui.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PreDestroy;

// Runs one thing at a time in the background and keeps what it logged, so the page can show progress and the result
// after the request that started it has returned. Only the library's own messages (io.* loggers, INFO and above) from
// the run's thread are kept.
@Service
public class RunService {

    public enum State { RUNNING, DONE, FAILED }

    public interface Body {
        void run() throws Exception;
    }

    // What the page polls: the lines from index `from` on, and the OUTPUT files once the run has ended
    public record Snapshot(String id, String label, String workspaceId, State state, int warnings, int errors, int from, List<String> lines,
            List<String> outputs) {}

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "alms-run"));
    private volatile Run current;

    public synchronized String start(String label, Workspace workspace, Body body) {
        if (isRunning()) {
            throw new IllegalStateException("A run is in progress; wait for it to finish.");
        }

        Run run = new Run(UUID.randomUUID().toString(), label, workspace);
        current = run;
        executor.submit(() -> run.execute(body));
        return run.id;
    }

    public boolean isRunning() {
        Run run = current;
        return run != null && run.state == State.RUNNING;
    }

    public Optional<Snapshot> current(int from) {
        Run run = current;
        return run == null ? Optional.empty() : Optional.of(run.snapshot(from));
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    private static final class Run {
        private final String id;
        private final String label;
        private final Workspace workspace;
        private final List<String> lines = new ArrayList<>();
        private volatile State state = State.RUNNING;
        private volatile List<String> outputs = List.of();
        private int warnings;
        private int errors;

        Run(String id, String label, Workspace workspace) {
            this.id = id;
            this.label = label;
            this.workspace = workspace;
        }

        void execute(Body body) {
            Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            String thread = Thread.currentThread().getName();
            AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
                @Override
                protected void append(ILoggingEvent event) {
                    if (thread.equals(event.getThreadName()) && event.getLoggerName().startsWith("io.") && event.getLevel().isGreaterOrEqual(Level.INFO)) {
                        add(event.getLevel(), event.getFormattedMessage());
                    }
                }
            };
            appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
            appender.start();
            root.addAppender(appender);

            State end = State.DONE;
            try {
                body.run();
            } catch (Exception e) {
                add(Level.ERROR, "Run stopped: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                end = State.FAILED;
            } finally {
                root.detachAppender(appender);
                appender.stop();
                try {
                    outputs = workspace.outputs();
                } catch (Exception e) {
                    add(Level.WARN, "Couldn't list the OUTPUT files: " + e.getMessage());
                }
                state = end;
            }
        }

        private synchronized void add(Level level, String message) {
            if (level == Level.WARN) {
                warnings++;
            } else if (level == Level.ERROR) {
                errors++;
            }
            lines.add(level.levelStr + " " + message);
        }

        synchronized Snapshot snapshot(int from) {
            int start = Math.max(0, Math.min(from, lines.size()));
            return new Snapshot(id, label, workspace.id(), state, warnings, errors, start, List.copyOf(lines.subList(start, lines.size())), outputs);
        }
    }
}
