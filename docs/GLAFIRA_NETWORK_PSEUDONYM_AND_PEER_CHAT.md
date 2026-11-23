# GLAFIRA Network Pseudonym, Invitation and Peer Chat

**Status:** owner-approved product direction; specification baseline, not a claim of completed runtime implementation. Related: #358 and #361.

## Product flow

Each GLAFIRA has a human-readable network pseudonym (handle). The owner asks GLAFIRA to create/refresh a shareable invitation key, copies it into any external messenger and sends it. The recipient pastes it into their GLAFIRA and says **"Создай чат"**. GLAFIRA validates it, resolves the peer identity, requests first-contact owner approval, establishes a protected relationship and creates a separate persistent one-to-one chat.

The home screen shows peer conversations as first-class rows alongside the assistant and agent entries. Each row is named by the peer's network pseudonym; a private owner-local nickname/avatar may override display. A peer chat is not the general assistant transcript and not an Agent task thread.

Manual copy/share is always supported. Optional messenger connector sending is only available when the connector is authorized and the owner explicitly selects and confirms the exact recipient and message. Never silently scrape contacts or bulk-send.

## Identity model

Keep identifiers distinct:
- `accountId`: immutable GLAFIRA account identity.
- `deviceId`: enrolled device identity.
- `coreId`: installation/Core identity.
- `networkHandle`: human-readable owner-selected pseudonym; mutable display/routing alias, never a security principal.
- `keyEpoch`: monotonic identity-key generation.
- `conversationId`: stable local peer-chat identity.
- `peerAccountId`: immutable remote account bound to the conversation.

The handle resolves through authenticated directory/rendezvous to signed current device/key records and short-lived endpoint candidates. The handle survives IP changes, Wi-Fi/mobile transitions and relay changes. Define rename, collision, recovery, anti-impersonation, tombstone/reassignment and revocation policies. Never derive identity from IP.

## Invitation card

A versioned, signed card (text, QR and/or deep link) contains protocol/schema version, network handle, opaque account ID, public-key fingerprint and signature, rendezvous service identifier, high-entropy short-lived single-use or narrowly scoped invitation capability, expiry/revocation metadata and optional short-lived endpoint hints.

Never include private keys, permanent bearer credentials, private LAN addresses, passwords or chat secrets. Possession is bootstrap proof, not blanket authorization. Protect against tampering, expiry, replay and downgrade. The owner can revoke/refresh the card.

## Recipient and chat lifecycle

1. Paste the card into the GLAFIRA composer or "Add GLAFIRA contact".
2. Validate schema, signature, fingerprint, expiry, revocation and replay status.
3. Resolve handle/account; show identity and requested scopes.
4. Require explicit recipient-owner approval for first contact and minimal scopes.
5. Create durable contact and separate `conversationId` bound to immutable `peerAccountId` and verified keys.
6. Add a separate home-screen chat row named by network pseudonym.
7. Persist contact/history across restarts and offline periods. Handle changes update routing/display, not identity/history.

Provide block, disconnect, revoke, re-pair, key-rotation and removal controls. Local nickname is private to that installation.

## Discovery and transport

Use authenticated rendezvous/signaling. Attempt direct connectivity when viable (ICE with STUN); use authenticated encrypted relay for NAT/CGNAT/carrier firewall restrictions. Do not promise raw cellular IP dialability or require both peers on the same Wi-Fi. Support IPv4/IPv6 and Wi-Fi/mobile route migration.

Separate signaling from message transport. Preserve peer identity and E2EE across route changes. Rendezvous/relay must not see plaintext. Prefer maintained audited E2EE protocols; evaluate Matrix and hybrid Matrix + WebRTC before any custom transport. No hand-rolled cryptography.

Messages need stable IDs, durable inbox/outbox, ACKs, bounded retries, deduplication, offline queue and sent/delivered/failed states. Reconnect must not lose or duplicate messages. Unexpected identity/key changes fail closed.

## Platform and security contract

Android, Windows Desktop and future iOS share versioned identity/invitation/contact/conversation contracts. Windows Agents are subordinate workers, not human peer contacts. Peer chat does not transfer Core task ownership or grant tool permissions.

Require explicit first-contact approval; short-lived revocable invitations; secure platform key storage; authenticated signaling; E2EE; metadata minimization; anti-replay/impersonation/handle-squatting/downgrade controls; no plaintext or invitation secrets in relay logs. Messenger sends require connector authorization and recipient/content confirmation.

Main screen separates GLAFIRA assistant, configured agents/modules and peer chats. Peer rows show verification and connectivity state, last activity and delivery state; never present raw IP as identity.

## Acceptance gates

1. Create card on device A, transfer via arbitrary messenger, paste on B, approve and create separate chat.
2. Chat is a distinct home-screen row and persists, bound to immutable peer account ID.
3. Valid card works; expired/revoked/malformed/tampered/replayed/wrong-version cards fail closed.
4. IP changes, Wi-Fi/mobile migration, relay fallback and handle rename preserve identity/history.
5. Direct route works when viable; CGNAT/firewall uses encrypted relay.
6. Offline delivery is once-only with stable IDs/ACK; retries do not duplicate.
7. Rotation, revocation, blocking, disconnect and re-pair are tested.
8. Messenger send is opt-in, authorized and recipient-confirmed; manual sharing always works.
9. No plaintext or invitation capability appears in rendezvous/relay logs.
10. Android/Windows/iOS contract conformance and physical-device acceptance pass before feature completion is claimed.

## Delivery priority

Architecture, threat model and shared contracts may progress in parallel, but runtime work must not displace the owner's order: (1) Android GLAFIRA safely working with GitHub; (2) Windows Desktop Agent/Hub safely working with GitHub; (3) Core, backup/recovery, Bridge and Android↔Windows Core integration; (4) network pseudonyms, invitation discovery and dedicated peer chats. This document is a specification, not proof that directory, relay, messenger sending or complete peer-chat runtime exists.
