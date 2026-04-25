# Does Interface Abstraction Hurt Performance in Low-Latency Systems?

## The question

"Adding too many abstractions sometimes causes performance or latency lag" — does our pipeline pattern with interfaces (`PipelineStage`, `Scorer`, `BudgetPacer`, etc.) hurt the hot path?

## Short answer

No. Our interface calls add ~2-5 nanoseconds each. Our SLA is 50,000,000 nanoseconds. That's 0.00001% overhead. The JVM JIT compiler eliminates most of it entirely.

## Why interface dispatch is free in practice

The JVM's JIT compiler profiles running code. When it sees `PipelineStage.process()` is called in a loop with only 2-3 concrete types, it does one of:

1. **Monomorphic inlining** — only one implementation seen → direct call, zero overhead
2. **Bimorphic inlining** — two implementations seen → type check + direct call (~1ns)
3. **Megamorphic dispatch** — 3+ implementations → virtual dispatch via vtable (~5ns)

Even the worst case (megamorphic) is 5 nanoseconds. We have 8 pipeline stages max → 40ns total dispatch overhead on a 50ms budget.

## What actually costs latency in RTB

| Operation | Latency | Our total budget |
|-----------|---------|-----------------|
| Redis network round-trip | 0.5-2ms | 50ms |
| JSON parsing (ObjectMapper) | 0.1-0.5ms | 50ms |
| Kafka async publish | 0.05-0.1ms | 50ms |
| GC pause (ZGC) | <0.5ms | 50ms |
| **Interface dispatch (8 stages)** | **0.00004ms** | **50ms** |

The bottleneck is always I/O (Redis, Kafka, network) and GC, never interface dispatch. This is why Moloco, Criteo, and The Trade Desk all use interface-based pipeline patterns in production.

## When abstraction DOES hurt

The concern is real in specific cases — but none apply to us:

1. **Deep inheritance (5+ levels)** — each level adds a vtable lookup. We use flat interfaces, no inheritance chain.
2. **Autoboxing in tight loops** — `int` → `Integer` on every iteration allocates. We use primitives (long for nanos, double for price).
3. **Iterator/Stream chains** — `list.stream().filter().map().collect()` creates 4-5 intermediate objects per call. We use direct for-loops.
4. **Reflection/AOP proxies** — Spring's `@Transactional` wraps every call in a proxy with reflection. We don't use Spring.
5. **Excessive object wrapping** — `Optional<List<Map<String, Object>>>` style nesting. We use flat records and primitives.

## What we do instead

- **Flat loop** over `List<PipelineStage>` — the JIT's favorite pattern to optimize
- **Mutable BidContext** — one object per request, no intermediate allocations
- **Primitives** for timing (`long nanoTime`) — no boxing
- **Final classes** — `final` on all handlers and stages tells the JIT there's no subclass override
- **Phase 11** adds object pooling for BidContext (reuse instead of allocate/GC)

## The real performance principle

Abstraction overhead matters when it's **per-element in a tight loop processing millions of items** (like SIMD vs scalar in C++). It doesn't matter when it's **per-request in a 50ms budget with I/O being 99.99% of the cost**.

The pipeline pattern isn't an abstraction tax — it's an organization tool that costs nothing measurable and gives us the ability to add/remove/reorder stages without touching existing code.
