$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
$profilePath = Join-Path $projectRoot 'profiles/device-profiles.json'
$sourcePath = Join-Path $projectRoot 'catalog/sources.json'

function Fail([string]$Message) {
    throw $Message
}

function Read-JsonObject([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Fail "Required JSON file is missing: $Path"
    }
    try {
        $value = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
    }
    catch {
        Fail "Invalid JSON in $Path : $($_.Exception.Message)"
    }
    if ($null -eq $value -or $value -is [System.Array]) {
        Fail "Expected a JSON object in $Path"
    }
    return $value
}

function Test-Rect($Rect, [string]$Label) {
    if ($null -eq $Rect) { Fail "$Label`: rectangle is missing" }
    foreach ($field in @('x', 'y', 'width', 'height')) {
        if ($null -eq $Rect.PSObject.Properties[$field]) {
            Fail "$Label`: missing numeric field '$field'"
        }
        $number = 0.0
        if (-not [double]::TryParse([string]$Rect.$field, [ref]$number)) {
            Fail "$Label`: '$field' must be numeric"
        }
    }
    $x = [double]$Rect.x
    $y = [double]$Rect.y
    $width = [double]$Rect.width
    $height = [double]$Rect.height
    if ($x -lt 0 -or $y -lt 0 -or $width -le 0 -or $height -le 0 -or
        ($x + $width) -gt 1.0001 -or ($y + $height) -gt 1.0001) {
        Fail "$Label`: rectangle is outside normalized bounds"
    }
}

$profileDoc = Read-JsonObject $profilePath
if ($profileDoc.schemaVersion -ne 1 -or $null -eq $profileDoc.profiles -or @($profileDoc.profiles).Count -eq 0) {
    Fail 'profiles/device-profiles.json: unsupported schema or empty profiles list'
}

$expectedKeys = @(
    'UP', 'DOWN', 'LEFT', 'RIGHT', 'SELECT', 'SOFT_LEFT', 'SOFT_RIGHT', 'BACK',
    'CALL', 'END', 'DIGIT_0', 'DIGIT_1', 'DIGIT_2', 'DIGIT_3', 'DIGIT_4',
    'DIGIT_5', 'DIGIT_6', 'DIGIT_7', 'DIGIT_8', 'DIGIT_9', 'STAR', 'HASH'
)
$navigationStyles = @('cross_dpad', 'joystick', 's60_ring')
$visualStyles = @('hit_area', 'soft_key', 'back_key', 'numeric_key', 'call_key', 'end_key')
$runtimeFamilies = @('JAVA_ME', 'SYMBIAN')
$seenProfileIds = @{}
$profileCount = 0

