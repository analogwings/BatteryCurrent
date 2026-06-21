param(
    [Parameter(Mandatory = $true)]
    [string]$ReleaseNotes,

    [switch]$SkipDirtyCheck,
    [switch]$Push
)

$ErrorActionPreference = "Stop"

function Invoke-Git {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    & git @Args
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Args -join ' ') failed"
    }
}

function Get-CurrentBranch {
    $branch = (& git branch --show-current).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($branch)) {
        throw "Unable to determine current git branch"
    }
    return $branch
}

function Get-GradleVersions {
    param([string]$GradlePath)

    $content = Get-Content -Raw $GradlePath
    $codeMatch = [regex]::Match($content, 'val\s+appVersionCode\s*=\s*(\d+)')
    $nameMatch = [regex]::Match($content, 'val\s+appVersionName\s*=\s*"([^"]+)"')

    if (-not $codeMatch.Success -or -not $nameMatch.Success) {
        throw "Could not find appVersionCode/appVersionName in $GradlePath"
    }

    return [pscustomobject]@{
        Code = [int]$codeMatch.Groups[1].Value
        Name = $nameMatch.Groups[1].Value
    }
}

function Set-GradleVersions {
    param(
        [string]$GradlePath,
        [int]$Code,
        [string]$Name
    )

    $content = Get-Content -Raw $GradlePath
    $content = [regex]::Replace($content, 'val\s+appVersionCode\s*=\s*\d+', "val appVersionCode = $Code")
    $content = [regex]::Replace($content, 'val\s+appVersionName\s*=\s*"[^"]+"', "val appVersionName = `"$Name`"")
    Set-Content -Path $GradlePath -Value $content -NoNewline
}

function Get-NextVersionName {
    param([string]$ReleaseVersion)

    $match = [regex]::Match($ReleaseVersion, '^(\d+)\.(\d{2})$')
    if (-not $match.Success) {
        throw "Release version '$ReleaseVersion' is not in expected format X.YY"
    }

    $major = [int]$match.Groups[1].Value
    $minor = [int]$match.Groups[2].Value + 1
    if ($minor -ge 100) {
        $major += 1
        $minor = 0
    }

    return ("{0}.{1:00}" -f $major, $minor)
}

$repoRoot = (& git rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($repoRoot)) {
    throw "This script must be run inside the BatteryCurrent git repository"
}

Set-Location $repoRoot

$branch = Get-CurrentBranch
if ($branch -ne "main") {
    throw "Current branch '$branch' is not 'main'. Check out main before releasing."
}

$gradlePath = Join-Path $repoRoot "app/build.gradle.kts"
$versions = Get-GradleVersions -GradlePath $gradlePath

$devMatch = [regex]::Match($versions.Name, '^dev-(\d+\.\d{2})$')
if (-not $devMatch.Success) {
    throw "appVersionName '$($versions.Name)' is not in expected format dev-X.YY"
}

$releaseVersion = $devMatch.Groups[1].Value
$releaseCode = $versions.Code
$tagName = "v$releaseVersion"
$nextVersion = Get-NextVersionName -ReleaseVersion $releaseVersion
$nextCode = $releaseCode + 1

$existingTagOutput = @(& git rev-parse -q --verify "refs/tags/$tagName")
if ($LASTEXITCODE -eq 0 -and $existingTagOutput.Count -gt 0) {
    $existingTag = ($existingTagOutput -join "").Trim()
    if (-not [string]::IsNullOrWhiteSpace($existingTag)) {
        throw "Tag '$tagName' already exists. Do not reuse a Play Store version."
    }
}

$dirty = (& git status --porcelain)
if ($dirty -and -not $SkipDirtyCheck) {
    throw "Working tree has uncommitted changes. Commit them first or rerun with -SkipDirtyCheck to include them in a pre-release commit."
}

if ($dirty -and $SkipDirtyCheck) {
    Invoke-Git add -A
    Invoke-Git commit -m "Prepare release content"
}

Set-GradleVersions -GradlePath $gradlePath -Code $releaseCode -Name $releaseVersion
Invoke-Git add $gradlePath
Invoke-Git commit -m "Prepare release $tagName ($releaseCode)"
Invoke-Git tag -a $tagName -m "BatteryCurrent $tagName ($releaseCode)`n`n$ReleaseNotes"

if ($Push) {
    Invoke-Git push origin main
    Invoke-Git push origin $tagName
}

Write-Host ""
Write-Host "Release $tagName ($releaseCode) is ready." -ForegroundColor Green
Write-Host "Build and upload the signed AAB now. The app should show versionName '$releaseVersion' and versionCode $releaseCode." -ForegroundColor Yellow
Read-Host "Press ENTER after the AAB is built/uploaded to roll main forward to dev-$nextVersion ($nextCode)"

Set-GradleVersions -GradlePath $gradlePath -Code $nextCode -Name "dev-$nextVersion"
Invoke-Git add $gradlePath
Invoke-Git commit -m "Start development dev-$nextVersion ($nextCode)"

if ($Push) {
    Invoke-Git push origin main
}

Write-Host ""
Write-Host "Main is now on dev-$nextVersion ($nextCode)." -ForegroundColor Green
