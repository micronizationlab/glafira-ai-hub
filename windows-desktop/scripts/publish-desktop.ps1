$ErrorActionPreference = "Stop"

$project = Join-Path $PSScriptRoot "..\GLAFIRA.Desktop.App\GLAFIRA.Desktop.App.csproj"
$output = Join-Path $PSScriptRoot "..\publish\win-x64"

if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
    throw "The .NET SDK is required to publish GLAFIRA Desktop."
}
if (Test-Path $output) {
    Remove-Item $output -Recurse -Force
}
New-Item -ItemType Directory -Path $output -Force | Out-Null

dotnet publish $project -c Release -r win-x64 --self-contained true -o $output
if ($LASTEXITCODE -ne 0) {
    throw "GLAFIRA Desktop publish failed with exit code $LASTEXITCODE."
}

Write-Host "GLAFIRA Desktop published to: $output"
