# Notes — Profiling the bidder

A working document covering **profiling concepts** (the learning side) and
**what we actually observe when we profile this bidder** (the application
side). Filled in over time as we run profiles after each optimisation.

For deeper fundamentals on profiling and JVM internals, see the broader
learning modules at `performance-engineering/level-2-measurement-and-profiling`
and `level-7-jvm-performance`. This doc is **bidder-specific** — what we
do here, what we see, what we change as a result.

---

## Concepts

### What profiling actually measures

Profiling answers one question: **"what is my code spending time on?"**
Different profiler types measure different things. Pick the right one for
the question you're asking.

| Type | Question it answers | When to use |
|---|---|---|
| **CPU time** | Which methods are running on the CPU the most? | "Why is this CPU-bound code slow?" |
| **Wall-clock time** | Which methods are *taking* the longest, even when blocked on I/O? | "Why is this I/O-bound code slow?" — this is what we want for a bidder |
| **Allocation** | Which methods are creating the most garbage? | "Why is GC running so often?" |
| **Lock contention** | Which threads are blocked on which locks? | "Why aren't my cores saturating under load?" |
| **Heap snapshot** | What objects exist in memory right now? | "Where's my memory going? Memory leak?" |

A naive timer-based "stopwatch" profiler only captures CPU time, which
**misses the entire story for I/O-bound services like ours.** Most of a
bidder's time is spent waiting on Redis, not crunching numbers. Wall-clock
and allocation are the modes we'll use most.

### Why wall-clock matters more than CPU for I/O-bound code

Imagine a method that takes 1 ms total — 50 µs CPU + 950 µs blocked on
Redis. A CPU profiler shows the method using 50 µs (5% of its actual
duration). A wall-clock profiler shows 1 ms (the truth from the user's
perspective).

For RTB pipelines where each request spends 80%+ of its time waiting on
network/Redis, wall-clock is the only honest measurement.

### Two tools we'll actually use

| Tool | What it is | Strengths | Pitfalls |
|---|---|---|---|
| **JFR (Java Flight Recorder)** | Built into the JVM (free since Java 11). Continuous low-overhead recording. | Always-on production profiling. Catches issues that only appear under real load. Records GC, JIT, locks, I/O — not just CPU. | The native UI (Java Mission Control) is clunky; most people convert JFR files to flame graphs |
| **async-profiler** | Open-source native agent. Generates flame graphs directly. | Best for "what's hot RIGHT NOW?" on a running process. Supports CPU, wall-clock, alloc, lock modes. | Need to attach manually (download agent, point JVM at it); learning curve |

There are others (VisualVM, JProfiler, YourKit) but JFR + async-profiler
cover ~95% of what we need.

### Microbenchmarks vs profiling — different concepts

| | Profiling | Microbenchmarking (JMH) |
|---|---|---|
| Question | "Why is the whole app slow?" | "Why is this one method slow?" |
| Scope | Full running app under load | One method in isolation |
| Tool | JFR, async-profiler | JMH (Java Microbenchmark Harness) |
| When | Find the bottleneck | Optimise a known hot path |

Order of operations: **profile first → identify hot path → microbenchmark
that path → optimise → profile again to verify.** Don't microbenchmark
random code; you'll waste time on cold paths.

---

## Flame graphs — the most important visualisation

A flame graph displays where time is spent across the call stack as a
horizontally-stacked rectangle layout.

```
                     ┌─── pipeline.execute() (100%) ────────────────┐
                     │                                               │
            ┌─ FrequencyCapStage.process() (60%) ─┐  Scoring (30%)   │
            │                                     │                  │
   ┌─ MGET ─┐ ┌─ filter ─┐                        │ scorer.score()   │
   │ (50%)  │ │  (10%)   │                        │      (30%)       │
```

### How to read it

- **Y-axis: call stack** — bottom = entry point (e.g. `pipeline.execute`),
  top = the leaf methods actually doing work
- **X-axis: proportion of samples** — wider boxes = more time spent there
- **Wider leaves at the top = your hot spots**
- **Wide unexpected boxes = surprises worth investigating**

### Patterns and what they mean

| Pattern | Likely meaning |
|---|---|
| Wide box on `Object.wait()` / `LockSupport.park()` | Threads blocked on locks or queues — investigate contention |
| Wide box on `java.util.HashMap.resize()` | Collections growing on the hot path — pre-size them |
| Wide box on `String.format()` / `StringBuilder` in hot path | String building on the hot path — refactor |
| Wide box on `Class.forName()` or reflection methods | Reflective dispatch — replace with method handles or direct calls |
| Wide box on a getter/setter | Megamorphic call site preventing JIT inlining — mark classes `final` |
| Big allocation flame on `Arrays.copyOf` | Collection re-allocating — pre-size or pool |
| Wide leaf on `RedisFuture.get()` | Blocking on Redis synchronously — may indicate a sync→async opportunity |
| Wide leaf on `Vert.x.executeBlocking()` callbacks | Worker pool saturation — bump pool size or remove blocking |

