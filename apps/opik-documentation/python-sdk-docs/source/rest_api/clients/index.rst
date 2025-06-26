REST API Clients
================

This section documents all the REST API client modules available through ``opik.rest_client``.
Each client provides methods for interacting with specific resources in the Opik platform.

Core Resource Clients
---------------------

.. toctree::
   :maxdepth: 1
   
   traces
   spans
   datasets
   experiments
   projects

These clients handle the main resources you'll work with in Opik: traces for observability,
spans for detailed execution tracking, datasets for evaluation data, experiments for testing,
and projects for organization.

Feedback & Evaluation Clients
-----------------------------

.. toctree::
   :maxdepth: 1
   
   feedback_definitions
   automation_rule_evaluators
   optimizations

These clients manage evaluation and feedback systems: defining feedback score types,
setting up automated evaluation rules, and running optimization experiments.

Content & Asset Clients
-----------------------

.. toctree::
   :maxdepth: 1
   
   prompts
   attachments

These clients handle content management: prompt templates and versions, and file attachments
for traces and spans.

System & Configuration Clients
------------------------------

.. toctree::
   :maxdepth: 1
   
   check
   workspaces
   llm_provider_key
   service_toggles
   system_usage

These clients provide system-level functionality: health checks, workspace management,
API key configuration, feature toggles, and usage monitoring.

Integration Clients
-------------------

.. toctree::
   :maxdepth: 1
   
   chat_completions
   open_telemetry_ingestion
   guardrails
   redirect

These clients support integrations with external systems: chat completion APIs,
OpenTelemetry data ingestion, content validation, and URL redirection.

Usage Examples
--------------

Each client page includes specific usage examples. Here's how to access any client:

.. code-block:: python

   import opik
   
   client = opik.Opik()
   
   # Access any client through the rest_client property
   traces_client = client.rest_client.traces
   datasets_client = client.rest_client.datasets
   experiments_client = client.rest_client.experiments
   
   # Use the client methods
   trace = traces_client.get_trace_by_id("trace-id")
   datasets = datasets_client.find_datasets(page=0, size=10)