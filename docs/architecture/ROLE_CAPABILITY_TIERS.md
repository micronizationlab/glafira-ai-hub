# Role Capability Tiers: Standard and Premium

**Status:** Accepted product/architecture requirement; implementation deferred.  
**Owner decision:** 2026-11-23.  
**Implementation gate:** Begin only after the currently prioritized Android GitHub workflow, Windows Desktop GitHub workflow, Core ownership/result-delivery, Backup & Recovery and Bridge integration tasks have reached their acceptance gates.

## 1. Product decision

GLAFIRA specialist roles are first-class role definitions. Every existing and future role belongs to the **Standard** role category as its baseline product tier. Each role may later receive its own separately purchasable **Premium Upgrade**, unlocking that role's expanded capability set.

In the current development stage, **all roles operate with Premium-level capabilities by default**. This is the development/product baseline and must not be interpreted as an instruction to remove, hide, or restrict capabilities that already exist. The Standard/Premium commercial split is a future rollout.

## 2. Two capability tiers

- **Standard** — the baseline capability bundle for a role. It defines the default scope of that role after the future tier split is activated.
- **Premium** — an expanded capability bundle for the same role, enabled by a valid Premium Upgrade entitlement for that specific role.

The split is role-specific. Purchasing Premium for one role does not automatically upgrade other roles. The role identity, mission, responsibility boundaries, project assignment, conversation context and provider/model selection remain separate from the tier entitlement.

The exact Standard and Premium capability lists for each role are a later product-design task. Do not invent or silently impose feature restrictions before those per-role matrices are approved.

## 3. Main-screen presentation

The main agent/role list must display a tier status immediately beside each agent's name:

- `STANDARD` for a role currently operating with its Standard capability bundle.
- `PREMIUM` for a role with Premium capabilities enabled.

The indicator must be understandable without relying on color alone (text label plus accessible semantics). It should remain visually compact and consistent across Android and Windows Desktop. In the current development stage, every role's status is `PREMIUM`.

The status represents the effective capability tier, not whether the agent is online, which model is selected, whether a provider quota is exhausted, or whether the agent is busy. Those states must remain independently represented.

## 4. Entitlement and upgrade model (future)

When commercial activation is explicitly scheduled:

1. Maintain a versioned capability manifest for each role and tier.
2. Resolve the effective tier per role from the role's valid entitlement; default to Standard only after the future rollout is deliberately activated.
3. Support an independently purchasable Premium Upgrade for each role. The intended settlement currency is the GLAFIRA network token recorded in `docs/GLAFIRA_NETWORK_BUSINESS_AND_TOKEN_ECONOMY.md`; do not substitute fiat billing or a third-party payment processor by assumption.
4. Verify purchase/restore/refund/revocation through the authoritative Mother token ledger and trusted entitlement boundary. Token debit and role entitlement grant must be atomic or recoverably idempotent, bound to the account, stable role ID, entitlement version and ledger transaction. Do not trust a client-side flag or a value supplied by an LLM.
5. Make entitlement checks available to the authoritative Core at execution time; UI visibility alone must never grant a capability.
6. Record entitlement changes and capability decisions in the audit/Trace trail without logging payment secrets or credentials.
7. Define offline behavior, entitlement expiry, refunds and revocation before enabling paid upgrades; fail closed for Premium-only operations when entitlement validity cannot be established, while preserving safe Standard functionality.
8. Keep the entitlement model portable across Android and Windows Desktop so the same owner identity and role have consistent effective capabilities across devices.

No billing SDK, payment processor, purchase screen, pricing, subscription, or charge flow is authorized by this architecture note. Those require a separate owner-approved implementation decision. Preserve the project's free-first principle and do not introduce paid infrastructure as a prerequisite for the basic platform.

## 5. Security and policy boundaries

- Premium is a capability tier, not an authority override.
- Premium must not bypass owner approval, Core ownership/generation fencing, least-privilege scopes, safety rules, audit requirements or external-action confirmation.
- A role may only use capabilities granted by both its effective tier and its project/account permissions.
- Model/provider selection does not change the role's entitlement or permissions.
- The UI may display the effective tier, but the Core remains authoritative for execution authorization.
- Downgrades, expired entitlements and revoked upgrades must not erase role data, history or owner-created configuration.

## 6. Deferred implementation sequence

This work is intentionally below the active delivery priorities and must not displace them.

1. After current Android, Windows Desktop, Core, Backup & Recovery and Bridge acceptance gates, inventory all existing role definitions and their current capabilities.
2. Define and owner-review a Standard/Premium capability matrix separately for each role.
3. Add a versioned role-tier/entitlement contract and shared Kotlin/C# conformance fixtures.
4. Implement Core-side capability resolution and tests for missing, expired, revoked, mismatched-role and cross-device entitlements.
5. Add the compact tier indicator beside each agent name on Android and Windows Desktop, initially showing Premium for every role.
6. Only after a separate commercial approval, implement purchase, restore, refund, revocation and customer-facing upgrade flows.
7. Verify that the Standard experience remains useful and that no existing capability is restricted before the approved rollout date.

## 7. Acceptance criteria

- Every role has an explicit role ID and a separately resolvable effective tier.
- Current pre-rollout behavior reports Premium for every role and preserves all existing role capabilities.
- Future Standard/Premium capability matrices are role-specific, versioned and testable.
- The Android and Windows Desktop main screens show the tier text beside each agent name, independently from online/provider/quota status.
- The Core rejects Premium-only operations without a valid role-matching entitlement once the future tier split is active.
- Premium never bypasses owner approval, Core fencing or least-privilege authorization.
- No billing or paid dependency is introduced before explicit owner approval.
