# GLAFIRA Dual-Core, pairing and recovery acceptance matrix

**Parent sprint:** #634 · **Tracker:** #637  
**WBS:** 1.1.3 Core transfer; 1.3.2 Bridge pairing; 1.2.2 Backup & Recovery; 1.7.4 CI/acceptance  
**Status:** acceptance specification; scenarios marked NOT IMPLEMENTED are not claimed as passing.  
**Rule:** emulator, unit tests and physical-device tests are distinct evidence classes.

| ID | Scenario | Required expected result | Existing evidence / status | Required acceptance layer |
|---|---|---|---|---|
| CORE-XFER-01 | Duplicate transfer envelope | One durable receipt and one owner-generation change; duplicate returns same receipt | PR #627 test `appliesTransferAtomicallyAndDuplicateSurvivesTaskStoreRestart`; exact-head CI pending after backup fix | Android JVM + emulator |
| CORE-XFER-02 | Concurrent competing transfers / split-brain attempt | Exactly one CAS winner; loser sees stale ownership; no dual owner | PR #627 test `concurrentTransfersHaveExactlyOneWinner` covers receiver-side concurrency, not full networked dual-Core split-brain | Android instrumentation + two-Core integration |
| CORE-XFER-03 | Network partition for 10 minutes, then reconnect | Durable pending state survives; retry delivery only; no handler/provider replay; eventual exact ACK | Not implemented end-to-end; #266 remains blocked by #366 | Two-Core integration / chaos |
| CORE-XFER-04 | Stale fencing token after owner generation advances | Old worker/result rejected; new owner generation remains authoritative | Main has execution-fencing coverage in #468; PR #627 stale ownership test is related but not a substitute for stale worker-token test | Android + Windows Hub integration |
| CORE-XFER-05 | Crash before receiver transaction commits | No partial owner change, receipt or Trace event | PR #627 injected receipt-insert rollback test covers transaction rollback, not process-kill at every instruction boundary | Android instrumentation / process-kill |
| CORE-XFER-06 | Crash after receiver commit but before ACK | Duplicate delivery returns committed receipt; sender retains outbox until exact ACK; no re-execution | PR #627 duplicate/restart covers local receipt; sender/ACK path not implemented | Two-Core integration / process-kill |
| CORE-XFER-07 | Duplicate exact ACK | Idempotent acceptance; outbox remains terminal/acknowledged exactly once | Not implemented for Android Core transfer ACK | Android + Windows integration |
| CORE-XFER-08 | ACK with wrong task, generation, recipient or digest | Reject; retain outbox; never infer success | Not implemented for Android Core transfer ACK | Contract + integration |
| CORE-XFER-09 | Replayed nonce / transfer ID | Reject replay; exact duplicate ID+digest returns prior receipt; conflicting digest rejected | PR #627 tests nonce replay; transfer-ID conflict path needs explicit assertion | Android JVM + emulator |
| PAIR-10 | Revoked peer / old key epoch | New requests fail closed after revocation or rotation; no race with in-flight acceptance | PR #627 revoked-peer test and synchronized peer trust lease; add explicit key-epoch rotation test | Android instrumentation + two-device |
| PAIR-11 | Expired QR one-time proof | Enrollment rejected; no credential or scope issued | Existing pairing contract #21 includes proof validation; verify exact test and current host wiring | Android + Windows |
| PAIR-12 | SAS mismatch / owner declines | Pairing aborted; no peer trust, key pin or scopes persisted | Not implemented / evidence not yet found | Android↔Windows physical |
| PAIR-13 | Wi-Fi change / endpoint or certificate rotation / reconnect | Re-authenticate pinned identity; old endpoint/key rejected; no silent trust | Existing Bridge contracts cover pinning; end-to-end rotation acceptance remains open in #22 | Two-PC LAN integration |
| BACKUP-14 | Clean install / clean-device restore | Archive verifies; expected data counts/hashes match; destination keys re-encrypt payloads; excluded authority is not restored | Existing backup instrumentation covers snapshot and restore paths; #25 still open for clean-device/physical acceptance | Android instrumentation + physical device |
| BACKUP-15 | Interrupt backup/restore at journal and swap boundaries | Resume or verified rollback; no partial state exposed; dispatch stays paused until healthy | Existing recovery journal/rollback tests; full process-death matrix remains open | Android instrumentation / process-kill |
| BACKUP-16 | Corrupt, oversized, wrong-password or unsupported archive | Reject before mutation; preserve current state | Existing wrong-passphrase, malformed snapshot and truncated AES-GCM tests; add explicit size/version cases if absent | Android JVM + instrumentation |
| BACKUP-17 | Secret exclusion | No API key, OAuth token, password, HMAC secret, pairing ticket, owner session or Keystore private key in archive/logs | Existing preference allowlist/secret-exclusion tests; complete audit across all stores remains open | Automated archive inspection + security review |
| E2E-18 | Physical Android↔Windows pairing, revoke, transfer, ACK and reconnect | Authenticated peer pair; visible SAS match; revocation blocks; transfer and exact ACK survive reconnect | NOT RUN. Emulator does not satisfy this gate. | Owner physical acceptance with screenshots/video and logs |

## Evidence record required per run

- Scenario ID and exact source SHA.
- PR/merge SHA and exact workflow run/job IDs.
- Device/OS/app versions; for physical tests, device models and network topology.
- Test command or manual procedure; expected and actual result.
- JUnit/XML, redacted logs, screenshot/video evidence and artifact links.
- Failure classification: source defect, test defect, environment, timeout, cancellation, skipped, or owner acceptance pending.
- For network/chaos cases: partition duration, reconnect time, retry count, task/result/ACK IDs and proof no handler replay occurred.

## Execution gates

1. No scenario is PASS if it was skipped, cancelled, not executed, or tested only on a different SHA.
2. Tests for the sender/receiver Inbox/Outbox/ACK cannot be marked complete until both Core implementations and the authenticated encrypted transport exist.
3. Physical scenarios remain OWNER_ACCEPTANCE until run on actual devices and evidence is attached.
4. The current Core receiver PR #627 is only one slice; it does not satisfy CORE-XFER-03, 06–08 or E2E-18.
5. The current backup format remains authoritative. No Restic/Borg migration is part of this matrix.
