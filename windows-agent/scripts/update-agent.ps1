param(
    [Parameter(Mandatory)][string]$PackagePath,
    [Parameter(Mandatory)][string]$ExpectedPackageSha256,
    [string]$InstallDirectory = (Join-Path $env:ProgramFiles "GLAFIRA\Agent"),
    [string]$CredentialPath = (Join-Path $env:ProgramData "GLAFIRA\Agent\enrollment.dpapi")
)

$ErrorActionPreference = "Stop"
$serviceName = "GLAFIRA Agent"
$targetExecutable = Join-Path $InstallDirectory "GLAFIRA.Agent.Worker.exe"

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run update-agent.ps1 from an elevated PowerShell session."
}
$package = [IO.Path]::GetFullPath($PackagePath)
$service = Get-CimInstance Win32_Service -Filter "Name='$serviceName'"
if ($null -eq $service) {
    throw "GLAFIRA Agent service is not installed. Run install-agent.ps1 first."
}
if ($service.StartName -notin @("LocalSystem", "NT AUTHORITY\SYSTEM")) {
    throw "Refusing update: GLAFIRA Agent service is not running as LocalSystem."
}
$configuredExecutable = $service.PathName.Trim().Trim([char]'"')
if (-not [StringComparer]::OrdinalIgnoreCase.Equals(
    [IO.Path]::GetFullPath($configuredExecutable), [IO.Path]::GetFullPath($targetExecutable))) {
    throw "Refusing update: installed service binary path does not match InstallDirectory."
}

Import-Module (Join-Path $PSScriptRoot "AgentUpdateTransaction.psm1") -Force
$stop = {
    $current = Get-Service -Name $serviceName
    if ($current.Status -ne "Stopped") {
        Stop-Service -Name $serviceName -Force
        $current.WaitForStatus("Stopped", [TimeSpan]::FromSeconds(30))
    }
}
$start = {
    Start-Service -Name $serviceName
}
$assertHealthy = {
    $current = Get-Service -Name $serviceName
    $current.WaitForStatus("Running", [TimeSpan]::FromSeconds(30))
    $identity = Get-CimInstance Win32_Service -Filter "Name='$serviceName'"
    if ($identity.StartName -notin @("LocalSystem", "NT AUTHORITY\SYSTEM")) {
        throw "Updated Agent service identity is not LocalSystem."
    }
}

$updateRoot = Join-Path ([IO.Path]::GetTempPath()) ("glafira-agent-package-" + [Guid]::NewGuid().ToString("N"))
try {
    $expanded = Join-Path $updateRoot "expanded"
    $payload = Join-Path $updateRoot "payload"
    New-Item -ItemType Directory -Path $payload | Out-Null
    Expand-GlafiraAgentPackage -PackagePath $package -ExpectedPackageSha256 $ExpectedPackageSha256 -Destination $expanded
    $published = Join-Path $expanded "publish\win-x64"
    $packagedScripts = Join-Path $expanded "scripts"
    if (-not (Test-Path -LiteralPath (Join-Path $published "GLAFIRA.Agent.Worker.exe") -PathType Leaf)) {
        throw "Verified Agent package does not contain publish\win-x64\GLAFIRA.Agent.Worker.exe."
    }
    Copy-Item -Path (Join-Path $published "*") -Destination $payload -Recurse -Force
    if (Test-Path -LiteralPath $packagedScripts -PathType Container) {
        $payloadScripts = Join-Path $payload "scripts"
        New-Item -ItemType Directory -Path $payloadScripts | Out-Null
        Copy-Item -Path (Join-Path $packagedScripts "*") -Destination $payloadScripts -Recurse -Force
    }
    $executableDigest = (Get-FileHash -LiteralPath (Join-Path $payload "GLAFIRA.Agent.Worker.exe") -Algorithm SHA256).Hash.ToLowerInvariant()

    Invoke-GlafiraAgentUpdateTransaction `
        -PublishDirectory $payload `
        -InstallDirectory $InstallDirectory `
        -CredentialPath $CredentialPath `
        -ExpectedExecutableSha256 $executableDigest `
        -StopServiceAction $stop `
        -StartServiceAction $start `
        -AssertServiceHealthyAction $assertHealthy
}
finally {
    Remove-Item -LiteralPath $updateRoot -Recurse -Force -ErrorAction SilentlyContinue
}
