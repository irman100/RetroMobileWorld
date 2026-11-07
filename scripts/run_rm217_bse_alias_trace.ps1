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
    '--disasm-thumb=0xF0104300:0x100',
    '--disasm-thumb=0xF0104800:0x140',
    '--disasm-thumb=0xF0104960:0x100',
    '--dump-image=0xF0104424:0x10',
    '--watch-pc=0xF0103ECE',
    '--watch-pc=0xF0103C04',
    '--watch-pc=0xF010432A',
    '--watch-pc=0xF010432E',
    '--watch-pc=0xF0104330',
    '--watch-pc=0xF0104332',
    '--watch-pc=0xF0104874',
    '--watch-pc=0xF0104880',
    '--watch-pc=0xF0104898',
    '--watch-pc=0xF0104976',
    '--watch-pc=0xF0103C0A',
    '--watch-pc=0xF010433A',
    '--watch-pc=0xF0104346',
    '--watch-pc=0xF010437C',
    '--watch-pc=0xF010438A',
    '--watch-pc=0xF010439E',
    '--watch-pc=0xF01043A0'
)

$Summary = Join-Path ([Environment]::GetFolderPath('Desktop')) 'rm217-bse-alias-mmuv2.summary.txt'
$Summarizer = Join-Path $PSScriptRoot 'summarize_rm217_bse_trace.py'

Write-Host '=== RM-217 BSE ALIAS / ARM926 MMU TRACE ==='
Write-Host "Probe:    $Probe"
Write-Host "Firmware: $Firmware"
Write-Host "Log:      $Log"
Write-Host "Summary:  $Summary"
Write-Host ''
Write-Host 'Running diagnostic-only NOR/ASIC overrides; this does not patch the guest memory map.'
Write-Host 'Full trace is redirected to the log file; only the compact summary will be printed.'
Write-Host ''

& $Probe @ProbeArgs *> $Log
$ProbeExit = $LASTEXITCODE

if (Test-Path -LiteralPath $Summarizer -PathType Leaf) {
    $PythonLauncher = Get-Command py -ErrorAction SilentlyContinue
    if ($PythonLauncher) {
        & $PythonLauncher.Source -3 $Summarizer --output $Summary $Log
        $SummaryExit = $LASTEXITCODE
        if ($SummaryExit -ne 0) {
            Write-Warning "Trace summarizer exited with code $SummaryExit"
        }
    } else {
        $PythonLauncher = Get-Command python -ErrorAction SilentlyContinue
        if ($PythonLauncher) {
            & $PythonLauncher.Source $Summarizer --output $Summary $Log
            $SummaryExit = $LASTEXITCODE
            if ($SummaryExit -ne 0) {
                Write-Warning "Trace summarizer exited with code $SummaryExit"
            }
        } else {
            Write-Warning "Python was not found. Manually run: py -3 $Summarizer --output $Summary $Log"
        }
    }
} else {
    Write-Warning "Summarizer not found beside runner: $Summarizer"
}

Write-Host ''
Write-Host "Probe exit code: $ProbeExit"
Write-Host "Full trace saved: $Log"
if (Test-Path -LiteralPath $Summary -PathType Leaf) {
    Write-Host "Compact summary saved: $Summary"
}

exit $ProbeExit
