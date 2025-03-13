import dspy
from opik.integrations.dspy.callback import OpikCallback # HIGHLIGHTED_LINE
import os

# INJECT_OPIK_CONFIGURATION

lm = dspy.LM(model="openai/gpt-4o-mini")
dspy.configure(lm=lm)
opik_callback = OpikCallback() # HIGHLIGHTED_LINE
dspy.settings.configure(callbacks=[opik_callback]) # HIGHLIGHTED_LINE
cot = dspy.ChainOfThought("question -> answer")
print(cot(question="What is the meaning of life?"))
