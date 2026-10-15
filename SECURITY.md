# Security Policy

We take security bugs in Opik seriously, and we appreciate the work of researchers
who report them responsibly. This document explains how to reach us privately and
what happens after you do.

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues,
discussions, or pull requests.**

Report them through
[GitHub's private vulnerability reporting](https://github.com/comet-ml/opik/security/advisories/new).
The report is visible only to you and the Opik maintainers, and we use that advisory
to discuss details, develop a fix, and coordinate public disclosure with you.

If you are unable to use GitHub Security Advisories, email **support@comet.com**.

### What to include

The more of this you can provide, the faster we can triage:

- The type of issue (SSRF, RCE, path traversal, injection, auth bypass, ...)
- The affected component and version or commit, and whether it is the backend,
  frontend, an SDK, or the Helm chart / Docker Compose deployment
- Any configuration required to reproduce it — in particular, whether
  authentication was enabled
- Step-by-step reproduction instructions, and proof-of-concept code if you have it
- The impact, and how you think an attacker would use it

### What to expect

We will acknowledge your report, assess it, and keep you updated as we work on a
fix. How long that takes depends on the severity and complexity of the issue.

We will credit you in the published advisory unless you prefer to remain anonymous.
Please give us a chance to ship a fix before disclosing publicly.

## Scope

Opik runs in several configurations, and they do not share a threat model. All of
them are in scope for this policy — report anything you find in any of them — but
please read the note on open-source deployments before filing.

| Deployment | In scope |
| --- | --- |
| Opik Cloud (`comet.com`) | Yes |
| Opik self-hosted, Enterprise | Yes |
| Opik self-hosted, open source | Yes, with the caveat below |
| Opik SDKs and integrations | Yes |

### Open-source self-hosted deployments

Open-source Opik ships with **authentication disabled** (`AUTH_ENABLED=false`), and
authentication methods — SAML, OIDC, JWT — are
[an Enterprise feature that is not available in open-source deployments](https://www.comet.com/docs/opik/administration/authentication/overview).
A stock `docker compose` or Helm install therefore serves every request as a fully
authorized user of the `default` workspace.

This is a deliberate default for local and trusted-network use, not an oversight.
**Do not expose an open-source Opik deployment directly to the internet.** Place it
behind your own authenticating reverse proxy, VPN, or network boundary, and treat
anyone who can reach the API as an administrator of that instance.

Consequently, a report whose substance is "an internet-exposed open-source install
requires no login" describes this documented default, and we will close it as such.
A report of a *specific* flaw that an attacker can reach in that configuration —
server-side request forgery, remote code execution, path traversal, injection,
deserialization, or access to data across workspace boundaries — is a vulnerability,
is in scope, and we want to hear about it.

#### Features that run user-supplied code

Some Opik features execute Python code supplied by the user, by design: Python
custom metrics in online evaluation rules, for example, run in the
`opik-python-backend` service. How that code is isolated depends on
`PYTHON_CODE_EXECUTOR_STRATEGY` (see
[the architecture docs](https://www.comet.com/docs/opik/self-host/architecture)):

- `process`, the default in the open-source `docker compose` and Helm installs, runs
  the code in a worker process of the Python backend, with a timeout but no
  sandbox. The code has the service's privileges, environment, and network access.
- `docker` runs each execution in a separate, non-root container with memory and CPU
  limits and, by default, no network access.

In an open-source deployment, anyone who can reach the API is an administrator of
the instance (see above), and administrators are allowed to run code through these
features. Code execution through them, and what that code can reach in the `process`
strategy, is therefore the documented behaviour of that configuration rather than a
vulnerability — the same way the absence of a login is. If your deployment runs
code from people you do not trust with the Python backend's access, use the `docker`
strategy and keep the instance behind the network boundary described above.

These reports remain in scope:

- Escaping the `docker` executor's sandbox, or reaching resources it is configured
  to block.
- Executing code through a path that is not meant to run user code.
- Running code through these features without the permission they require, in a
  deployment where authentication is enabled (Opik Cloud or self-hosted Enterprise).

Hardening suggestions for the `process` strategy and the default deployment are
welcome through the same private channel; we may track them as hardening work rather
than as vulnerabilities.

## Supported versions

Opik releases frequently, and security fixes land in the next release rather than
being backported. Only the latest release is supported. We strongly recommend
tracking [the most recent release](https://github.com/comet-ml/opik/releases) to
receive security updates.

## Learn more

For Comet's certifications, subprocessors, and security documentation, see the
[Comet Trust Center](https://trust.comet.com).
