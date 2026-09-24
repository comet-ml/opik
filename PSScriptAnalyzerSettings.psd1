# PSScriptAnalyzer configuration for the repo's PowerShell scripts
# (opik.ps1, scripts/dev-runner.ps1). Enforced by:
#   - scripts/precommit-powershell-check.sh   (pre-commit hook)
#   - .github/workflows/powershell_checks.yml (windows-latest, authoritative)
#
# Gate severity is Error + Warning. Information is excluded: it is dominated by
# stylistic advice that would make the check noisy without catching the
# regressions this exists to catch.
#
# The exclusions below were derived from an actual baseline run (PSScriptAnalyzer
# 1.25.0), not assumed. The baseline was 26 findings across three rules; the two
# PSUseBOMForUnicodeEncodedFile findings were FIXED rather than suppressed (see
# below), leaving 24 excluded. The gate starts green, so any new finding is a real
# regression. Each exclusion is here because the rule does not fit what these
# scripts are -- prefer fixing a finding over adding to this list.
@{
    Severity = @('Error', 'Warning')

    ExcludeRules = @(
        # 23 findings. Wants -WhatIf/-Confirm support on every Start-/Stop-/New-/
        # Remove- function. These are internal helpers in a CLI launcher, not
        # exported cmdlets a user composes into a pipeline: the script IS the
        # confirmation prompt. Retrofitting ShouldProcess onto 23 functions would
        # be a large, risky rewrite of both launchers for no user-visible benefit.
        'PSUseShouldProcessForStateChangingFunctions',

        # NOT excluded: PSUseBOMForUnicodeEncodedFile. Its 2 findings were real --
        # README tells Windows users to run `powershell -ExecutionPolicy ByPass -c
        # ".\opik.ps1"`, i.e. Windows PowerShell 5.1, which decodes a BOM-less file
        # as ANSI. The banner's box-drawing characters and emoji would render as
        # mojibake. The [Console]::OutputEncoding lines at the top of the script do
        # not help: they set how output is written, not how the source is decoded.
        # Both files were given a UTF-8 BOM instead.

        # 1 finding: scripts/dev-runner.ps1 health-check poll loop. The empty catch
        # is correct -- a failed request means "backend not up yet, keep waiting",
        # and the loop has an explicit timeout. Logging it would emit up to 60
        # lines of noise per startup.
        'PSAvoidUsingEmptyCatchBlock',

        # 1 finding, and a false positive: opik.ps1 asks whether a container is
        # healthy via `if ((docker inspect ... $c 2>$null) -eq 'healthy')`. The
        # comparison is a correct -eq; the 2> is stderr redirection, not a
        # mistyped -gt. The same `2>$null` appears 10 times in the file and only
        # this one trips the rule, because the redirection sits inside parens
        # directly followed by a comparison, which defeats the heuristic.
        'PSPossibleIncorrectUsageOfRedirectionOperator',

        # Not currently triggered, but excluded deliberately: both launchers are
        # user-facing CLIs whose job is printing status to a human at a terminal
        # (221 Write-Host calls). Write-Output would pollute the pipeline and
        # change what callers capturing these scripts see.
        'PSAvoidUsingWriteHost',

        # Not currently triggered at Warning level, but excluded deliberately:
        # function names describe collections (Get-Containers, Find-JarFiles,
        # Stop-Containers). Renaming them to the singular would be a gratuitous
        # rename of both scripts' surface, and would read worse.
        'PSUseSingularNouns'
    )
}