foreach ($profile in @($profileDoc.profiles)) {
    $id = [string]$profile.id
    if ($id -notmatch '^[a-z0-9][a-z0-9-]+$' -or $seenProfileIds.ContainsKey($id)) {
        Fail "Invalid or duplicate device profile id: $id"
    }
    $seenProfileIds[$id] = $true
    $profileCount++

    $width = [int]$profile.display.width
    $height = [int]$profile.display.height
    if ($width -lt 48 -or $width -gt 2048 -or $height -lt 48 -or $height -gt 2048) {
        Fail "$id`: invalid logical display size"
    }

    $aperture = $profile.shell.displayAperture
    Test-Rect $aperture "$id.shell.displayAperture"
    $apertureRatio = ([double]$aperture.width * 400) / ([double]$aperture.height * 900)
    if ([math]::Abs($apertureRatio - ($width / $height)) -ge 0.02) {
        Fail "$id`: display aperture aspect ratio does not match logical display"
    }

    if ($navigationStyles -notcontains [string]$profile.keypad.navigation.style) {
        Fail "$id`: invalid navigation style"
    }
    Test-Rect $profile.keypad.navigation.bounds "$id.keypad.navigation.bounds"

    $mapping = [string]$profile.keypad.mapping
    $mappingPath = Join-Path (Join-Path $projectRoot 'profiles') ($mapping -replace '/', [IO.Path]::DirectorySeparatorChar)
    if (-not (Test-Path -LiteralPath $mappingPath -PathType Leaf)) {
        Fail "$id`: missing keymap file $mapping"
    }

    $controls = @($profile.keypad.controls)
    $actualKeys = @($controls | ForEach-Object { [string]$_.key })
    if ($actualKeys.Count -ne $expectedKeys.Count -or @($actualKeys | Select-Object -Unique).Count -ne $expectedKeys.Count) {
        Fail "$id`: controls must contain each required canonical key exactly once"
    }
    foreach ($key in $expectedKeys) {
        if ($actualKeys -notcontains $key) { Fail "$id`: missing required key $key" }
    }
    foreach ($control in $controls) {
        Test-Rect $control.bounds "$id.keypad.$($control.key).bounds"
        if ($visualStyles -notcontains [string]$control.visual -or [string]::IsNullOrWhiteSpace([string]$control.label)) {
            Fail "$id`: invalid visual/label for $($control.key)"
        }
    }

    $targets = @($profile.runtimeTargets)
    $families = @($targets | ForEach-Object { [string]$_.family })
    if ($targets.Count -eq 0 -or @($families | Select-Object -Unique).Count -ne $families.Count) {
        Fail "$id`: invalid or duplicate runtime targets"
    }
    foreach ($family in $families) {
        if ($runtimeFamilies -notcontains $family) { Fail "$id`: unsupported runtime family $family" }
    }
    if ($families -notcontains [string]$profile.defaultRuntime) {
        Fail "$id`: default runtime is not declared"
    }
    foreach ($target in $targets) {
        if ($target.family -eq 'SYMBIAN' -and $target.systemImageRequired -ne $true) {
            Fail "$id`: Symbian target must require a user-provided system image"
        }
    }
}

$sourceDoc = Read-JsonObject $sourcePath
if ($sourceDoc.schemaVersion -ne 1 -or $null -eq $sourceDoc.sources) {
    Fail 'catalog/sources.json: unsupported schema or missing sources list'
}
$statusValues = @('verified_live', 'verified_project', 'archived', 'historical', 'unavailable', 'not_checked')
$redistributionValues = @('metadata_only', 'link_only', 'redistribution_confirmed', 'source_license_reviewed', 'copyleft_review_required', 'unknown')
$seenSourceIds = @{}
$sourceCount = 0
foreach ($source in @($sourceDoc.sources)) {
    $id = [string]$source.id
    if ($id -notmatch '^[a-z0-9][a-z0-9-]+$' -or $seenSourceIds.ContainsKey($id)) {
        Fail "Invalid or duplicate catalogue source id: $id"
    }
    $seenSourceIds[$id] = $true
    $sourceCount++

    try { $uri = [System.Uri]([string]$source.url) }
    catch { Fail "$id`: invalid source URL" }
    if ($uri.Scheme -ne 'https' -or [string]::IsNullOrWhiteSpace($uri.Host)) {
        Fail "$id`: source URL must be HTTPS"
    }
    if ($statusValues -notcontains [string]$source.status) { Fail "$id`: unknown source status" }
    if ($redistributionValues -notcontains [string]$source.redistribution) { Fail "$id`: unknown redistribution status" }
    if ([string]::IsNullOrWhiteSpace([string]$source.notes)) { Fail "$id`: source verification notes are required" }
    if ($source.status -eq 'verified_live' -and [string]::IsNullOrWhiteSpace([string]$source.lastChecked)) {
        Fail "$id`: verified live source must record lastChecked"
    }
}

Write-Host "PASS: $profileCount device profiles validated" -ForegroundColor Green
Write-Host 'PASS: logical display/aperture ratios and per-key hit areas validated' -ForegroundColor Green
Write-Host 'PASS: all profiles declare the required canonical keys and runtime targets' -ForegroundColor Green
Write-Host "PASS: $sourceCount catalogue sources validated with explicit status and rights metadata" -ForegroundColor Green
