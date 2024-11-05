Bedrock
=======

Opik integrates with Bedrock to allow you to log your Bedrock calls to the Opik platform, simply wrap the Bedrock client with `track_bedrock` to start logging::

   from opik.integrations.bedrock import track_bedrock
   import boto3

   bedrock = boto3.client(
       service_name="bedrock-runtime",
       region_name=REGION,
   )
   bedrock_client = track_bedrock(bedrock, project_name="bedrock-integration-demo")

   response = bedrock_client.converse(
       modelId=MODEL_ID,
       messages=[{"role": "user", "content": [{"text": "Hello World!"}]}],
   )

You can learn more about the `track_bedrock` decorator in the following section:

.. toctree::
   :maxdepth: 4
   :titlesonly:

   track_bedrock
