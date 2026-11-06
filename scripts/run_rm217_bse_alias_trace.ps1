param(
    [Parameter(Position = 0)]
    [string]$Firmware = 'E:\RM-217.zip'
)

$ErrorActionPreference = 'Stop'
$Probe = Join-Path $PSScriptRoot 'rm217-boot-probe.exe'
$Log = Join-Path ([Environment]::GetFolderPath('Desktop')) 'rm217-bse-alias-mmuv2.txt'

if (-not (Test-Path -LiteralPath $Probe -PathType Leaf)) {
    throw "Probe executable not found: $Probe. Extract rm217-boot-probe.exe next to this script."
}
if (-not (Test-Path -LiteralPath $Firmware -PathType Leaf)) {
    throw "Firmware ZIP not found: $Firmware. Pass the full path to the RM-217 Phoenix ZIP as the first argument."
}
if ([IO.Path]::GetExtension($Firmware) -ine '.zip') {
    throw "Firmware path must point to the Phoenix .zip archive, not a folder or extracted directory: $Firmware"
}

$ProbeArgs = @(
    $Firmware,
    '--entry=nolo',
    '--instructions=1000000',
    '--trace=96',
    '--ready-bit=0x20032D80:0',
    '--ready-bit=0x20032D20:0',
    '--ready-bit=0x20032D40:0',
    '--nor-id=0x00EC:0x2208',
    '--legacy-unmapped-as-ram',
    '--hle-secure-service6-launch',
    '--trace-bse-memory',
    '--watch-pc=0xF0103ECE',
    '--watch-pc=0xF0103C04',
    '--watch-pc=0xF0103C0A',
    '--watch-pc=0xF010433A',
    '--watch-pc=0xF0104346',
    '--watch-pc=0xF010437C',
    '--watch-pc=0xF010438A',
    '--watch-pc=0xF010439E',
    '--watch-pc=0xF01043A0'
)

Write-Host '=== RM-217 BSE ALIAS / ARM926 MMU TRACE ==='
Write-Host "Probe:    $Probe"
Write-Host "Firmware: $Firmware"
Write-Host "Log:      $Log"
Write-Host ''
Write-Host 'Running diagnostic-only NOR/ASIC overrides; this does not patch the guest memory map.'
Write-Host ''

& $Probe @ProbeArgs 2>&1 | Tee-Object -FilePath $Log
$ProbeExit = $LASTEXITCODE

Write-Host ''
Write-Host "Probe exit code: $ProbeExit"
Write-Host "Trace saved: $Log"

exit $ProbeExit
