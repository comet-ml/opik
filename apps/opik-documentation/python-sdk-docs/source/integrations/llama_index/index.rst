llama_index
===========

Opik integrates with LlamaIndex to allow you to log your LlamaIndex calls to the Opik platform. To enable the logging to Opik, simply set::
    
    from llama_index.core import Settings
    from llama_index.core.callbacks import CallbackManager
    from opik.integrations.llama_index import LlamaIndexCallbackHandler

    opik_callback_handler = LlamaIndexCallbackHandler()
    Settings.callback_manager = CallbackManager([opik_callback_handler])

You can learn more about the `LlamaIndexCallbackHandler` callback in the following section:

.. toctree::
   :maxdepth: 4
   :titlesonly:

   LlamaIndexCallbackHandler
