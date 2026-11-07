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
$sdkRoot = $env:ANDROID_SDK_ROOT
if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
    $sdkRoot = $env:ANDROID_HOME
}
if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
    $sdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk"
}

$ndkRoot = Join-Path $sdkRoot "ndk\$NdkVersion"
if (-not (Test-Path -LiteralPath $ndkRoot -PathType Container)) {
    throw "Android NDK $NdkVersion was not found at '$ndkRoot'. Install it from Android Studio > SDK Manager > SDK Tools > NDK (Side by side), or set ANDROID_SDK_ROOT."
}

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

Push-Location $repoRoot
try {
    Write-Host "Building c3emu native libraries for arm64-v8a, armeabi-v7a and x86_64..."
    Invoke-Checked -File "cargo" -Arguments @(
        "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-o", "app/src/main/jniLibs",
        "build",
        "--release",
        "--manifest-path", "engine/c3emu-android/Cargo.toml"
    )
}
finally {
    Pop-Location
}

$abis = @("arm64-v8a", "armeabi-v7a", "x86_64")
foreach ($abi in $abis) {
    $library = Join-Path $repoRoot "app\src\main\jniLibs\$abi\libc3emu_android.so"
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
