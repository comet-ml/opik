# Security Policy

## Reporting
- Please report suspected vulnerabilities privately to **security@estimatedstocks.com**.
- Include: affected version/tag/commit, environment, minimal reproduction steps, and any proof-of-concept output.
- Do **not** open public issues for exploitable bugs until a fix is available.

## Response
- We will acknowledge reports within 5 business days and coordinate investigation, fixes, and disclosure timing.
- Security fixes are issued in patch releases; mitigations and advisories will be published in release notes when applicable.
- CVE handling: if a vulnerability qualifies, we will request a CVE, publish an advisory with CVSS assessment, and include fixed versions and mitigation steps.
- Target timelines (best effort, severity-based):
  - Critical/High: triage within 5 business days, fix/mitigation aimed within 30 days.
  - Medium: triage within 10 business days, fix/mitigation aimed within 60 days.
  - Low/Informational: best-effort scheduling; may be deferred to next planned release.

## Scope
- ESNODE-Core code, build/release artifacts, and configuration shipped in this repository.
- Excludes third-party dependencies except where our integration introduces the vulnerability.

## Safe Harbor
- Good-faith security research that follows this policy will not be pursued legally by Estimatedstocks AB.
