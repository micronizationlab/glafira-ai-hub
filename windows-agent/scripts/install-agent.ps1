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
    if ($existing.Status -ne "Stopped") {
        Stop-Service -Name $serviceName -Force
        $existing.WaitForStatus("Stopped", [TimeSpan]::FromSeconds(30))
    }
    sc.exe delete $serviceName | Out-Null
    Start-Sleep -Seconds 2
}

if (Test-Path $InstallDirectory) {
    Remove-Item $InstallDirectory -Recurse -Force
}
New-Item -ItemType Directory -Path $InstallDirectory -Force | Out-Null
Copy-Item -Path (Join-Path $PublishDirectory "*") -Destination $InstallDirectory -Recurse -Force

New-Service -Name $serviceName -BinaryPathName ('"' + $targetExecutable + '"') -DisplayName "GLAFIRA Agent" -Description "Owner-approved GLAFIRA background Agent." -StartupType Automatic
$service = Get-CimInstance Win32_Service -Filter "Name='$serviceName'"
if ($service.StartName -notin @("LocalSystem", "NT AUTHORITY\\SYSTEM")) {
    throw "GLAFIRA Agent must run as LocalSystem because its credential vault uses LocalMachine DPAPI and a SYSTEM-only service ACL."
}
sc.exe sidtype $serviceName unrestricted | Out-Null
Start-Service -Name $serviceName

Write-Host "GLAFIRA Agent service installed and started as LocalSystem."
Write-Host "Next, open an elevated console and run:"
Write-Host ($targetExecutable + " --pair")
Write-Host "After pairing and owner approval in GLAFIRA Desktop, restart the service."
