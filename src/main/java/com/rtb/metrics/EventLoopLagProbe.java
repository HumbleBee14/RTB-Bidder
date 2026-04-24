package com.rtb.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Measures event-loop lag — the #1 symptom of trouble in a Vert.x/Netty system.
 *
 * Schedules a task on the event loop every {@value #PROBE_INTERVAL_MS}ms.
 * If the task runs more than the interval after it was scheduled, something
 * blocked the event loop for that extra time. The excess (actual - expected)
 * is recorded as lag.
 *
 * Why it matters: Vert.x runs all handlers on a small pool of event-loop
 * threads (2 × cores by default). Any blocking call — a slow Redis command,
 * a synchronous Kafka publish, a CPU-hot JSON parse, a log flush — blocks
 * the loop and queues every other request behind it. End-to-end latency
 * metrics tell you WHEN things got slow; event-loop lag tells you WHY.
 *
 * Healthy loop: lag p99 under 1ms.
 * Blocked loop: lag p99 jumps to tens or hundreds of ms under load.
 */
public final class EventLoopLagProbe {

    private static final Logger logger = LoggerFactory.getLogger(EventLoopLagProbe.class);

    private static final long PROBE_INTERVAL_MS = 100;

    private final Vertx vertx;
    private final Timer lagTimer;
    private long timerId = -1;

    public EventLoopLagProbe(Vertx vertx, MeterRegistry registry) {
        this.vertx = vertx;
        this.lagTimer = Timer.builder("event_loop_lag_seconds")
                .description("Event-loop scheduling drift (actual - expected interval)")
                .publishPercentiles(0.5, 0.9, 0.99, 0.999)
                .register(registry);
    }

    public void start() {
        long expectedNextFireAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROBE_INTERVAL_MS);
        final long[] scheduled = { expectedNextFireAt };
        this.timerId = vertx.setPeriodic(PROBE_INTERVAL_MS, id -> {
            long now = System.nanoTime();
            long lag = now - scheduled[0];
            if (lag > 0) {
                lagTimer.record(lag, TimeUnit.NANOSECONDS);
            }
            scheduled[0] = now + TimeUnit.MILLISECONDS.toNanos(PROBE_INTERVAL_MS);
        });
        logger.info("Event-loop lag probe started (interval={}ms)", PROBE_INTERVAL_MS);
    }

    public void stop() {
        if (timerId != -1) {
            vertx.cancelTimer(timerId);
            timerId = -1;
        }
    }
}
