[CmdletBinding()]
param(
    [string]$NdkVersion = "27.2.12479018"
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

$repoRoot = Split-Path -Parent $PSScriptRoot

# Resolve the SDK from environment, this checkout's local.properties, or common
# Windows locations. Keep the path local to this process; do not rewrite user env.
$sdkCandidates = @()
if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
    $sdkCandidates += $env:ANDROID_SDK_ROOT
}
if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
    $sdkCandidates += $env:ANDROID_HOME
}

$localProperties = Join-Path $repoRoot "local.properties"
if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
    $sdkLine = Get-Content -LiteralPath $localProperties |
        Where-Object { $_ -match '^\s*sdk\.dir=' } |
        Select-Object -First 1
    if ($sdkLine) {
        $localSdk = ($sdkLine -replace '^\s*sdk\.dir=', '').Trim()
        # local.properties uses Java-properties escaping on Windows.
        $localSdk = $localSdk.Replace('\\', '\').Replace('\:', ':').Replace('\=', '=').Replace('\ ', ' ')
        if (-not [string]::IsNullOrWhiteSpace($localSdk)) {
            $sdkCandidates += $localSdk
        }
    }
}

# D:\Android\Sdk is also supported for workstations whose SDK was moved
# away from the Android Studio default under %LOCALAPPDATA%.
$sdkCandidates += @(
    'D:\Android\Sdk',
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
    (Join-Path $env:ProgramFiles 'Android\Sdk'),
    'C:\Android\Sdk'
)

$sdkRoot = $null
foreach ($candidate in ($sdkCandidates | Select-Object -Unique)) {
    if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
    $candidateNdk = Join-Path $candidate "ndk\$NdkVersion"
    if (Test-Path -LiteralPath $candidateNdk -PathType Container) {
        $sdkRoot = (Resolve-Path -LiteralPath $candidate).Path
        break
    }
}

if (-not $sdkRoot) {
    $checked = ($sdkCandidates | Select-Object -Unique) -join [Environment]::NewLine
    throw "Android NDK $NdkVersion was not found. SDK locations checked: $checked. Install the NDK in Android Studio > SDK Manager > SDK Tools, or set ANDROID_SDK_ROOT."
}

$ndkRoot = Join-Path $sdkRoot "ndk\$NdkVersion"
Write-Host "Using Android SDK: $sdkRoot"
Write-Host "Using Android NDK: $ndkRoot"

$env:ANDROID_SDK_ROOT = $sdkRoot
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_NDK_HOME = $ndkRoot
$env:ANDROID_NDK_ROOT = $ndkRoot

if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw "Rust Cargo is missing. Install the stable Rust toolchain first."
}
if (-not (Get-Command rustup -ErrorAction SilentlyContinue)) {
    throw "rustup is missing. Install Rust through rustup.rs first."
}

Write-Host "Installing Android Rust targets..."
Invoke-Checked -File "rustup" -Arguments @(
    "target", "add",
    "aarch64-linux-android",
    "armv7-linux-androideabi",
    "x86_64-linux-android"
)

if (-not (Get-Command cargo-ndk -ErrorAction SilentlyContinue)) {
    Write-Host "Installing cargo-ndk..."
    Invoke-Checked -File "cargo" -Arguments @("install", "cargo-ndk", "--locked")
}

$nativeProject = Join-Path $repoRoot "engine\c3emu-android"
$outputDirectory = Join-Path $repoRoot "app\src\main\jniLibs"
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

Push-Location $nativeProject
try {
    Write-Host "Building c3emu native libraries for arm64-v8a, armeabi-v7a and x86_64..."
    Invoke-Checked -File "cargo" -Arguments @(
        "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-o", "..\..\app\src\main\jniLibs",
        "build",
        "--release"
    )
}
finally {
    Pop-Location
}

$abis = @("arm64-v8a", "armeabi-v7a", "x86_64")
foreach ($abi in $abis) {
    $library = Join-Path $outputDirectory "$abi\libc3emu_android.so"
    if (-not (Test-Path -LiteralPath $library -PathType Leaf)) {
        throw "Native library for $abi was not produced: $library"
    }
    $item = Get-Item -LiteralPath $library
    if ($item.Length -le 0) {
        throw "Native library for $abi is empty: $library"
    }
    Write-Host ("OK {0} ({1:N0} bytes)" -f $abi, $item.Length)
}

Write-Host "c3emu Android native libraries are ready."
