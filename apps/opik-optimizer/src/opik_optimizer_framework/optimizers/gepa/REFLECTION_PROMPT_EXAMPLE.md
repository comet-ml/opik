# Reflection Prompt — Algorithm & Rendered Example

## Algorithm: Building the Reflection Prompt

The reflection prompt is assembled in `ReflectionProposer.propose()` and rendered by GEPA's `InstructionProposalSignature`. Here's the full pipeline for a single parameter:

### Step 1: Build the parameter header

`_build_header(name, candidate)` constructs a context block:

```
Parameter: {name}
Description: {description}                      ← only if config_descriptions[name] exists

Other parameters in this system (for context only — do NOT modify these):
- {other_name}: {other_description}              ← for each sibling parameter
- {other_name_2}                                 ← no description available
```

The header tells the reflection LLM:
- **What** it's optimizing (the named parameter)
- **Why** it exists (the description)
- **What else** is in the system (siblings, read-only context)

### Step 2: Compose `current_instruction`

```python
current_instruction = f"{header}\n{candidate[name]}"
```

This becomes the `<curr_param>` placeholder value in the template.

### Step 3: Build the reflective dataset

`ReflectiveDatasetBuilder.build()` transforms minibatch evaluation results into structured feedback records.

**Single-run items** (runs_per_item = 1) produce:
- **Inputs**: all dataset item fields (except `id`)
- **Generated Outputs**: the LLM's response
- **Feedback**: FAILED assertions (with reasons) and PASSED assertions

**Multi-run items** (runs_per_item > 1) produce:
- **Inputs**: all dataset item fields (except `id`)
- **Failure History** (optional, see below): persistent failure context — placed before run data
- **Summary**: pass/fail stats across runs, consistent failures, blocking assertion rates
- **Worst Run**: output and assertion details from the single worst run only

**Failure History annotation** (applied to both single-run and multi-run items):

The `FailureAwareBatchSampler` tracks cumulative assertion failures across the entire optimization. When an assertion has failed >= `persistent_failure_threshold` times (default 7, configurable via `GepaConfig`) AND failed again in the current evaluation, a Failure History section is inserted showing:
- Which assertions are persistently failing
- The failure count out of total evaluations (e.g., "failed 12 out of 15 evaluations")

Failure History is placed right after Inputs (before any run data) via dict key reordering, so it's the first thing the reflection LLM sees after the input context.

Records are sorted by difficulty (most failures first). This becomes the `<side_info>` placeholder value.

### Step 4: Render via GEPA's `InstructionProposalSignature`

```python
input_dict = {
    "current_instruction_doc": current_instruction,  # → <curr_param>
    "dataset_with_feedback": dataset_records,          # → <side_info>
    "prompt_template": GENERALIZATION_REFLECTION_TEMPLATE,
}
rendered = InstructionProposalSignature.prompt_renderer(input_dict)
```

GEPA substitutes placeholders and formats `<side_info>` records as numbered `# Example N` blocks with `## key` headers.

### Step 5: Call the reflection LLM

```python
result = InstructionProposalSignature.run(lm=lm, input_dict=input_dict)
new_text = result["new_instruction"]
```

### Step 6: Strip the header

The LLM sometimes echoes the header in its output. `_strip_header(text, name)` removes leading lines that match known header patterns: `Parameter:`, `Description:`, `Other parameters`, sibling bullet items, bare parameter names, and echoed description text.

### Step 7: Validate template variables

If the original parameter contains `{var}` placeholders, `_validate_template_vars()` checks that the proposal preserves all of them. Missing variables → the proposal is rejected and the component keeps its current text.

### Step 8: Log

The full reflection call is logged to `_reflection_log` for debugging:
- `component`: parameter name
- `current_instruction`: the full `<curr_param>` value
- `dataset_with_feedback`: the structured records
- `rendered_prompt`: what the LLM actually received
- `proposed_text`: the stripped output
- `rejected` (if applicable): reason for rejection (e.g., `"missing_template_vars"`)

---

## Template

The `GENERALIZATION_REFLECTION_TEMPLATE` is task-agnostic — no domain-specific examples, works for any parameter in any agent system.

```
I have a system that uses the following parameter to guide its behavior:
```
<curr_param>
```

The following are examples of inputs along with the system's outputs and
feedback showing which assertions PASSED and which FAILED.
Examples are sorted by priority — the ones with the most failures come first:
```
<side_info>
```

Your task is to write an improved version of this parameter that fixes
the FAILED assertions while preserving PASSED ones. Use the existing
parameter as your starting point.

STEP 1 — DIAGNOSE: [identify missing behaviors from FAILED, prefer keeping PASSED rules]
STEP 2 — CHECK FAILURE HISTORY: [persistent failures need fundamentally different approach —
         escalate via restructuring, step-by-step procedures, conditional logic,
         or section rewrite; may use more specific rules than normal,
         but still avoid non-generalizable specifics]
STEP 3 — WRITE FIXES: [update existing rules before adding new ones;
         observable, verifiable actions — generalize to any input;
         NEVER copy specific names/details/scenarios from feedback,
         abstract into general categories]
STEP 4 — STRUCTURE: [markdown formatting, group by behavior pattern under ## headers,
         merge overlaps]

IMPORTANT:
- Output ONLY the parameter text. No metadata lines.
- Preserve ALL template variables exactly (e.g. {var}).

Provide the new parameter within ``` blocks.
```

---

## Rendered Example

Scenario: 2 dataset items, optimizing `system_prompt` alongside `user_message`.
Baseline prompt: `"You are a customer support agent. Answer questions briefly."`
Prompt descriptions provided for both parameters.

````
I have a system that uses the following parameter to guide its behavior:
```
Parameter: system_prompt
Description: Main customer-facing support agent system prompt

