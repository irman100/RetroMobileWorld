[CmdletBinding()]
param(
    [string]$Serial,
    [string]$Branch = "feature/c3emu-touch-shell-speed-20261107",
    [string]$NdkVersion = "27.2.12479018",
    [switch]$InstallOnly,
    [switch]$UninstallOnSignatureMismatch
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string]$File,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$File exited with code $LASTEXITCODE."
    }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $repoRoot

# Never switch branches or pull over local edits. Preserve all user work.
$dirty = @(& git status --porcelain)
if ($LASTEXITCODE -ne 0) { throw "Could not read Git working-tree status." }
if ($dirty.Count -gt 0) {
    Write-Host "Uncommitted files were found:"
    $dirty | ForEach-Object { Write-Host "  $_" }
    throw "Save or commit these changes first. The script will not stash, reset or delete anything."
}

$currentBranch = (& git branch --show-current).Trim()
Write-Host "Current branch: $currentBranch"
Write-Host "Target branch:  $Branch"

if ($InstallOnly) {
    if ($currentBranch -ne $Branch) {
        throw "-InstallOnly requires the current branch to be $Branch; current branch is $currentBranch."
    }
} else {
    # Explicitly update the remote-tracking ref. Fetching only the branch name
    # may populate FETCH_HEAD without creating origin/<branch> on some clones.
    $sourceRef = "refs/heads/$Branch"
    $remoteRef = "refs/remotes/origin/$Branch"
    try {
        # Suppress automatic housekeeping during fetch: this workstation reports
        # Windows file-lock failures while Git tries to remove old pack indexes.
        Invoke-Checked -File "git" -Arguments @("-c", "gc.auto=0", "fetch", "origin", "${sourceRef}:${remoteRef}")
    } catch {
        # Retry Schannel/TLS handshake failures using Git for Windows OpenSSL
        # backend for this one command only; do not change global Git config.
        Write-Warning "Git fetch failed with the configured TLS backend. Retrying once with OpenSSL."
        Invoke-Checked -File "git" -Arguments @("-c", "gc.auto=0", "-c", "http.sslBackend=openssl", "fetch", "origin", "${sourceRef}:${remoteRef}")
    }

    if ($currentBranch -ne $Branch) {
        $localBranch = @(& git branch --list $Branch)
        if ($localBranch.Count -gt 0) {
            Invoke-Checked -File "git" -Arguments @("switch", $Branch)
        } else {
            # Git switch --track expects the remote branch shorthand, not its
            # full refs/remotes/... storage path.
            Invoke-Checked -File "git" -Arguments @("switch", "--track", "-c", $Branch, "origin/$Branch")
        }
    }

    # The fetch above already retrieved the remote head. Avoid a second TLS
    # handshake; advance the local branch only when a fast-forward is possible.
    Invoke-Checked -File "git" -Arguments @("-c", "gc.auto=0", "merge", "--ff-only", $remoteRef)
}

$commit = (& git rev-parse HEAD).Trim()
Write-Host "Using commit: $commit"

# Discover the installed SDK, including the D:\Android\Sdk location used by this PC.
$sdkCandidates = @()
if ($env:ANDROID_SDK_ROOT) { $sdkCandidates += $env:ANDROID_SDK_ROOT }
if ($env:ANDROID_HOME) { $sdkCandidates += $env:ANDROID_HOME }

$localProperties = Join-Path $repoRoot "local.properties"
if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
    $sdkLine = Get-Content -LiteralPath $localProperties |
        Where-Object { $_ -match '^\s*sdk\.dir=' } |
        Select-Object -First 1
    if ($sdkLine) {
        $localSdk = ($sdkLine -replace '^\s*sdk\.dir=', '').Trim()
        $localSdk = $localSdk.Replace('\\', '\').Replace('\:', ':').Replace('\=', '=').Replace('\ ', ' ')
        if ($localSdk) { $sdkCandidates += $localSdk }
    }
}
$sdkCandidates += @(
    'D:\Android\Sdk',
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
    (Join-Path $env:ProgramFiles 'Android\Sdk'),
    'C:\Android\Sdk'
)

$sdkRoot = $null
foreach ($candidate in ($sdkCandidates | Select-Object -Unique)) {
    if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
    $ndkCandidate = Join-Path $candidate "ndk\$NdkVersion"
    if (Test-Path -LiteralPath $ndkCandidate -PathType Container) {
        $sdkRoot = (Resolve-Path -LiteralPath $candidate).Path
        break
    }
}
if (-not $sdkRoot) {
    throw "Android SDK with NDK $NdkVersion was not found. This PC previously reported D:\Android\Sdk; verify that NDK $NdkVersion is installed there."
}

$env:ANDROID_SDK_ROOT = $sdkRoot
$env:ANDROID_HOME = $sdkRoot
$Adb = Join-Path $sdkRoot "platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) {
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) { $Adb = $adbCommand.Source }
    else { throw "adb.exe was not found under $sdkRoot\platform-tools." }
}

$Apk = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"
$buildStamp = Join-Path $repoRoot "app\build\c3emu-local-build-commit.txt"

