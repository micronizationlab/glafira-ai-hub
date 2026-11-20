# GLAFIRA Windows Agent

The Windows Agent is a separate background-worker product, not a Desktop clone.

## Build and publish

From the repository root on a Windows machine with the .NET 8 SDK:

```powershell
.\windows-agent\scripts\publish-agent.ps1
```

The self-contained x64 publish output is written to `windows-agent\publish\win-x64`.

Copy the published directory to the Agent computer, or copy the repository's `windows-agent` directory and run the scripts there.

## Install

Open PowerShell **as Administrator** on the Agent computer:

```powershell
.\scripts\install-agent.ps1
```

The installer creates the `GLAFIRA Agent` automatic Windows service under LocalSystem. The installer verifies the service identity because the credential vault uses machine-scoped DPAPI.

## Pair with Desktop

1. On the Desktop computer, start Bridge HTTPS and create a one-time pairing ticket. The ticket expires after five minutes.
2. On the Agent computer, open an elevated PowerShell window and run:

```powershell
& "$env:ProgramFiles\GLAFIRA\Agent\GLAFIRA.Agent.Worker.exe" --pair
```

3. Enter the Desktop HTTPS endpoint, TLS SHA-256 fingerprint, ticket ID, one-time pairing code and Agent ID. The pairing code is masked while typing.
4. The Agent saves the credential as a DPAPI LocalMachine-protected blob under `%ProgramData%\GLAFIRA\Agent`. The directory and file ACLs grant access only to LocalSystem and local Administrators.
5. In GLAFIRA Desktop, select the pending Agent, assign only the capabilities it should receive, choose the parallelism limit and explicitly approve it.
6. Restart the Windows service:

```powershell
Restart-Service "GLAFIRA Agent"
```

## Remove

Open elevated PowerShell:

```powershell
.\scripts\uninstall-agent.ps1
```

The default uninstall retains the machine-protected enrollment file. To also remove it:

```powershell
.\scripts\uninstall-agent.ps1 -RemoveCredentials
```

## Security and implementation boundary

- The Agent uses a pinned HTTPS pairing endpoint and a 256-bit one-time ticket.
- The shared secret is stored as a DPAPI LocalMachine-protected blob; plaintext is not written to disk.
- The credential directory and file are ACL-protected for LocalSystem and local Administrators.
- The Windows service runs as LocalSystem so it can decrypt the machine-scoped credential.
- Desktop enrollment creates a pending Agent with no capabilities. The owner explicitly assigns capabilities and approves it; revocation disables the credential and enrollment.

The current increment implements installation, secure one-time enrollment, machine-protected credential storage, pending registration, owner approval/revocation and an authenticated TLS-pinned HMAC heartbeat every 30 seconds. Desktop shows an Agent online only after the owner approves it and the Hub receives a valid heartbeat. **Task polling, lease renewal, typed task execution and result ACK are not enabled yet.** The service must not be described as a functioning remote task executor until those pieces and two-computer acceptance tests pass.
