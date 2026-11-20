param(
    [switch]$RemoveCredentials,
    [string]$InstallDirectory = (Join-Path $env:ProgramFiles "GLAFIRA\Agent"),
    [string]$CredentialPath = (Join-Path $env:ProgramData "GLAFIRA\Agent\enrollment.dpapi")
)

$ErrorActionPreference = "Stop"
$serviceName = "GLAFIRA Agent"

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run uninstall-agent.ps1 from an elevated PowerShell session."
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
if ($RemoveCredentials -and (Test-Path $CredentialPath)) {
    Remove-Item $CredentialPath -Force
}

Write-Host "GLAFIRA Agent service and binaries removed."
if (-not $RemoveCredentials) {
    Write-Host "Machine-protected enrollment was retained. Re-run with -RemoveCredentials to remove local credentials."
}