if ($InstallOnly) {
    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
        throw "Existing local APK is missing: $Apk"
    }
    if (-not (Test-Path -LiteralPath $buildStamp -PathType Leaf) -or
        (Get-Content -LiteralPath $buildStamp -Raw).Trim() -ne $commit) {
        throw "The local APK does not have a matching build stamp for HEAD $commit. Run the script without -InstallOnly to rebuild."
    }
    Write-Host "Using previously built local APK for commit $commit."
} else {
    Write-Host " "
    Write-Host "=== BUILD NATIVE C3-00 ENGINE ==="
    & (Join-Path $repoRoot "scripts\build-c3emu-android.ps1") -NdkVersion $NdkVersion
    if ($LASTEXITCODE -ne 0) { throw "Native C3-00 build failed." }

    Write-Host " "
    Write-Host "=== ANDROID TESTS + DEBUG APK ==="
    Invoke-Checked -File (Join-Path $repoRoot "gradlew.bat") -Arguments @(
        "--no-daemon", ":app:testDebugUnitTest", ":app:assembleDebug"
    )

    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
        throw "Debug APK was not produced: $Apk"
    }
    Set-Content -LiteralPath $buildStamp -Value $commit -NoNewline -Encoding ASCII
}

Write-Host " "
Write-Host "APK ready:"
Get-Item -LiteralPath $Apk | Select-Object FullName, Length, LastWriteTime

Write-Host " "
Write-Host "=== PHYSICAL ANDROID DEVICES ==="
$deviceLines = @(& $Adb devices -l)
if ($LASTEXITCODE -ne 0) { throw "adb devices failed." }
$deviceLines | ForEach-Object { Write-Host $_ }

$rows = @(& $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '^\S+\s+\S+$' })
$availablePhones = @()
$unauthorizedPhones = @()
foreach ($row in $rows) {
    $parts = $row.Trim() -split '\s+'
    $deviceSerial = $parts[0]
    $deviceState = $parts[1]
    if ($deviceSerial -match '^emulator[-_]') { continue }
    if ($deviceState -eq "device") { $availablePhones += $deviceSerial }
    if ($deviceState -eq "unauthorized") { $unauthorizedPhones += $deviceSerial }
}

if ($Serial) {
    if ($Serial -match '^emulator[-_]') {
        throw "Serial '$Serial' identifies an Android emulator, not a physical phone."
    }
    if ($Serial -notin $availablePhones) {
        if ($Serial -in $unauthorizedPhones) {
            throw "Phone $Serial is unauthorized. Unlock it and accept the USB debugging RSA prompt, then rerun this script."
        }
        throw "Phone $Serial is not in the ADB 'device' state. Check the USB cable, USB mode and USB debugging."
    }
} else {
    if ($availablePhones.Count -eq 0) {
        if ($unauthorizedPhones.Count -gt 0) {
            throw "Phone detected but unauthorized: $($unauthorizedPhones -join ', '). Unlock it and accept the USB debugging RSA prompt, then rerun."
        }
        throw "No authorized physical phone found. Keep the phone connected, enable USB debugging and rerun."
    }
    if ($availablePhones.Count -ne 1) {
        throw "Found $($availablePhones.Count) physical phones: $($availablePhones -join ', '). Rerun with -Serial <phone-serial>."
    }
    $Serial = $availablePhones[0]
}

Write-Host " "
Write-Host "Selected physical phone: $Serial"
$state = (& $Adb -s $Serial get-state).Trim()
if ($state -ne "device") { throw "ADB state is '$state', expected 'device'." }

$manufacturer = (& $Adb -s $Serial shell getprop ro.product.manufacturer).Trim()
$model = (& $Adb -s $Serial shell getprop ro.product.model).Trim()
$devOptions = (& $Adb -s $Serial shell settings get global development_settings_enabled).Trim()
$adbEnabled = (& $Adb -s $Serial shell settings get global adb_enabled).Trim()
Write-Host "Phone: $manufacturer $model"
Write-Host "Developer options flag: $devOptions"
Write-Host "USB debugging flag: $adbEnabled"

if ($adbEnabled -ne "1") {
    Write-Warning "Android does not report adb_enabled=1. Check Developer options > USB debugging on the phone."
}

Write-Host " "
Write-Host "=== INSTALL LOCALLY BUILT APK ON THE PHYSICAL PHONE ==="
$installOutput = @(& $Adb -s $Serial install -r $Apk 2>&1)
$installExitCode = $LASTEXITCODE
$installOutput | ForEach-Object { Write-Host "$_" }

if ($installExitCode -ne 0) {
    $installText = $installOutput -join [Environment]::NewLine
    $signatureMismatch = $installText -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match"
    if ($signatureMismatch) {
        if (-not $UninstallOnSignatureMismatch) {
            throw "The installed GitHub APK and this local APK have different signing certificates. The existing app and its private data were left untouched. Only rerun with -UninstallOnSignatureMismatch after confirming you can lose this app's private firmware library and C: storage."
        }
        Write-Warning "Removing the existing app will delete its private firmware library, saved C: storage and app settings."
        Invoke-Checked -File $Adb -Arguments @("-s", $Serial, "uninstall", "org.retromobile.world")
        Invoke-Checked -File $Adb -Arguments @("-s", $Serial, "install", "-r", $Apk)
    } else {
        throw "APK installation failed with exit code $installExitCode."
    }
}

Write-Host " "
Write-Host "=== LAUNCH RETRO MOBILE WORLD ON THE PHONE ==="
Invoke-Checked -File $Adb -Arguments @("-s", $Serial, "shell", "monkey", "-p", "org.retromobile.world", "1")

Write-Host " "
Write-Host "=== INSTALLED PACKAGE ==="
& $Adb -s $Serial shell dumpsys package org.retromobile.world |
    Select-String -Pattern "versionCode=|versionName="
Write-Host " "
Write-Host "SUCCESS: locally built C3-00-enabled APK installed and launched on physical phone $Serial."
