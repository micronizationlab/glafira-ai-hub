Set-StrictMode -Version Latest

function Test-GlafiraDesktopPathWithin {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Parent)
    $separator = [IO.Path]::DirectorySeparatorChar
    $trimCharacters = [char[]]@($separator, [IO.Path]::AltDirectorySeparatorChar)
    $prefix = $Parent.TrimEnd($trimCharacters) + $separator
    return $Path.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)
}

function Get-GlafiraDesktopDataFingerprint {
    param([Parameter(Mandatory)][string]$Path)

    $root = [IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $root -PathType Container)) {
        return [pscustomobject]@{ Exists = $false; Entries = @() }
    }
    $entries = @(
        Get-ChildItem -LiteralPath $root -File -Recurse -Force | Sort-Object FullName | ForEach-Object {
            [pscustomobject]@{
                RelativePath = [IO.Path]::GetRelativePath($root, $_.FullName)
                Length = $_.Length
                Sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        }
    )
    return [pscustomobject]@{ Exists = $true; Entries = $entries }
}

function Test-GlafiraDesktopFingerprintEqual {
    param($Left, $Right)

    if ($Left.Exists -ne $Right.Exists -or $Left.Entries.Count -ne $Right.Entries.Count) {
        return $false
    }
    for ($index = 0; $index -lt $Left.Entries.Count; $index++) {
        $before = $Left.Entries[$index]
        $after = $Right.Entries[$index]
        if ($before.RelativePath -ne $after.RelativePath -or
            $before.Length -ne $after.Length -or
            $before.Sha256 -ne $after.Sha256) {
            return $false
        }
    }
    return $true
}

