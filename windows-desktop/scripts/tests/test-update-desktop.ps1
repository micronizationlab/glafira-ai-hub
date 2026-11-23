$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "..\DesktopUpdateTransaction.psm1") -Force

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Assert-Throws([scriptblock]$Action, [string]$ExpectedMessage) {
    try { & $Action }
    catch {
        if ($_.Exception.Message -notlike ("*" + $ExpectedMessage + "*")) {
            throw "Expected error containing '$ExpectedMessage', got '$($_.Exception.Message)'."
        }
        return
    }
    throw "Expected action to fail with '$ExpectedMessage'."
}

function New-Fixture([string]$Root) {
    $publish = Join-Path $Root "publish"
    $install = Join-Path $Root "installed"
    $data = Join-Path $Root "local-app-data\GLAFIRA\DesktopHub"
    New-Item -ItemType Directory -Path $publish, $install, $data -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $publish "GLAFIRA.Desktop.App.exe") -Value "new-desktop" -NoNewline
    Set-Content -LiteralPath (Join-Path $publish "new-runtime.dll") -Value "new-runtime" -NoNewline
    Set-Content -LiteralPath (Join-Path $install "GLAFIRA.Desktop.App.exe") -Value "old-desktop" -NoNewline
    Set-Content -LiteralPath (Join-Path $install "old-runtime.dll") -Value "old-runtime" -NoNewline
    Set-Content -LiteralPath (Join-Path $data "hub.db") -Value "protected-hub-state" -NoNewline
    return [pscustomobject]@{
        Publish = $publish
        Install = $install
        Data = $data
        Digest = (Get-FileHash -LiteralPath (Join-Path $publish "GLAFIRA.Desktop.App.exe") -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

$root = Join-Path ([IO.Path]::GetTempPath()) ("glafira-desktop-update-tests-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $root | Out-Null
try {
    $success = New-Fixture (Join-Path $root "success")
    $events = [Collections.Generic.List[string]]::new()
    Invoke-GlafiraDesktopUpdateTransaction -PublishDirectory $success.Publish -InstallDirectory $success.Install `
        -UserDataPath $success.Data -ExpectedExecutableSha256 $success.Digest `
        -StopApplicationAction { [void]$events.Add("stop") } `
        -AssertUpdatedApplicationHealthyAction { [void]$events.Add("healthy") }
    Assert-True ((Get-Content -Raw (Join-Path $success.Install "GLAFIRA.Desktop.App.exe")) -eq "new-desktop") "New executable was not committed."
    Assert-True (-not (Test-Path (Join-Path $success.Install "old-runtime.dll"))) "Old runtime leaked into the new installation."
    Assert-True ((Get-Content -Raw (Join-Path $success.Data "hub.db")) -eq "protected-hub-state") "Desktop Hub state changed."
    Assert-True (($events -join ",") -eq "stop,healthy") "Unexpected successful update sequence."

    $failure = New-Fixture (Join-Path $root "failure")
    Assert-Throws {
        Invoke-GlafiraDesktopUpdateTransaction -PublishDirectory $failure.Publish -InstallDirectory $failure.Install `
            -UserDataPath $failure.Data -ExpectedExecutableSha256 $failure.Digest `
            -StopApplicationAction { } -AssertUpdatedApplicationHealthyAction { throw "injected launch failure" }
    } "injected launch failure"
    Assert-True ((Get-Content -Raw (Join-Path $failure.Install "GLAFIRA.Desktop.App.exe")) -eq "old-desktop") "Rollback did not restore the old executable."
    Assert-True (Test-Path (Join-Path $failure.Install "old-runtime.dll")) "Rollback did not restore the old runtime."
    Assert-True (-not (Test-Path (Join-Path $failure.Install "new-runtime.dll"))) "Failed payload remained after rollback."

    $dataMutation = New-Fixture (Join-Path $root "data-mutation")
    Assert-Throws {
        Invoke-GlafiraDesktopUpdateTransaction -PublishDirectory $dataMutation.Publish -InstallDirectory $dataMutation.Install `
            -UserDataPath $dataMutation.Data -ExpectedExecutableSha256 $dataMutation.Digest `
            -StopApplicationAction { } `
            -AssertUpdatedApplicationHealthyAction { Set-Content -LiteralPath (Join-Path $dataMutation.Data "hub.db") -Value "unexpected-mutation" -NoNewline }
    } "user data changed"
    Assert-True ((Get-Content -Raw (Join-Path $dataMutation.Install "GLAFIRA.Desktop.App.exe")) -eq "old-desktop") "Data mutation did not roll back binaries."

    $digest = New-Fixture (Join-Path $root "digest")
    $stopState = [pscustomobject]@{ Stopped = $false }
    Assert-Throws {
        Invoke-GlafiraDesktopUpdateTransaction -PublishDirectory $digest.Publish -InstallDirectory $digest.Install `
            -UserDataPath $digest.Data -ExpectedExecutableSha256 ("0" * 64) `
            -StopApplicationAction { $stopState.Stopped = $true } -AssertUpdatedApplicationHealthyAction { }
    } "digest does not match"
    Assert-True (-not $stopState.Stopped) "Digest mismatch stopped Desktop before preflight completed."

    $inside = New-Fixture (Join-Path $root "inside")
    $insideData = Join-Path $inside.Install "DesktopHub"
    New-Item -ItemType Directory -Path $insideData | Out-Null
    Assert-Throws {
        Invoke-GlafiraDesktopUpdateTransaction -PublishDirectory $inside.Publish -InstallDirectory $inside.Install `
            -UserDataPath $insideData -ExpectedExecutableSha256 $inside.Digest `
            -StopApplicationAction { } -AssertUpdatedApplicationHealthyAction { }
    } "outside InstallDirectory"

    $archiveSource = Join-Path $root "archive-source"
    $archivePublish = Join-Path $archiveSource "publish\win-x64"
    New-Item -ItemType Directory -Path $archivePublish -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $archivePublish "GLAFIRA.Desktop.App.exe") -Value "archive-desktop" -NoNewline
    $archive = Join-Path $root "desktop.zip"
    Compress-Archive -Path (Join-Path $archiveSource "*") -DestinationPath $archive
    $archiveDigest = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    $expanded = Join-Path $root "expanded-valid"
    Expand-GlafiraDesktopPackage -PackagePath $archive -ExpectedPackageSha256 $archiveDigest -Destination $expanded
    Assert-True (Test-Path (Join-Path $expanded "publish\win-x64\GLAFIRA.Desktop.App.exe")) "Verified package was not extracted."

    $tampered = Join-Path $root "tampered.zip"
    Copy-Item -LiteralPath $archive -Destination $tampered
    [IO.File]::AppendAllText($tampered, "tamper")
    Assert-Throws {
        Expand-GlafiraDesktopPackage -PackagePath $tampered -ExpectedPackageSha256 $archiveDigest -Destination (Join-Path $root "expanded-tampered")
    } "digest does not match"

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $unsafe = Join-Path $root "unsafe.zip"
    $stream = [IO.File]::Open($unsafe, [IO.FileMode]::CreateNew)
    try {
        $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create, $false)
        try { [void]$zip.CreateEntry("../escape.txt") }
        finally { $zip.Dispose() }
    }
    finally { $stream.Dispose() }
    $unsafeDigest = (Get-FileHash -LiteralPath $unsafe -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-Throws {
        Expand-GlafiraDesktopPackage -PackagePath $unsafe -ExpectedPackageSha256 $unsafeDigest -Destination (Join-Path $root "expanded-unsafe")
    } "unsafe archive path"
    Assert-True (-not (Test-Path (Join-Path $root "escape.txt"))) "Unsafe archive entry escaped extraction root."

    Write-Host "Passed 8 GLAFIRA Desktop package/update transaction tests."
}
finally {
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
