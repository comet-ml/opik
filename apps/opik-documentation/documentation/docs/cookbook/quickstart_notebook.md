# Quickstart notebook - Summarization task

In this notebook, we will look at how you can use Opik to track your LLM calls, chains and agents. We will introduce the concept of tracing and how to automate the evaluation of your LLM workflows.

We will be using a technique called Chain of Density Summarization to summarize Arxiv papers. You can learn more about this technique in the [From Sparse to Dense: GPT-4 Summarization with Chain of Density Prompting](https://arxiv.org/abs/2309.04269) paper.

## Getting started

We will first install the required dependencies and configure both Opik and OpenAI.


```python
%pip install -U opik openai requests PyPDF2 --quiet
```


[Comet](https://www.comet.com/site?from=llm&utm_source=opik&utm_medium=colab&utm_content=langchain&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=colab&utm_content=langchain&utm_campaign=opik) and grab you API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=langchain&utm_campaign=opik) for more information.


```python
import opik
import os

# Configure Opik
opik.configure()
```

## Implementing Chain of Density Summarization

The idea behind this approach is to first generate a sparse candidate summary and then iteratively refine it with missing information without making it longer. We will start by defining two prompts:

1. Iteration summary prompt: This prompt is used to generate and refine a candidate summary.
2. Final summary prompt: This prompt is used to generate the final summary from the sparse set of candidate summaries.


```python
import opik

ITERATION_SUMMARY_PROMPT = opik.Prompt(
    name="Iteration Summary Prompt",
    prompt="""
Document: {{document}}
Current summary: {{current_summary}}
Instruction to focus on: {{instruction}}

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
Given this summary: {{current_summary}}
And this instruction to focus on: {{instruction}}
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


@opik.track(project_name="Chain of Density Summarization")
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
                            "description": "Score between 1-5 for how well the summary addresses the instruction",
                        },
                        "explanation": {
                            "type": "string",
                            "description": "Brief explanation of the relevance score",
                        },
                    },
                    "required": ["score", "explanation"],
                },
                "conciseness": {
                    "type": "object",
                    "properties": {
                        "score": {
                            "type": "integer",
                            "minimum": 1,
                            "maximum": 5,
                            "description": "Score between 1-5 for how concise the summary is while retaining key information",
                        },
                        "explanation": {
                            "type": "string",
                            "description": "Brief explanation of the conciseness score",
                        },
                    },
                    "required": ["score", "explanation"],
                },
                "technical_accuracy": {
                    "type": "object",
                    "properties": {
                        "score": {
                            "type": "integer",
                            "minimum": 1,
                            "maximum": 5,
                            "description": "Score between 1-5 for how accurately the summary conveys technical details",
                        },
                        "explanation": {
                            "type": "string",
                            "description": "Brief explanation of the technical accuracy score",
                        },
                    },
                    "required": ["score", "explanation"],
                },
            },
            "required": ["relevance", "conciseness", "technical_accuracy"],
            "additionalProperties": False,
        },
    },
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
            model=model,
            max_tokens=1000,
            messages=[{"role": "user", "content": prompt}],
            response_format=json_schema,
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
    project_name="Chain of Density Summarization"
)
```

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
    project_name="Chain of Density Summarization"
)
```

You can now compare the results between the two experiments in the Opik UI:

![Trace UI](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/static/img/cookbook/chain_density_trace_comparison_cookbook.png)
