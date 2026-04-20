package com.rtb.resilience;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Circuit breaker with sliding window failure detection.
 *
 * CLOSED:    normal operation. Failures tracked in a time window.
 * OPEN:      after N failures within the window, stop calling dependency.
 * HALF_OPEN: after cooldown, allow ONE test call. Success → CLOSED. Failure → OPEN.
 *
 * Sliding window vs consecutive count:
 *   Consecutive: 4 failures → 1 success → resets to 0 → flapping dependency never trips
 *   Sliding window: 4 failures + 1 success in 60s = 4 failures → one more trips it
 */
public final class CircuitBreaker {

    private static final Logger logger = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold;
    private final long cooldownMs;
    private final long windowMs;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong windowStartTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicLong totalTrips = new AtomicLong(0);

    public CircuitBreaker(String name, int failureThreshold, long cooldownMs, long windowMs) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be >= 1, got: " + failureThreshold);
        }
        if (cooldownMs < 100) {
            throw new IllegalArgumentException("cooldownMs must be >= 100, got: " + cooldownMs);
        }
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
        this.windowMs = windowMs;
    }

    public CircuitBreaker(String name, int failureThreshold, long cooldownMs) {
        this(name, failureThreshold, cooldownMs, 60_000);
    }

    public <T> T execute(Supplier<T> operation, Supplier<T> fallback) {
        State currentState = state.get();

        if (currentState == State.OPEN) {
            if (shouldAttemptReset()) {
                return tryHalfOpen(operation, fallback);
            }
            return fallback.get();
        }

        // HALF_OPEN: only the thread that won the CAS in tryHalfOpen runs the test call.
        // All other threads arriving here while HALF_OPEN get fallback.
        if (currentState == State.HALF_OPEN) {
            return fallback.get();
        }

        return tryExecute(operation, fallback);
    }

    public void execute(Runnable operation, Runnable fallback) {
        execute(() -> { operation.run(); return null; }, () -> { fallback.run(); return null; });
    }

    /**
     * Record a failure from an external source (e.g., Kafka async callback).
     * Allows circuit breaker to detect failures even when exceptions are caught internally.
     */
    public void recordExternalFailure(Exception e) {
        onFailure(e);
    }

    private <T> T tryExecute(Supplier<T> operation, Supplier<T> fallback) {
        try {
            T result = operation.get();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure(e);
            return fallback.get();
        }
    }

    private <T> T tryHalfOpen(Supplier<T> operation, Supplier<T> fallback) {
        if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
            logger.info("Circuit breaker [{}]: OPEN → HALF_OPEN (testing)", name);
            try {
                T result = operation.get();
                onSuccess();
                return result;
            } catch (Exception e) {
                onFailure(e);
                return fallback.get();
            }
        }
        return fallback.get();
    }

    private void onSuccess() {
        if (state.get() == State.HALF_OPEN) {
            state.set(State.CLOSED);
            resetWindow();
            logger.info("Circuit breaker [{}]: HALF_OPEN → CLOSED (recovered)", name);
        }
        // CLOSED: success does NOT reset failure count — failures expire when window rolls over
    }

    private void onFailure(Exception e) {
        lastFailureTime.set(System.currentTimeMillis());

        if (isWindowExpired()) {
            resetWindow();
        }

        int failures = failureCount.incrementAndGet();

        if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
            totalTrips.incrementAndGet();
            logger.warn("Circuit breaker [{}]: HALF_OPEN → OPEN (test failed: {})", name, e.getMessage());
        } else if (failures >= failureThreshold && state.compareAndSet(State.CLOSED, State.OPEN)) {
            totalTrips.incrementAndGet();
            logger.warn("Circuit breaker [{}]: CLOSED → OPEN after {} failures in {}s window ({})",
                    name, failures, windowMs / 1000, e.getMessage());
        }
    }

    private boolean isWindowExpired() {
        return System.currentTimeMillis() - windowStartTime.get() >= windowMs;
    }

    private void resetWindow() {
        windowStartTime.set(System.currentTimeMillis());
        failureCount.set(0);
    }

    private boolean shouldAttemptReset() {
        return System.currentTimeMillis() - lastFailureTime.get() >= cooldownMs;
    }

    public void registerMetrics(MeterRegistry registry) {
        List<Tag> tags = List.of(Tag.of("name", name));
        registry.gauge("circuit_breaker_state", tags, this,
                cb -> cb.state.get() == State.CLOSED ? 0 : (cb.state.get() == State.OPEN ? 1 : 0.5));
        registry.gauge("circuit_breaker_failures", tags, this, cb -> cb.failureCount.get());
        registry.gauge("circuit_breaker_trips_total", tags, this, cb -> cb.totalTrips.get());
    }

    public State getState() { return state.get(); }
    public int getFailureCount() { return failureCount.get(); }
    public String getName() { return name; }
}
