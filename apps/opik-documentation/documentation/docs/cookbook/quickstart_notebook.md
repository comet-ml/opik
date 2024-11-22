# Quickstart notebook - Summarization task

In this notebook, we will look at how you can use Opik to track your LLM calls, chains and agents. We will introduce the concept of tracing and how to automate the evaluation of your LLM workflows.

We will be using a technique called Chain of Density Summarization to summarize Arxiv papers. You can learn more about this technique in the [From Sparse to Dense: GPT-4 Summarization with Chain of Density Prompting](https://arxiv.org/abs/2309.04269) paper.

## Getting started

We will first install the required dependencies and configure both Opik and OpenAI.


```python
%pip install -U opik openai requests PyPDF2 --quiet
```

    Requirement already satisfied: opik in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (1.1.10)
    Requirement already satisfied: openai in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (1.54.4)
    Collecting openai
      Downloading openai-1.55.0-py3-none-any.whl.metadata (24 kB)
    Requirement already satisfied: requests in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (2.32.3)
    Requirement already satisfied: PyPDF2 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (3.0.1)
    Requirement already satisfied: click in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (8.1.7)
    Requirement already satisfied: httpx<1.0.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (0.27.2)
    Requirement already satisfied: levenshtein~=0.25.1 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (0.25.1)
    Requirement already satisfied: litellm in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (1.51.2)
    Requirement already satisfied: pydantic-settings<3.0.0,>=2.0.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (2.4.0)
    Requirement already satisfied: pydantic<3.0.0,>=2.0.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (2.8.2)
    Requirement already satisfied: pytest in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (8.3.2)
    Requirement already satisfied: rich in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (13.7.1)
    Requirement already satisfied: tqdm in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (4.66.5)
    Requirement already satisfied: uuid7<1.0.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from opik) (0.1.0)
    Requirement already satisfied: anyio<5,>=3.5.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from openai) (4.4.0)
    Requirement already satisfied: distro<2,>=1.7.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from openai) (1.9.0)
    Requirement already satisfied: jiter<1,>=0.4.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from openai) (0.5.0)
    Requirement already satisfied: sniffio in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from openai) (1.3.1)
    Requirement already satisfied: typing-extensions<5,>=4.11 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from openai) (4.12.2)
    Requirement already satisfied: charset-normalizer<4,>=2 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from requests) (3.3.2)
    Requirement already satisfied: idna<4,>=2.5 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from requests) (3.7)
    Requirement already satisfied: urllib3<3,>=1.21.1 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from requests) (2.2.3)
    Requirement already satisfied: certifi>=2017.4.17 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from requests) (2024.7.4)
    Requirement already satisfied: httpcore==1.* in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from httpx<1.0.0->opik) (1.0.5)
    Requirement already satisfied: h11<0.15,>=0.13 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from httpcore==1.*->httpx<1.0.0->opik) (0.14.0)
    Requirement already satisfied: rapidfuzz<4.0.0,>=3.8.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from levenshtein~=0.25.1->opik) (3.9.6)
    Requirement already satisfied: annotated-types>=0.4.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from pydantic<3.0.0,>=2.0.0->opik) (0.7.0)
    Requirement already satisfied: pydantic-core==2.20.1 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from pydantic<3.0.0,>=2.0.0->opik) (2.20.1)
    Requirement already satisfied: python-dotenv>=0.21.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from pydantic-settings<3.0.0,>=2.0.0->opik) (1.0.1)
    Requirement already satisfied: aiohttp in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from litellm->opik) (3.10.10)
    Requirement already satisfied: importlib-metadata>=6.8.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from litellm->opik) (8.2.0)
    Requirement already satisfied: jinja2<4.0.0,>=3.1.2 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from litellm->opik) (3.1.4)
    Requirement already satisfied: jsonschema<5.0.0,>=4.22.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from litellm->opik) (4.23.0)
    Requirement already satisfied: tiktoken>=0.7.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from litellm->opik) (0.7.0)
    Requirement already satisfied: tokenizers in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from litellm->opik) (0.19.1)
    Requirement already satisfied: iniconfig in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from pytest->opik) (2.0.0)
    Requirement already satisfied: packaging in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from pytest->opik) (24.1)
    Requirement already satisfied: pluggy<2,>=1.5 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from pytest->opik) (1.5.0)
    Requirement already satisfied: markdown-it-py>=2.2.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from rich->opik) (3.0.0)
    Requirement already satisfied: pygments<3.0.0,>=2.13.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from rich->opik) (2.18.0)
    Requirement already satisfied: zipp>=0.5 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from importlib-metadata>=6.8.0->litellm->opik) (3.19.2)
    Requirement already satisfied: MarkupSafe>=2.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from jinja2<4.0.0,>=3.1.2->litellm->opik) (2.1.5)
    Requirement already satisfied: attrs>=22.2.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from jsonschema<5.0.0,>=4.22.0->litellm->opik) (24.2.0)
    Requirement already satisfied: jsonschema-specifications>=2023.03.6 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from jsonschema<5.0.0,>=4.22.0->litellm->opik) (2023.12.1)
    Requirement already satisfied: referencing>=0.28.4 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from jsonschema<5.0.0,>=4.22.0->litellm->opik) (0.35.1)
    Requirement already satisfied: rpds-py>=0.7.1 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from jsonschema<5.0.0,>=4.22.0->litellm->opik) (0.20.0)
    Requirement already satisfied: mdurl~=0.1 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from markdown-it-py>=2.2.0->rich->opik) (0.1.2)
    Requirement already satisfied: regex>=2022.1.18 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from tiktoken>=0.7.0->litellm->opik) (2024.7.24)
    Requirement already satisfied: aiohappyeyeballs>=2.3.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from aiohttp->litellm->opik) (2.3.5)
    Requirement already satisfied: aiosignal>=1.1.2 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from aiohttp->litellm->opik) (1.3.1)
    Requirement already satisfied: frozenlist>=1.1.1 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from aiohttp->litellm->opik) (1.4.1)
    Requirement already satisfied: multidict<7.0,>=4.5 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from aiohttp->litellm->opik) (6.0.5)
    Requirement already satisfied: yarl<2.0,>=1.12.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from aiohttp->litellm->opik) (1.17.1)
    Requirement already satisfied: huggingface-hub<1.0,>=0.16.4 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from tokenizers->litellm->opik) (0.26.2)
    Requirement already satisfied: filelock in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from huggingface-hub<1.0,>=0.16.4->tokenizers->litellm->opik) (3.15.4)
    Requirement already satisfied: fsspec>=2023.5.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from huggingface-hub<1.0,>=0.16.4->tokenizers->litellm->opik) (2024.10.0)
    Requirement already satisfied: pyyaml>=5.1 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from huggingface-hub<1.0,>=0.16.4->tokenizers->litellm->opik) (6.0.2)
    Requirement already satisfied: propcache>=0.2.0 in /opt/homebrew/Caskroom/miniconda/base/envs/py312_llm_eval/lib/python3.12/site-packages (from yarl<2.0,>=1.12.0->aiohttp->litellm->opik) (0.2.0)
    Downloading openai-1.55.0-py3-none-any.whl (389 kB)
    Installing collected packages: openai
      Attempting uninstall: openai
        Found existing installation: openai 1.54.4
        Uninstalling openai-1.54.4:
          Successfully uninstalled openai-1.54.4
    [31mERROR: pip's dependency resolver does not currently take into account all the packages that are installed. This behaviour is the source of the following dependency conflicts.
    predibase 2024.9.3 requires urllib3==1.26.12, but you have urllib3 2.2.3 which is incompatible.[0m[31m
    [0mSuccessfully installed openai-1.55.0
    
    [1m[[0m[34;49mnotice[0m[1;39;49m][0m[39;49m A new release of pip is available: [0m[31;49m24.2[0m[39;49m -> [0m[32;49m24.3.1[0m
    [1m[[0m[34;49mnotice[0m[1;39;49m][0m[39;49m To update, run: [0m[32;49mpip install --upgrade pip[0m
    Note: you may need to restart the kernel to use updated packages.



[Comet](https://www.comet.com/site?from=llm&utm_source=opik&utm_medium=colab&utm_content=langchain&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=colab&utm_content=langchain&utm_campaign=opik) and grab you API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=langchain&utm_campaign=opik) for more information.


```python
import opik
import os

# Configure Opik
opik.configure()
```

    OPIK: Opik is already configured. You can check the settings by viewing the config file at /Users/jacquesverre/.opik.config


## Implementing Chain of Density Summarization

The idea behind this approach is to first generate a sparse candidate summary and then iteratively refine it with missing information without making it longer. We will start by defining two prompts:

1. Iteration summary prompt: This prompt is used to generate and refine a candidate summary.
2. Final summary prompt: This prompt is used to generate the final summary from the sparse set of candidate summaries.


```python
import opik

ITERATION_SUMMARY_PROMPT = opik.Prompt(
    name="Iteration Summary Prompt",
    prompt="""
Document: {document}
Current summary: {current_summary}
Instruction to focus on: {instruction}

Generate a concise, entity-dense, and highly technical summary from the provided Document that specifically addresses the given Instruction.

Guidelines:
- Make every word count: If there is a current summary re-write it to improve flow, density and conciseness.
- Remove uninformative phrases like "the article discusses".
- The summary should become highly dense and concise yet self-contained, e.g. , easily understood without the Document.
- Make sure that the summary specifically addresses the given Instruction
""".rstrip().lstrip(),
)

FINAL_SUMMARY_PROMPT = opik.Prompt(
    name="Final Summary Prompt",
    prompt="""
Given this summary: {current_summary}
And this instruction to focus on: {instruction}
Create an extremely dense, final summary that captures all key technical information in the most concise form possible, while specifically addressing the given instruction.
""".rstrip().lstrip(),
)
```

We can now define the summarization chain by combining the two prompts. In order to track the LLM calls, we will use Opik's integration with OpenAI through the `track_openai` function and we will add the `@opik.track` decorator to each function so we can track the full chain and not just individual LLM calls:


```python
from opik.integrations.openai import track_openai
from openai import OpenAI
import opik

# Use a dedicated quickstart endpoint, replace with your own OpenAI API Key in your own code
openai_client = track_openai(
    OpenAI(
        base_url="https://odbrly0rrk.execute-api.us-east-1.amazonaws.com/Prod/",
        api_key="Opik-Quickstart",
    )
)


@opik.track
def summarize_current_summary(
    document: str,
    instruction: str,
    current_summary: str,
    model: str = "gpt-4o-mini",
):
    prompt = ITERATION_SUMMARY_PROMPT.format(
        document=document, current_summary=current_summary, instruction=instruction
    )

    response = openai_client.chat.completions.create(
        model=model, max_tokens=4096, messages=[{"role": "user", "content": prompt}]
    )

    return response.choices[0].message.content


@opik.track
def iterative_density_summarization(
    document: str,
    instruction: str,
    density_iterations: int,
    model: str = "gpt-4o-mini",
):
    summary = ""
    for iteration in range(1, density_iterations + 1):
        summary = summarize_current_summary(document, instruction, summary, model)
    return summary


@opik.track
def final_summary(instruction: str, current_summary: str, model: str = "gpt-4o-mini"):
    prompt = FINAL_SUMMARY_PROMPT.format(
        current_summary=current_summary, instruction=instruction
    )

    return (
        openai_client.chat.completions.create(
            model=model, max_tokens=4096, messages=[{"role": "user", "content": prompt}]
        )
        .choices[0]
        .message.content
    )


@opik.track
def chain_of_density_summarization(
    document: str,
    instruction: str,
    model: str = "gpt-4o-mini",
    density_iterations: int = 2,
):
    summary = iterative_density_summarization(
        document, instruction, density_iterations, model
    )
    final_summary_text = final_summary(instruction, summary, model)

    return final_summary_text
```

Let's call the summarization chain with a sample document:


```python
import textwrap

os.environ["OPIK_PROJECT_NAME"] = "Chain of Density Summarization"

document = """
Artificial intelligence (AI) is transforming industries, revolutionizing healthcare, finance, education, and even creative fields. AI systems
today are capable of performing tasks that previously required human intelligence, such as language processing, visual perception, and
decision-making. In healthcare, AI assists in diagnosing diseases, predicting patient outcomes, and even developing personalized treatment plans.
In finance, it helps in fraud detection, algorithmic trading, and risk management. Education systems leverage AI for personalized learning, adaptive
testing, and educational content generation. Despite these advancements, ethical concerns such as data privacy, bias, and the impact of AI on employment
remain. The future of AI holds immense potential, but also significant challenges.
"""

instruction = "Summarize the main contributions of AI to different industries, and highlight both its potential and associated challenges."

summary = chain_of_density_summarization(document, instruction)

print("\n".join(textwrap.wrap(summary, width=80)))
```

    OPIK: Started logging traces to the "Default Project" project at https://www.comet.com/opik/jacques-comet/redirect/projects?name=Default%20Project.


    Please provide the current summary and the specific instruction you would like
    me to focus on for the final summary.


Thanks to the `@opik.track` decorator and Opik's integration with OpenAI, we can now track the entire chain and all the LLM calls in the Opik UI:

![Trace UI](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/static/img/cookbook/chain_density_trace_cookbook.png)

## Automatting the evaluation process

### Defining a dataset
Now that we have a working chain, we can automate the evaluation process. We will start by defining a dataset of documents and instructions:


```python
import opik

dataset_items = [
    {
        "pdf_url": "https://arxiv.org/pdf/2301.00234",
        "title": "A Survey on In-context Learning",
        "instruction": "Summarize the key findings on the impact of prompt engineering in in-context learning.",
    },
    {
        "pdf_url": "https://arxiv.org/pdf/2301.03728",
        "title": "Scaling Laws for Generative Mixed-Modal Language Models",
        "instruction": "How do scaling laws apply to generative mixed-modal models according to the paper?",
    },
    {
        "pdf_url": "https://arxiv.org/pdf/2308.10792",
        "title": "Instruction Tuning for Large Language Models: A Survey",
        "instruction": "What are the major challenges in instruction tuning for large language models identified in the paper?",
    },
    {
        "pdf_url": "https://arxiv.org/pdf/2302.08575",
        "title": "Foundation Models in Natural Language Processing: A Survey",
        "instruction": "Explain the role of foundation models in the current natural language processing landscape.",
    },
    {
        "pdf_url": "https://arxiv.org/pdf/2306.13398",
        "title": "Large-scale Multi-Modal Pre-trained Models: A Comprehensive Survey",
        "instruction": "What are the cutting edge techniques used in multi-modal pre-training models?",
    },
    {
        "pdf_url": "https://arxiv.org/pdf/2103.07492",
        "title": "Continual Learning in Neural Networks: An Empirical Evaluation",
        "instruction": "What are the main challenges of continual learning for neural networks according to the paper?",
    },
    {
        "pdf_url": "https://arxiv.org/pdf/2304.00685v2",
        "title": "Vision-Language Models for Vision Tasks: A Survey",
        "instruction": "What are the most widely used vision-language models?",
    },
    {
        "pdf_url": "https://arxiv.org/pdf/2303.08774",
        "title": "GPT-4 Technical Report",
        "instruction": "What are the main differences between GPT-4 and GPT-3.5?",
    },
    {
        "pdf_url": "https://arxiv.org/pdf/2406.04744",
        "title": "CRAG -- Comprehensive RAG Benchmark",
        "instruction": "What was the approach to experimenting with different data mixtures?",
    },
]

client = opik.Opik()
DATASET_NAME = "arXiv Papers"
dataset = client.get_or_create_dataset(name=DATASET_NAME)
dataset.insert(dataset_items)
```

*Note:* Opik automatically deduplicates dataset items to make it easier to iterate on your dataset.

### Defining the evaluation metrics

Opik includes a [library of evaluation metrics](https://www.comet.com/docs/opik/evaluation/metrics/overview) that you can use to evaluate your chains. For this particular example, we will be using a custom metric that evaluates the relevance, conciseness and technical accuracy of each summary


```python
from opik.evaluation.metrics import base_metric, score_result
import json

# We will define the response format so the output has the correct schema. You can also use structured outputs with Pydantic models for this.
json_schema = {
    "type": "json_schema",
    "json_schema": {
        "name": "summary_evaluation_schema",
        "schema": {
            "type": "object",
            "properties": {
                "relevance": {
                    "type": "object",
                    "properties": {
                        "score": {
                            "type": "integer",
                            "minimum": 1,
                            "maximum": 5,
                            "description": "Score between 1-5 for how well the summary addresses the instruction"
                        },
                        "explanation": {
                            "type": "string",
                            "description": "Brief explanation of the relevance score"
                        }
                    },
                    "required": ["score", "explanation"]
                },
                "conciseness": {
                    "type": "object", 
                    "properties": {
                        "score": {
                            "type": "integer",
                            "minimum": 1,
                            "maximum": 5,
                            "description": "Score between 1-5 for how concise the summary is while retaining key information"
                        },
                        "explanation": {
                            "type": "string",
                            "description": "Brief explanation of the conciseness score"
                        }
                    },
                    "required": ["score", "explanation"]
                },
                "technical_accuracy": {
                    "type": "object",
                    "properties": {
                        "score": {
                            "type": "integer", 
                            "minimum": 1,
                            "maximum": 5,
                            "description": "Score between 1-5 for how accurately the summary conveys technical details"
                        },
                        "explanation": {
                            "type": "string",
                            "description": "Brief explanation of the technical accuracy score"
                        }
                    },
                    "required": ["score", "explanation"]
                }
            },
            "required": ["relevance", "conciseness", "technical_accuracy"],
            "additionalProperties": False
        }
    }
}

# Custom Metric: One template/prompt to extract 4 scores/results
class EvaluateSummary(base_metric.BaseMetric):
    # Constructor
    def __init__(self, name: str):
        self.name = name

    def score(
        self, summary: str, instruction: str, model: str = "gpt-4o-mini", **kwargs
    ):
        prompt = f"""
            Summary: {summary}
            Instruction: {instruction}

            Evaluate the summary based on the following criteria:
            1. Relevance (1-5): How well does the summary address the given instruction?
            2. Conciseness (1-5): How concise is the summary while retaining key information?
            3. Technical Accuracy (1-5): How accurately does the summary convey technical details?

            Your response MUST be in the following JSON format:
            {{
                "relevance": {{
                    "score": <int>,
                    "explanation": "<string>"
                }},
            "conciseness": {{
                "score": <int>,
                "explanation": "<string>"
                }},
            "technical_accuracy": {{
                "score": <int>,
                "explanation": "<string>"
                }}
            }}

            Ensure that the scores are integers between 1 and 5, and that the explanations are concise.
        """

        response = openai_client.chat.completions.create(
            model=model, max_tokens=1000, messages=[{"role": "user", "content": prompt}], response_format=json_schema
        )

        eval_dict = json.loads(response.choices[0].message.content)

        return [
            score_result.ScoreResult(
                name="summary_relevance",
                value=eval_dict["relevance"]["score"],
                reason=eval_dict["relevance"]["explanation"],
            ),
            score_result.ScoreResult(
                name="summary_conciseness",
                value=eval_dict["conciseness"]["score"],
                reason=eval_dict["conciseness"]["explanation"],
            ),
            score_result.ScoreResult(
                name="summary_technical_accuracy",
                value=eval_dict["technical_accuracy"]["score"],
                reason=eval_dict["technical_accuracy"]["explanation"],
            ),
            score_result.ScoreResult(
                name="summary_average_score",
                value=round(sum(eval_dict[k]["score"] for k in eval_dict) / 3, 2),
                reason="The average of the 3 summary evaluation metrics",
            ),
        ]
```

### Create the task we want to evaluate

We can now create the task we want to evaluate. In this case, we will have the dataset item as an input and return a dictionary containing the summary and the instruction so that we can use this in the evaluation metrics:


```python
import requests
import io
from PyPDF2 import PdfReader
from typing import Dict


# Load and extract text from PDFs
@opik.track
def load_pdf(pdf_url: str) -> str:
    # Download the PDF
    response = requests.get(pdf_url)
    pdf_file = io.BytesIO(response.content)

    # Read the PDF
    pdf_reader = PdfReader(pdf_file)

    # Extract text from all pages
    text = ""
    for page in pdf_reader.pages:
        text += page.extract_text()

    # Truncate the text to 100000 characters as this is the maximum supported by OpenAI
    text = text[:100000]
    return text


def evaluation_task(x: Dict):
    text = load_pdf(x["pdf_url"])
    instruction = x["instruction"]
    model = MODEL
    density_iterations = DENSITY_ITERATIONS

    result = chain_of_density_summarization(
        document=text,
        instruction=instruction,
        model=model,
        density_iterations=density_iterations,
    )

    return {"summary": result}
```

### Run the automated evaluation

We can now use the `evaluate` method to evaluate the summaries in our dataset:


```python
from opik.evaluation import evaluate

os.environ["OPIK_PROJECT_NAME"] = "summary-evaluation-prompts"

MODEL = "gpt-4o-mini"
DENSITY_ITERATIONS = 2

experiment_config = {
    "iteration_summary_prompt": ITERATION_SUMMARY_PROMPT,
    "final_summary_prompt": FINAL_SUMMARY_PROMPT,
    "model": MODEL,
    "density_iterations": DENSITY_ITERATIONS,
}

res = evaluate(
    dataset=dataset,
    experiment_config=experiment_config,
    task=evaluation_task,
    scoring_metrics=[EvaluateSummary(name="summary-metrics")],
    prompt=ITERATION_SUMMARY_PROMPT,
)
```

    Evaluation: 100%|██████████| 18/18 [00:25<00:00,  1.44s/it]



<pre style="white-space:pre;overflow-x:auto;line-height:normal;font-family:Menlo,'DejaVu Sans Mono',consolas,'Courier New',monospace">╭─ arXiv Papers (18 samples) ──────────────╮
│                                          │
│ <span style="font-weight: bold">Total time:       </span> 00:00:26              │
│ <span style="font-weight: bold">Number of samples:</span> 18                    │
│                                          │
│ <span style="color: #008000; text-decoration-color: #008000; font-weight: bold">summary_relevance: 1.1111 (avg)</span>          │
│ <span style="color: #008000; text-decoration-color: #008000; font-weight: bold">summary_conciseness: 3.0000 (avg)</span>        │
│ <span style="color: #008000; text-decoration-color: #008000; font-weight: bold">summary_technical_accuracy: 1.0000 (avg)</span> │
│ <span style="color: #008000; text-decoration-color: #008000; font-weight: bold">summary_average_score: 1.7028 (avg)</span>      │
│                                          │
╰──────────────────────────────────────────╯
</pre>




<pre style="white-space:pre;overflow-x:auto;line-height:normal;font-family:Menlo,'DejaVu Sans Mono',consolas,'Courier New',monospace">Uploading results to Opik <span style="color: #808000; text-decoration-color: #808000">...</span> 
</pre>




<pre style="white-space:pre;overflow-x:auto;line-height:normal;font-family:Menlo,'DejaVu Sans Mono',consolas,'Courier New',monospace">View the results <a href="https://www.comet.com/opik/jacques-comet/experiments/0192ba83-4a5c-779b-bf51-48562b767f33/compare?experiments=%5B%2206740816-d7fa-7051-8000-806a033848a8%22%5D" target="_blank">in your Opik dashboard</a>.
</pre>



The experiment results are now available in the Opik UI:

![Trace UI](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/static/img/cookbook/chain_density_experiment_cookbook.png)


## Comparing prompt templates

We will update the iteration summary prompt and evaluate its impact on the evaluation metrics.


```python
import opik

ITERATION_SUMMARY_PROMPT = opik.Prompt(
    name="Iteration Summary Prompt",
    prompt="""Document: {document}
Current summary: {current_summary}
Instruction to focus on: {instruction}

Generate a concise, entity-dense, and highly technical summary from the provided Document that specifically addresses the given Instruction.

Guidelines:
1. **Maximize Clarity and Density**: Revise the current summary to enhance flow, density, and conciseness.
2. **Eliminate Redundant Language**: Avoid uninformative phrases such as "the article discusses."
3. **Ensure Self-Containment**: The summary should be dense and concise, easily understandable without referring back to the document.
4. **Align with Instruction**: Make sure the summary specifically addresses the given instruction.

""".rstrip().lstrip(),
)
```


```python
from opik.evaluation import evaluate

os.environ["OPIK_PROJECT_NAME"] = "summary-evaluation-prompts"

MODEL = "gpt-4o-mini"
DENSITY_ITERATIONS = 2

experiment_config = {
    "iteration_summary_prompt": ITERATION_SUMMARY_PROMPT,
    "final_summary_prompt": FINAL_SUMMARY_PROMPT,
    "model": MODEL,
    "density_iterations": DENSITY_ITERATIONS,
}

res = evaluate(
    dataset=dataset,
    experiment_config=experiment_config,
    task=evaluation_task,
    scoring_metrics=[EvaluateSummary(name="summary-metrics")],
    prompt=ITERATION_SUMMARY_PROMPT,
)
```

    Evaluation: 100%|██████████| 18/18 [00:24<00:00,  1.37s/it]



<pre style="white-space:pre;overflow-x:auto;line-height:normal;font-family:Menlo,'DejaVu Sans Mono',consolas,'Courier New',monospace">╭─ arXiv Papers (18 samples) ──────────────╮
│                                          │
│ <span style="font-weight: bold">Total time:       </span> 00:00:25              │
│ <span style="font-weight: bold">Number of samples:</span> 18                    │
│                                          │
│ <span style="color: #008000; text-decoration-color: #008000; font-weight: bold">summary_relevance: 1.0000 (avg)</span>          │
│ <span style="color: #008000; text-decoration-color: #008000; font-weight: bold">summary_conciseness: 2.8333 (avg)</span>        │
│ <span style="color: #008000; text-decoration-color: #008000; font-weight: bold">summary_technical_accuracy: 1.0000 (avg)</span> │
│ <span style="color: #008000; text-decoration-color: #008000; font-weight: bold">summary_average_score: 1.6094 (avg)</span>      │
│                                          │
╰──────────────────────────────────────────╯
</pre>




<pre style="white-space:pre;overflow-x:auto;line-height:normal;font-family:Menlo,'DejaVu Sans Mono',consolas,'Courier New',monospace">Uploading results to Opik <span style="color: #808000; text-decoration-color: #808000">...</span> 
</pre>




<pre style="white-space:pre;overflow-x:auto;line-height:normal;font-family:Menlo,'DejaVu Sans Mono',consolas,'Courier New',monospace">View the results <a href="https://www.comet.com/opik/jacques-comet/experiments/0192ba83-4a5c-779b-bf51-48562b767f33/compare?experiments=%5B%2206740853-e93a-7fea-8000-c9487836fcbc%22%5D" target="_blank">in your Opik dashboard</a>.
</pre>



You can now compare the results between the two experiments in the Opik UI:

![Trace UI](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/static/img/cookbook/chain_density_trace_comparison_cookbook.png)
