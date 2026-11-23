param(
    [string]$PublishDirectory = (Join-Path $PSScriptRoot "..\publish\win-x64"),
    [string]$InstallDirectory = (Join-Path $env:ProgramFiles "GLAFIRA\Agent")
)

$ErrorActionPreference = "Stop"
$serviceName = "GLAFIRA Agent"
$executableName = "GLAFIRA.Agent.Worker.exe"
$sourceExecutable = Join-Path $PublishDirectory $executableName
$targetExecutable = Join-Path $InstallDirectory $executableName

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run install-agent.ps1 from an elevated PowerShell session."
}
if (-not (Test-Path $sourceExecutable)) {
    throw "Published Agent executable not found: $sourceExecutable. Run publish-agent.ps1 first."
}

$existing = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if ($null -ne $existing) {
    throw "GLAFIRA Agent is already installed. Use update-agent.ps1 for a staged update or uninstall it explicitly first."
}

if (Test-Path $InstallDirectory) {
    Remove-Item $InstallDirectory -Recurse -Force
}
New-Item -ItemType Directory -Path $InstallDirectory -Force | Out-Null
Copy-Item -Path (Join-Path $PublishDirectory "*") -Destination $InstallDirectory -Recurse -Force
$installedScripts = Join-Path $InstallDirectory "scripts"
New-Item -ItemType Directory -Path $installedScripts -Force | Out-Null
foreach ($script in @("update-agent.ps1", "AgentUpdateTransaction.psm1", "uninstall-agent.ps1")) {
    $sourceScript = Join-Path $PSScriptRoot $script
    if (-not (Test-Path -LiteralPath $sourceScript -PathType Leaf)) {
        throw "Required lifecycle script not found: $sourceScript"
    }
    Copy-Item -LiteralPath $sourceScript -Destination $installedScripts -Force
}

New-Service -Name $serviceName -BinaryPathName ('"' + $targetExecutable + '"') -DisplayName "GLAFIRA Agent" -Description "Owner-approved GLAFIRA background Agent." -StartupType Automatic
$service = Get-CimInstance Win32_Service -Filter "Name='$serviceName'"
if ($service.StartName -notin @("LocalSystem", "NT AUTHORITY\SYSTEM")) {
    throw "GLAFIRA Agent must run as LocalSystem because its credential vault uses LocalMachine DPAPI and a SYSTEM-only service ACL."
}
sc.exe sidtype $serviceName unrestricted | Out-Null
Start-Service -Name $serviceName

Write-Host "GLAFIRA Agent service installed and started as LocalSystem."
Write-Host "Next, open an elevated console and run:"
Write-Host ($targetExecutable + " --pair")
Write-Host "After pairing, restart the service to load the credential; then approve the Agent in GLAFIRA Desktop. Heartbeat should connect within 30 seconds after approval."
