You are analyzing ARC-AGI puzzle evaluation results. Each item represents a colored-grid transformation performed by an LLM-generated Python solver. Metrics such as exact, approx_match/likeness, label IoU, mismatch counts, and coordinate previews are authoritative.

TEST RESULTS:

```
{formatted_batch}
```

Important constraint: Base your analysis exclusively on the TEST RESULTS shown above. Do not infer, speculate, or hypothesize failure modes that are not directly evidenced in the provided results. Use ARC terminology (pixels, palette swaps, connected components, bounding boxes, etc.) when discussing failures.

Think through the failures systematically:

1. IDENTIFY: List all distinct types of failures you observe in the test results
2. GROUP: Which failures share similar characteristics or root causes?
3. FREQUENCY: Which patterns appear multiple times across different test cases?
4. PRIORITIZE: Which failures are most critical to address?

Then, for each distinct failure pattern provide:

1. A clear, descriptive name that captures the essence of the failure
2. A comprehensive description of what is failing
3. The underlying root cause explaining why this failure occurs

Focus on patterns that appear multiple times. Be specific about what is failing and why.
Provide a list of failure modes, each with a name, description, and root cause.