function Expand-GlafiraDesktopPackage {
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
        throw "Desktop update package not found: $package"
    }
    $packageInfo = Get-Item -LiteralPath $package
    if ($packageInfo.Length -le 0 -or $packageInfo.Length -gt 1GB) {
        throw "Desktop update package size is outside the accepted bounds."
    }
    $actual = (Get-FileHash -LiteralPath $package -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected) {
        throw "Desktop update package digest does not match ExpectedPackageSha256."
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($package)
    try {
        if ($archive.Entries.Count -lt 1 -or $archive.Entries.Count -gt 10000) {
            throw "Desktop update package entry count is outside the accepted bounds."
        }
        [long]$expandedBytes = 0
        foreach ($entry in $archive.Entries) {
            $name = $entry.FullName.Replace('\', '/')
            if ([string]::IsNullOrWhiteSpace($name) -or
                [IO.Path]::IsPathRooted($entry.FullName) -or
                $name.StartsWith('/') -or
                $name.Contains(':') -or
                $name -match '(^|/)\.\.(/|$)') {
                throw "Desktop update package contains an unsafe archive path."
            }
            $expandedBytes += $entry.Length
            if ($expandedBytes -gt 2GB) {
                throw "Desktop update package expanded size exceeds the accepted bound."
            }
        }
    }
    finally {
        $archive.Dispose()
    }

    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    [IO.Compression.ZipFile]::ExtractToDirectory($package, $destination, $false)
}

function Invoke-GlafiraDesktopUpdateTransaction {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$PublishDirectory,
        [Parameter(Mandatory)][string]$InstallDirectory,
        [Parameter(Mandatory)][string]$UserDataPath,
        [Parameter(Mandatory)][string]$ExpectedExecutableSha256,
        [Parameter(Mandatory)][scriptblock]$StopApplicationAction,
        [Parameter(Mandatory)][scriptblock]$AssertUpdatedApplicationHealthyAction
    )

    $publish = [IO.Path]::GetFullPath($PublishDirectory)
    $install = [IO.Path]::GetFullPath($InstallDirectory)
    $userData = [IO.Path]::GetFullPath($UserDataPath)
    $expected = $ExpectedExecutableSha256.ToLowerInvariant()
    if ($expected -notmatch '^[0-9a-f]{64}$') {
        throw "ExpectedExecutableSha256 must be a canonical SHA-256 digest."
    }
    if (Test-GlafiraDesktopPathWithin -Path $userData -Parent $install) {
        throw "UserDataPath must be outside InstallDirectory so an update can never replace Desktop Hub state."
    }
    if (-not (Test-Path -LiteralPath $install -PathType Container)) {
        throw "Existing Desktop installation not found: $install. Run install-desktop.ps1 first."
    }

    $executableName = "GLAFIRA.Desktop.App.exe"
    $sourceExecutable = Join-Path $publish $executableName
    $installedExecutable = Join-Path $install $executableName
    if (-not (Test-Path -LiteralPath $sourceExecutable -PathType Leaf)) {
        throw "Update payload executable not found: $sourceExecutable"
    }
    if (-not (Test-Path -LiteralPath $installedExecutable -PathType Leaf)) {
        throw "Installed Desktop executable not found: $installedExecutable"
    }
    $sourceDigest = (Get-FileHash -LiteralPath $sourceExecutable -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($sourceDigest -ne $expected) {
        throw "Update payload digest does not match ExpectedExecutableSha256."
    }
    $previousDigest = (Get-FileHash -LiteralPath $installedExecutable -Algorithm SHA256).Hash.ToLowerInvariant()

    $parent = Split-Path -Parent $install
    if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $transaction = [Guid]::NewGuid().ToString("N")
    $staging = Join-Path $parent (".glafira-desktop-staging-" + $transaction)
    $rollback = Join-Path $parent (".glafira-desktop-rollback-" + $transaction)
    $dataBefore = Get-GlafiraDesktopDataFingerprint -Path $userData
    $oldMoved = $false
    $newMoved = $false

    try {
        New-Item -ItemType Directory -Path $staging | Out-Null
        Copy-Item -Path (Join-Path $publish "*") -Destination $staging -Recurse -Force
        $stagedExecutable = Join-Path $staging $executableName
        $stagedDigest = (Get-FileHash -LiteralPath $stagedExecutable -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($stagedDigest -ne $expected) {
            throw "Staged Desktop executable digest changed during copy."
        }

        & $StopApplicationAction
        Move-Item -LiteralPath $install -Destination $rollback
        $oldMoved = $true
        Move-Item -LiteralPath $staging -Destination $install
        $newMoved = $true
        & $AssertUpdatedApplicationHealthyAction

        $dataAfter = Get-GlafiraDesktopDataFingerprint -Path $userData
        if (-not (Test-GlafiraDesktopFingerprintEqual -Left $dataBefore -Right $dataAfter)) {
            throw "Desktop Hub user data changed during the isolated update health check."
        }

        Remove-Item -LiteralPath $rollback -Recurse -Force
        $oldMoved = $false
        Write-Host "GLAFIRA Desktop update committed after digest, isolated launch and user-data preservation checks."
    }
    catch {
        $updateFailure = $_
        $rollbackFailure = $null
        try {
            try { & $StopApplicationAction } catch { }
            if ($newMoved -and (Test-Path -LiteralPath $install)) {
                Remove-Item -LiteralPath $install -Recurse -Force
                $newMoved = $false
            }
            if ($oldMoved -and (Test-Path -LiteralPath $rollback)) {
                Move-Item -LiteralPath $rollback -Destination $install
                $oldMoved = $false
            }
            $restoredExecutable = Join-Path $install $executableName
            if (-not (Test-Path -LiteralPath $restoredExecutable -PathType Leaf) -or
                (Get-FileHash -LiteralPath $restoredExecutable -Algorithm SHA256).Hash.ToLowerInvariant() -ne $previousDigest) {
                throw "Rollback did not restore the previous Desktop executable."
            }
        }
        catch {
            $rollbackFailure = $_
        }
        if ($null -ne $rollbackFailure) {
            throw "Desktop update failed: $($updateFailure.Exception.Message) Rollback also failed: $($rollbackFailure.Exception.Message)"
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

Export-ModuleMember -Function Expand-GlafiraDesktopPackage, Invoke-GlafiraDesktopUpdateTransaction
