<h1 align="center" style="border-bottom: none">
    <div>
        <a href="https://www.comet.com/site/products/opik?utm_source=opik&utm_medium=github&utm_content=header_img"><picture>
            <source media="(prefers-color-scheme: dark)" srcset="/apps/opik-documentation/documentation/static/img/logo-dark-mode.svg">
            <source media="(prefers-color-scheme: light)" srcset="/apps/opik-documentation/documentation/static/img/opik-logo.svg">
            <img alt="Comet Opik logo" src="/apps/opik-documentation/documentation/static/img/opik-logo.svg" width="200" />
        </picture></a>
        <br>
        Opik
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
    <a href="https://www.comet.com/site/products/opik?utm_source=opik&utm_medium=github&utm_content=website_button)"><b>Website</b></a> •
    <a href="https://chat.comet.com"><b>Slack community</b></a> •
    <a href="https://x.com/Cometml"><b>Twitter</b></a> •
    <a href="https://www.comet.com/docs/opik/"><b>Documentation</b></a>
</p>

![Opik thumbnail](readme-thumbnail.png)

## 🚀 What is Opik?

Opik is an open-source platform for evaluating, testing and monitoring LLM applications. Built by [Comet](https://www.comet.com?utm_source=opik&utm_medium=github&utm_content=what_is_opik_link).

<br>

You can use Opik for:
* **Development:**
  * **Tracing:** Track all LLM calls and traces during development and production ([Quickstart](https://www.comet.com/docs/opik/quickstart/?utm_source=opik&utm_medium=github&utm_content=quickstart_link), [Integrations](https://www.comet.com/docs/opik/integrations/overview/?utm_source=opik&utm_medium=github&utm_content=integrations_link)
  * **Annotations:** Annotate your LLM calls by logging feedback scores using the [Python SDK](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?utm_source=opik&utm_medium=github&utm_content=sdk_link) or the [UI](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-through-the-ui?utm_source=opik&utm_medium=github&utm_content=ui_link). 

* **Evaluation**: Automate the evaluation process of your LLM application:

    * **Datasets and Experiments**: Store test cases and run experiments ([Datasets](https://www.comet.com/docs/opik/evaluation/manage_datasets/?utm_source=opik&utm_medium=github&utm_content=datasets_link), [Evaluate your LLM Application](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?utm_source=opik&utm_medium=github&utm_content=eval_link))

    * **LLM as a judge metrics**: Use Opik's LLM as a judge metric for complex issues like [hallucination detection](https://www.comet.com/docs/opik/evaluation/metrics/hallucination/?utm_source=opik&utm_medium=github&utm_content=hallucination_link), [moderation](https://www.comet.com/docs/opik/evaluation/metrics/moderation/?utm_source=opik&utm_medium=github&utm_content=moderation_link) and RAG evaluation ([Answer Relevance](https://www.comet.com/docs/opik/evaluation/metrics/answer_relevance/?utm_source=opik&utm_medium=github&utm_content=alex_link), [Context Precision](https://www.comet.com/docs/opik/evaluation/metrics/context_precision/?utm_source=opik&utm_medium=github&utm_content=context_link)

    * **CI/CD integration**: Run evaluations as part of your CI/CD pipeline using our [PyTest integration](https://www.comet.com/docs/opik/testing/pytest_integration/?utm_source=opik&utm_medium=github&utm_content=pytest_link)

* **Production Monitoring**: Monitor your LLM application in production and easily close the feedback loop by adding error traces to your evaluation datasets.

<br>

## 🛠️ Installation
Opik is available as a fully open source local installation or using Comet.com as a hosted solution.
The easiest way to get started with Opik is by creating a free Comet account at [comet.com](https://www.comet.com/signup?from=llm?utm_source=opik&utm_medium=github&utm_content=install).

If you'd like to self-host Opik, you can do so by cloning the repository and starting the platform using Docker Compose:

```bash
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the opik/deployment/docker-compose directory
cd opik/deployment/docker-compose

# Start the Opik platform
docker compose up --detach

# You can now visit http://localhost:5173 on your browser!
```

For more information about the different deployment options, please see our deployment guides:

| Installation methods | Docs link |
| ------------------- | --------- |
| Local instance | [![Local Deployment](https://img.shields.io/badge/Local%20Deployments-%232496ED?style=flat&logo=docker&logoColor=white)](https://www.comet.com/docs/opik/self-host/local_deployment?utm_source=opik&utm_medium=github&utm_content=self_host_link)
| Kubernetes | [![Kubernetes](https://img.shields.io/badge/Kubernetes-%23326ce5.svg?&logo=kubernetes&logoColor=white)](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?utm_source=opik&utm_medium=github&utm_content=kubernetes_link)


## 🏁 Get Started

To get started, you will need to first install the Python SDK:

```bash
pip install opik
```

Once the SDK is installed, you can configure it by running the `opik configure` command:

```bash
opik configure
```
This will allow you to configure Opik locally by setting the correct local server address or if you're using the Cloud platform by setting the API Key


> [!TIP]  
> You can also call the `opik.configure(use_local=True)` method from your Python code to configure the SDK to run on the local installation.


You are now ready to start logging traces using the [Python SDK](https://www.comet.com/docs/opik/python-sdk-reference/?utm_source=opik&utm_medium=github&utm_content=sdk_link2).

### 📝 Logging Traces

The easiest way to get started is to use one of our integrations. Opik supports:

| Integration | Description | Documentation | Try in Colab |
| ----------- | ----------- | ------------- | ------------ |
| OpenAI | Log traces for all OpenAI LLM calls | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/openai/?utm_source=opik&utm_medium=github&utm_content=openai_link) | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/openai.ipynb) |
| LangChain | Log traces for all LangChain LLM calls | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/langchain/?utm_source=opik&utm_medium=github&utm_content=langchain_link) | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/langchain.ipynb) |
| LlamaIndex | Log traces for all LlamaIndex LLM calls | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/llama_index?utm_source=opik&utm_medium=github&utm_content=llama_index_link) | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/llama-index.ipynb) |
| Ollama | Log traces for all Ollama LLM calls | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/ollama?utm_source=opik&utm_medium=github&utm_content=ollama_link) | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/ollama.ipynb) |
| Predibase | Fine-tune and serve open-source Large Language Models | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/predibase?utm_source=opik&utm_medium=github&utm_content=predibase_link) | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/predibase.ipynb) |
| Ragas | Evaluation framework for your Retrieval Augmented Generation (RAG) pipelines | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/ragas?utm_source=opik&utm_medium=github&utm_content=ragas_link) | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/ragas.ipynb) |

