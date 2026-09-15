"""Reflection prompt template for the GEPA optimizer.

GEPA owns its search loop, but ``gepa.optimize()`` does accept a
``reflection_prompt_template`` that replaces the default instruction-proposal
prompt (``gepa/strategies/instruction_proposal.py``). That default tells the
reflection LM to "write a new instruction" from scratch and to "identify all
niche and domain specific factual information about the task and include it in
the instruction" — which, on a prompt whose message holds a template variable,
reads as an instruction to inline one dataset row's content in place of the
variable. That is the OPIK-7510 failure mode.

The template below is GEPA's default text, unchanged, plus an additive block
that makes the variable contract explicit and redirects the "inline the facts"
instruction to the surrounding text. Keeping the upstream wording intact is
deliberate: the reflection behaviour behind current candidate quality is
retained by construction, and the only delta is the added constraint. See
tests/unit/algorithms/gepa_optimizer/test_gepa_reflection_template.py, which
fails if upstream's default drifts so the two can be re-synced consciously.

Note the template is consumed by ``str.replace`` on the ``<curr_param>`` and
``<side_info>`` markers (never ``str.format``), so the literal curly-brace
examples below are safe. Both markers are mandatory — GEPA's
``InstructionProposalSignature.validate_prompt_template`` rejects a template
that omits either one.
"""

REFLECTION_PROMPT_TEMPLATE = """I provided an assistant with the following instructions to perform a task for me:
```
<curr_param>
```

The following are examples of different task inputs provided to the assistant along with the assistant's response for each of them, and some feedback on how the assistant's response could be better:
```
<side_info>
```

Your task is to write a new instruction for the assistant.

Read the inputs carefully and identify the input format and infer detailed task description about the task I wish to solve with the assistant.

Read all the assistant responses and the corresponding feedback. Identify all niche and domain specific factual information about the task and include it in the instruction, as a lot of it may not be available to the assistant in the future. The assistant may have utilized a generalizable strategy to solve the task, if so, include that in the instruction as well.

The current instruction may contain template variables: short identifiers wrapped in single curly braces, such as {question}, {context} or {user_input}. A template variable is a placeholder that is filled in with a different real value on every run - it is not example text, and not a value for you to resolve. Treat them as follows:

- Reproduce every template variable that appears in the current instruction verbatim in your new instruction, with identical spelling, casing and curly braces.
- Never delete a template variable, never rename one, and never replace one with a concrete value taken from the examples above. The examples show one run's data; the variable must stay open for every other run.
- You may move a variable to a better position and rewrite the text around it.
- Do not introduce new template variables that are absent from the current instruction.

The factual and strategic information you extract from the examples belongs in the surrounding instruction text, never in place of a template variable.

Provide the new instructions within ``` blocks."""

DEFAULT_PROMPTS: dict[str, str] = {
    "reflection_prompt_template": REFLECTION_PROMPT_TEMPLATE,
}
