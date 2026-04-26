# RTB Bidder

Production-grade Real-Time Bidding engine in Java 21. Accepts OpenRTB `POST /bid`, returns a bid within 50ms, scales to 50K+ req/s.

## Documentation map

Read to find info; update when making relevant changes — keep these living.

| Topic | File |
|---|---|
| Architecture, flow diagrams, module deep-dives | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Operations (setup, run, test, load test, troubleshoot) | [docs/GUIDE.md](docs/GUIDE.md) |
| Phased build roadmap | [docs/PLAN.md](docs/PLAN.md) |
| Per-phase implementation notes | `docs/phase-<N>-<slug>.md` (goal · what you build · what you learn · test) |
| Cross-cutting design essays | `docs/notes-<topic>.md` (not inside phase docs) |
| User-facing commands | [Makefile](Makefile) |

## User preferences

- Explain WHY, not just WHAT.
- Production patterns only — no hacky shortcuts. Prefer asking user for clarification instead of assuming anything.
- Language-neutral architecture explanations where possible (future Rust/C++ rewrite).
- Concise, professional tone.

## Workflow rule

Always read the plan before any new task or in ambiguity. Never build multiple phases in one go. For each phase: think and plan -> implement -> verify edge cases, common failure points, and test end-to-end, modular individual features and complete flow, only then propose the next phase. Also, if something needs to be tested in future due to dependency in later work, add a TODO note flag.


## Note

Treat `CLAUDE.md` and `docs/PLAN.md` as living documents. As work progresses — new phases, decisions, gotchas discovered, operational facts learned — update them in the same turn, without being asked. Prune stale entries. The goal is that any agent opening this repo later has an accurate, minimal picture of state without having to re-derive it.
