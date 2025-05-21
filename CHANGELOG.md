# Changelog

> **Note**: This changelog only contains breaking and critical changes for self-hosted deployments. For a complete changelog, please see our [official changelog](https://www.comet.com/docs/opik/changelog).

### Release 1.7.15, 2025-05-05

#### Critical Changes
- Updated port mapping when using `opik.sh` - This may affect existing deployments
- Fixed persistence when using Docker compose deployments - Requires reconfiguration for existing deployments

### Release 1.7.11, 2025-04-21

#### Security Change
- Updated Dockerfiles to ensure all containers run as non root users - This is a security-critical change that requires container recreation

### Release 1.7.0, 2025-04-09 [#](https://www.comet.com/docs/opik/self-host/local_deployment#troubleshooting)

#### Backward Incompatible Change

In this release, we migrated the Clickhouse table engines to their replicated version. The migration was automated, and we don't expect any errors. However, if you have any issues, please check [this link](https://www.comet.com/docs/opik/self-host/local_deployment#troubleshooting) or feel free to open an issue in this repository.

### Release 1.0.3, 2024-10-29 [#](apps/opik-backend/data-migrations/1.0.3/README.md)

#### Backward Incompatible Change

The structure of dataset items has changed to include new dynamic fields. Dataset items logged before version 1.0.3 will still show but would not be searchable. 
If you would like to migrate previous dataset items to the new format, please see the instructions below: [dataset item migration](apps/opik-backend/data-migrations/1.0.3/README.md) for more information*.

### Release 1.0.2, 2024-10-21

#### Backward Incompatible Change
- Updated datasets to support more flexible data schema - This change affects how dataset items are structured and may require updates to existing dataset insertion code. See [Dataset Documentation](https://www.comet.com/docs/opik/datasets/overview) for details.
- The `context` field is now optional in the [Hallucination metric](https://www.comet.com/docs/opik/evaluation/metrics/overview#hallucination) - This may affect existing evaluation configurations

### Release 1.0.1, 2024-09-30

#### Backward Incompatible Change
- Changed dataset item insertion behavior - Duplicate items are now silently ignored instead of being ingested, which may affect data collection workflows. See [Dataset Documentation](https://www.comet.com/docs/opik/datasets/overview) for details.

### Release 1.0.0, 2024-11-25

#### Backward Incompatible Change
- Updated OpenAI integration to track structured output calls using `beta.chat.completions.parse` - This may affect existing OpenAI integration code. See [OpenAI Integration Documentation](https://www.comet.com/docs/opik/tracing/integrations/openai) for details.
- Fixed issue with `update_current_span` and `update_current_trace` that did not support updating the `output` field - This may affect existing trace update code. See [Tracing Documentation](https://www.comet.com/docs/opik/tracing/overview) for details.

### Release 1.0.0, 2025-03-17

#### Backward Incompatible Change
- Added support for images in google.genai calls - This may affect existing Gemini integration code. See [Gemini Integration Documentation](https://www.comet.com/docs/opik/tracing/integrations/gemini) for details.
- [LangFlow integration](https://github.com/langflow-ai/langflow/pull/6928) has been merged - This may affect existing LangFlow deployments


