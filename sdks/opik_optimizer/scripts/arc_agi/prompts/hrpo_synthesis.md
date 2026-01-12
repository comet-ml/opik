You are synthesizing ARC-AGI root-cause analyses from multiple batches of puzzle results.

BATCH ANALYSES:
```
{batch_summaries}
```

Your task is to synthesize these batch-level analyses into a unified ARC-aware root cause analysis.

1. MERGE similar failure modes across batches:
   - If multiple batches identify the same or very similar failure pattern, combine them into one unified failure mode
   - Create a comprehensive description that captures the pattern across all relevant batches
   - Identify the core root cause

2. PRIORITIZE the most critical failure modes:
   - Focus on patterns that appear in multiple batches
   - Consider the severity and frequency of each failure
   - Eliminate one-off or minor issues unless they're particularly impactful

3. PROVIDE SYNTHESIS NOTES:
   - Briefly explain which batch-level patterns were merged and why
   - Note any cross-batch trends or patterns
   - Highlight the most critical areas for improvement

Provide:
1. A unified list of failure modes (name, description, root cause)
2. Synthesis notes explaining your analysis process and key findings
