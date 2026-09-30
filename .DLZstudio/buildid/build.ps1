# IEMS standard build entry (Windows) - DLZstudio BUILDID system
#
# Usage:
#   .\build.ps1            internal build -> IEMS-<mod_version>-BUILD.<8-digit>.jar
#   .\build.ps1 -Release   release build  -> <mod_id>-<mc_version>-neoforge-<semver>.<n>.jar
#
# Release mode injects the BUILDID as the last segment of the mod version
# (semver.<n>, e.g. 0.8.0-beta.96) so the jar name and the version written into
# META-INF/neoforge.mods.toml stay in sync, matching the usual Minecraft mod
# platform naming convention.
param(
    [switch]$Release
)

$ErrorActionPreference = "Stop"

$buildIdFile = Join-Path $PSScriptRoot "BUILDID.txt"
$current = [int](Get-Content $buildIdFile -Raw).Trim()
$next = $current + 1
[System.IO.File]::WriteAllText($buildIdFile, $next.ToString("D8"))
$buildId = "BUILD." + $next.ToString("D8")
Write-Host "==> IEMS build id: $buildId"

$projectRoot = Join-Path $PSScriptRoot "..\.."
$propsFile = Join-Path $projectRoot "gradle.properties"

function Read-Prop([string]$name) {
    $hit = Select-String -Path $propsFile -Pattern ("^" + [regex]::Escape($name) + "=(.*)$") | Select-Object -First 1
    if (-not $hit) { throw "property '$name' not found in gradle.properties" }
    return $hit.Matches[0].Groups[1].Value.Trim()
}

$modId = Read-Prop "mod_id"
$semver = Read-Prop "mod_version"

Push-Location $projectRoot
try {
    $libs = Join-Path $projectRoot "build\libs"

    if ($Release) {
        $mcVersion = Read-Prop "minecraft_version"
        $releaseVersion = "$semver.$next"
        Write-Host "==> release version: $releaseVersion"

        & ".\gradlew.bat" build "-Pmod_version=$releaseVersion"
        if ($LASTEXITCODE -ne 0) { throw "gradlew build failed: $LASTEXITCODE" }

        $jar = Get-ChildItem $libs -Filter "$modId-$releaseVersion.jar" | Select-Object -First 1
        if (-not $jar) { throw "release jar not found: $libs\$modId-$releaseVersion.jar" }
        $newName = "$modId-$mcVersion-neoforge-$releaseVersion.jar"
        Rename-Item -Path $jar.FullName -NewName $newName -Force
        Write-Host "==> artifact: $newName"
    } else {
        & ".\gradlew.bat" build
        if ($LASTEXITCODE -ne 0) { throw "gradlew build failed: $LASTEXITCODE" }

        $jar = Get-ChildItem $libs -Filter "$modId-$semver.jar" | Select-Object -First 1
        if ($jar) {
            $newName = "IEMS-$semver-$buildId.jar"
            Rename-Item -Path $jar.FullName -NewName $newName -Force
            Write-Host "==> artifact: $newName"
        }
    }
} finally {
    Pop-Location
}