### Example: what a healthy bidder flame graph SHOULD look like

For our use case, the dominant boxes should be:

- **Network I/O wait** (~40%) — Redis MGET round-trip, Kafka publish
- **JSON parse/serialise** (~15%) — Jackson streaming codec
- **Pipeline stage iteration** (~10%) — orchestration overhead
- **Scoring** (~10%) — varies based on scorer type
- **Targeting filter** (~10%) — segment overlap math
- **Other** (~15%) — small allocations, metric updates, log calls

If anything outside these boxes is wider than ~5%, that's a surprise worth investigating.

---

## Tools setup for THIS bidder

### Enable JFR continuous recording

Add to `JVM_LOAD` in [Makefile](../../Makefile):

```makefile
JVM_LOAD := $(JVM_PROD) \
            -Drtb.log.level=WARN \
            -XX:+FlightRecorder \
            -XX:StartFlightRecording=duration=180s,filename=results/flight.jfr,settings=profile
```

Notes:
- `duration=180s` — auto-stops after 3 minutes, write JFR file
- `settings=profile` — higher detail than `default`; use `default` if overhead is concerning (typically <1%)
- File appears at `results/flight.jfr` after the duration elapses

### Open the JFR file

```bash
# Option A — JDK Mission Control (GUI, comes with JDK)
jmc results/flight.jfr

# Option B — convert to flame graph with async-profiler
java -jar async-profiler.jar -f results/flight.jfr -o flamegraph.html
open flamegraph.html
```

### One-shot async-profiler attach (for ad-hoc profiling)

When the bidder is already running and you want a focused profile without
restarting:

```bash
# Get bidder PID
PID=$(pgrep -f rtb-bidder)

# 60-second wall-clock profile → flame graph
java -jar async-profiler.jar -d 60 -e wall -f results/wall-flame.html $PID
open results/wall-flame.html

# 60-second allocation profile (where's my GC pressure coming from?)
java -jar async-profiler.jar -d 60 -e alloc -f results/alloc-flame.html $PID
```

Trigger a stress test in another terminal during the 60 seconds:

```bash
make load-test-stress-5k
```

The flame graph will show what the bidder was doing during that window.

---

## Workflow — when to profile

1. **After each optimisation lands** — profile the new state, save the
   flame graph, note observations in the "Observations" section below.
2. **When you don't know what's wrong** — before guessing at a fix,
   take a wall-clock profile under representative load.
3. **When p99 climbs unexpectedly** — wall-clock + GC profile to find
   whether it's allocation-driven or contention-driven.
4. **Before declaring "we're done"** — final profile to confirm no
   surprise hot spots remain.

**Don't profile when:**
- Load is too low to be representative (idle bidder shows nothing useful)
- The system is broken (errors, timeouts) — fix correctness first
- You haven't formed a hypothesis to test (random profiling = wasted time)

---

## Observations log

This section is filled in as we actually run profiles. Each entry: date,
what we profiled, what we observed, what we changed as a result.

### YYYY-MM-DD — pre-async-switch baseline (TBD)

_To be captured before the Lettuce sync→async change ships, so we have
a "before" flame graph to compare against._

Plan: 60-second wall-clock profile during `load-test-stress-5k`.

Expected hot spots based on theory:
- `RedisFuture.get()` — sync API blocking worker threads
- Lettuce dispatcher thread serialisation
- GC / allocation activity (~2,700 pauses/min observed in v3)

To verify after profile lands: do these hypotheses match what async-profiler
actually shows?

### YYYY-MM-DD — post-async-switch (TBD)

_Filled in after the Lettuce sync→async switch lands. Re-run same profile,
compare flame graphs side-by-side._

Expected:
- `RedisFuture.get()` should drop dramatically as a CPU/wall hot spot
- New top-N may surface a different bottleneck (this is the value of
  re-profiling)

---

## Sources / further reading

- [JEP 328 — Flight Recorder](https://openjdk.org/jeps/328)
- [async-profiler GitHub](https://github.com/async-profiler/async-profiler)
- [Brendan Gregg — Flame Graphs explainer](https://www.brendangregg.com/flamegraphs.html)
- [Java Mission Control docs](https://docs.oracle.com/javacomponents/jmc-5-4/jmc-user-guide/toc.htm)
