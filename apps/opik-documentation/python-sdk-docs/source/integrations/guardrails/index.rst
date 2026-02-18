Guardrails AI
=============

Opik integrates with Guardrails AI to allow you to log your activities to the Opik platform, simply invoke `track_guardrails` to start logging.


First, install the politeness check from the guardrails hub:

``guardrails hub install hub://guardrails/politeness_check``


Then you can run the example:
::

    from guardrails import Guard, OnFailAction
    from guardrails.hub import PolitenessCheck

    import opik
    from opik.integrations.guardrails import track_guardrails

    politeness_check = PolitenessCheck(
        llm_callable="gpt-3.5-turbo", on_fail=OnFailAction.NOOP
    )

    guard: Guard = Guard().use(politeness_check)
    guard = track_guardrails(guard, project_name="guardrails-integration-example")

    result = guard.validate(
        "Would you be so kind to pass me a cup of tea?",
    )

Every guardrails check will be logged as a separate trace. Opik will capture inputs, outputs, and provide the trace with a tag "fail" or "pass" for easier management.

You can learn more about the `track_guardrails` decorator in the following section:

.. toctree::
   :maxdepth: 4
   :titlesonly:

   track_guardrails
