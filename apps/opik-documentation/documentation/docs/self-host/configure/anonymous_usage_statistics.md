---
sidebar_label: Anonymous Usage Statistics
description: Describes the usage statistics that are collected by Opik
---

# Anonymous Usage Statistics

Opik includes a system that optionally sends anonymous reports non-sensitive, non-personally identifiable information about the usage of the Opik platform. This information is used to help us understand how the Opik platform is being used and to identify areas for improvement.

The anonymous usage statistics reporting is enabled by default. You can opt-out by setting the `OPIK_USAGE_REPORT_ENABLED` environment variable to `false`.

## What data is collected?

When usage statistics reporting is enabled, report are collected by a server that is run and maintained by the Opik team.

The usage statistics include the following information:

- Information about the Opik server version:
  - A randomly generated ID that is unique to the Opik server instance such as `bdc47d37-2256-4604-a31e-18a34567cad1`
  - The Opik server version such as `0.1.7`
- Information about Opik users: This is not relevant for self-hosted deployments as no user management is available.
  - Total number of users
  - Daily number of users
- Information about Opik's usage reported daily:
  - The number of traces created
  - The number of experiments created
  - The number of datasets created

No personally identifiable information is collected and no user data is sent to the Opik team. The event payload that is sent to the Opik team follows the format:

```json
{
  "anonymous_id": "bdc47d37-2256-4604-a31e-18a34567cad1",
  "event_type": "opik_os_statistics_be",
  "event_properties": {
    "opik_app_version": "0.1.7",
    "total_users": "1",
    "daily_users": "1",
    "daily_traces": "123",
    "daily_experiments": "123",
    "daily_datasets": "123"
  }
}
```
