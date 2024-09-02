<h1 align="center" style="border-bottom: none">
    <div>
        <a href="https://www.comet.com/site/products/opik">
            <img src="/apps/opik-documentation/documentation/static/img/logo.svg" width="80" />
            <br>
            Opik
        </a>
    </div>
    Open-source end-to-end LLM Development Platform<br>
</h1>

<p align="center">
Confidently evaluate, test and monitor LLM applications. 
</p>

<div align="center">

[![Python SDK](https://img.shields.io/pypi/v/opik)](https://pypi.org/project/opik/)
[![License](https://img.shields.io/github/license/comet-ml/opik)](https://github.com/comet-ml/opik/blob/main/LICENSE)
[![Build](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml/badge.svg)](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml)
<a target="_blank" href="https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/opik_quickstart.ipynb">
  <!-- <img src="https://colab.research.google.com/assets/colab-badge.svg" alt="Open Quickstart In Colab"/> -->
</a>

</div>

<p align="center">
    <a href="http://www.comet.com/products/opik"><b>Website</b></a> •
    <a href="https://chat.comet.com"><b>Slack community</b></a> •
    <a href="https://x.com/Cometml"><b>Twitter</b></a> •
    <a href="https://www.comet.com/docs/opik/"><b>Documentation</b></a>
</p>

![Opik thumbnail](readme-thumbnail.png)

## 🚀 What is Opik?

[Opik](https://www.comet.com/site/products/opik) is an open-source platform for evaluating, testing and monitoring LLM applications. Built by [Comet](https://www.comet.com).

<br>

You can use Opik for:
* **Development:**
  * **Tracing:** Track all LLM calls and traces during development and production ([Quickstart](https://www.comet.com/docs/opik/quickstart), [Integrations](https://www.comet.com/docs/opik/integrations/overview))
  * **Annotations:** Annotate your LLM calls by logging feedback scores using the [Python SDK](...), [Rest API](...) or the [UI](...). 

* **Evaluation**: Automate the evaluation process of your LLM application:

    * **Datasets and Experiments**: Store test cases and run experiments ([Datasets](https://www.comet.com/docs/opik/evaluation/manage_datasets), [Evaluate your LLM Application](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm))

    * **LLM as a judge metrics**: Use Opik's LLM as a judge metric for complex issues like [hallucination detection](https://www.comet.com/docs/opik/evaluation/metrics/hallucination), [moderation](https://www.comet.com/docs/opik/evaluation/metrics/moderation) and RAG evaluation ([Answer Relevance](https://www.comet.com/docs/opik/evaluation/metrics/answer_relevance), [Context Precision](https://www.comet.com/docs/opik/evaluation/metrics/context_precision) and [Answer Relevance](https://www.comet.com/docs/opik/evaluation/metrics/answer_relevance))

    * **CI/CD integration**: Run evaluations as part of your CI/CD pipeline using our [PyTest integration](...)

* **Production Monitoring**: Monitor your LLM application in production and easily close the feedback loop by adding error traces to your evaluation datasets.

<br>

## 🛠️ Installation

The easiest way to get started with Opik is by creating a free Comet account at [comet.com](https://www.comet.com/signup?from=llm).



If you'd like to self-host Opik, you create a simple local version of Opik using::

```bash
pip install opik-installer

opik-server install
```

For more information about the different deployment options, please see our deployment guides:

| Installation methods | Docs link |
| ------------------- | --------- |
| Local instance | [![Minikube](https://img.shields.io/badge/minikube-%230db7ed.svg?&logo=data:image/svg%2bxml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyMDAiIGhlaWdodD0iMjAwIiB2aWV3Qm94PSIwIDAgMzIgMzIiPgogIDxkZWZzPgogICAgPG1hc2sgaWQ9InZzY29kZUljb25zRm9sZGVyVHlwZU1pbmlrdWJlMCIgd2lkdGg9IjIxIiBoZWlnaHQ9IjIwLjQ0OSIgeD0iMTAiIHk9IjEwLjU3NSIgbWFza1VuaXRzPSJ1c2VyU3BhY2VPblVzZSI+CiAgICAgIDxwYXRoIGZpbGw9IiNmZmYiIGZpbGwtcnVsZT0iZXZlbm9kZCIgZD0iTTMxIDMxLjAyNXYtMjAuNDVIMTB2MjAuNDVoMjF6Ii8+CiAgICA8L21hc2s+CiAgPC9kZWZzPgogIDxwYXRoIGZpbGw9IiM1NWI1YmYiIGQ9Ik0yNy45IDZoLTkuOGwtMiA0SDV2MTdoMjVWNlptLjEgNGgtNy44bDEtMkgyOFoiLz4KICA8ZyBtYXNrPSJ1cmwoI3ZzY29kZUljb25zRm9sZGVyVHlwZU1pbmlrdWJlMCkiPgogICAgPHBhdGggZmlsbD0iIzMyNmRlNiIgZmlsbC1ydWxlPSJldmVub2RkIiBkPSJNMjAuNTIgMTAuNTc1YTIuMDM4IDIuMDM4IDAgMCAwLS44NDEuMTkxbC02Ljg3MSAzLjI4NmExLjkyMSAxLjkyMSAwIDAgMC0xLjA1OSAxLjMxN2wtMS43IDcuMzczYTEuOTI0IDEuOTI0IDAgMCAwIC4zODEgMS42NTZsNC43NTIgNS45MDdhMS45MTcgMS45MTcgMCAwIDAgMS41MDcuNzJoNy42MThhMS45MTcgMS45MTcgMCAwIDAgMS41MDctLjcybDQuNzU0LTUuOTA1YTEuOTE0IDEuOTE0IDAgMCAwIC4zODEtMS42NTZsLTEuNy03LjM3M2ExLjkyMSAxLjkyMSAwIDAgMC0xLjA1OS0xLjMxN2wtNi44NDMtMy4yODZhMS45MzkgMS45MzkgMCAwIDAtLjgyOS0uMTkxbTAgLjYzOWExLjMyIDEuMzIgMCAwIDEgLjU1Ny4xMjJsNi44NzEgMy4yNzJhMS4zMjIgMS4zMjIgMCAwIDEgLjcwNi44ODNsMS43IDcuMzczYTEuMjY5IDEuMjY5IDAgMCAxLS4yNTggMS4xMTNsLTQuNzUyIDUuOTA3YTEuMyAxLjMgMCAwIDEtMS4wMTkuNDg5SDE2LjdhMS4zIDEuMyAwIDAgMS0xLjAxOS0uNDg5bC00Ljc1Mi01LjkwN2ExLjM2MSAxLjM2MSAwIDAgMS0uMjU4LTEuMTEzbDEuNy03LjM3M2ExLjMgMS4zIDAgMCAxIC43MDYtLjg4M2w2Ljg3MS0zLjI4NmExLjYzMyAxLjYzMyAwIDAgMSAuNTctLjEwOCIvPgogIDwvZz4KICA8cGF0aCBmaWxsPSIjMWZiZmNmIiBmaWxsLXJ1bGU9ImV2ZW5vZGQiIGQ9Ik0xNi41NDUgMjguNjQ5YTEuMjYxIDEuMjYxIDAgMCAwIC45OS40NzRsNS45NzgtLjAxYTEuMjg5IDEuMjg5IDAgMCAwIC45ODctLjQ3NWwzLjY0NC00LjU4OGExLjI4IDEuMjggMCAwIDAgLjA5NC0uNjYxdi02LjQwN2wtNy44MyA0LjUwOGwtNy44MjItNC41djYuNGExLjA3NiAxLjA3NiAwIDAgMCAuMjQxLjY3MVoiLz4KICA8cGF0aCBmaWxsPSIjYzllOWVjIiBmaWxsLXJ1bGU9ImV2ZW5vZGQiIGQ9Im0yMC40MDggMjEuNDlsNy44My00LjUwOGwtNy44MzctNC41MDVsLTcuODE1IDQuNTA5bDcuODIyIDQuNTA0eiIvPgogIDxwYXRoIGZpbGw9IiMzMjZkZTYiIGZpbGwtcnVsZT0iZXZlbm9kZCIgZD0iTTIyLjI3NiAyNC45NzNhLjU0NS41NDUgMCAwIDEtLjcxNS0uMTIyYS40NjQuNDY0IDAgMCAxLS4xLS4yMjVsLS4xODUtMy4zMjZhNi4xOTQgNi4xOTQgMCAwIDEgMy42NzQgMS43NzZabS0yLjc3Ni0uNDI5YS41NTkuNTU5IDAgMCAxLS41NTEuNTMxYS40ODIuNDgyIDAgMCAxLS4yNDUtLjA2MWwtMi43MTUtMS45MzlBNi4yMzMgNi4yMzMgMCAwIDEgMTkuMDMgMjEuNGMuMjI1LS4wNDEuNDI5LS4wODIuNjU0LS4xMjJabTcuNjM0LTEuMzY3bC4yLS4xODR2LS4wNDFhLjQ1OS40NTkgMCAwIDEgLjEtLjMwNmE1Ljk3MSA1Ljk3MSAwIDAgMSAuOTE4LS42MzJjLjA2MS0uMDQxLjEyMy0uMDYyLjE4NC0uMWEyLjk4NiAyLjk4NiAwIDAgMCAuMzQ3LS4yYy4wMi0uMDIuMDYxLS4wNC4xLS4wODFjLjAyLS4wMjEuMDQxLS4wMjEuMDQxLS4wNDFhLjY4Mi42ODIgMCAwIDAgLjE0My0uOTE5YS41OC41OCAwIDAgMC0uNDctLjIyNGEuNzU5Ljc1OSAwIDAgMC0uNDQ5LjE2M2wtLjA0MS4wNDFjLS4wNC4wMi0uMDYxLjA2MS0uMS4wODJhMy40NDcgMy40NDcgMCAwIDAtLjI2NS4yODVhLjk2NC45NjQgMCAwIDEtLjE0My4xNDNhNS4yNCA1LjI0IDAgMCAxLS44MTYuNzM1YS4zMzEuMzMxIDAgMCAxLS4xODQuMDYxYS4yNjYuMjY2IDAgMCAxLS4xMjMtLjAyaC0uMDRsLS4yMzYuMTYxYTkuOTUzIDkuOTUzIDAgMCAwLS44MzctLjc3NmE4LjE1NyA4LjE1NyAwIDAgMC00LjI2Ni0xLjY5NGwtLjAyLS4yNjVsLS4wNDEtLjA0MWEuNDI5LjQyOSAwIDAgMS0uMTY0LS4yNjVhNy4xOTMgNy4xOTMgMCAwIDEgLjA2Mi0xLjF2LS4wMjFhLjcuNyAwIDAgMSAuMDQxLS4yYy4wMi0uMTIyLjA0LS4yNDUuMDYxLS4zODh2LS4xODNhLjYyMy42MjMgMCAwIDAtMS4wODItLjQ3YS42NDYuNjQ2IDAgMCAwLS4xODQuNDd2LjE2M2ExLjIxNCAxLjIxNCAwIDAgMCAuMDYxLjM4OGMuMDIxLjA2MS4wMjEuMTIyLjA0MS4ydi4wMmE1LjMzIDUuMzMgMCAwIDEgLjA2MiAxLjFhLjQzMi40MzIgMCAwIDEtLjE2NC4yNjVsLS4wNDEuMDQxbC0uMDIuMjY1YTEwLjQ2MSAxMC40NjEgMCAwIDAtMS4xLjE2M2E3Ljg3IDcuODcgMCAwIDAtNC4wNDIgMi4yODZsLS4yLS4xNDNoLS4wNDFjLS4wNCAwLS4wODEuMDIxLS4xMjIuMDIxYS4zMzkuMzM5IDAgMCAxLS4xODQtLjA2MWE1LjQyIDUuNDIgMCAwIDEtLjgxNi0uNzU2YS45NjEuOTYxIDAgMCAwLS4xNDMtLjE0MmEzLjQ1NSAzLjQ1NSAwIDAgMC0uMjY1LS4yODZjLS4wMjEtLjAyMS0uMDYyLS4wNDEtLjEtLjA4MmMtLjAyMS0uMDItLjA0MS0uMDItLjA0MS0uMDQxYS43MTUuNzE1IDAgMCAwLS40NTUtLjE2OGEuNTgxLjU4MSAwIDAgMC0uNDcuMjI1YS42ODEuNjgxIDAgMCAwIC4xNDMuOTE4Yy4wMjEgMCAuMDIxLjAyLjA0MS4wMmMuMDQxLjAyMS4wNjEuMDYyLjEuMDgyYTIuOTg2IDIuOTg2IDAgMCAwIC4zNDcuMmEuODQ2Ljg0NiAwIDAgMSAuMTg0LjFhNS45NyA1Ljk3IDAgMCAxIC45MTguNjMzYS4zNzUuMzc1IDAgMCAxIC4xLjMwNnYuMDQxbC4yLjE4NGEuNzY3Ljc2NyAwIDAgMC0uMS4xNjNhNy45ODYgNy45ODYgMCAwIDAtLjYxMiAxLjE3OGwxLjE2NCAxLjQzNmE2LjQxIDYuNDEgMCAwIDEgLjY5My0xLjU5M2wyLjQyOSAyLjE2M2EuNTQ0LjU0NCAwIDAgMSAuMDYyLjc1NWEuNDExLjQxMSAwIDAgMS0uMjQ1LjE2NGwtMS40LjQwN2wuNy44NmExLjI2MSAxLjI2MSAwIDAgMCAuOTkuNDc0bDUuOTc4LS4wMWExLjI4OSAxLjI4OSAwIDAgMCAuOTg3LS40NzVsLjY1NC0uODI0bC0xLjQzOC0uNDA3YS41NTMuNTUzIDAgMCAxLS4zODgtLjY1M2EuNDkuNDkgMCAwIDEgLjEyMy0uMjI1bDIuNDY5LTIuMjIyYTYuNDYzIDYuNDYzIDAgMCAxIC43MDUgMS42NTZsMS4xODctMS40OTRhOC42MTYgOC42MTYgMCAwIDAtLjY4Ny0xLjI4NVoiLz4KPC9zdmc+)](https://www.comet.com/docs/opik/self-host/self_hosting_opik#all-in-one-installation)
| Kubernetes | [![Kubernetes](https://img.shields.io/badge/kubernetes-%23326ce5.svg?&logo=kubernetes&logoColor=white)](https://www.comet.com/docs/opik/self-host/self_hosting_opik#all-in-one-installation) |


## 🏁 Get Started

If you are logging traces to the Cloud Opik platform, you will need to get your API key from the user menu and set it as the `OPIK_API_KEY` environment variable:

```bash
export OPIK_API_KEY=<your-api-key>
```

If you are using a local Opik instance, you don't need to set the `OPIK_API_KEY` environment variable and isntead set the environment variable `OPIK_BASE_URL` to point to your local Opik instance:
```bash
export OPIK_BASE_URL=http://localhost:5173
```

You are now ready to start logging traces using either the [Python SDK](https://www.comet.com/docs/opik/python-sdk/overview) or the [REST API](https://www.comet.com/docs/opik/rest-api).

### 📝 Logging Traces

The easiest way to get started is to use one of our integrations. Opik supports:

| Integration | Description | Documentation | Try in Colab |
| ----------- | ----------- | ------------- | ------------ |
| OpenAI | Log traces for all OpenAI LLM calls | [Documentation](https://www.comet.com/docs/opik/integrations/openai) | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/openai.ipynb) |
| LiteLLM | Log traces for all OpenAI LLM calls | [Documentation](https://www.comet.com/docs/opik/integrations/openai) | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/litellm.ipynb) |
| LangChain | Log traces for all LangChain LLM calls | [Documentation](https://www.comet.com/docs/opik/integrations/langchain) | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/langchain.ipynb) |
| LlamaIndex | Log traces for all LlamaIndex LLM calls | [Documentation](https://www.comet.com/docs/opik/integrations/llamaindex) | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/llama-index.ipynb) |
| Ragas | Log traces for all Ragas evaluations | [Documentation](https://www.comet.com/docs/opik/integrations/ragas) | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/ragas.ipynb) |

> [!TIP]  
> If the framework you are using is not listed above, feel free to [open an issue](https://github.com/comet-ml/opik/issues) or submit a PR with the integration.

If you are not using any of the frameworks above, you can also using the `track` function decorator to [log traces](https://www.comet.com/docs/opik/tracing/log_traces):

```python
from opik import track

@track
def my_llm_function(user_question: str) -> str:
    # Your LLM code here

    return "Hello"
```

> [!TIP]  
> The track decorator can be used in conjunction with any of our integrations and can also be used to track nested function calls.

### 🧑‍⚖️ LLM as a Judge metrics

The Python Opik SDK includes a number of LLM as a judge metrics to help you evaluate your LLM application. Learn more about it in the [metrics documentation](https://www.comet.com/docs/opik/evaluation/metrics/overview).

To use them, simply import the relevant metric and use the `score` function:

```python
from opik.evaluation.metrics import Hallucination

metric = Hallucination()
score = metric.score(
    input="What is the capital of France?",
    output="Paris",
    context=["France is a country in Europe."]
)
print(score)
```

Opik also includes a number of pre-built heuristic metrics as well as the ability to create your own. Learn more about it in the [metrics documentation](https://www.comet.com/docs/opik/evaluation/metrics/overview).

### 🔍 Evaluating your LLM Application

Opik allows you to evaluate your LLM application during development through [Datasets](https://www.comet.com/docs/opik/evaluation/manage_datasets) and [Experiments](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm).

You can also run evaluations as part of your CI/CD pipeline using our [PyTest integration](...).

## 🤝 Contributing

There are many ways to contribute to Opik:

* Submit [bug reports](https://github.com/comet-ml/opik/issues) and [feature requests](https://github.com/comet-ml/opik/issues)
* Review the documentation and submit [Pull Requests](https://github.com/comet-ml/opik/pulls) to improve it
* Speaking or writing about Opik and [letting us know](https://chat.comet.com)
* Upvoting [popular feature requests](https://github.com/comet-ml/opik/issues?q=is%3Aissue+is%3Aopen+label%3A%22feature+request%22) to show your support

To learn more about how to contribute to Opik, please see our [contributing guidelines](CONTRIBUTING.md).
