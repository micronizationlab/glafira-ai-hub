param(
    [switch]$RemoveUserData,
    [string]$InstallDirectory = (Join-Path $env:ProgramFiles "GLAFIRA\Desktop")
)

$ErrorActionPreference = "Stop"
$firewallRule = "GLAFIRA Desktop Hub HTTPS (Private LAN)"
$shortcutPath = Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs\GLAFIRA Desktop.lnk"
$userDataPath = Join-Path $env:LocalAppData "GLAFIRA\DesktopHub"

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run uninstall-desktop.ps1 from an elevated PowerShell session."
}

Get-Process -Name "GLAFIRA.Desktop.App" -ErrorAction SilentlyContinue | Stop-Process -Force
Get-NetFirewallRule -DisplayName $firewallRule -ErrorAction SilentlyContinue | Remove-NetFirewallRule
if (Test-Path $shortcutPath) {
    Remove-Item $shortcutPath -Force
}
if (Test-Path $InstallDirectory) {
    Remove-Item $InstallDirectory -Recurse -Force
}
if ($RemoveUserData -and (Test-Path $userDataPath)) {
    Remove-Item $userDataPath -Recurse -Force
}

Write-Host "GLAFIRA Desktop binaries and firewall rule removed."
if (-not $RemoveUserData) {
    Write-Host "Desktop Hub database and owner data were retained under $userDataPath."
}
