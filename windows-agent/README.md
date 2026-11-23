# GLAFIRA Windows Agent

The Windows Agent is a separate background worker, not a Desktop clone. It pairs with a GLAFIRA Desktop Hub and runs as a Windows service.

## Ready-to-install package

Download the GitHub Actions artifact named `GLAFIRA-Agent-win-x64` from a successful **GLAFIRA Windows deployment packages** run and extract it on the Agent computer. The ZIP already contains `publish\win-x64` and the `scripts` folder; no .NET SDK is required on the target PC.

For developers who build from source, the self-contained x64 output is written to `windows-agent\publish\win-x64` by `windows-agent\scripts\publish-agent.ps1`.

## Install

Open PowerShell **as Administrator** on the Agent computer:

~~~powershell
.\scripts\install-agent.ps1
~~~

The installer creates the `GLAFIRA Agent` automatic Windows service under LocalSystem and verifies the service identity because the credential vault uses machine-scoped DPAPI.

## Pair with Desktop

1. On the Desktop computer, start Bridge HTTPS and create a one-time pairing ticket. The ticket expires after five minutes.
2. On the Agent computer, open an elevated PowerShell window and run:

~~~powershell
& "$env:ProgramFiles\GLAFIRA\Agent\GLAFIRA.Agent.Worker.exe" --pair
~~~

3. Enter the Desktop HTTPS endpoint, TLS SHA-256 fingerprint, ticket ID, one-time pairing code and Agent ID. The pairing code is masked while typing.
4. The Agent saves the credential as a DPAPI LocalMachine-protected blob under `%ProgramData%\GLAFIRA\Agent`. The directory and file ACLs grant access only to LocalSystem and local Administrators.
5. Restart the Windows service so it loads the new credential:

~~~powershell
Restart-Service "GLAFIRA Agent"
~~~

6. In GLAFIRA Desktop, select the pending Agent, assign only the capabilities it should receive, choose the parallelism limit and explicitly approve it. The Agent should appear online within 30 seconds after approval; no second restart is required.

## Update without replacing enrollment

Do not run `install-agent.ps1` over an existing service. Download the new package without extracting it, obtain that ZIP's SHA-256 from a separately trusted release manifest/channel, and run the updater installed with the current Agent from an elevated PowerShell session:

~~~powershell
& "$env:ProgramFiles\GLAFIRA\Agent\scripts\update-agent.ps1" `
  -PackagePath ".\GLAFIRA-Agent-win-x64.zip" `
  -ExpectedPackageSha256 "<64 lowercase hex characters>"
~~~

The updater verifies the ZIP before extraction, builds a complete payload, verifies the staged executable before stopping the service, swaps same-volume directories without deleting/recreating the service, waits for the existing LocalSystem service to become healthy, and verifies that the machine enrollment blob and its ACL are unchanged. A failed start or health check restores the previous binaries and lifecycle scripts and restarts the old service. Staging and rollback directories are removed after a conclusive outcome.

This is a digest-pinned update transaction, not proof of release signing. Until an approved Windows signing certificate and signed installer/catalog are available, unsigned CI packages remain non-production and must not be treated as trusted merely because their digest is known.

## Current task execution boundary

The service currently registers exactly one typed task handler:

- `diagnostics.snapshot` — bounded, read-only collection of a fixed set of non-identifying runtime facts (Windows platform, process/OS architecture and .NET runtime version). It writes a redacted, content-addressed artifact and returns its reference and digest.

The Agent does have task polling, authenticated lease handling, durable execution journal, result-artifact outbox and exact Hub ACK infrastructure. The handler registry is not empty: this diagnostics handler is registered in `Program.cs`. Do not describe the current build as a general-purpose remote executor.

The following remain disabled or unimplemented:

- arbitrary shell/process execution;
- arbitrary filesystem, registry, environment-variable or installed-software inspection;
- non-read-only task effects;
- general-purpose Windows coding/GitHub task execution;
- a broader handler set beyond the fixed diagnostics snapshot.

The executor fails closed for unknown task types, unapproved capabilities and every effect class other than `ReadOnly`. A connected Agent with no owner-approved capability profile does not claim tasks.

## Security and implementation boundary

- The Agent uses a pinned HTTPS pairing endpoint and a 256-bit one-time ticket.
- The shared secret is stored as a DPAPI LocalMachine-protected blob; plaintext is not written to disk.
- The credential directory and file are ACL-protected for LocalSystem and local Administrators.
- The Windows service runs as LocalSystem so it can decrypt the machine-scoped credential.
- Desktop enrollment creates a pending Agent with no capabilities. The owner explicitly assigns capabilities and approves it; revocation disables the credential and enrollment.
- The service sends a TLS-pinned, HMAC-signed Bridge heartbeat every 30 seconds. Pending Agents cannot refresh liveness or receive a capability profile.
- Task claims require the Agent to be in the Ready state with at least one registered handler.
- Results and redacted artifacts remain journaled/outboxed until the Hub acknowledges the exact task/fence/digest tuple.

## Remove

Open elevated PowerShell:

~~~powershell
.\scripts\uninstall-agent.ps1
~~~

The default uninstall retains the machine-protected enrollment file. To also remove it:

~~~powershell
.\scripts\uninstall-agent.ps1 -RemoveCredentials
~~~
