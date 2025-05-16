DSPy
====

Opik integrates with DSPy to allow you to log your DSPy runs to the Opik platform::

    import dspy
    from opik.integrations.dspy.callback import OpikCallback

    project_name = "DSPY"

    lm = dspy.LM(
        model="openai/gpt-4o-mini",
    )
    dspy.configure(lm=lm)


    opik_callback = OpikCallback(project_name=project_name, log_graph=True)
    dspy.settings.configure(
        callbacks=[opik_callback],
    )

    cot = dspy.ChainOfThought("question -> answer")
    cot(question="What is the meaning of life?")


You can learn more about the `OpikCallback` in the following section:

.. toctree::
   :maxdepth: 4
   :titlesonly:

   OpikCallback