Other parameters in this system (for context only — do NOT modify these):
- user_message: User message template with question placeholder

You are a customer support agent. Answer questions briefly.
```

The following are examples of inputs along with the system's outputs and
feedback showing which assertions PASSED and which FAILED.
Examples are sorted by priority — the ones with the most failures come first:
```
# Example 1
## Inputs
### question
I ordered a laptop 2 weeks ago, it arrived broken, I called 3 times and each
time was told someone would call back but nobody did. I am EXTREMELY frustrated
and want a FULL refund PLUS compensation!

### context
Order #98765, 3 previous support tickets closed without resolution

## Failure History
These assertions have been persistently failing and failed again in this
evaluation: "Response sincerely apologizes for the repeated failures in service"
(failed 12 out of 15 evaluations).

## Summary
0/3 runs passed. Consistent failures: Response sincerely apologizes for the
repeated failures in service
Blocking assertions (failed in at least one run):
- "Response sincerely apologizes for the repeated failures in service": failed 3/3 runs
- "Response does not make promises the agent cannot guarantee": failed 1/3 runs

## Worst Run
Worst run (1/3):
Output: I apologize for the inconvenience you've experienced. Please provide
your order number so I can assist you further.
FAILED assertions (fix these):
- Assertion: Response sincerely apologizes for the repeated failures in service
  Reason: Generic apology, does not acknowledge repeated failures
- Assertion: Response acknowledges the frustration of multiple unreturned callbacks
  Reason: No mention of the three unreturned callbacks
PASSED assertions (preserve these):
- Response is relevant to the user question
- Response does not make promises the agent cannot guarantee


# Example 2
## Inputs
### question
I think someone hacked my account! I see 3 orders I didn't make!

### context
3 unknown orders in last 24h, customer account flagged

## Generated Outputs
I'm sorry to hear that! Please change your password immediately. We can
help you investigate the unauthorized orders.

## Feedback
PASSED assertions (preserve these):
- Response treats the security concern with appropriate urgency
- Response advises immediate steps to secure the account
- Response mentions that unauthorized orders will be investigated


```

Your task is to write an improved version of this parameter that fixes
the FAILED assertions while preserving PASSED ones. Use the existing
parameter as your starting point.

STEP 1 — DIAGNOSE:
Read the FAILED assertions and identify what specific behaviors are missing or
wrong. Read the PASSED assertions — the current parameter already produces
these. Prefer keeping rules that drive successes, but you may tighten or
rephrase them if needed to fix failures.

STEP 2 — CHECK FAILURE HISTORY:
If any example has a "Failure History" section, the listed assertions have been
persistently failing across many previous attempts to fix them (the failure
count out of total evaluations is shown). The existing rules for these
assertions are not working — do NOT refine or rephrase them. Instead, try a
fundamentally different approach:
(a) restructure the parameter so the failing behavior gets more prominent
placement;
(b) add explicit step-by-step procedures with concrete example phrases;
(c) add conditional logic ("When X, always do Y before Z");
(d) rewrite the section governing the failing behavior from scratch.
For persistent failures, you may use more specific and detailed rules than you
normally would, but still avoid copying non-generalizable specifics from the
feedback examples.

STEP 3 — WRITE FIXES:
For each failing assertion, first check whether an existing rule can be updated
to cover the failing behavior before adding a new one. You may change multiple
related rules together if the failure requires coordinated changes. Every rule
must describe an observable, verifiable action — not abstract guidance. Rules
must generalize to any input. NEVER copy specific names, details, or scenarios
from the feedback examples into the parameter — they are just samples and will
change at runtime. Abstract them into general categories.

STEP 4 — STRUCTURE:
Use markdown formatting. Group rules by behavior pattern under ## headers, not
by scenario type. Merge overlapping rules. Keep the parameter concise.

IMPORTANT:
- Output ONLY the parameter text. Do NOT include any metadata such as
"Parameter:", "Description:", or "Other parameters" lines — those are
context for you, not part of the parameter.
- Preserve ALL template variables exactly as they appear in the original
parameter (e.g. {{var}}, {var}, <var>, {% var %}). These are runtime
placeholders filled by the system — do NOT rename, remove, or reformat them.
If the original has {var}, your output MUST also contain {var}.

Provide the new parameter within ``` blocks.
````
