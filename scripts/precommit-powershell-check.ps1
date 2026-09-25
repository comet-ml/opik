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
    [switch]$Annotate,

    # Print the required PSScriptAnalyzer version and exit. Lets the workflow
    # install exactly the version this script enforces, instead of repeating the
    # literal in two places that can drift.
    [switch]$PrintRequiredAnalyzerVersion
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

# The one place this version is declared. The workflow installs it by reading
# this value, so CI and local runs cannot drift onto different rule sets, and
# bumping the analyzer is a one-line change here.
$RequiredAnalyzerVersion = '1.25.0'

if ($PrintRequiredAnalyzerVersion) {
    Write-Output $RequiredAnalyzerVersion
    exit 0
}

if ($Paths.Count -gt 0) {
    # Reject missing paths rather than filtering them out: a typo or a stale path
    # would otherwise fall through to "no files to check" and report success
    # without having validated anything.
    $missing = @($Paths | Where-Object { -not (Test-Path $_) })
    if ($missing.Count -gt 0) {
        foreach ($m in $missing) { Write-Host "Path not found: $m" }
        exit 2
    }
    $targets = @($Paths | ForEach-Object { (Resolve-Path $_).Path })
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
# Hard failure, not a skip: this hook advertises parse + analyzer, so exiting 0
# with only the parse check done would pass a file the analyzer might reject.
# The .sh wrapper is what tolerates a machine with no PowerShell at all; once
# pwsh is present, the analyzer is required.
if (-not (Get-Module -ListAvailable -Name PSScriptAnalyzer)) {
    Write-Host 'PSScriptAnalyzer is not installed, so the analyzer half of this check cannot run.'
    Write-Host "Install it with: pwsh -Command `"Install-Module PSScriptAnalyzer -RequiredVersion $RequiredAnalyzerVersion -Scope CurrentUser`""
    exit 2
}

# Pin the version here as well as in the workflow: an unpinned import lets a
# local run (or a changed runner image) lint against a different rule set than
# the baseline in PSScriptAnalyzerSettings.psd1 was measured against.
$analyzerModule = Get-Module -ListAvailable -Name PSScriptAnalyzer |
    Where-Object { $_.Version -eq [version]$RequiredAnalyzerVersion } |
    Select-Object -First 1

if (-not $analyzerModule) {
    $found = (Get-Module -ListAvailable -Name PSScriptAnalyzer |
        ForEach-Object { $_.Version.ToString() }) -join ', '
    Write-Host "PSScriptAnalyzer $RequiredAnalyzerVersion is required; found: $found"
    Write-Host "Install it with: pwsh -Command `"Install-Module PSScriptAnalyzer -RequiredVersion $RequiredAnalyzerVersion -Scope CurrentUser`""
    exit 2
}

Import-Module PSScriptAnalyzer -RequiredVersion $RequiredAnalyzerVersion

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
