---
title: Observability for [FRAMEWORK_NAME] with Opik
description: Start here to integrate Opik into your [FRAMEWORK_NAME]-based genai application for end-to-end LLM observability, unit testing, and optimization.
---

[Brief description of the framework and what it's used for. For example: "[FRAMEWORK_NAME] is a Python framework designed to build production-grade AI applications."]

[Brief explanation of the framework's primary advantage or key feature that makes it valuable for AI development.]

## Account Setup

[Comet](https://www.comet.com/site?from=llm&utm_source=opik&utm_medium=colab&utm_content=[framework_name]&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=colab&utm_content=[framework_name]&utm_campaign=opik) and grab your API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=[framework_name]&utm_campaign=opik) for more information.

<Frame>
  <img src="/img/tracing/[framework_name]_integration.png" alt="[FRAMEWORK_NAME] tracing" />

<!--
Screenshot should be placed at: apps/opik-documentation/documentation/fern/img/tracing/[framework_name]_integration.png
Documentation reference path: /img/tracing/[framework_name]_integration.png
-->
</Frame>

## Getting started

To use the [FRAMEWORK_NAME] integration with Opik, you will need to have [FRAMEWORK_NAME] and the required OpenTelemetry packages installed:

```bash
pip install --upgrade [framework_package] [framework_otel_packages] opentelemetry-api opentelemetry-sdk opentelemetry-exporter-otlp
```

In addition, you will need to set the following environment variables to configure OpenTelemetry to send data to Opik:

<Tabs>
    <Tab value="Opik Cloud" title="Opik Cloud">
        If you are using Opik Cloud, you will need to set the following
        environment variables:
            
        ```bash wordWrap
        export OTEL_EXPORTER_OTLP_ENDPOINT=https://www.comet.com/opik/api/v1/private/otel
        export OTEL_EXPORTER_OTLP_HEADERS='Authorization=<your-api-key>,Comet-Workspace=default'
        ```

        <Tip>
            To log the traces to a specific project, you can add the
            `projectName` parameter to the `OTEL_EXPORTER_OTLP_HEADERS`
            environment variable:

            ```bash wordWrap
            export OTEL_EXPORTER_OTLP_HEADERS='Authorization=<your-api-key>,Comet-Workspace=default,projectName=<your-project-name>'
            ```

            You can also update the `Comet-Workspace` parameter to a different
            value if you would like to log the data to a different workspace.
        </Tip>
    </Tab>
    <Tab value="Enterprise deployment" title="Enterprise deployment">
        If you are using an Enterprise deployment of Opik, you will need to set the following
        environment variables:

        ```bash wordWrap
        export OTEL_EXPORTER_OTLP_ENDPOINT=https://<comet-deployment-url>/opik/api/v1/private/otel
        export OTEL_EXPORTER_OTLP_HEADERS='Authorization=<your-api-key>,Comet-Workspace=default'
        ```

        <Tip>
            To log the traces to a specific project, you can add the
            `projectName` parameter to the `OTEL_EXPORTER_OTLP_HEADERS`
            environment variable:

            ```bash wordWrap
            export OTEL_EXPORTER_OTLP_HEADERS='Authorization=<your-api-key>,Comet-Workspace=default,projectName=<your-project-name>'
            ```

            You can also update the `Comet-Workspace` parameter to a different
            value if you would like to log the data to a different workspace.
        </Tip>
    </Tab>
    <Tab value="Self-hosted instance" title="Self-hosted instance">

    If you are self-hosting Opik, you will need to set the following environment
    variables:

    ```bash
    export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:5173/api/v1/private/otel
    ```

    <Tip>
        To log the traces to a specific project, you can add the `projectName`
        parameter to the `OTEL_EXPORTER_OTLP_HEADERS` environment variable:

        ```bash
        export OTEL_EXPORTER_OTLP_HEADERS='projectName=<your-project-name>'
        ```

    </Tip>
    </Tab>

</Tabs>

## Using Opik with [FRAMEWORK_NAME]

To track your [FRAMEWORK_NAME] applications, you will need to configure OpenTelemetry to instrument your framework:

```python
[framework_specific_instrumentation_code]
```

## Advanced usage

<!--
⚠️  DO NOT AUTO-GENERATE THIS SECTION ⚠️
Only include this section if the framework has genuine advanced features to showcase.
Examples of legitimate advanced features:
- Framework-specific configuration options
- Multi-agent workflows
- Custom tool integrations
- Production-ready enterprise configurations

❌ DO NOT include:
- Generic OpenTelemetry configurations
- Batch vs Simple span processors
- Generic resource configurations
- Standard tracer provider setups
-->

You can customize [FRAMEWORK_NAME] for more advanced use cases:

```python
[framework_specific_advanced_features]
```

[Description of framework-specific advanced features, not generic OpenTelemetry configurations.]

## Further improvements

If you would like to see us improve this integration, simply open a new feature
request on [Github](https://github.com/comet-ml/opik/issues).
