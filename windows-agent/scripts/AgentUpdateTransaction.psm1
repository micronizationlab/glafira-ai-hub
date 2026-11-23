Set-StrictMode -Version Latest

function Get-GlafiraCredentialFingerprint {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return [pscustomobject]@{ Exists = $false; Sha256 = $null; Sddl = $null }
    }
    $acl = Get-Acl -LiteralPath $Path
    return [pscustomobject]@{
        Exists = $true
        Sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
        Sddl = $acl.Sddl
    }
}

function Test-GlafiraFingerprintEqual {
    param($Left, $Right)
    return $Left.Exists -eq $Right.Exists -and
        $Left.Sha256 -eq $Right.Sha256 -and
        $Left.Sddl -eq $Right.Sddl
}

function Test-GlafiraPathWithin {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Parent)
    $separator = [IO.Path]::DirectorySeparatorChar
    $trimCharacters = [char[]]@($separator, [IO.Path]::AltDirectorySeparatorChar)
    $prefix = $Parent.TrimEnd($trimCharacters) + $separator
    return $Path.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)
}

function Expand-GlafiraAgentPackage {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$PackagePath,
        [Parameter(Mandatory)][string]$ExpectedPackageSha256,
        [Parameter(Mandatory)][string]$Destination
    )

    $package = [IO.Path]::GetFullPath($PackagePath)
    $destination = [IO.Path]::GetFullPath($Destination)
    $expected = $ExpectedPackageSha256.ToLowerInvariant()
    if ($expected -notmatch '^[0-9a-f]{64}$') {
        throw "ExpectedPackageSha256 must be a canonical SHA-256 digest."
    }
    if (-not (Test-Path -LiteralPath $package -PathType Leaf)) {
        throw "Agent update package not found: $package"
    }
    $packageInfo = Get-Item -LiteralPath $package
    if ($packageInfo.Length -le 0 -or $packageInfo.Length -gt 1GB) {
        throw "Agent update package size is outside the accepted bounds."
    }
    $actual = (Get-FileHash -LiteralPath $package -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected) {
        throw "Agent update package digest does not match ExpectedPackageSha256."
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($package)
    try {
        if ($archive.Entries.Count -lt 1 -or $archive.Entries.Count -gt 10000) {
            throw "Agent update package entry count is outside the accepted bounds."
        }
        [long]$expandedBytes = 0
        foreach ($entry in $archive.Entries) {
            $name = $entry.FullName.Replace('\', '/')
            if ([string]::IsNullOrWhiteSpace($name) -or
                [IO.Path]::IsPathRooted($entry.FullName) -or
                $name.StartsWith('/') -or
                $name.Contains(':') -or
                $name -match '(^|/)\.\.(/|$)') {
                throw "Agent update package contains an unsafe archive path."
            }
            $expandedBytes += $entry.Length
            if ($expandedBytes -gt 2GB) {
                throw "Agent update package expanded size exceeds the accepted bound."
            }
        }
    }
    finally {
        $archive.Dispose()
    }

    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    [IO.Compression.ZipFile]::ExtractToDirectory($package, $destination, $false)
}

function Invoke-GlafiraAgentUpdateTransaction {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$PublishDirectory,
        [Parameter(Mandatory)][string]$InstallDirectory,
        [Parameter(Mandatory)][string]$CredentialPath,
        [Parameter(Mandatory)][string]$ExpectedExecutableSha256,
        [Parameter(Mandatory)][scriptblock]$StopServiceAction,
        [Parameter(Mandatory)][scriptblock]$StartServiceAction,
        [Parameter(Mandatory)][scriptblock]$AssertServiceHealthyAction
    )

    $publish = [IO.Path]::GetFullPath($PublishDirectory)
    $install = [IO.Path]::GetFullPath($InstallDirectory)
    $credential = [IO.Path]::GetFullPath($CredentialPath)
    $expected = $ExpectedExecutableSha256.ToLowerInvariant()
    if ($expected -notmatch '^[0-9a-f]{64}$') {
        throw "ExpectedExecutableSha256 must be a canonical SHA-256 digest."
    }
    if (Test-GlafiraPathWithin -Path $credential -Parent $install) {
        throw "CredentialPath must be outside InstallDirectory so an update can never replace enrollment."
    }
    if (-not (Test-Path -LiteralPath $install -PathType Container)) {
        throw "Existing Agent installation not found: $install. Run install-agent.ps1 first."
    }

    $executableName = "GLAFIRA.Agent.Worker.exe"
    $sourceExecutable = Join-Path $publish $executableName
    if (-not (Test-Path -LiteralPath $sourceExecutable -PathType Leaf)) {
        throw "Update payload executable not found: $sourceExecutable"
    }
    $sourceDigest = (Get-FileHash -LiteralPath $sourceExecutable -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($sourceDigest -ne $expected) {
        throw "Update payload digest does not match ExpectedExecutableSha256."
    }

    $parent = Split-Path -Parent $install
    if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $transaction = [Guid]::NewGuid().ToString("N")
    $staging = Join-Path $parent (".glafira-agent-staging-" + $transaction)
    $rollback = Join-Path $parent (".glafira-agent-rollback-" + $transaction)
    $credentialBefore = Get-GlafiraCredentialFingerprint -Path $credential
    $serviceStopped = $false
    $oldMoved = $false
    $newMoved = $false

    try {
        New-Item -ItemType Directory -Path $staging | Out-Null
        Copy-Item -Path (Join-Path $publish "*") -Destination $staging -Recurse -Force
        $stagedExecutable = Join-Path $staging $executableName
        $stagedDigest = (Get-FileHash -LiteralPath $stagedExecutable -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($stagedDigest -ne $expected) {
            throw "Staged Agent executable digest changed during copy."
        }

        & $StopServiceAction
        $serviceStopped = $true
        Move-Item -LiteralPath $install -Destination $rollback
        $oldMoved = $true
        Move-Item -LiteralPath $staging -Destination $install
        $newMoved = $true
        & $StartServiceAction
        & $AssertServiceHealthyAction

        $credentialAfter = Get-GlafiraCredentialFingerprint -Path $credential
        if (-not (Test-GlafiraFingerprintEqual -Left $credentialBefore -Right $credentialAfter)) {
            throw "Machine enrollment content or ACL changed during the Agent update."
        }

        Remove-Item -LiteralPath $rollback -Recurse -Force
        $oldMoved = $false
        Write-Host "GLAFIRA Agent update committed after digest, service health and credential ACL checks."
    }
    catch {
        $updateFailure = $_
        $rollbackFailure = $null
        try {
            if ($serviceStopped) {
                try { & $StopServiceAction } catch { }
            }
            if ($newMoved -and (Test-Path -LiteralPath $install)) {
                Remove-Item -LiteralPath $install -Recurse -Force
                $newMoved = $false
            }
            if ($oldMoved -and (Test-Path -LiteralPath $rollback)) {
                Move-Item -LiteralPath $rollback -Destination $install
                $oldMoved = $false
            }
            if ($serviceStopped) {
                & $StartServiceAction
                & $AssertServiceHealthyAction
            }
        }
        catch {
            $rollbackFailure = $_
        }
        if ($null -ne $rollbackFailure) {
            throw "Agent update failed: $($updateFailure.Exception.Message) Rollback also failed: $($rollbackFailure.Exception.Message)"
        }
        throw $updateFailure
    }
    finally {
        if (Test-Path -LiteralPath $staging) {
            Remove-Item -LiteralPath $staging -Recurse -Force
        }
        if (-not $oldMoved -and (Test-Path -LiteralPath $rollback)) {
            Remove-Item -LiteralPath $rollback -Recurse -Force
        }
    }
}

Export-ModuleMember -Function Expand-GlafiraAgentPackage, Invoke-GlafiraAgentUpdateTransaction
