package com.secureitsource.ui.log;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;

public class QueueAppender extends AppenderBase<ILoggingEvent> {

    private final BlockingQueue<String> queue;
    private volatile boolean done = false;

    public QueueAppender(BlockingQueue<String> queue) {
        this.queue = queue;
        setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!done) {
            queue.offer("[" + event.getLevel().levelStr + "] " + event.getFormattedMessage());
        }
    }

    public void markDone() {
        done = true;
        queue.offer("__DONE__");
    }
}
