# ADR-001: GLAFIRA Connect messaging and adaptive P2P transport

- **Status:** Proposed — owner review required; no runtime implementation decision is implied.
- **Date:** 2026-11-21
- **Related owner request:** [Issue #361](https://github.com/micronizationlab/glafira-ai-hub/issues/361)
- **Prior architecture:** [Merged network addressing and cloud retrieval PR #360](https://github.com/micronizationlab/glafira-ai-hub/pull/360)
- **Decision scope:** transport, durable delivery, identity binding, mobile bootstrap, route migration and offline behavior.

## 1. Context and owner experience

The owner wants two independent GLAFIRA accounts to establish contact by exchanging a text invitation:

1. Owner A asks: "Глафира, дай свежий псевдоним".
2. GLAFIRA creates a fresh, signed, short-lived invitation that can be copied from chat and sent through email or any messenger.
3. Owner B pastes the invitation into GLAFIRA. The client recognizes it, validates it and offers a first-contact connection. Owner B approves.
4. The initiating device may use its currently available cellular/mobile-data path to reach rendezvous/signaling. The invite is not a raw carrier IP address and does not assume inbound reachability through CGNAT.
5. Once both sides are online, the clients discover viable routes and prefer a direct peer path when possible. Each side may be on a different Wi-Fi network. If the Wi-Fi route is unavailable, use mobile data or an encrypted relay.
6. If a device changes from cellular to Wi-Fi or back, the session attempts route migration without changing peer identity or losing/duplicating messages.
7. The user can save the immutable GLAFIRA account identity under a local nickname, e.g. "Константин Берестнев".

The network handle, account ID and invitation token are separate concepts. A human-readable alias is not a cryptographic identity; a mutable IP address is never an identity.

## 2. Decision drivers

- Strong end-to-end confidentiality and authenticated peer identity.
- Reliable store-and-forward delivery when either device is offline.
- Optional direct P2P path for live chat and data exchange.
- Mobile ↔ Wi-Fi route migration during an active session.
- Works across Android, Windows Desktop/Agent and future iOS.
- No requirement for both owners to share one LAN or mobile carrier.
- Resilient to CGNAT, symmetric NAT, carrier firewalls, IPv4/IPv6 differences and changing addresses.
- Avoid hand-rolled cryptography and avoid making an always-on central server the plaintext recipient.
- Bounded relay cost and explicit observability of direct/relay route selection.
- Clear separation between rendezvous/signaling, durable messaging, and transport.

## 3. Free-only infrastructure constraint

GLAFIRA Connect must not require a paid Matrix homeserver, paid STUN/TURN provider, rented VPS, metered signaling API or mandatory subscription. Open-source software is preferred, but an open-source server still needs compute, storage, bandwidth and maintenance; software being free does not make hosting free.

- First evaluate whether an existing owner-controlled device/network can host the required services without new spending, and document its uptime, inbound reachability, storage, backup and bandwidth limits.
- A public community/free-tier homeserver or relay may be used only as an optional, replaceable best-effort path after checking its current terms, regional availability, quotas, privacy and account restrictions. Do not treat it as guaranteed infrastructure or a durable single point of failure.
- The client must support relay-disabled operation and explain when direct P2P is unavailable. Direct P2P is opportunistic and cannot be promised through CGNAT/firewalls.
- Prototype signaling may use a manual out-of-band exchange for test SDP/metadata if this avoids deploying a paid service; this is not a production-scale rendezvous solution.
- If no free, owner-controlled or explicitly approved infrastructure is available, defer the dependent deployment/feature rather than introduce a paid dependency.

This constraint is a release gate, not an optimization to revisit after implementation.

## 4. Options considered

### Option A — Matrix-only messaging

Use Matrix Client-Server API and encrypted rooms as the complete messaging system. GLAFIRA Directory resolves the invite/handle to a GLAFIRA account and its Matrix identity or homeserver. Matrix handles room membership, message history, synchronization, receipts, offline delivery and federation policy.

**Advantages**
- Established protocol for rooms, devices, event synchronization and offline message delivery.
- Mature end-to-end encryption implementation and device verification model.
- Less custom networking code; server-side store-and-forward works when devices are offline.
- Federation can be considered later without making it mandatory for the initial controlled deployment.

**Limitations**
- The message path is normally through one or more homeservers, not a direct device-to-device path.
- A homeserver stores encrypted events and still sees metadata such as room/event timing, account and network-level request metadata.
- Matrix does not by itself provide the requested cellular-bootstrap → adaptive direct Wi-Fi/P2P transport.
- Operating homeservers, federation, abuse controls, retention and account recovery introduce service operations.

**Assessment:** good durable messaging foundation, but insufficient by itself for the owner's desired direct-path behavior.

### Option B — WebRTC-only native messaging

Use a GLAFIRA-specific rendezvous/signaling service and WebRTC DataChannels as the chat transport. Use ICE candidate gathering with STUN and TURN fallback. The client maintains a local durable outbox and retries until the remote client acknowledges each message.

**Advantages**
- Direct device-to-device transport is possible when ICE connectivity checks find a viable path.
- DataChannels provide an encrypted peer transport using WebRTC's DTLS/SCTP stack.
- ICE restart can renegotiate a path when network conditions change.
- TURN can relay encrypted packets when direct connectivity is not possible.

**Limitations**
- WebRTC does not provide durable offline message history, account directory, room membership, multi-device sync, receipts or long-term delivery by itself.
- A custom reliable messaging protocol would need durable sender outbox, receiver inbox, exact ACK, deduplication, retries, ordering, membership changes, device revocation, history sync and recovery.
- ICE/TURN service operations and relay costs still exist; direct P2P cannot be guaranteed.
- DataChannel transport encryption must be bound to the authenticated GLAFIRA account/device identity through signed signaling and verified key fingerprints. Do not treat successful ICE connectivity as proof of identity.

**Assessment:** strong optional live transport, but too much reliability and identity infrastructure would need to be built from scratch if used alone.

### Option C — Hybrid Matrix durable messaging + WebRTC adaptive live path

Use Matrix with audited E2EE as the durable canonical conversation/event store and synchronization mechanism. Add WebRTC DataChannels as an optional live fast path for direct peer exchange, presence/typing, session control and suitable data transfer. Both paths use the same application-level message IDs and authenticated GLAFIRA identity binding.

**Advantages**
- Matrix supplies durable offline delivery, room membership, event history, multi-device synchronization and E2EE.
- WebRTC adds direct low-latency transport where network conditions allow.
- If direct connectivity fails, the conversation remains functional through Matrix; TURN is available for real-time sessions where appropriate.
- Network migration can use ICE restart without changing the account identity or conversation identity.
- Can roll out in stages: reliable Matrix chat first, then optional P2P live path.

**Limitations**
- More moving parts and two delivery paths to reconcile.
- Requires careful deduplication and ordering by immutable application message ID.
- Must prevent a direct-path message and its Matrix durable copy from appearing twice.
- Need explicit routing policy and privacy disclosure for metadata visible to Matrix homeservers and rendezvous/relay services.
- Cross-platform Matrix SDK bindings and WebRTC native libraries need pinned, tested versions and platform adapters.

**Assessment:** best fit for the combined requirements, subject to a small cross-platform prototype and owner approval.

### Option D — GLAFIRA-native HTTPS/WebSocket service

A native service could combine identity, event sync and signaling. It would still need an audited E2EE library and would reproduce many Matrix reliability features.

**Assessment:** defer. Reconsider only if the hybrid prototype demonstrates a material blocker (licensing, resource footprint, operational cost or integration limits) that cannot be solved by configuration or a supported SDK. Never implement bespoke cryptographic primitives.

## 5. Proposed direction

**Recommend Option C: Matrix for durable conversation state and E2EE; WebRTC DataChannel as an optional adaptive direct transport.** This is a recommendation for prototype validation, not authorization to start production runtime implementation.

### 4.1 Identity and invitation

A fresh invite should be a versioned, signed, expiring GLAFIRA Connect invitation containing:
- immutable opaque GLAFIRA Account ID;
- inviter device ID or a device-set reference and public-key fingerprint;
- protocol version and supported capabilities;
- rendezvous/signaling service identifier;
- Matrix identity/homeserver routing hint if the deployment uses Matrix;
- high-entropy, single-use or narrowly scoped invitation token, expiry and revocation reference;
- optional short-lived network hints discovered by the local client, only if useful and privacy-reviewed;
- signature over the canonical invitation payload.

Never include private keys, passwords, long-lived access tokens, raw private LAN addresses, or an unbounded bearer credential. Do not promise that a cellular IP is publicly reachable. A recipient must verify the signature, expiry, revocation and identity fingerprint, then approve first contact. Invite possession alone grants no access to private memory, files or other rooms.

### 4.2 Mobile bootstrap and rendezvous

"Mobile bootstrap" means the initiating device may choose its cellular data interface to reach the rendezvous/signaling service. It does not mean the recipient can directly dial a carrier IP. CGNAT, firewall rules, IPv6 policy and dynamic addressing may make direct inbound connectivity impossible.

Rendezvous exchanges only the minimum necessary identity-bound session metadata and ICE signaling. It must not receive plaintext conversation content. It must be authenticated, rate-limited, abuse-protected and resistant to invite enumeration/replay.

### 4.3 Adaptive route selection

- Gather viable host, server-reflexive and relay candidates using the platform WebRTC stack.
- Use STUN for server-reflexive discovery and TURN as a fallback when direct paths fail.
- Prefer a direct path only after ICE connectivity checks succeed and the peer identity/fingerprint is verified.
- Do not assume that Wi-Fi on both sides means same-LAN connectivity or direct reachability.
- Observe the active path category (direct LAN, direct reflexive, TURN relay, Matrix durable fallback) without exposing raw IP addresses in normal UI or logs.
- On interface/network change, attempt ICE restart/path migration. Preserve the old path until the new path is usable where the platform permits.
- Keep the Matrix durable channel available as the recovery and offline-sync path.
- Route policy must account for battery, mobile-data cost, metered networks, privacy and owner settings. Never silently use an expensive relay if the owner has disabled it; explain that live P2P is unavailable and fall back to ordinary durable messaging.

### 4.4 Message identity and delivery

Every application message has an immutable random message ID, conversation ID, sender account/device identity, creation time, payload digest and protocol version. The message body is end-to-end encrypted before it enters either transport path.

If a live message is delivered over WebRTC, the sender also commits the canonical encrypted event to Matrix for durable history/synchronization. The receiver deduplicates by application message ID when the same event arrives via Matrix later. If the direct path fails before durable receiver ACK, the sender retries through the canonical Matrix route. A direct transport ACK alone must not delete the sender's durable outbox unless the protocol proves receiver durable commit and the canonical history copy is safely committed.

Use at-least-once transport with idempotent receiver processing. Define exact ACK binding to conversation ID, message ID, sender, recipient/device scope and payload digest. Ordering must be explicit (per conversation sequence or causal metadata); do not assume DataChannel arrival order across reconnects or multiple devices.

### 4.5 Security and privacy

- Matrix E2EE is enabled for private rooms; implement device verification/cross-signing and key backup/recovery deliberately.
- WebRTC DTLS transport security must be cryptographically bound to the verified GLAFIRA device identity through authenticated signaling/transcript checks.
- A TURN server relays encrypted packets and must not terminate application E2EE.
- Directory, rendezvous, Matrix homeserver and TURN have distinct trust and data-retention boundaries. Document metadata each can observe.
- Device revocation invalidates future sessions and room key sharing according to the chosen Matrix/device policy; active sessions must close or reauthenticate.
- First contact and any new device must be owner-approved. Local aliases are private and editable.
- No plaintext message content in Directory/rendezvous/TURN logs. Avoid logging raw IPs beyond narrowly scoped operational security needs and documented retention.
- Never grant the other owner's GLAFIRA access to memory, files, credentials or unrelated conversations by connecting a chat.

## 6. Cross-platform contract

Keep the application-facing contract platform-neutral. Android, Windows Desktop/Agent and future iOS implement adapters for:
- invitation parse / canonical validation / signature verification / expiry / revocation;
- GLAFIRA Account ID and device identity;
- local contact alias mapping;
- rendezvous/signaling;
- Matrix session, room, E2EE and sync state;
- WebRTC peer connection, ICE candidate and DataChannel lifecycle;
- route telemetry (coarse category only);
- durable outbox/inbox, message ID dedupe and exact ACK;
- network change notifications and reconnect/ICE restart;
- owner approval, pause, revoke and relay-cost settings.

No platform may become a second authority for owner identity, policy or permissions. Core remains the owner-scoped task and approval authority.

## 7. Prototype plan and acceptance gates

### Prototype 1 — invitation and identity (no production chat)
- Generate a signed, expiring invitation on Android over cellular data.
- Transfer its text through an external messenger/email and redeem on a second test account.
- Reject malformed, tampered, expired, revoked and replayed invitations.
- Verify inviter identity through a separate fingerprint/QR confirmation and require first-contact approval.

### Prototype 2 — durable Matrix E2EE chat
- Establish one-to-one encrypted room between two test accounts.
- Test offline recipient, reconnect, history sync, multi-device login, device verification, device revocation and key recovery.
- Verify homeserver cannot read message content.
- Measure client binary size, memory, CPU, battery and integration cost on Android and Windows; evaluate Swift bindings for future iOS.

### Prototype 3 — WebRTC adaptive live path
- Connect devices on separate Wi-Fi networks and verify a direct path where available.
- Test cellular ↔ Wi-Fi change while a session is active; verify ICE restart and that the previous route remains until the new route is usable where supported.
- Test CGNAT/symmetric NAT/firewall scenarios and TURN relay fallback.
- Test relay-disabled policy and explicit fallback to Matrix durable messaging.
- Test simultaneous WebRTC/Matrix delivery, duplicate event suppression, offline outbox, receiver durable ACK, sender restart and replay.

### Release gates
- No data loss or duplicate visible messages across path migration, reconnect or dual-path delivery.
- Peer identity and encryption remain unchanged across route migration.
- Revoked devices cannot resume or receive future room keys.
- No private memory or file access is granted by chat invitation.
- Exact-head Android and Windows tests pass; iOS contract/SDK feasibility is documented before claiming parity.
- Relay usage, cost, battery and mobile-data behavior are measurable and owner-controllable.

## 8. Evidence and references

- [Matrix Client-Server API specification](https://spec.matrix.org/latest/client-server-api/) — rooms, sync and optional E2EE; cross-signing guidance is in the current spec.
- [Matrix Rust SDK repository](https://github.com/matrix-org/matrix-rust-sdk) — SDK layers for client API, UI, and crypto; repository states it supports embedding through Kotlin, Swift, JavaScript and Node bindings. Pin and validate versions; binding API stability must be tested.
- [Matrix Rust SDK encryption module](https://matrix-org.github.io/matrix-rust-sdk/matrix_sdk/encryption/index.html) — E2EE setup and room-key/device-verification model.
- [MDN: WebRTC session lifetime and ICE restart](https://developer.mozilla.org/en-US/docs/Web/API/WebRTC_API/Session_lifetime) — describes ICE restart when a peer changes between cellular and Wi-Fi and continuity while the new path is established.
- [MDN: RTCPeerConnection.restartIce()](https://developer.mozilla.org/en-US/docs/Web/API/RTCPeerConnection/restartIce) — API semantics and renegotiation behavior.

## 9. Consequences and open questions for owner review

1. Approve or reject the hybrid recommendation before runtime implementation.
2. Choose a free-only initial Matrix deployment model: an owner-controlled existing device if operationally viable, or an optional community/free-tier server with verified limits and replaceability. Do not assume a paid hosted service or new rented server. Federation is not required for the first private MVP.
3. Decide whether WebRTC direct transport initially carries only presence/typing/live sessions and file transfer, or also live chat payloads mirrored to Matrix.
4. Approve a free-only relay policy and per-owner mobile-data/relay budget controls. A paid TURN/STUN/signaling service is out of scope unless the owner explicitly changes the no-paid-resources rule.
5. Decide invite lifetime, one-time-use semantics and whether QR/deep-link is included alongside copyable text.
6. Define data retention and metadata minimization for Directory, rendezvous, homeserver and TURN.
7. Validate SDK license, maintenance, release cadence, binary footprint and platform binding stability before dependency adoption.

**No production dependency or transport implementation should be merged solely because this ADR exists.**
