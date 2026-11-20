param(
    [string]$PublishDirectory = (Join-Path $PSScriptRoot "..\publish\win-x64"),
    [string]$InstallDirectory = (Join-Path $env:ProgramFiles "GLAFIRA\Desktop")
)

$ErrorActionPreference = "Stop"
$executableName = "GLAFIRA.Desktop.App.exe"
$sourceExecutable = Join-Path $PublishDirectory $executableName
$targetExecutable = Join-Path $InstallDirectory $executableName
$firewallRule = "GLAFIRA Desktop Hub HTTPS (Private LAN)"
$shortcutPath = Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs\GLAFIRA Desktop.lnk"

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run install-desktop.ps1 from an elevated PowerShell session."
}
if (-not (Test-Path $sourceExecutable)) {
    throw "Published Desktop executable not found: $sourceExecutable. Run publish-desktop.ps1 first."
}

Get-Process -Name "GLAFIRA.Desktop.App" -ErrorAction SilentlyContinue | Stop-Process -Force
if (Test-Path $InstallDirectory) {
    Remove-Item $InstallDirectory -Recurse -Force
}
New-Item -ItemType Directory -Path $InstallDirectory -Force | Out-Null
Copy-Item -Path (Join-Path $PublishDirectory "*") -Destination $InstallDirectory -Recurse -Force

Get-NetFirewallRule -DisplayName $firewallRule -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName $firewallRule -Description "Allow GLAFIRA Desktop HTTPS Hub only from the local subnet on Private networks." -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8443 -Profile Private -RemoteAddress LocalSubnet -EdgeTraversalPolicy Block | Out-Null

$shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut($shortcutPath)
$shortcut.TargetPath = $targetExecutable
$shortcut.WorkingDirectory = $InstallDirectory
$shortcut.IconLocation = "$targetExecutable,0"
$shortcut.Description = "GLAFIRA Desktop Control Center"
$shortcut.Save()

Write-Host "GLAFIRA Desktop installed to: $InstallDirectory"
Write-Host "Private-profile firewall rule created for TCP 8443 from LocalSubnet only."
Write-Host "Start GLAFIRA Desktop from the Start Menu. In Bridge, select the private LAN address and click Start HTTPS."
