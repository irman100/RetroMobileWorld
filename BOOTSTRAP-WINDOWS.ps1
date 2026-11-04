$ErrorActionPreference = 'Stop'

$repoOwner = 'irman100'
$repoName = 'RetroMobileWorld'
$repoFullName = "$repoOwner/$repoName"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host '=== RETRO MOBILE WORLD: GITHUB BOOTSTRAP ===' -ForegroundColor Cyan
Write-Host "Project: $projectRoot"
Write-Host "Target:  https://github.com/$repoFullName"

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    throw 'Git is not installed or is not available in PATH.'
}
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw 'GitHub CLI (gh) is not installed. Install it, run gh auth login, then run this script again.'
}

if (Test-Path -LiteralPath (Join-Path $projectRoot '.git')) {
    throw 'This folder already contains a .git directory. The script will not modify an existing repository.'
}

$userName = (& git config --global user.name 2>$null | Out-String).Trim()
$userEmail = (& git config --global user.email 2>$null | Out-String).Trim()
if ([string]::IsNullOrWhiteSpace($userName) -or [string]::IsNullOrWhiteSpace($userEmail)) {
    throw 'Git author identity is not configured. Set git config --global user.name and user.email, then retry.'
}

& gh auth status
if ($LASTEXITCODE -ne 0) {
    throw 'GitHub CLI is not authenticated. Run gh auth login and retry.'
}

$authenticatedLogin = (& gh api user --jq '.login' 2>$null | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($authenticatedLogin)) {
    throw 'Could not verify the authenticated GitHub account with gh api user.'
}
if ($authenticatedLogin -ne $repoOwner) {
    throw "Authenticated GitHub account is '$authenticatedLogin', but this bootstrap targets '$repoOwner'. Run gh auth switch --user $repoOwner and retry."
}

# Listing the owner's repositories succeeds when the target does not exist.
# An absent target is represented by an empty result, not a command error.
$ownerRepoNames = @(& gh repo list $repoOwner --limit 1000 --json name --jq '.[].name' 2>$null)
$repoListExitCode = $LASTEXITCODE
if ($repoListExitCode -ne 0) {
    throw "Could not list repositories for $repoOwner (gh exit code $repoListExitCode). Check GitHub connectivity and authentication."
}
if ($ownerRepoNames -contains $repoName) {
    throw "The GitHub repository $repoFullName already exists. The script stopped without changing it."
}

Write-Host 'PASS: Git author identity and GitHub account verified.' -ForegroundColor Green
Write-Host 'PASS: target repository does not exist; continuing with creation.' -ForegroundColor Green

Push-Location $projectRoot
try {
    & git init --initial-branch=main
    if ($LASTEXITCODE -ne 0) { throw 'git init failed.' }

    & git add --all
    if ($LASTEXITCODE -ne 0) { throw 'git add failed.' }

    & git commit -m 'chore: bootstrap Retro Mobile World shell and specification'
    if ($LASTEXITCODE -ne 0) { throw 'git commit failed. Configure user.name and user.email, then inspect the repository state before retrying.' }

    & gh repo create $repoFullName --private --source $projectRoot --remote origin --push --description 'A modular retro phone shell for Java ME and Symbian emulation.'
    if ($LASTEXITCODE -ne 0) { throw 'GitHub repository creation or initial push failed. Inspect gh output and repository state before retrying.' }

    Write-Host "Created and pushed https://github.com/$repoFullName" -ForegroundColor Green
}
finally {
    Pop-Location
}
