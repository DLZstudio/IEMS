# IEMS standard build entry (Windows) - DLZstudio BUILDID system
$ErrorActionPreference = "Stop"
$buildIdFile = Join-Path $PSScriptRoot "BUILDID.txt"
$current = [int](Get-Content $buildIdFile -Raw).Trim()
$next = $current + 1
[System.IO.File]::WriteAllText($buildIdFile, $next.ToString("D8"))
$buildId = "BUILD." + $next.ToString("D8")
Write-Host "==> IEMS build id: $buildId"

$projectRoot = Join-Path $PSScriptRoot "..\.."
Push-Location $projectRoot
try {
    & ".\gradlew.bat" build
    if ($LASTEXITCODE -ne 0) { throw "gradlew build failed: $LASTEXITCODE" }

    $libs = Join-Path $projectRoot "build\libs"
    $jar = Get-ChildItem $libs -Filter "iems-0.8.0-beta.jar" | Select-Object -First 1
    if ($jar) {
        $newName = "IEMS-0.8.0-beta-$buildId.jar"
        Rename-Item -Path $jar.FullName -NewName $newName -Force
        Write-Host "==> artifact: $newName"
    }
} finally {
    Pop-Location
}
