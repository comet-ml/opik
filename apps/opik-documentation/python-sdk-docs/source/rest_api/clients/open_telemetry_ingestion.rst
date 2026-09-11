OpenTelemetry Ingestion Client
==============================

The OpenTelemetry Ingestion client provides methods for ingesting OpenTelemetry data into the Opik platform.

.. autoclass:: opik.rest_api.open_telemetry_ingestion.client.OpenTelemetryIngestionClient
   :members:
   :undoc-members:
   :show-inheritance:
   :inherited-members:
   :exclude-members: with_raw_response

Usage Example
-------------

.. code-block:: python

   import opik
   
   client = opik.Opik()
   
   # Ingest OpenTelemetry traces data
   client.rest_client.open_telemetry_ingestion.ingest_traces(
       traces_data=traces_payload
   )
   
   # Ingest OpenTelemetry logs data
   client.rest_client.open_telemetry_ingestion.ingest_logs(
       logs_data=logs_payload
   )