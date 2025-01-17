---
sidebar_label: LlamaIndex
description: Describes how to track LlamaIndex pipelines using Opik
---

# LlamaIndex

[LlamaIndex](https://github.com/run-llama/llama_index) is a flexible data framework for building LLM applications:

    LlamaIndex is a "data framework" to help you build LLM apps. It provides the following tools:

    - Offers data connectors to ingest your existing data sources and data formats (APIs, PDFs, docs, SQL, etc.).
    - Provides ways to structure your data (indices, graphs) so that this data can be easily used with LLMs.
    - Provides an advanced retrieval/query interface over your data: Feed in any LLM input prompt, get back retrieved context and knowledge-augmented output.
    - Allows easy integrations with your outer application framework (e.g. with LangChain, Flask, Docker, ChatGPT, anything else).

<div style="display: flex; align-items: center; flex-wrap: wrap; margin: 20px 0;">
  <span style="margin-right: 10px;">You can check out the Colab Notebook if you'd like to jump straight to the code:</span>
  <a href="https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/llama-index.ipynb" target="_blank" rel="noopener noreferrer">
    <img src="https://colab.research.google.com/assets/colab-badge.svg" alt="Open In Colab" style="vertical-align: middle;"/>
  </a>
</div>

## Getting Started

To use the Opik integration with LlamaIndex, you'll need to have both the `opik` and `llama_index` packages installed. You can install them using pip:

```bash
pip install opik llama-index llama-index-agent-openai llama-index-llms-openai llama-index-callbacks-opik
```

In addition, you can configure Opik using the `opik configure` command which will prompt you for the correct local server address or if you are using the Cloud platform your API key:

```bash
opik configure
```

## Using the Opik integration

To use the Opik integration with LLamaIndex, you can use the `set_global_handler` function from the LlamaIndex package to set the global tracer:

```python
from llama_index.core import global_handler, set_global_handler

set_global_handler("opik")
opik_callback_handler = global_handler
```

Now that the integration is set up, all the LlamaIndex runs will be traced and logged to Opik.

## Example

To showcase the integration, we will create a new a query engine that will use Paul Graham's essays as the data source.

**First step:**
Configure the Opik integration:

```python
from llama_index.core import global_handler, set_global_handler

set_global_handler("opik")
opik_callback_handler = global_handler
```

**Second step:**
Download the example data:

```python
import os
import requests

# Create directory if it doesn't exist
os.makedirs('./data/paul_graham/', exist_ok=True)

# Download the file using requests
url = 'https://raw.githubusercontent.com/run-llama/llama_index/main/docs/docs/examples/data/paul_graham/paul_graham_essay.txt'
response = requests.get(url)
with open('./data/paul_graham/paul_graham_essay.txt', 'wb') as f:
    f.write(response.content)
```

**Third step:**

Configure the OpenAI API key:

```python
import os
import getpass

if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")
```

**Fourth step:**

We can now load the data, create an index and query engine:

```python
from llama_index.core import VectorStoreIndex, SimpleDirectoryReader

documents = SimpleDirectoryReader("./data/paul_graham").load_data()
index = VectorStoreIndex.from_documents(documents)
query_engine = index.as_query_engine()

response = query_engine.query("What did the author do growing up?")
print(response)
```

Given that the integration with Opik has been set up, all the traces are logged to the Opik platform:

![llama_index](/img/tracing/llama_index_integration.png)
