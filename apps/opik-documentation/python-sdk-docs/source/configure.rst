Dynamic Tracing Control
-----------------------

Opik provides the ability to dynamically enable or disable tracing at runtime. This is useful when you want to temporarily disable tracing for certain parts of your application or in specific environments.

.. code-block:: python

   from opik.config import set_tracing_active, is_tracing_active
   
   # Check if tracing is currently active
   if is_tracing_active():
       print("Tracing is enabled")
   
   # Disable tracing
   set_tracing_active(False)
   
   # Your code here - no traces will be created
   
   # Re-enable tracing
   set_tracing_active(True)

When tracing is disabled:

* The ``@track`` decorator will not create traces or spans
* Integration trackers (like ``track_openai``, ``track_anthropic``, etc.) will return the original client without instrumentation
* Existing instrumented clients will not create traces for new calls

This feature is particularly useful for:

* Disabling tracing in test environments
* Temporarily disabling tracing for performance-critical sections
* Implementing feature flags for tracing
