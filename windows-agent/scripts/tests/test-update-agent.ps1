$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "..\AgentUpdateTransaction.psm1") -Force

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Assert-Throws([scriptblock]$Action, [string]$ExpectedMessage) {
    try {
        & $Action
    }
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
    $data = Join-Path $Root "program-data"
    New-Item -ItemType Directory -Path $publish, $install, $data -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $publish "GLAFIRA.Agent.Worker.exe") -Value "new-agent" -NoNewline
    Set-Content -LiteralPath (Join-Path $publish "new-runtime.dll") -Value "new-runtime" -NoNewline
    Set-Content -LiteralPath (Join-Path $install "GLAFIRA.Agent.Worker.exe") -Value "old-agent" -NoNewline
    Set-Content -LiteralPath (Join-Path $install "old-runtime.dll") -Value "old-runtime" -NoNewline
    $credential = Join-Path $data "enrollment.dpapi"
    Set-Content -LiteralPath $credential -Value "machine-protected-credential" -NoNewline
    return [pscustomobject]@{
        Publish = $publish
        Install = $install
        Credential = $credential
        Digest = (Get-FileHash -LiteralPath (Join-Path $publish "GLAFIRA.Agent.Worker.exe") -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

$root = Join-Path ([IO.Path]::GetTempPath()) ("glafira-agent-update-tests-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $root | Out-Null
try {
    $success = New-Fixture (Join-Path $root "success")
    $events = [Collections.Generic.List[string]]::new()
    Invoke-GlafiraAgentUpdateTransaction -PublishDirectory $success.Publish -InstallDirectory $success.Install `
        -CredentialPath $success.Credential -ExpectedExecutableSha256 $success.Digest `
        -StopServiceAction { [void]$events.Add("stop") } `
        -StartServiceAction { [void]$events.Add("start") } `
        -AssertServiceHealthyAction { [void]$events.Add("healthy") }
    Assert-True ((Get-Content -Raw (Join-Path $success.Install "GLAFIRA.Agent.Worker.exe")) -eq "new-agent") "New executable was not committed."
    Assert-True (-not (Test-Path (Join-Path $success.Install "old-runtime.dll"))) "Old runtime leaked into the new installation."
    Assert-True ((Get-Content -Raw $success.Credential) -eq "machine-protected-credential") "Credential content changed."
    Assert-True (($events -join ",") -eq "stop,start,healthy") "Unexpected successful service sequence."

    $failure = New-Fixture (Join-Path $root "failure")
    $health = [pscustomobject]@{ Checks = 0 }
    Assert-Throws {
        Invoke-GlafiraAgentUpdateTransaction -PublishDirectory $failure.Publish -InstallDirectory $failure.Install `
            -CredentialPath $failure.Credential -ExpectedExecutableSha256 $failure.Digest `
            -StopServiceAction { } -StartServiceAction { } `
            -AssertServiceHealthyAction {
                $health.Checks++
                if ($health.Checks -eq 1) { throw "injected health failure" }
            }
    } "injected health failure"
    Assert-True ((Get-Content -Raw (Join-Path $failure.Install "GLAFIRA.Agent.Worker.exe")) -eq "old-agent") "Rollback did not restore the old executable."
    Assert-True (Test-Path (Join-Path $failure.Install "old-runtime.dll")) "Rollback did not restore the old runtime."
    Assert-True (-not (Test-Path (Join-Path $failure.Install "new-runtime.dll"))) "Failed payload remained after rollback."
    Assert-True ((Get-Content -Raw $failure.Credential) -eq "machine-protected-credential") "Rollback changed credential content."

    $digest = New-Fixture (Join-Path $root "digest")
    $stopState = [pscustomobject]@{ Stopped = $false }
    Assert-Throws {
        Invoke-GlafiraAgentUpdateTransaction -PublishDirectory $digest.Publish -InstallDirectory $digest.Install `
            -CredentialPath $digest.Credential -ExpectedExecutableSha256 ("0" * 64) `
            -StopServiceAction { $stopState.Stopped = $true } -StartServiceAction { } -AssertServiceHealthyAction { }
    } "digest does not match"
    Assert-True (-not $stopState.Stopped) "Digest mismatch stopped the service before preflight completed."
    Assert-True ((Get-Content -Raw (Join-Path $digest.Install "GLAFIRA.Agent.Worker.exe")) -eq "old-agent") "Digest failure changed installation."

    $inside = New-Fixture (Join-Path $root "inside")
    $insideCredential = Join-Path $inside.Install "enrollment.dpapi"
    Set-Content -LiteralPath $insideCredential -Value "bad-layout" -NoNewline
    Assert-Throws {
        Invoke-GlafiraAgentUpdateTransaction -PublishDirectory $inside.Publish -InstallDirectory $inside.Install `
            -CredentialPath $insideCredential -ExpectedExecutableSha256 $inside.Digest `
            -StopServiceAction { } -StartServiceAction { } -AssertServiceHealthyAction { }
    } "outside InstallDirectory"

    $archiveSource = Join-Path $root "archive-source"
    $archivePublish = Join-Path $archiveSource "publish\win-x64"
    New-Item -ItemType Directory -Path $archivePublish -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $archivePublish "GLAFIRA.Agent.Worker.exe") -Value "archive-agent" -NoNewline
    $archive = Join-Path $root "agent.zip"
    Compress-Archive -Path (Join-Path $archiveSource "*") -DestinationPath $archive
    $archiveDigest = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    $expanded = Join-Path $root "expanded-valid"
    Expand-GlafiraAgentPackage -PackagePath $archive -ExpectedPackageSha256 $archiveDigest -Destination $expanded
    Assert-True (Test-Path (Join-Path $expanded "publish\win-x64\GLAFIRA.Agent.Worker.exe")) "Verified package was not extracted."

    $tampered = Join-Path $root "tampered.zip"
    Copy-Item -LiteralPath $archive -Destination $tampered
    [IO.File]::AppendAllText($tampered, "tamper")
    Assert-Throws {
        Expand-GlafiraAgentPackage -PackagePath $tampered -ExpectedPackageSha256 $archiveDigest -Destination (Join-Path $root "expanded-tampered")
    } "digest does not match"

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $unsafe = Join-Path $root "unsafe.zip"
    $stream = [IO.File]::Open($unsafe, [IO.FileMode]::CreateNew)
    try {
        $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create, $false)
        try {
            [void]$zip.CreateEntry("../escape.txt")
        }
        finally {
            $zip.Dispose()
        }
    }
    finally {
        $stream.Dispose()
    }
    $unsafeDigest = (Get-FileHash -LiteralPath $unsafe -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-Throws {
        Expand-GlafiraAgentPackage -PackagePath $unsafe -ExpectedPackageSha256 $unsafeDigest -Destination (Join-Path $root "expanded-unsafe")
    } "unsafe archive path"
    Assert-True (-not (Test-Path (Join-Path $root "escape.txt"))) "Unsafe archive entry escaped extraction root."

    Write-Host "Passed 7 GLAFIRA Agent package/update transaction tests."
}
finally {
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
