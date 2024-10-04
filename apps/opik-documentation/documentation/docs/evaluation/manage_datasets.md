---
sidebar_position: 2
sidebar_label: Manage Datasets
---

# Manage Datasets

Datasets can be used to track test cases you would like to evaluate your LLM on. Each dataset is made up of DatasetItems which include `input` and optional `expected_output` and `metadata` fields. These datasets can be created from:

* Python SDK: You can use the Python SDK to create an dataset and add items to it.
* Traces table: You can add existing logged traces (from a production application for example) to a dataset.
* The Opik UI: You can manually create a dataset and add items to it.

Once a dataset has been created, you can run Experiments on it. Each Experiment will evaluate an LLM application based on the test cases in the dataset using an evaluation metric and report the results back to the dataset.

## Creating a dataset using the SDK

You can create a dataset and log items to it using the `Dataset` method:

```python
from opik import Opik

# Create a dataset
client = Opik()
dataset = client.create_dataset(name="My dataset")
```

### Insert items

You can insert items to a dataset using the `insert` method:

```python
from opik import DatasetItem
from opik import Opik

# Get or create a dataset
client = Opik()
try:
    dataset = client.create_dataset(name="My dataset")
except:
    dataset = client.get_dataset(name="My dataset")

# Add dataset items to it
dataset.insert([
    DatasetItem(input={"user_question": "Hello, world!"}, expected_output={"assistant_answer": "Hello, world!"}),
    DatasetItem(input={"user_question": "What is the capital of France?"}, expected_output={"assistant_answer": "Paris"}),
])
```

:::tip
Opik automatically deduplicates items that are inserted into a dataset when using the Python SDK. This means that you can insert the same item multiple times without duplicating it in the dataset.
:::

Instead of using the `DatasetItem` class, you can also use a dictionary to insert items to a dataset. The dictionary should have the `input` key while the `expected_output` and `metadata` are optional:

```python
dataset.insert([
    {"input": {"user_question": "Hello, world!"}},
    {"input": {"user_question": "What is the capital of France?"}, "expected_output": {"assistant_answer": "Paris"}},
])
```

You can also insert items from a JSONL file:

```python
dataset.read_jsonl_from_file("path/to/file.jsonl")
```
The format of the JSONL file should be a JSON object per line. For example:

```
{"input": {"user_question": "Hello, world!"}}
{"input": {"user_question": "What is the capital of France?"}, "expected_output": {"assistant_answer": "Paris"}}
``` 


Once the items have been inserted, you can view them them in the Opik UI:

![Opik Dataset](/img/evaluation/dataset_items_page.png)




### Deleting items

You can delete items in a dataset by using the `delete` method:

```python
from opik import Opik

# Get or create a dataset
client = Opik()
dataset = client.get_dataset(name="My dataset")

dataset.delete(items_ids=["123", "456"])
```

:::tip
You can also remove all the items in a dataset by using the `clear` method:

```python
from opik import Opik

# Get or create a dataset
client = Opik()
dataset = client.get_dataset(name="My dataset")

dataset.clear()
```
:::

## Downloading a dataset from Opik

You can download a dataset from Opik using the `get_dataset` method:

```python
from opik import Opik

client = Opik()
dataset = client.get_dataset(name="My dataset")
```

Once the dataset has been retrieved, you can access it's items using the `to_pandas()` or `to_json` methods:

```python
from opik import Opik

client = Opik()
dataset = client.get_dataset(name="My dataset")

# Convert to a Pandas DataFrame
dataset.to_pandas()

# Convert to a JSON array
dataset.to_json()
```
