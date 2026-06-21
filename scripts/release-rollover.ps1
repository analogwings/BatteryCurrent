param(
    [string]$GradleFile = "app\build.gradle.kts",
    [string]$WorkCommitMessage = "Prepare release content",
    [string]$ReleaseNotes = "Release",
    [switch]$SkipDirtyCheck,
    [switch]$Push
)

$ErrorActionPreference = "Stop"

function Get-CommandText {
    param([scriptblock]$Command)

    $output = @(& $Command)
    if ($LASTEXITCODE -ne 0 -or $output.Count -eq 0) {
        return ""
    }
    return (($output -join "`n").Trim())
}

function Require-MainBranch {
    $branch = Get-CommandText { git branch --show-current }
    if ([string]::IsNullOrWhiteSpace($branch)) {
        throw "Current checkout is detached from a branch. Run 'git switch main' before releasing."
    }
    if ($branch -ne "main") {
        throw "Current branch '$branch' is not 'main'. BatteryCurrent releases are rolled from main."
    }
}

function Require-CleanOrAllowed {
    param([switch]$SkipDirtyCheck)
    if ($SkipDirtyCheck) { return }
    $status = git status --porcelain --untracked-files=no
    if ($status) {
        throw "Working tree has tracked changes. Commit/stash first, or rerun with -SkipDirtyCheck to auto-commit tracked changes."
    }
}

function Get-VersionInfo {
    param([string]$FilePath)
    $content = Get-Content $FilePath -Raw

    if ($content -notmatch 'val\s+appVersionCode\s*=\s*(\d+)') {
        throw "Could not find appVersionCode in $FilePath"
    }
    $versionCode = [int]$matches[1]

    if ($content -notmatch 'val\s+appVersionName\s*=\s*"([^"]+)"') {
        throw "Could not find appVersionName in $FilePath"
    }
    $versionName = $matches[1]

    return @{
        VersionCode = $versionCode
        VersionName = $versionName
        Content = $content
    }
}

function Parse-DevVersion {
    param([string]$VersionName)
    if ($VersionName -notmatch '^dev-(\d+)\.(\d+)$') {
        throw "appVersionName '$VersionName' is not in expected BatteryCurrent dev format dev-X.YY"
    }
    return @{
        Major = [int]$matches[1]
        Minor = [int]$matches[2]
    }
}

function Set-VersionInfo {
    param(
        [string]$FilePath,
        [int]$VersionCode,
        [string]$VersionName
    )
    $content = Get-Content $FilePath -Raw
    $content = [regex]::Replace($content, 'val\s+appVersionCode\s*=\s*\d+', "val appVersionCode = $VersionCode")
    $content = [regex]::Replace($content, 'val\s+appVersionName\s*=\s*"[^"]+"', "val appVersionName = `"$VersionName`"")
    Set-Content $FilePath $content -NoNewline
}

function Git-CommitIfNeeded {
    param(
        [string]$Message,
        [string[]]$Paths = @()
    )
    if ($Paths.Count -gt 0) {
        git add -- $Paths | Out-Null
    } else {
        git add -u | Out-Null
    }
    $status = git diff --cached --name-only
    if ($status) {
        git commit -m $Message
    }
}

function Require-ReleaseTagAvailable {
    param([string]$TagName)

    $existingTagCommit = Get-CommandText { git rev-parse -q --verify "refs/tags/$TagName^{}" }
    if (-not [string]::IsNullOrWhiteSpace($existingTagCommit)) {
        throw "Release tag '$TagName' already exists at $existingTagCommit. Build from that tag, or bump appVersionName/appVersionCode before rolling a new release."
    }
}

function Ensure-ReleaseTag {
    param(
        [string]$TagName,
        [string]$TagMessage
    )

    $headCommit = Get-CommandText { git rev-parse HEAD }
    if ([string]::IsNullOrWhiteSpace($headCommit)) {
        throw "Unable to determine current HEAD commit."
    }

    $existingTagCommit = Get-CommandText { git rev-parse -q --verify "refs/tags/$TagName^{}" }
    if (-not [string]::IsNullOrWhiteSpace($existingTagCommit)) {
        if ($existingTagCommit -ne $headCommit) {
            throw "Tag '$TagName' already exists, but it points to $existingTagCommit instead of current release commit $headCommit. Do not overwrite it automatically."
        }
        Write-Host "Release tag already exists at current commit: $TagName" -ForegroundColor Yellow
        return
    }

    git tag -a $TagName -m $TagMessage
    Write-Host "Created release tag: $TagName" -ForegroundColor Green
}

Require-MainBranch
$version = Get-VersionInfo -FilePath $GradleFile
$parsed = Parse-DevVersion -VersionName $version.VersionName
$releaseName = "$($parsed.Major).$('{0:D2}' -f $parsed.Minor)"
$releaseTag = "v$releaseName"

Require-ReleaseTagAvailable -TagName $releaseTag
Require-CleanOrAllowed -SkipDirtyCheck:$SkipDirtyCheck

if ($SkipDirtyCheck) {
    Git-CommitIfNeeded -Message $WorkCommitMessage
}

Set-VersionInfo -FilePath $GradleFile -VersionCode $version.VersionCode -VersionName $releaseName
Git-CommitIfNeeded -Message "Prepare release $releaseTag ($($version.VersionCode))" -Paths @($GradleFile)

Ensure-ReleaseTag -TagName $releaseTag -TagMessage "$releaseTag ($($version.VersionCode)) - $ReleaseNotes"

if ($Push) {
    git push origin main
    git push origin $releaseTag
}

Write-Host ""
Write-Host "=== RELEASE READY ===" -ForegroundColor Green
Write-Host "Release tag: $releaseTag"
Write-Host "Release version: $releaseName ($($version.VersionCode))"
Write-Host "Build and upload the AAB from this release state now." -ForegroundColor Yellow
Write-Host "Press ENTER after the AAB is built/uploaded to roll forward to the next dev version..." -ForegroundColor Cyan
Read-Host

$nextMinor = $parsed.Minor + 1
$nextCode = $version.VersionCode + 1
$nextDevName = "dev-$($parsed.Major).$('{0:D2}' -f $nextMinor)"

Set-VersionInfo -FilePath $GradleFile -VersionCode $nextCode -VersionName $nextDevName
Git-CommitIfNeeded -Message "Start $nextDevName ($nextCode)" -Paths @($GradleFile)

if ($Push) {
    git push origin main
}

Write-Host ""
Write-Host "=== RELEASE ROLLOVER COMPLETE ===" -ForegroundColor Green
Write-Host "Released tag: $releaseTag"
Write-Host "Release version: $releaseName ($($version.VersionCode))"
Write-Host "Next dev version: $nextDevName ($nextCode)"
if ($Push) {
    Write-Host "Pushed main and $releaseTag." -ForegroundColor Green
} else {
    Write-Host "Not pushed. Review locally, then run:" -ForegroundColor Yellow
    Write-Host "  git push origin main"
    Write-Host "  git push origin $releaseTag"
}
