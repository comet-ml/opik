# Static validation for the repo's PowerShell scripts: parse check + PSScriptAnalyzer.
#
# One implementation, two callers:
#   - scripts/precommit-powershell-check.sh  (pre-commit hook: pwsh, or Windows PowerShell 5.1 on Windows)
#   - .github/workflows/powershell_checks.yml (windows-latest, once under pwsh and once under Windows PowerShell 5.1)
#
# So this script must itself run on both 5.1 and 7.
#
# Keeping the logic here rather than inline in the workflow means the local hook
# and the CI gate cannot drift apart.
#
# Files are passed in by the caller (pre-commit passes changed files; the workflow
# passes its glob discovery). With no arguments, discovers every PowerShell file.
[CmdletBinding()]
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$Paths = @(),

    # Emit ::error/::warning workflow commands so findings land as inline
    # annotations on the PR diff. Off for local runs, where they'd be noise.
    [switch]$Annotate,

    # The analyzer version the CALLER believes is correct, asserted against this
    # script's own constant below. CI passes the version it actually installed,
    # which is declared in the workflow (trusted, branch-protected) rather than
    # read from here -- a PR must not be able to choose which module version the
    # gate installs. Omitted for local runs, where the constant alone applies.
    # Named-only (no Position): otherwise it swallows the first file path.
    [Parameter(Mandatory = $false)]
    [string]$ExpectedAnalyzerVersion
)

$ErrorActionPreference = 'Stop'

# A script-terminating error does not by itself set a non-zero process exit code,
# so an unexpected failure in here would otherwise be reported to the caller as a
# pass. Trap it and exit 2 (distinct from 1 = genuine findings).
trap {
    # Inline rather than ConvertTo-LogLine: this can fire before that is defined.
    Write-Host ("PowerShell check script failed: $_" -replace '[\r\n]+', ' ')
    exit 2
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$settingsFile = Join-Path $repoRoot 'PSScriptAnalyzerSettings.psd1'
$settingsName = 'PSScriptAnalyzerSettings.psd1'

# The analyzer version the committed baseline in PSScriptAnalyzerSettings.psd1
# was measured against. Bumping the analyzer means changing this AND the matching
# literal in .github/workflows/powershell_checks.yml -- deliberately two edits,
# because the workflow copy is what CI installs and it must stay under branch
# protection rather than being read out of a PR's worktree.
$RequiredAnalyzerVersion = '1.25.0'

# The two engines keep modules in separate directories, so the install hint must
# name the one running this script. The engine label also tells the two CI legs'
# annotations apart: a finding only one leg reports is version-specific.
$engineExe = if ($PSVersionTable.PSEdition -eq 'Core') { 'pwsh' } else { 'powershell' }
$engine = "PowerShell $($PSVersionTable.PSVersion)"
$installHint = "Install it with: $engineExe -Command `"Install-Module PSScriptAnalyzer -RequiredVersion $RequiredAnalyzerVersion -Scope CurrentUser`""

# Cross-check the two declarations. CI passes the version it installed; if a PR
# edits the constant above, this fails instead of silently letting the gate
# validate against a version nobody reviewed.
if ($ExpectedAnalyzerVersion -and $ExpectedAnalyzerVersion -ne $RequiredAnalyzerVersion) {
    Write-Host "Analyzer version mismatch: caller installed $ExpectedAnalyzerVersion, this script requires $RequiredAnalyzerVersion."
    Write-Host 'Update both .github/workflows/powershell_checks.yml and this script together.'
    exit 2
}

# Workflow-command escaping, per GitHub's rules (@actions/core): a raw `,` or `:`
# in a path would truncate the annotation's file= property, and a CR/LF would end
# the command early and start a new line that the runner parses on its own.
function ConvertTo-AnnotationData {
    param([string]$Value)
    return $Value.Replace('%', '%25').Replace("`r", '%0D').Replace("`n", '%0A')
}

function ConvertTo-AnnotationProperty {
    param([string]$Value)
    return (ConvertTo-AnnotationData $Value).Replace(':', '%3A').Replace(',', '%2C')
}

# Plain log lines aren't commands, but the runner parses any line starting with
# `::`, so a CR/LF in a path or message must not be able to start one.
function ConvertTo-LogLine {
    param([string]$Value)
    return $Value -replace '[\r\n]+', ' '
}

if ($Paths.Count -gt 0) {
    # -LiteralPath throughout: a plain path is a wildcard pattern, so a real file
    # named a[1].ps1 would be reported missing.
    # Reject missing paths rather than filtering them out: a typo or a stale path
    # would otherwise fall through to "no files to check" and report success
    # without having validated anything.
    $missing = @($Paths | Where-Object { -not (Test-Path -LiteralPath $_) })
    if ($missing.Count -gt 0) {
        foreach ($m in $missing) { Write-Host (ConvertTo-LogLine "Path not found: $m") }
        exit 2
    }
    $targets = @($Paths | ForEach-Object { (Resolve-Path -LiteralPath $_).Path })
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
    $full = (Resolve-Path -LiteralPath $Path).Path
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
                Write-Host "::error file=$(ConvertTo-AnnotationProperty $relative),line=$line,col=$col::$(ConvertTo-AnnotationData "[$engine] $($e.Message)")"
            }
            Write-Host (ConvertTo-LogLine "  ${relative}:${line}:${col} $($e.Message)")
        }
        Write-Host (ConvertTo-LogLine "FAIL $relative -- $($errors.Count) parse error(s)")
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
    Write-Host $installHint
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
    Write-Host $installHint
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
        # -Path is a wildcard pattern and there is no -LiteralPath: unescaped, a
        # file named a[1].ps1 matches nothing and is silently passed unanalyzed.
        Invoke-ScriptAnalyzer -Path ([WildcardPattern]::Escape($file)) -Settings $settingsFile
    }
)

foreach ($f in $findings) {
    $relative = Get-RelativePath $f.ScriptPath
    if ($Annotate) {
        $level = if ($f.Severity -eq 'Error') { 'error' } else { 'warning' }
        Write-Host "::${level} file=$(ConvertTo-AnnotationProperty $relative),line=$($f.Line),col=$($f.Column)::$(ConvertTo-AnnotationData "[$engine] [$($f.RuleName)] $($f.Message)")"
    }
    Write-Host (ConvertTo-LogLine "  ${relative}:$($f.Line) [$($f.RuleName)] $($f.Message)")
}

if ($findings.Count -gt 0) {
    if ($Annotate) {
        Write-Host "::error::PSScriptAnalyzer reported $($findings.Count) finding(s)."
    }
    Write-Host "PSScriptAnalyzer reported $($findings.Count) finding(s) at Error or Warning severity."
    exit 1
}

Write-Host "PowerShell checks passed under $engine ($($targets.Count) file(s): parse + PSScriptAnalyzer)."
exit 0
