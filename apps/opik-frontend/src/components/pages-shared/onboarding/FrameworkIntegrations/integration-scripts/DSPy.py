import dspy
from opik.integrations.dspy.callback import OpikCallback

# INJECT_OPIK_CONFIGURATION

lm = dspy.LM(model="openai/gpt-4o-mini")
dspy.configure(lm=lm)
opik_callback = OpikCallback()
dspy.settings.configure(callbacks=[opik_callback])
cot = dspy.ChainOfThought("question -> answer")
print(cot(question="What is the meaning of life?"))
