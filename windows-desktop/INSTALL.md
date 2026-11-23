# GLAFIRA Windows Desktop Control Center

## Ready-to-install package

Download the GitHub Actions artifact named `GLAFIRA-Desktop-win-x64` from a successful **GLAFIRA Windows deployment packages** run and extract it on the Desktop/Hub computer. The ZIP already contains `publish\win-x64` and the `scripts` folder; no .NET SDK is required on the target PC.

For developers who build from source, use `windows-desktop\scripts\publish-desktop.ps1`; the self-contained x64 output is written to `windows-desktop\publish\win-x64`.

## Install on the Desktop/Hub computer

Open PowerShell **as Administrator**.

From the extracted release ZIP root:

~~~powershell
.\scripts\install-desktop.ps1
~~~

From the repository root:

~~~powershell
.\windows-desktop\scripts\install-desktop.ps1
~~~

The installer copies the app to `%ProgramFiles%\GLAFIRA\Desktop`, creates a Start Menu shortcut and adds an inbound Windows Firewall rule for TCP 8443 restricted to the **Private** network profile and `LocalSubnet`. It does not open the port to Public networks or the internet.

## Update and automatic rollback

Download the new `GLAFIRA-Desktop-win-x64.zip` and copy its SHA-256 digest from the matching release manifest. Do not reuse a digest from another workflow run. From an elevated PowerShell session, run:

~~~powershell
.\scripts\update-desktop.ps1 -PackagePath .\GLAFIRA-Desktop-win-x64.zip -ExpectedPackageSha256 <64-hex-digest>
~~~

The updater verifies the complete archive before extraction, rejects unsafe archive paths, stages the new binaries beside the current installation, stops Desktop, and swaps the installation atomically. The updated executable must pass an isolated launch check that exits before loading identity, database, credentials, or UI. If the check fails, the previous binaries are restored. Hub state remains outside the install directory under `%LocalAppData%\GLAFIRA\DesktopHub`; the updater fingerprints that state and refuses to commit if the isolated check changes it.

## Start the Hub

1. Launch GLAFIRA Desktop from the Start Menu.
2. Open the Bridge section.
3. Select the active private LAN address shown by the app.
4. Click **Start HTTPS**.
5. Create a one-time pairing ticket and share its endpoint, TLS fingerprint, ticket ID and one-time code only with the Agent computer.
6. After the Agent has enrolled and restarted its service, select it in the Agents tab, assign only the capabilities it should receive and explicitly approve it. The Agent should appear online within 30 seconds after approval.
7. This release verifies pairing and heartbeat only; task polling, task execution and result ACK are not enabled yet.

The Hub binds only to private LAN addresses. The Agent must be able to reach the Desktop computer on TCP 8443. If Windows classifies the LAN as Public, change the network profile to Private only when that is appropriate for the network.

## Uninstall

From the extracted release ZIP root, open elevated PowerShell:

~~~powershell
.\scripts\uninstall-desktop.ps1
~~~

From the repository root:

~~~powershell
.\windows-desktop\scripts\uninstall-desktop.ps1
~~~

The default uninstall removes binaries, shortcut and firewall rule but retains the Hub database and owner data under `%LocalAppData%\GLAFIRA\DesktopHub`. Use `-RemoveUserData` only when you intentionally want to delete that data.
