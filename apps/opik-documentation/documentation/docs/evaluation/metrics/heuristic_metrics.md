---
sidebar_label: Heuristic Metrics
description: Describes all the built-in heuristic metrics provided by Opik
---

# Heuristic Metrics

Heuristic metrics are rule-based evaluation methods that allow you to check specific aspects of language model outputs. These metrics use predefined criteria or patterns to assess the quality, consistency, or characteristics of generated text.

You can use the following heuristic metrics:

| Metric       | Description                                                                                       |
| ------------ | ------------------------------------------------------------------------------------------------- |
| Equals       | Checks if the output exactly matches an expected string                                           |
| Contains     | Check if the output contains a specific substring, can be both case sensitive or case insensitive |
| RegexMatch   | Checks if the output matches a specified regular expression pattern                               |
| IsJson       | Checks if the output is a valid JSON object                                                       |
| Levenshtein  | Calculates the Levenshtein distance between the output and an expected string                     |
| SentenceBLEU | Calculates a single-sentence BLEU score for a candidate vs. one or more references                |
| CorpusBLEU   | Calculates a corpus-level BLEU score for multiple candidates vs. their references                 |

## Score an LLM response

You can score an LLM response by first initializing the metrics and then calling the `score` method:

```python
from opik.evaluation.metrics import Contains

metric = Contains(name="contains_hello", case_sensitive=True)

score = metric.score(output="Hello world !", reference="Hello")

print(score)
```

## Metrics

### Equals

The `Equals` metric can be used to check if the output of an LLM exactly matches a specific string. It can be used in the following way:

```python
from opik.evaluation.metrics import Equals

metric = Equals()

score = metric.score(output="Hello world !", reference="Hello, world !")
print(score)
```

### Contains

The `Contains` metric can be used to check if the output of an LLM contains a specific substring. It can be used in the following way:

```python
from opik.evaluation.metrics import Contains

metric = Contains(case_sensitive=False)

score = metric.score(output="Hello world !", reference="Hello")
print(score)
```

### RegexMatch

The `RegexMatch` metric can be used to check if the output of an LLM matches a specified regular expression pattern. It can be used in the following way:

```python
from opik.evaluation.metrics import RegexMatch

metric = RegexMatch(regex="^[a-zA-Z0-9]+$")

score = metric.score("Hello world !")
print(score)
```

### IsJson

The `IsJson` metric can be used to check if the output of an LLM is valid. It can be used in the following way:

```python
from opik.evaluation.metrics import IsJson

metric = IsJson(name="is_json_metric")

score = metric.score(output='{"key": "some_valid_sql"}')
print(score)
```

### LevenshteinRatio

The `LevenshteinRatio` metric can be used to check if the output of an LLM is valid. It can be used in the following way:

```python
from opik.evaluation.metrics import LevenshteinRatio

metric = LevenshteinRatio()

score = metric.score(output="Hello world !", reference="hello")
print(score)
```

### BLEU

The BLEU (Bilingual Evaluation Understudy) metrics estimate how close the LLM outputs are to one or more reference translations. Opik provides two separate classes:

- `SentenceBLEU` – Single-sentence BLEU
- `CorpusBLEU` – Corpus-level BLEU
  Both rely on the underlying NLTK BLEU implementation with optional smoothing methods, weights, and variable n-gram orders.

You will need nltk library:

```bash
pip install nltk
```

Use `SentenceBLEU` to compute single-sentence BLEU between a single candidate and one (or more) references:

```python
from opik.evaluation.metrics import SentenceBLEU

metric = SentenceBLEU(n_grams=4, smoothing_method="method1")

# Single reference
score = metric.score(
    output="Hello world!",
    reference="Hello world"
)
print(score.value, score.reason)

# Multiple references
score = metric.score(
    output="Hello world!",
    reference=["Hello planet", "Hello world"]
)
print(score.value, score.reason)

```

Use `CorpusBLEU` to compute corpus-level BLEU for multiple candidates vs. multiple references. Each candidate and its references align by index in the list:

```python
from opik.evaluation.metrics import CorpusBLEU

metric = CorpusBLEU()

outputs = ["Hello there", "This is a test."]
references = [
    # For the first candidate, two references
    ["Hello world", "Hello there"],
    # For the second candidate, one reference
    "This is a test."
]

score = metric.score(output=outputs, reference=references)
print(score.value, score.reason)
```

You can also customize n-grams, smoothing methods, or weights:

```python
from opik.evaluation.metrics import SentenceBLEU

metric = SentenceBLEU(
    n_grams=4,
    smoothing_method="method2",
    weights=[0.25, 0.25, 0.25, 0.25]
)

score = metric.score(
    output="The cat sat on the mat",
    reference=["The cat is on the mat", "A cat sat here on the mat"]
)
print(score.value, score.reason)
```

**Note:** If any candidate or reference is empty, SentenceBLEU or CorpusBLEU will raise a MetricComputationError. Handle or validate inputs accordingly.
