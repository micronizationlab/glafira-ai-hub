# GLAFIRA Bridge — physical LAN acceptance checklist

Status: implementation and CI coverage exist; **physical Android/Windows-or-macOS validation has not yet been performed**. Use synthetic project data and a private Wi-Fi LAN only.

## Before starting

- Install the Debug APK produced by the successful GitHub Actions run.
- Use an Android device and a Desktop computer on the same trusted private Wi-Fi network.
- Do not use guest Wi-Fi with client isolation, public Wi-Fi, port forwarding, a public relay or a hosted endpoint.
- Build/run the glafira-desktop-bridge-cli distribution from a real terminal. It requires a terminal because invitation, credential bundle and vault passphrase are entered with hidden input.
- Create a synthetic project and a disposable conversation. Do not use real customer data for first acceptance.

## Positive pairing and request flow

1. On Android, open Settings → Security → Device pairing.
2. Grant notification permission when Android asks. Enter/confirm the Android device's private IPv4 address and press Start.
3. Confirm the persistent GLAFIRA notification says the local channel is active. Keep Android awake for the test.
4. On Android, authenticate as owner and create a one-time invitation. Copy it to the Desktop clipboard.
5. Run the Desktop CLI and select Pair. Paste the invitation into its hidden prompt. Request only project.read, conversation.read and conversation.append.
6. On Android, refresh the pending queue. Verify the device label/platform and requested scopes. Explicitly select the actual granted scopes and approve.
7. Copy the owner-issued credential bundle from Android. Paste it into the Desktop CLI's hidden prompt and create a strong vault passphrase.
8. On Desktop, use the imported credential to read the synthetic project. Expect SUCCEEDED and the correct project name.
9. Read a synthetic conversation using the exact projectId_agentName conversation ID. Verify project isolation and message contents.
10. Append a clearly synthetic test message. Verify the message appears once in the Android chat history.
11. Stop the Android service. Confirm Desktop requests fail closed. Start it again and repeat a read request.
12. Stop and restart the Android app process. Confirm the owner screen does not show a stale server-running state; explicitly start the service again.

## Negative/security checks

- Expired invitation (wait more than 120 seconds) is rejected.
- Reusing a consumed invitation is rejected and does not create a second pending enrollment.
- Rejecting the enrollment on Android never creates a Desktop credential.
- A credential bundle with an unknown scope, duplicate JSON field, missing field, wrong field type or extra field is rejected by Desktop import.
- Wrong vault passphrase and tampered vault ciphertext fail authentication.
- A changed certificate pin is rejected. A wrong hostname/IP must fail hostname verification.
- An unpaired device, revoked/unknown credential, wrong HMAC, expired request and replayed idempotency key are rejected.
- A conversation ID for another project, unknown agent or path traversal is rejected.
- APPROVAL_REQUEST fails closed until the durable owner action-approval queue is implemented.
- No raw invitation capability, HMAC secret or passphrase appears in logs or normal terminal output.

## Record results

Record Android model/version, Desktop OS/JDK version, APK commit SHA, Desktop CLI commit SHA, Wi-Fi topology, and pass/fail for each step. Do not paste invitation JSON, credential bundles, passphrases or real chat contents into issue comments.

A successful CI build is not a substitute for this physical LAN acceptance. Do not call Android↔Desktop connectivity release-ready until the positive flow and negative checks pass on actual devices.
