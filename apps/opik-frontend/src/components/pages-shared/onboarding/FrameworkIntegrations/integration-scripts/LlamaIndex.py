import os
from pathlib import Path
import requests
from llama_index.core import (
    SimpleDirectoryReader,
    VectorStoreIndex,
    global_handler,
    set_global_handler,
)

# INJECT_OPIK_CONFIGURATION

set_global_handler("opik")
opik_callback_handler = global_handler
url = "https://raw.githubusercontent.com/run-llama/llama_index/main/docs/docs/examples/data/paul_graham/paul_graham_essay.txt"
response = requests.get(url, timeout=60)
path = Path("./data/paul_graham/paul_graham_essay.txt")
path.parent.mkdir(parents=True, exist_ok=True)
path.write_bytes(response.content)
documents = SimpleDirectoryReader("./data/paul_graham").load_data()
index = VectorStoreIndex.from_documents(documents)
query_engine = index.as_query_engine()
response = query_engine.query("What did the author do growing up?")
print(response)
