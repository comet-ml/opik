Attachments Client
==================

The Attachments client provides methods for managing file attachments in the Opik platform.

.. autoclass:: opik.rest_api.attachments.client.AttachmentsClient
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
   
   # Upload an attachment
   client.rest_client.attachments.upload_attachment(
       entity_type="trace",
       entity_id="trace-id",
       name="results.json",
       content=b"{'result': 'success'}"
   )
   
   # List attachments for an entity
   attachments = client.rest_client.attachments.list_attachments(
       entity_type="trace",
       entity_id="trace-id"
   )
   
   # Download an attachment
   content = client.rest_client.attachments.download_attachment(
       entity_type="trace",
       attachment_id="attachment-id"
   )
