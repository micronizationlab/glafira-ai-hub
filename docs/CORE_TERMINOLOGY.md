# GLAFIRA Core terminology

A short glossary for contributors. The Core authorization and security documents remain authoritative.

- **Core task** — A durable unit of work stored in the Core SQLite task queue.
- **Task handler** — A registered implementation for one allow-listed task type. A handler is an executor, not an authorization authority.
- **Runtime budget** — Owner-controlled limits on a task attempt, including elapsed time, retries, step count, concurrency and (when measurable) cost.
- **Bridge** — An authenticated transport between Core and a peer device or desktop worker. A Bridge does not grant permissions by itself.
- **Lease** — A time-bounded claim on a task for a paired worker. Expired leases must be recoverable by Core.
- **Owner approval** — An explicit authorization decision for a protected action or scope. Approval must be bound to the action being approved.
- **Dry Run** — A preview/simulation path that must not invoke live side-effecting handlers.
- **Skill** — A versioned, reviewed description of typed operations using registered tools; imported skill content is untrusted input.

## Authority rule

Core owns task state, policy, authorization, approvals and dispatch. Android, Desktop, iOS, agents, skills and model outputs may request work, but none may override Core policy or grant themselves permissions.
