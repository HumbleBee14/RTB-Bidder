package com.rtb.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Simple circuit breaker state machine: CLOSED → OPEN → HALF_OPEN → CLOSED.
 *
 * CLOSED:    normal operation. Failures increment counter.
 * OPEN:      after N failures, stop calling the dependency entirely. Return fallback.
 * HALF_OPEN: after cooldown, allow ONE test call. Success → CLOSED. Failure → OPEN.
 *
 * Why: a slow/down dependency without a circuit breaker causes cascading failure.
 * Every request waits for timeout (e.g., 5s), thread pool fills up, all requests fail.
 * Circuit breaker short-circuits after N failures — instant fallback, no waiting.
 *
 * Thread-safe: AtomicReference for state, AtomicInteger for failure count.
 */
public final class CircuitBreaker {

    private static final Logger logger = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold;
    private final long cooldownMs;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);

    public CircuitBreaker(String name, int failureThreshold, long cooldownMs) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
    }

    /**
     * Execute an operation through the circuit breaker.
     * If OPEN → returns fallback immediately (no call to supplier).
     * If CLOSED/HALF_OPEN → calls supplier, tracks success/failure.
     */
    public <T> T execute(Supplier<T> operation, Supplier<T> fallback) {
        State currentState = state.get();

        if (currentState == State.OPEN) {
            if (shouldAttemptReset()) {
                return tryHalfOpen(operation, fallback);
            }
            return fallback.get();
        }

        return tryExecute(operation, fallback);
    }

    /** Execute a void operation (e.g., Kafka publish). */
    public void execute(Runnable operation, Runnable fallback) {
        execute(() -> { operation.run(); return null; }, () -> { fallback.run(); return null; });
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
            failureCount.set(0);
            logger.info("Circuit breaker [{}]: HALF_OPEN → CLOSED (recovered)", name);
        } else {
            failureCount.set(0);
        }
    }

    private void onFailure(Exception e) {
        lastFailureTime.set(System.currentTimeMillis());
        int failures = failureCount.incrementAndGet();

        if (state.get() == State.HALF_OPEN) {
            state.set(State.OPEN);
            logger.warn("Circuit breaker [{}]: HALF_OPEN → OPEN (test failed: {})", name, e.getMessage());
        } else if (failures >= failureThreshold) {
            state.set(State.OPEN);
            logger.warn("Circuit breaker [{}]: CLOSED → OPEN after {} failures ({})",
                    name, failures, e.getMessage());
        }
    }

    private boolean shouldAttemptReset() {
        return System.currentTimeMillis() - lastFailureTime.get() >= cooldownMs;
    }

    public State getState() { return state.get(); }
    public int getFailureCount() { return failureCount.get(); }
    public String getName() { return name; }
}
