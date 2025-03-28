# Using Opik with LlamaIndex

This notebook showcases how to use Opik with LlamaIndex. [LlamaIndex](https://github.com/run-llama/llama_index) is a flexible data framework for building LLM applications:
> LlamaIndex is a "data framework" to help you build LLM apps. It provides the following tools:
>
> - Offers data connectors to ingest your existing data sources and data formats (APIs, PDFs, docs, SQL, etc.).
> - Provides ways to structure your data (indices, graphs) so that this data can be easily used with LLMs.
> - Provides an advanced retrieval/query interface over your data: Feed in any LLM input prompt, get back retrieved context and knowledge-augmented output.
> - Allows easy integrations with your outer application framework (e.g. with LangChain, Flask, Docker, ChatGPT, anything else).

For this guide we will be downloading the essays from Paul Graham and use them as our data source. We will then start querying these essays with LlamaIndex.

## Creating an account on Comet.com

[Comet](https://www.comet.com/site?from=llm&utm_source=opik&utm_medium=colab&utm_content=llamaindex&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm&=opik&utm_medium=colab&utm_content=llamaindex&utm_campaign=opik) and grab your API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=llamaindex&utm_campaign=opik) for more information.


```python
%pip install opik llama-index llama-index-agent-openai llama-index-llms-openai --upgrade --quiet
```


```python
import opik

opik.configure(use_local=False)
```

## Preparing our environment

First, we will download the Chinook database and set up our different API keys.

And configure the required environment variables:


```python
import os
import getpass

if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")
```

In addition, we will download the Paul Graham essays:


```python
import os
import requests

# Create directory if it doesn't exist
os.makedirs("./data/paul_graham/", exist_ok=True)

# Download the file using requests
url = "https://raw.githubusercontent.com/run-llama/llama_index/main/docs/docs/examples/data/paul_graham/paul_graham_essay.txt"
response = requests.get(url)
with open("./data/paul_graham/paul_graham_essay.txt", "wb") as f:
    f.write(response.content)
```

## Using LlamaIndex

### Configuring the Opik integration

You can use the Opik callback directly by calling:


```python
from llama_index.core import Settings
from llama_index.core.callbacks import CallbackManager
from opik.integrations.llama_index import LlamaIndexCallbackHandler

opik_callback_handler = LlamaIndexCallbackHandler()
Settings.callback_manager = CallbackManager([opik_callback_handler])
```

Now that the callback handler is configured, all traces will automatically be logged to Opik.

### Using LLamaIndex

The first step is to load the data into LlamaIndex. We will use the `SimpleDirectoryReader` to load the data from the `data/paul_graham` directory. We will also create the vector store to index all the loaded documents.


```python
from llama_index.core import VectorStoreIndex, SimpleDirectoryReader

documents = SimpleDirectoryReader("./data/paul_graham").load_data()
index = VectorStoreIndex.from_documents(documents)
query_engine = index.as_query_engine()
```

We can now query the index using the `query_engine` object:


```python
response = query_engine.query("What did the author do growing up?")
print(response)
```

You can now go to the Opik app to see the trace:

![LlamaIndex trace in Opik](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/static/img/cookbook/llamaIndex_cookbook.png)


