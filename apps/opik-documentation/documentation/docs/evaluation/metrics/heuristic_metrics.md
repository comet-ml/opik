---
sidebar_label: Heuristic Metrics
description: Describes all the built-in heuristic metrics provided by Opik
---

# Heuristic Metrics

Heuristic metrics are rule-based evaluation methods that allow you to check specific aspects of language model outputs. These metrics use predefined criteria or patterns to assess the quality, consistency, or characteristics of generated text.

You can use the following heuristic metrics:

| Metric      | Description                                                                                       |
| ----------- | ------------------------------------------------------------------------------------------------- |
| Equals      | Checks if the output exactly matches an expected string                                           |
| Contains    | Check if the output contains a specific substring, can be both case sensitive or case insensitive |
| RegexMatch  | Checks if the output matches a specified regular expression pattern                               |
| IsJson      | Checks if the output is a valid JSON object                                                       |
| Levenshtein | Calculates the Levenshtein distance between the output and an expected string                     |

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
