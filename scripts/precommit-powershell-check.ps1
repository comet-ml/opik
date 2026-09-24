# Static validation for the repo's PowerShell scripts: parse check + PSScriptAnalyzer.
#
# One implementation, two callers:
#   - scripts/precommit-powershell-check.sh  (pre-commit hook, PowerShell Core on Linux/macOS)
#   - .github/workflows/powershell_checks.yml (windows-latest, native PowerShell)
#
# Keeping the logic here rather than inline in the workflow means the local hook
# and the CI gate cannot drift apart.
#
# Files are passed in by the caller (pre-commit passes changed files; the workflow
# passes its glob discovery). With no arguments, discovers every PowerShell file.
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Paths = @(),

    # Emit ::error/::warning workflow commands so findings land as inline
    # annotations on the PR diff. Off for local runs, where they'd be noise.
    [switch]$Annotate
)

$ErrorActionPreference = 'Stop'

# A script-terminating error does not by itself set a non-zero process exit code,
# so an unexpected failure in here would otherwise be reported to the caller as a
# pass. Trap it and exit 2 (distinct from 1 = genuine findings).
trap {
    Write-Host "PowerShell check script failed: $_"
    exit 2
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$settingsFile = Join-Path $repoRoot 'PSScriptAnalyzerSettings.psd1'
$settingsName = 'PSScriptAnalyzerSettings.psd1'

if ($Paths.Count -gt 0) {
    $targets = @($Paths | Where-Object { Test-Path $_ } | ForEach-Object { (Resolve-Path $_).Path })
}
else {
    $targets = @(
        Get-ChildItem -Path $repoRoot -Recurse -File -Include *.ps1, *.psm1, *.psd1 |
            Where-Object { $_.FullName -notmatch '[\\/](node_modules|\.git)[\\/]' } |
            ForEach-Object { $_.FullName }
    )
}

if ($targets.Count -eq 0) {
    Write-Host 'No PowerShell files to check.'
    exit 0
}

function Get-RelativePath {
    param([string]$Path)
    $full = (Resolve-Path $Path).Path
    if ($full.StartsWith($repoRoot)) {
        return $full.Substring($repoRoot.Length).TrimStart('\', '/').Replace('\', '/')
    }
    return $full
}

# --- Parse check ------------------------------------------------------------
# Independent of lint configuration, and not suppressible via the settings file:
# a syntax error is always fatal.
$parseFailures = 0

foreach ($file in $targets) {
    $relative = Get-RelativePath $file
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($file, [ref]$null, [ref]$errors)

    if ($errors -and $errors.Count -gt 0) {
        $parseFailures++
        foreach ($e in $errors) {
            $line = $e.Extent.StartLineNumber
            $col = $e.Extent.StartColumnNumber
            if ($Annotate) {
                Write-Host "::error file=$relative,line=$line,col=$col::$($e.Message)"
            }
            Write-Host "  ${relative}:${line}:${col} $($e.Message)"
        }
        Write-Host "FAIL $relative -- $($errors.Count) parse error(s)"
    }
}

# Report every file's parse errors before bailing, so one broken file doesn't
# mask another's. Skip the analyzer though: it can't say anything useful about a
# file that doesn't parse.
if ($parseFailures -gt 0) {
    if ($Annotate) { Write-Host "::error::$parseFailures file(s) failed to parse." }
    Write-Host "$parseFailures file(s) failed to parse."
    exit 1
}

# --- PSScriptAnalyzer -------------------------------------------------------
if (-not (Get-Module -ListAvailable -Name PSScriptAnalyzer)) {
    Write-Host 'PSScriptAnalyzer is not installed - skipping lint (parse check passed).'
    Write-Host 'Install it with: pwsh -Command "Install-Module PSScriptAnalyzer -Scope CurrentUser"'
    exit 0
}

Import-Module PSScriptAnalyzer

# The settings file is itself a .psd1, so it is matched by the same glob. Parse-
# checking it above is wanted; analyzing it is not, as it is pure data.
$analyzeTargets = @($targets | Where-Object { (Split-Path $_ -Leaf) -ne $settingsName })

if ($analyzeTargets.Count -eq 0) {
    Write-Host 'Parse check passed; no files to analyze.'
    exit 0
}

# -Path takes a single path, not a collection, so analyze one file at a time.
# @(...) forces an array: a single finding would otherwise come back as a bare
# object whose .Count is $null, and the gate below would pass.
$findings = @(
    foreach ($file in $analyzeTargets) {
        Invoke-ScriptAnalyzer -Path $file -Settings $settingsFile
    }
)

foreach ($f in $findings) {
    $relative = Get-RelativePath $f.ScriptPath
    if ($Annotate) {
        $level = if ($f.Severity -eq 'Error') { 'error' } else { 'warning' }
        Write-Host "::${level} file=${relative},line=$($f.Line),col=$($f.Column)::[$($f.RuleName)] $($f.Message)"
    }
    Write-Host "  ${relative}:$($f.Line) [$($f.RuleName)] $($f.Message)"
}

if ($findings.Count -gt 0) {
    if ($Annotate) {
        Write-Host "::error::PSScriptAnalyzer reported $($findings.Count) finding(s)."
    }
    Write-Host "PSScriptAnalyzer reported $($findings.Count) finding(s) at Error or Warning severity."
    exit 1
}

Write-Host "PowerShell checks passed ($($targets.Count) file(s): parse + PSScriptAnalyzer)."
exit 0