> [!TIP]  
> If the framework you are using is not listed above, feel free to [open an issue](https://github.com/comet-ml/opik/issues) or submit a PR with the integration.

If you are not using any of the frameworks above, you can also using the `track` function decorator to [log traces](https://www.comet.com/docs/opik/tracing/log_traces/?utm_source=opik&utm_medium=github&utm_content=traces_link):

```python
import opik

opik.configure(use_local=True) # Run locally

@opik.track
def my_llm_function(user_question: str) -> str:
    # Your LLM code here

    return "Hello"
```

> [!TIP]  
> The track decorator can be used in conjunction with any of our integrations and can also be used to track nested function calls.

### 🧑‍⚖️ LLM as a Judge metrics

The Python Opik SDK includes a number of LLM as a judge metrics to help you evaluate your LLM application. Learn more about it in the [metrics documentation](https://www.comet.com/docs/opik/evaluation/metrics/overview/?utm_source=opik&utm_medium=github&utm_content=metrics_2_link).

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

Opik also includes a number of pre-built heuristic metrics as well as the ability to create your own. Learn more about it in the [metrics documentation](https://www.comet.com/docs/opik/evaluation/metrics/overview?utm_source=opik&utm_medium=github&utm_content=metrics_3_link).

### 🔍 Evaluating your LLM Application

Opik allows you to evaluate your LLM application during development through [Datasets](https://www.comet.com/docs/opik/evaluation/manage_datasets/?utm_source=opik&utm_medium=github&utm_content=datasets_2_link) and [Experiments](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?utm_source=opik&utm_medium=github&utm_content=experiments_link).

You can also run evaluations as part of your CI/CD pipeline using our [PyTest integration](https://www.comet.com/docs/opik/testing/pytest_integration/?utm_source=opik&utm_medium=github&utm_content=pytest_2_link).

## 🤝 Contributing

There are many ways to contribute to Opik:

* Submit [bug reports](https://github.com/comet-ml/opik/issues) and [feature requests](https://github.com/comet-ml/opik/issues)
* Review the documentation and submit [Pull Requests](https://github.com/comet-ml/opik/pulls) to improve it
* Speaking or writing about Opik and [letting us know](https://chat.comet.com)
* Upvoting [popular feature requests](https://github.com/comet-ml/opik/issues?q=is%3Aissue+is%3Aopen+label%3A%22enhancement%22) to show your support

To learn more about how to contribute to Opik, please see our [contributing guidelines](CONTRIBUTING.md).
