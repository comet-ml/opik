# Evaluating Opik's Hallucination Metric

For this guide we will be evaluating the Hallucination metric included in the LLM Evaluation SDK which will showcase both how to use the `evaluation` functionality in the platform as well as the quality of the Hallucination metric included in the SDK.

## Creating an account on Comet.com

[Comet](https://www.comet.com/site/?from=llm&utm_source=opik&utm_medium=colab&utm_content=eval_hall&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=colab&utm_content=eval_hall&utm_campaign=opik) and grab you API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=eval_hall&utm_campaign=opik) for more information.


```python
%pip install opik pyarrow fsspec huggingface_hub --upgrade
```

    Requirement already satisfied: opik in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (1.0.3)
    Requirement already satisfied: pyarrow in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (17.0.0)
    Collecting pyarrow
      Downloading pyarrow-18.0.0-cp312-cp312-macosx_12_0_arm64.whl.metadata (3.3 kB)
    Requirement already satisfied: fsspec in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (2024.9.0)
    Collecting fsspec
      Downloading fsspec-2024.10.0-py3-none-any.whl.metadata (11 kB)
    Requirement already satisfied: huggingface_hub in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (0.24.7)
    Collecting huggingface_hub
      Downloading huggingface_hub-0.26.2-py3-none-any.whl.metadata (13 kB)
    Requirement already satisfied: click in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (8.1.7)
    Requirement already satisfied: httpx<1.0.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (0.27.2)
    Requirement already satisfied: levenshtein~=0.25.1 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (0.25.1)
    Requirement already satisfied: litellm in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (1.49.1)
    Requirement already satisfied: openai<2.0.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (1.52.1)
    Requirement already satisfied: pandas<3.0.0,>=2.0.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (2.2.2)
    Requirement already satisfied: pydantic-settings<3.0.0,>=2.0.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (2.4.0)
    Requirement already satisfied: pydantic<3.0.0,>=2.0.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (2.8.2)
    Requirement already satisfied: pytest in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (8.3.2)
    Requirement already satisfied: rich in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (13.7.1)
    Requirement already satisfied: tqdm in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (4.66.5)
    Requirement already satisfied: questionary in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (2.0.1)
    Requirement already satisfied: uuid7<1.0.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (0.1.0)
    Requirement already satisfied: filelock in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from huggingface_hub) (3.15.4)
    Requirement already satisfied: packaging>=20.9 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from huggingface_hub) (24.1)
    Requirement already satisfied: pyyaml>=5.1 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from huggingface_hub) (6.0.2)
    Requirement already satisfied: requests in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from huggingface_hub) (2.32.3)
    Requirement already satisfied: typing-extensions>=3.7.4.3 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from huggingface_hub) (4.12.2)
    Requirement already satisfied: anyio in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from httpx<1.0.0->opik) (4.4.0)
    Requirement already satisfied: certifi in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from httpx<1.0.0->opik) (2024.7.4)
    Requirement already satisfied: httpcore==1.* in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from httpx<1.0.0->opik) (1.0.5)
    Requirement already satisfied: idna in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from httpx<1.0.0->opik) (3.7)
    Requirement already satisfied: sniffio in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from httpx<1.0.0->opik) (1.3.1)
    Requirement already satisfied: h11<0.15,>=0.13 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from httpcore==1.*->httpx<1.0.0->opik) (0.14.0)
    Requirement already satisfied: rapidfuzz<4.0.0,>=3.8.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from levenshtein~=0.25.1->opik) (3.9.6)
    Requirement already satisfied: distro<2,>=1.7.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from openai<2.0.0->opik) (1.9.0)
    Requirement already satisfied: jiter<1,>=0.4.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from openai<2.0.0->opik) (0.5.0)
    Requirement already satisfied: numpy>=1.26.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from pandas<3.0.0,>=2.0.0->opik) (1.26.4)
    Requirement already satisfied: python-dateutil>=2.8.2 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from pandas<3.0.0,>=2.0.0->opik) (2.9.0)
    Requirement already satisfied: pytz>=2020.1 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from pandas<3.0.0,>=2.0.0->opik) (2024.1)
    Requirement already satisfied: tzdata>=2022.7 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from pandas<3.0.0,>=2.0.0->opik) (2024.1)
    Requirement already satisfied: annotated-types>=0.4.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from pydantic<3.0.0,>=2.0.0->opik) (0.7.0)
    Requirement already satisfied: pydantic-core==2.20.1 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from pydantic<3.0.0,>=2.0.0->opik) (2.20.1)
    Requirement already satisfied: python-dotenv>=0.21.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from pydantic-settings<3.0.0,>=2.0.0->opik) (1.0.1)
    Requirement already satisfied: aiohttp in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from litellm->opik) (3.10.3)
    Requirement already satisfied: importlib-metadata>=6.8.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from litellm->opik) (8.2.0)
    Requirement already satisfied: jinja2<4.0.0,>=3.1.2 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from litellm->opik) (3.1.4)
    Requirement already satisfied: jsonschema<5.0.0,>=4.22.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from litellm->opik) (4.23.0)
    Requirement already satisfied: tiktoken>=0.7.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from litellm->opik) (0.7.0)
    Requirement already satisfied: tokenizers in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from litellm->opik) (0.19.1)
    Requirement already satisfied: charset-normalizer<4,>=2 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from requests->huggingface_hub) (3.3.2)
    Requirement already satisfied: urllib3<3,>=1.21.1 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from requests->huggingface_hub) (2.2.3)
    Requirement already satisfied: iniconfig in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from pytest->opik) (2.0.0)
    Requirement already satisfied: pluggy<2,>=1.5 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from pytest->opik) (1.5.0)
    Requirement already satisfied: prompt_toolkit<=3.0.36,>=2.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from questionary->opik) (3.0.36)
    Requirement already satisfied: markdown-it-py>=2.2.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from rich->opik) (3.0.0)
    Requirement already satisfied: pygments<3.0.0,>=2.13.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from rich->opik) (2.18.0)
    Requirement already satisfied: zipp>=0.5 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from importlib-metadata>=6.8.0->litellm->opik) (3.19.2)
    Requirement already satisfied: MarkupSafe>=2.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from jinja2<4.0.0,>=3.1.2->litellm->opik) (2.1.5)
    Requirement already satisfied: attrs>=22.2.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from jsonschema<5.0.0,>=4.22.0->litellm->opik) (24.2.0)
    Requirement already satisfied: jsonschema-specifications>=2023.03.6 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from jsonschema<5.0.0,>=4.22.0->litellm->opik) (2023.12.1)
    Requirement already satisfied: referencing>=0.28.4 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from jsonschema<5.0.0,>=4.22.0->litellm->opik) (0.35.1)
    Requirement already satisfied: rpds-py>=0.7.1 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from jsonschema<5.0.0,>=4.22.0->litellm->opik) (0.20.0)
    Requirement already satisfied: mdurl~=0.1 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from markdown-it-py>=2.2.0->rich->opik) (0.1.2)
    Requirement already satisfied: wcwidth in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from prompt_toolkit<=3.0.36,>=2.0->questionary->opik) (0.2.13)
    Requirement already satisfied: six>=1.5 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from python-dateutil>=2.8.2->pandas<3.0.0,>=2.0.0->opik) (1.16.0)
    Requirement already satisfied: regex>=2022.1.18 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from tiktoken>=0.7.0->litellm->opik) (2024.7.24)
    Requirement already satisfied: aiohappyeyeballs>=2.3.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from aiohttp->litellm->opik) (2.3.5)
    Requirement already satisfied: aiosignal>=1.1.2 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from aiohttp->litellm->opik) (1.3.1)
    Requirement already satisfied: frozenlist>=1.1.1 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from aiohttp->litellm->opik) (1.4.1)
    Requirement already satisfied: multidict<7.0,>=4.5 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from aiohttp->litellm->opik) (6.0.5)
    Requirement already satisfied: yarl<2.0,>=1.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from aiohttp->litellm->opik) (1.9.4)
    Downloading pyarrow-18.0.0-cp312-cp312-macosx_12_0_arm64.whl (29.5 MB)
    [2K   [90m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━[0m [32m29.5/29.5 MB[0m [31m22.5 MB/s[0m eta [36m0:00:00[0ma [36m0:00:01[0m
    [?25hDownloading fsspec-2024.10.0-py3-none-any.whl (179 kB)
    Downloading huggingface_hub-0.26.2-py3-none-any.whl (447 kB)
    Installing collected packages: pyarrow, fsspec, huggingface_hub
      Attempting uninstall: pyarrow
        Found existing installation: pyarrow 17.0.0
        Uninstalling pyarrow-17.0.0:
          Successfully uninstalled pyarrow-17.0.0
      Attempting uninstall: fsspec
        Found existing installation: fsspec 2024.9.0
        Uninstalling fsspec-2024.9.0:
          Successfully uninstalled fsspec-2024.9.0
      Attempting uninstall: huggingface_hub
        Found existing installation: huggingface-hub 0.24.7
        Uninstalling huggingface-hub-0.24.7:
          Successfully uninstalled huggingface-hub-0.24.7
    [31mERROR: pip's dependency resolver does not currently take into account all the packages that are installed. This behaviour is the source of the following dependency conflicts.
    gcsfs 2024.9.0.post1 requires fsspec==2024.9.0, but you have fsspec 2024.10.0 which is incompatible.
    predibase 2024.9.3 requires urllib3==1.26.12, but you have urllib3 2.2.3 which is incompatible.
    datasets 2.21.0 requires fsspec[http]<=2024.6.1,>=2023.1.0, but you have fsspec 2024.10.0 which is incompatible.[0m[31m
    [0mSuccessfully installed fsspec-2024.10.0 huggingface_hub-0.26.2 pyarrow-18.0.0
    Note: you may need to restart the kernel to use updated packages.



```python
import opik

opik.configure(use_local=False)
```

    OPIK: Opik is already configured. You can check the settings by viewing the config file at /Users/jacquesverre/.opik.config


## Preparing our environment

First, we will install configure the OpenAI API key and create a new Opik dataset


```python
import os
import getpass

if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")
```

We will be using the [HaluBench dataset](https://huggingface.co/datasets/PatronusAI/HaluBench?library=pandas) which according to this [paper](https://arxiv.org/pdf/2407.08488) GPT-4o detects 87.9% of hallucinations. The first step will be to create a dataset in the platform so we can keep track of the results of the evaluation.

Since the insert methods in the SDK deduplicates items, we can insert 50 items and if the items already exist, Opik will automatically remove them.


```python
# Create dataset
import opik
import pandas as pd

client = opik.Opik()

# Create dataset
dataset = client.get_or_create_dataset(
    name="HaluBench", description="HaluBench dataset"
)

# Insert items into dataset
df = pd.read_parquet(
    "hf://datasets/PatronusAI/HaluBench/data/test-00000-of-00001.parquet"
)
df = df.sample(n=50, random_state=42)

dataset_records = [
    {
        "input": x["question"],
        "context": [x["passage"]],
        "output": x["answer"],
        "expected_output": x["label"],
    } for x in df.to_dict(orient="records")
]

dataset.insert(dataset_records)
```

## Evaluating the hallucination metric

In order to evaluate the performance of the Opik hallucination metric, we will define:

- Evaluation task: Our evaluation task will use the data in the Dataset to return a hallucination score computed using the Opik hallucination metric.
- Scoring metric: We will use the `Equals` metric to check if the hallucination score computed matches the expected output.

By defining the evaluation task in this way, we will be able to understand how well Opik's hallucination metric is able to detect hallucinations in the dataset.


```python
from opik.evaluation.metrics import Hallucination, Equals
from opik.evaluation import evaluate
from opik import Opik
from opik.evaluation.metrics.llm_judges.hallucination.template import generate_query
from typing import Dict

# Define the evaluation task
def evaluation_task(x: Dict):
    metric = Hallucination()
    try:
        metric_score = metric.score(
            input=x["input"], context=x["context"], output=x["output"]
        )
        hallucination_score = metric_score.value
        hallucination_reason = metric_score.reason
    except Exception as e:
        print(e)
        hallucination_score = None
        hallucination_reason = str(e)

    return {
        "output": "FAIL" if hallucination_score == 1 else "PASS",
        "hallucination_reason": hallucination_reason,
        "reference": x["expected_output"],
    }


# Get the dataset
client = Opik()
dataset = client.get_dataset(name="HaluBench")

# Define the scoring metric
check_hallucinated_metric = Equals(name="Correct hallucination score")

# Add the prompt template as an experiment configuration
experiment_config = {
    "prompt_template": generate_query(
        input="{input}", context="{context}", output="{output}", few_shot_examples=[]
    )
}

res = evaluate(
    dataset=dataset,
    task=evaluation_task,
    scoring_metrics=[check_hallucinated_metric],
    experiment_config=experiment_config
)
```

    Evaluation: 100%|██████████| 100/100 [00:22<00:00,  4.49it/s]



<pre style="white-space:pre;overflow-x:auto;line-height:normal;font-family:Menlo,'DejaVu Sans Mono',consolas,'Courier New',monospace">╭─ HaluBench (100 samples) ─────────────────╮
│                                           │
│ <span style="font-weight: bold">Total time:       </span> 00:00:22               │
│ <span style="font-weight: bold">Number of samples:</span> 100                    │
│                                           │
│ <span style="color: #008000; text-decoration-color: #008000; font-weight: bold">Correct hallucination score: 0.8100 (avg)</span> │
│                                           │
╰───────────────────────────────────────────╯
</pre>




<pre style="white-space:pre;overflow-x:auto;line-height:normal;font-family:Menlo,'DejaVu Sans Mono',consolas,'Courier New',monospace">Uploading results to Opik <span style="color: #808000; text-decoration-color: #808000">...</span> 
</pre>




<pre style="white-space:pre;overflow-x:auto;line-height:normal;font-family:Menlo,'DejaVu Sans Mono',consolas,'Courier New',monospace">View the results <a href="https://www.comet.com/opik/jacques-comet/experiments/0192ba7d-67d1-70da-b19e-00f1c7b03339/compare?experiments=%5B%22067215da-e88a-788e-8000-f400b5344778%22%5D" target="_blank">in your Opik dashboard</a>.
</pre>



We can see that the hallucination metric is able to detect ~80% of the hallucinations contained in the dataset and we can see the specific items where hallucinations were not detected.

![Hallucination Evaluation](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/static/img/cookbook/hallucination_metric_cookbook.png)
