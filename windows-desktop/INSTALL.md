# GLAFIRA Windows Desktop Control Center

## Publish

On a Windows build machine with the .NET 8 SDK, from the repository root:

~~~powershell
.\windows-desktop\scripts\publish-desktop.ps1
~~~

The self-contained x64 output is written to \`windows-desktop\publish\win-x64\`.

## Install on the Desktop/Hub computer

Open PowerShell **as Administrator**.

From the repository root:

~~~powershell
.\windows-desktop\scripts\install-desktop.ps1
~~~

From the extracted `GLAFIRA-Desktop-win-x64.zip` package root:

~~~powershell
.\scripts\install-desktop.ps1
~~~

The installer copies the app to \`%ProgramFiles%\GLAFIRA\Desktop\`, creates a Start Menu shortcut and adds an inbound Windows Firewall rule for TCP 8443 restricted to the **Private** network profile and \`LocalSubnet\`. It does not open the port to Public networks or the internet.

## Start the Hub

1. Launch GLAFIRA Desktop from the Start Menu.
2. Open the Bridge section.
3. Select the active private LAN address shown by the app.
4. Click **Start HTTPS**.
5. Create a one-time pairing ticket and share its endpoint, TLS fingerprint, ticket ID and code only with the Agent computer.
6. After the Agent enrolls, approve it in the Agents tab and assign only the capabilities it should receive.

The Hub binds only to private LAN addresses. The Agent must be able to reach the Desktop computer on TCP 8443. If Windows classifies the LAN as Public, change the network profile to Private only when that is appropriate for the network.

## Uninstall

Open elevated PowerShell:

~~~powershell
.\windows-desktop\scripts\uninstall-desktop.ps1
~~~

The default uninstall removes binaries, shortcut and firewall rule but retains the Hub database and owner data under \`%LocalAppData%\GLAFIRA\DesktopHub\`. Use \`-RemoveUserData\` only when you intentionally want to delete that data.

## Current acceptance boundary

This packages and installs the Desktop Control Center and its private-LAN HTTPS listener. The Agent heartbeat increment is still under validation. Full task claim, lease renewal, typed task execution, result ACK and physical two-computer acceptance are not yet complete.
