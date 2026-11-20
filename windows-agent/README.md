# GLAFIRA Windows Agent

The Windows Agent is a separate background-worker product, not a Desktop clone.

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

## Remove

Open elevated PowerShell:

~~~powershell
.\scripts\uninstall-agent.ps1
~~~

The default uninstall retains the machine-protected enrollment file. To also remove it:

~~~powershell
.\scripts\uninstall-agent.ps1 -RemoveCredentials
~~~

## Security and implementation boundary

- The Agent uses a pinned HTTPS pairing endpoint and a 256-bit one-time ticket.
- The shared secret is stored as a DPAPI LocalMachine-protected blob; plaintext is not written to disk.
- The credential directory and file are ACL-protected for LocalSystem and local Administrators.
- The Windows service runs as LocalSystem so it can decrypt the machine-scoped credential.
- Desktop enrollment creates a pending Agent with no capabilities. The owner explicitly assigns capabilities and approves it; revocation disables the credential and enrollment.
- The service sends a TLS-pinned, HMAC-signed Bridge heartbeat every 30 seconds. Pending Agents cannot refresh liveness or receive a capability profile.

**Current runtime boundary:** pairing, owner approval and heartbeat are implemented. Task polling, lease renewal, typed task execution and result ACK are not enabled yet. Do not describe this build as a functioning remote task executor.
