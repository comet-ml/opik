# Security Policy

## Supported Versions


We strongly recommend always running the latest stable release to ensure you have the most recent security fixes.

---

## Reporting a Vulnerability

We take security vulnerabilities seriously. If you believe you have found a security issue in Opik, **please do not open a public GitHub issue**.

### Preferred Method: GitHub Private Security Advisory

Report vulnerabilities privately through GitHub's built-in disclosure mechanism:

👉 **[Submit a Private Security Advisory](https://github.com/comet-ml/opik/security/advisories/new)**

This ensures your report is handled confidentially and does not expose users to unnecessary risk before a fix is available.


---

## What to Include in Your Report

To help us triage and resolve the issue quickly, please provide:

- **Description** — A clear summary of the vulnerability
- **Affected component** — Endpoint, module, or function involved
- **Affected versions** — Which releases are impacted
- **Vulnerability type** — e.g., CWE identifier if known
- **Steps to reproduce** — Detailed reproduction steps or a PoC
- **Impact assessment** — Potential impact on confidentiality, integrity, or availability
- **Suggested fix** — Optional, but appreciated

---

## Our Commitment

| Timeline | Action |
| -------- | ------ |
| **Within 3 business days** | Acknowledge receipt of your report |
| **Within 14 days** | Provide an initial assessment and severity classification |
| **Within 30 days** | Resolve the vulnerability or agree on a disclosure timeline |

We follow a **30-day coordinated disclosure policy**. If we are unable to resolve the issue within 90 days, we will coordinate with you on an appropriate public disclosure date.

---

## CVE Assignment

For vulnerabilities that qualify, we will request a CVE identifier through GitHub's CNA program. You will be credited in the associated GitHub Security Advisory (GHSA) unless you prefer to remain anonymous.

---

## Scope

### In Scope

- Opik server (self-hosted deployment)
- Opik Python SDK (`opik` PyPI package)
- Opik REST API endpoints
- Authentication and authorization mechanisms
- Data integrity and confidentiality issues

### Out of Scope

- Vulnerabilities in third-party dependencies (please report these upstream)
- Issues in Comet.com cloud infrastructure (contact Comet directly)
- Security issues that require physical access to the host machine
- Social engineering attacks
- Denial of service via resource exhaustion without meaningful impact

---

## Safe Harbor

We will not pursue legal action against researchers who:

- Report vulnerabilities through this policy in good faith
- Avoid accessing, modifying, or deleting user data beyond what is necessary to demonstrate the vulnerability
- Do not disclose the vulnerability publicly before the agreed disclosure date
- Do not exploit the vulnerability for personal gain

---

## Recognition

We maintain a **Security Hall of Fame** for researchers who responsibly disclose valid vulnerabilities. If you would like to be credited, please let us know your preferred name or handle when submitting your report.

---

*This security policy is inspired by industry best practices including [CERT/CC Coordinated Vulnerability Disclosure](https://www.sei.cmu.edu/education-outreach/publications/publication.cfm?customel_datapageid_4415=21274) and [GitHub's CVD Guide](https://docs.github.com/en/code-security/security-advisories).*
