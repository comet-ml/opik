# Reflection Prompt — Algorithm & Rendered Example

## Algorithm: Building the Reflection Prompt

The reflection prompt is assembled in `ReflectionProposer.propose()` and rendered by GEPA's `InstructionProposalSignature`. Here's the full pipeline for a single parameter:

### Step 1: Build the parameter header

`_build_header(name, candidate)` constructs a context block:

```
Parameter: {name}
Description: {description}                      ← only if prompt_descriptions[name] exists

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

`ReflectiveDatasetBuilder.build()` transforms minibatch evaluation results into structured feedback records. Each record contains:

- **Inputs**: all dataset item fields (except `id`)
- **Generated Outputs** / **Runs**: the LLM's response(s)
- **Feedback**: FAILED assertions (with reasons) and PASSED assertions
- **Failure History** (optional): for items with consecutive failures, warns the LLM to try a different approach

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

The LLM sometimes echoes the header in its output. Strip it so it doesn't leak:

```python
if new_text.startswith(header + "\n"):
    new_text = new_text[len(header) + 1:]
elif new_text.startswith(f"Parameter: {name}\n"):
    new_text = new_text[len(f"Parameter: {name}\n"):]
```

### Step 7: Log

The full reflection call is logged to `_reflection_log` for debugging:
- `component`: parameter name
- `current_instruction`: the full `<curr_param>` value
- `dataset_with_feedback`: the structured records
- `rendered_prompt`: what the LLM actually received
- `proposed_text`: the stripped output

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

Your task is to write an improved version of this parameter. Preserve working
rules and make targeted additions or tweaks to fix the FAILED assertions.

STEP 1 — DIAGNOSE: [identify missing behaviors from FAILED, preserve PASSED]
STEP 2 — CHECK FAILURE HISTORY: [avoid repeating failed approaches]
STEP 3 — WRITE TARGETED FIXES: [observable actions, not vague guidance]
STEP 4 — STRUCTURE: [group rules, merge overlaps, keep concise]

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

## Runs
[Run 1/3]
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

[Run 2/3]
Output: I apologize for the inconvenience. I will escalate your case to ensure
a prompt resolution including refund and compensation.
FAILED assertions (fix these):
- Assertion: Response sincerely apologizes for the repeated failures in service
  Reason: Generic apology without acknowledging repeated service failures
- Assertion: Response does not make promises the agent cannot guarantee
  Reason: Commits to guaranteed refund and compensation
PASSED assertions (preserve these):
- Response is relevant to the user question
- Response offers a concrete resolution path like refund, escalation, or compensation

[Run 3/3]
...

## Summary
0/3 runs passed. Consistent failures: Response sincerely apologizes for the
repeated failures in service

## Failure History
This item has failed 2 consecutive iteration(s).
Still-failing assertions: Response sincerely apologizes for the repeated
failures in service. The current rules for these assertions are not working.


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

Your task is to write an improved version of this parameter. Preserve working
rules and make targeted additions or tweaks to fix the FAILED assertions.

STEP 1 — DIAGNOSE: Read the FAILED assertions and identify what behaviors
are missing. Read the PASSED assertions — the current parameter already
produces these. Preserve the rules that drive successes.

STEP 2 — CHECK FAILURE HISTORY: If any example has a "Failure History"
section, the current rules for that assertion already failed before.
Do NOT add another generic rule of the same kind. Instead embed concrete
example phrases or lookup instructions directly, or try a structurally
different approach.

STEP 3 — WRITE TARGETED FIXES: For each failing assertion, add or modify
a specific rule. Every rule must describe an observable action (what to say,
include, or avoid) — vague guidance does not reliably work. Rules must
generalize to any input in this domain; do NOT reference specific test inputs.

STEP 4 — STRUCTURE: Group related rules under short descriptive headers.
Merge overlapping rules. Remove redundant ones. Keep the parameter concise
— prefer tightening existing rules over appending new ones.

Provide the new parameter within ``` blocks.
````
