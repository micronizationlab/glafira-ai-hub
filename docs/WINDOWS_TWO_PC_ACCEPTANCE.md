# GLAFIRA Windows two-computer acceptance

This checklist validates Desktop ↔ Agent pairing, owner approval, authenticated heartbeat and revocation on two separate physical Windows PCs.

**Important scope:** the current Agent runtime supports secure enrollment and heartbeat/online status. It does not yet claim tasks, renew task leases, execute typed handlers or deliver result ACKs. Do not use this acceptance as proof of remote task execution.

## Preconditions

- [ ] Two separate Windows 10/11 x64 computers.
- [ ] Both are on the same trusted private LAN.
- [ ] Desktop computer's Windows network profile is Private.
- [ ] Both computers have automatic time synchronization enabled.
- [ ] Desktop HTTPS port TCP 8443 is reachable from the Agent computer on the local subnet.
- [ ] Download the two artifacts from the same successful **GLAFIRA Windows deployment packages** GitHub Actions run: `GLAFIRA-Desktop-win-x64` and `GLAFIRA-Agent-win-x64`. Open the run page and use its **Artifacts** section.
- [ ] Extract each ZIP on its target computer; both include the publish output and the matching install/uninstall scripts, so no .NET SDK is needed on the target PCs.

## Desktop/Hub computer

- [ ] Extract `GLAFIRA-Desktop-win-x64.zip`.
- [ ] Run elevated PowerShell: `.scriptsinstall-desktop.ps1`.
- [ ] Launch GLAFIRA Desktop from Start Menu.
- [ ] In Bridge, select the active private LAN address and click **Start HTTPS**.
- [ ] Confirm the endpoint is `https://<private-ip>:8443/bridge/v1`.
- [ ] Create a five-minute pairing ticket.
- [ ] Record endpoint, TLS SHA-256 fingerprint, ticket ID and one-time code. Do not put the code in chat, logs or screenshots.

## Agent computer

- [ ] Extract `GLAFIRA-Agent-win-x64.zip`.
- [ ] Run elevated PowerShell: `.scriptsinstall-agent.ps1`.
- [ ] Confirm service `GLAFIRA Agent` is installed and running as LocalSystem.
- [ ] Run the pairing wizard from elevated PowerShell:
  `& "$env:ProgramFiles\GLAFIRA\Agent\GLAFIRA.Agent.Worker.exe" --pair`.
- [ ] Enter the Desktop endpoint, TLS fingerprint, ticket ID and masked pairing code. Use a stable Agent ID.
- [ ] Confirm the wizard reports successful enrollment and does not print the shared secret.
- [ ] Restart the service: `Restart-Service "GLAFIRA Agent"`.

## Owner approval and heartbeat

- [ ] In Desktop, refresh the Agents tab.
- [ ] Confirm the new Agent appears pending, not approved and offline.
- [ ] Assign only the capabilities intended for this device and a conservative parallelism limit (start with 1).
- [ ] Explicitly approve the Agent.
- [ ] Within 30 seconds, confirm the Agent appears online after a valid signed heartbeat.
- [ ] Confirm the Agent service logs show Connected and the approved capability count. No task execution is expected yet.

## Recovery and revocation

- [ ] Restart the Agent service; confirm it reconnects without repeating pairing.
- [ ] Stop and restart the Desktop HTTPS host; confirm the Agent reconnects after HTTPS is available.
- [ ] Revoke the Agent from Desktop.
- [ ] Confirm it becomes offline and its credential/enrollment is disabled.
- [ ] Confirm the revoked Agent cannot become online again without a new pairing ticket and owner approval.

## Firewall and credential checks

- [ ] Confirm the firewall rule is inbound TCP 8443, Private profile only, RemoteAddress LocalSubnet.
- [ ] Confirm no Public-profile or internet-wide inbound rule was created.
- [ ] Confirm the credential file exists under `%ProgramData%\GLAFIRA\Agent\enrollment.dpapi`.
- [ ] Confirm the credential file is not readable by ordinary non-administrator users; its DACL should contain only LocalSystem and Administrators.
- [ ] Confirm the credential file contains DPAPI ciphertext, not JSON/plaintext.

## Evidence to record

- Desktop private IP and Hub ID (do not publish the pairing code).
- Agent ID and Windows service state.
- Time from owner approval to online heartbeat.
- Agent log lines for Enrolled → AwaitingOwnerApproval → Connected.
- Revocation time and time to offline.
- Any error codes, with credentials and pairing codes redacted.

## Pass criteria

The acceptance passes only if a physically separate Agent PC enrolls against the Desktop's pinned HTTPS identity, remains denied before owner approval, becomes online after approval, reconnects after restarts, and loses access after revocation. This does not close task execution acceptance; task claim, lease renewal, typed handler execution and result ACK require their own tests.
