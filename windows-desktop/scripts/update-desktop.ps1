param(
    [Parameter(Mandatory)][string]$PackagePath,
    [Parameter(Mandatory)][string]$ExpectedPackageSha256,
    [string]$InstallDirectory = (Join-Path $env:ProgramFiles "GLAFIRA\Desktop"),
    [string]$UserDataPath = (Join-Path $env:LocalAppData "GLAFIRA\DesktopHub")
)

$ErrorActionPreference = "Stop"
$processName = "GLAFIRA.Desktop.App"
$targetExecutable = Join-Path $InstallDirectory "GLAFIRA.Desktop.App.exe"

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run update-desktop.ps1 from an elevated PowerShell session."
}

Import-Module (Join-Path $PSScriptRoot "DesktopUpdateTransaction.psm1") -Force
$stop = {
    Get-Process -Name $processName -ErrorAction SilentlyContinue | Stop-Process -Force
}
$assertHealthy = {
    $process = Start-Process -FilePath $targetExecutable -ArgumentList "--glafira-update-health-check" -PassThru -Wait -WindowStyle Hidden
    if ($process.ExitCode -ne 0) {
        throw "Updated Desktop isolated health check exited with code $($process.ExitCode)."
    }
}

$updateRoot = Join-Path ([IO.Path]::GetTempPath()) ("glafira-desktop-package-" + [Guid]::NewGuid().ToString("N"))
try {
    $expanded = Join-Path $updateRoot "expanded"
    $payload = Join-Path $updateRoot "payload"
    New-Item -ItemType Directory -Path $payload | Out-Null
    Expand-GlafiraDesktopPackage -PackagePath $PackagePath -ExpectedPackageSha256 $ExpectedPackageSha256 -Destination $expanded
    $published = Join-Path $expanded "publish\win-x64"
    $packagedScripts = Join-Path $expanded "scripts"
    if (-not (Test-Path -LiteralPath (Join-Path $published "GLAFIRA.Desktop.App.exe") -PathType Leaf)) {
        throw "Verified Desktop package does not contain publish\win-x64\GLAFIRA.Desktop.App.exe."
    }
    Copy-Item -Path (Join-Path $published "*") -Destination $payload -Recurse -Force
    if (Test-Path -LiteralPath $packagedScripts -PathType Container) {
        $payloadScripts = Join-Path $payload "scripts"
        New-Item -ItemType Directory -Path $payloadScripts | Out-Null
        Copy-Item -Path (Join-Path $packagedScripts "*") -Destination $payloadScripts -Recurse -Force
    }
    $executableDigest = (Get-FileHash -LiteralPath (Join-Path $payload "GLAFIRA.Desktop.App.exe") -Algorithm SHA256).Hash.ToLowerInvariant()

    Invoke-GlafiraDesktopUpdateTransaction `
        -PublishDirectory $payload `
        -InstallDirectory $InstallDirectory `
        -UserDataPath $UserDataPath `
        -ExpectedExecutableSha256 $executableDigest `
        -StopApplicationAction $stop `
        -AssertUpdatedApplicationHealthyAction $assertHealthy
}
finally {
    Remove-Item -LiteralPath $updateRoot -Recurse -Force -ErrorAction SilentlyContinue
}
