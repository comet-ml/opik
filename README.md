<div align="center"><b><a href="README.md">English</a> | <a href="readme_CN.md">简体中文</a> | <a href="readme_JP.md">日本語</a> | <a href="readme_KO.md">한국어</a></b></div>

<h1 align="center" style="border-bottom: none">
    <div>
        <a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=header_img&utm_campaign=opik"><picture>
            <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/logo-dark-mode.svg">
            <source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg">
            <img alt="Comet Opik logo" src="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg" width="200" />
        </picture></a>
        <br>
        Opik
    </div>
    Open source LLM evaluation platform<br>
</h1>

<p align="center">
Opik helps you build, evaluate, and optimize LLM systems that run better, faster, and cheaper. From RAG chatbots to code assistants to complex agentic pipelines, Opik provides comprehensive tracing, evaluations, dashboards, and powerful features like <b>Opik Optimizer</b> and <b>Opik Guardrails</b>.
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
    <a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=website_button&utm_campaign=opik"><b>Website</b></a> •
    <a href="https://chat.comet.com"><b>Slack community</b></a> •
    <a href="https://x.com/Cometml"><b>Twitter</b></a> •
    <a href="https://www.comet.com/docs/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=docs_button&utm_campaign=opik"><b>Documentation</b></a>
</p>

![Opik thumbnail](readme-thumbnail.png)

## Important: Changelog
**For the latest updates and detailed changes, please refer to our [official changelog](https://www.comet.com/docs/opik/changelog).**

## 🚀 What is Opik?

Opik is an open-source platform designed to streamline the entire lifecycle of LLM applications. It empowers developers to evaluate, test, monitor, and optimize their models and agentic systems. Key offerings include:
* **Opik Optimizer**: A dedicated SDK and set of tools to enhance your model's performance, speed, and cost-efficiency.
* **Opik Guardrails**: Features to help you implement safe and responsible AI practices.
* **Comprehensive Observability**: Deep tracing of LLM calls, conversation logging, and agent activity.
* **Advanced Evaluation**: Robust tools for prompt evaluation, LLM-as-a-judge metrics, and experiment management.
* **Production-Ready**: Scalable monitoring dashboards and online evaluation rules for production environments.

Built by [Comet](https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=what_is_opik_link&utm_campaign=opik).

<br>

Key capabilities include:
* **Development & Tracing:**
  * Track all LLM calls and traces with detailed context during development and in production ([Quickstart](https://www.comet.com/docs/opik/quickstart/?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_link&utm_campaign=opik)).
  * Benefit from recent SDK enhancements: streaming support in ADK integration, cost tracking for ADK, OpenAI `responses.parse` support, and significant performance optimizations.
  * Seamlessly integrate with a growing list of frameworks including new additions like **Google ADK**, **Autogen**, and **Flowise AI**. ([Integrations](https://www.comet.com/docs/opik/tracing/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik))
  * Annotate traces and spans with feedback scores via the [Python SDK](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link&utm_campaign=opik) or the [UI](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-through-the-ui?from=llm&utm_source=opik&utm_medium=github&utm_content=ui_link&utm_campaign=opik).
  * Experiment with prompts and models in the [Prompt Playground](https://www.comet.com/docs/opik/prompt_engineering/playground), now with Gemini support.

* **Evaluation & Testing**:
    * Automate your LLM application evaluation with [Datasets](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_link&utm_campaign=opik) and [Experiments](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=eval_link&utm_campaign=opik).
    * Leverage powerful LLM-as-a-judge metrics for complex tasks like [hallucination detection](https://www.comet.com/docs/opik/evaluation/metrics/hallucination/?from=llm&utm_source=opik&utm_medium=github&utm_content=hallucination_link&utm_campaign=opik), [moderation](https://www.comet.com/docs/opik/evaluation/metrics/moderation/?from=llm&utm_source=opik&utm_medium=github&utm_content=moderation_link&utm_campaign=opik), and RAG assessment ([Answer Relevance](https://www.comet.com/docs/opik/evaluation/metrics/answer_relevance/?from=llm&utm_source=opik&utm_medium=github&utm_content=alex_link&utm_campaign=opik), [Context Precision](https://www.comet.com/docs/opik/evaluation/metrics/context_precision/?from=llm&utm_source=opik&utm_medium=github&utm_content=context_link&utm_campaign=opik)).
    * Integrate evaluations into your CI/CD pipeline with our [PyTest integration](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_link&utm_campaign=opik).
    * Benefit from dashboard improvements like Python code metrics for online evaluations and enhanced UI for feedback scores.

* **Production Monitoring & Optimization**:
    * Log high volumes of production traces—Opik is designed for scale (40M+ traces/day).
    * Monitor feedback scores, trace counts, and token usage over time in the [Opik Dashboard](https://www.comet.com/docs/opik/production/production_monitoring/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik).
    * Utilize [Online Evaluation Rules](https://www.comet.com/docs/opik/production/rules/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik) with LLM-as-a-Judge metrics to identify production issues.
    * Leverage **Opik Optimizer** and **Opik Guardrails** to continuously improve and secure your LLM applications in production.

> [!TIP]
> If you are looking for features that Opik doesn't have today, please raise a new [Feature request](https://github.com/comet-ml/opik/issues/new/choose) 🚀

<br>

## 🛠️ Installation
Opik is available as a fully open source local installation or using Comet.com as a hosted solution.
The easiest way to get started with Opik is by creating a free Comet account at [comet.com](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=install&utm_campaign=opik).

If you'd like to self-host Opik, you can do so by cloning the repository and starting the platform using Docker Compose. Note the new `.opik.sh` installation script:

On Linux or Mac do:
```bash
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the repository
cd opik

# Start the Opik platform
./opik.sh
```

On Windows do:
```powershell
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the repository
cd opik

# Start the Opik platform
powershell -ExecutionPolicy ByPass -c ".\\opik.ps1"
```

Use the `--help` or `--info` options to troubleshoot issues. Dockerfiles now ensure containers run as non-root users for enhanced security.

Once all is up and running, you can now visit [localhost:5173](http://localhost:5173) on your browser!

For more information about the different deployment options, please see our deployment guides:

| Installation methods | Docs link |
| ------------------- | --------- |
| Local instance | [![Local Deployment](https://img.shields.io/badge/Local%20Deployments-%232496ED?style=flat&logo=docker&logoColor=white)](https://www.comet.com/docs/opik/self-host/local_deployment?from=llm&utm_source=opik&utm_medium=github&utm_content=self_host_link&utm_campaign=opik)
| Kubernetes | [![Kubernetes](https://img.shields.io/badge/Kubernetes-%23326ce5.svg?&logo=kubernetes&logoColor=white)](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik)


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

You are now ready to start logging traces using the [Python SDK](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link2&utm_campaign=opik).

### 📝 Logging Traces

The easiest way to get started is to use one of our integrations. Opik supports a wide array of frameworks, including recent additions like **Google ADK**, **Autogen**, and **Flowise AI**:

| Integration | Description                                                                  | Documentation                                                                                                                                                      | Try in Colab                                                                                                                                                                                                                      |
|-------------|------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|\
| OpenAI      | Log traces for all OpenAI LLM calls                                          | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/openai/?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik)          | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/openai.ipynb)      |
| LiteLLM     | Call any LLM model using the OpenAI format                                   | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/litellm/?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik)                                                                                                                  | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/litellm.ipynb)     |
| LangChain   | Log traces for all LangChain LLM calls                                       | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/langchain/?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik)    | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/langchain.ipynb)   |
| Haystack    | Log traces for all Haystack calls                                            | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/haystack/?utm_source=opik&utm_medium=github&utm_content=haystack_link&utm_campaign=opik)      | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/haystack.ipynb)    |
| Anthropic   | Log traces for all Anthropic LLM calls                                       | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/anthropic?utm_source=opik&utm_medium=github&utm_content=anthropic_link&utm_campaign=opik)     | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/anthropic.ipynb)   |
| Bedrock     | Log traces for all Bedrock LLM calls                                         | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/bedrock?utm_source=opik&utm_medium=github&utm_content=bedrock_link&utm_campaign=opik)         | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/bedrock.ipynb)     |
| CrewAI      | Log traces for all CrewAI calls                                              | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/crewai?utm_source=opik&utm_medium=github&utm_content=crewai_link&utm_campaign=opik)           | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/crewai.ipynb)      |
| DeepSeek    | Log traces for all DeepSeek LLM calls                                        | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/deepseek?utm_source=opik&utm_medium=github&utm_content=deepseek_link&utm_campaign=opik)       | |
| DSPy        | Log traces for all DSPy runs                                                 | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/dspy?utm_source=opik&utm_medium=github&utm_content=dspy_link&utm_campaign=opik)               | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/dspy.ipynb)        |
| Gemini      | Log traces for all Gemini LLM calls                                          | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/gemini?utm_source=opik&utm_medium=github&utm_content=gemini_link&utm_campaign=opik)           | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/gemini.ipynb)      |
| Groq        | Log traces for all Groq LLM calls                                            | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/groq?utm_source=opik&utm_medium=github&utm_content=groq_link&utm_campaign=opik)               | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/groq.ipynb)        |
| Guardrails  | Log traces for all Guardrails validations                                    | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/guardrails/?utm_source=opik&utm_medium=github&utm_content=guardrails_link&utm_campaign=opik)    | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/guardrails-ai.ipynb)   |
| Instructor  | Log traces for all LLM calls made with Instructor                            | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/instructor/?utm_source=opik&utm_medium=github&utm_content=instructor_link&utm_campaign=opik)    | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/instructor.ipynb)   |
| LangGraph   | Log traces for all LangGraph executions                                      | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/langgraph/?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik)    | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/langgraph.ipynb)   |
| LlamaIndex  | Log traces for all LlamaIndex LLM calls                                      | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/llama_index?utm_source=opik&utm_medium=github&utm_content=llama_index_link&utm_campaign=opik) | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/llama-index.ipynb) |
| Ollama      | Log traces for all Ollama LLM calls                                          | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/ollama?utm_source=opik&utm_medium=github&utm_content=ollama_link&utm_campaign=opik)           | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/ollama.ipynb)      |
| Predibase   | Fine-tune and serve open-source Large Language Models                        | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/predibase?utm_source=opik&utm_medium=github&utm_content=predibase_link&utm_campaign=opik)     | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/predibase.ipynb)   |
| Pydantic AI | Fine-tune and serve open-source Large Language Models                        | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/predibase?utm_source=opik&utm_medium=github&utm_content=predibase_link&utm_campaign=opik)     | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/predibase.ipynb)   |
| Ragas       | PydanticAI is a Python agent framework designed to build production apps     | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/pydantic-ai?utm_source=opik&utm_medium=github&utm_content=pydantic_ai_link&utm_campaign=opik) | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/pydantic-ai.ipynb) |
| watsonx     | Log traces for all watsonx LLM calls                                         | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/watsonx?utm_source=opik&utm_medium=github&utm_content=watsonx_link&utm_campaign=opik)         | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/watsonx.ipynb)     |
| Google ADK  | Log traces for Google Agent Development Kit (ADK)                            | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/google_adk?utm_source=opik&utm_medium=github&utm_content=google_adk_link&utm_campaign=opik) | (*New*) |
| Autogen     | Log traces for Autogen agentic workflows (via OpenTelemetry)                 | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/autogen?utm_source=opik&utm_medium=github&utm_content=autogen_link&utm_campaign=opik)       | (*New*) |
| Flowise AI  | Log traces for Flowise AI visual LLM application builder                     | [Documentation](https://www.comet.com/docs/opik/tracing/integrations/flowise?utm_source=opik&utm_medium=github&utm_content=flowise_link&utm_campaign=opik)         | (*New*) |

> [!TIP]
> If the framework you are using is not listed above, feel free to [open an issue](https://github.com/comet-ml/opik/issues) or submit a PR with the integration.

If you are not using any of the frameworks above, you can also use the `track` function decorator to [log traces](https://www.comet.com/docs/opik/tracing/log_traces/?from=llm&utm_source=opik&utm_medium=github&utm_content=traces_link&utm_campaign=opik):

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

The Python Opik SDK includes a number of LLM as a judge metrics to help you evaluate your LLM application. Learn more about it in the [metrics documentation](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_2_link&utm_campaign=opik).

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

Opik also includes a number of pre-built heuristic metrics as well as the ability to create your own. Learn more about it in the [metrics documentation](https://www.comet.com/docs/opik/evaluation/metrics/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_3_link&utm_campaign=opik).

### 🔍 Evaluating your LLM Application

Opik allows you to evaluate your LLM application during development through [Datasets](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_2_link&utm_campaign=opik) and [Experiments](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=experiments_link&utm_campaign=opik). The Opik Dashboard offers enhanced charts for experiments and better handling of large traces.

You can also run evaluations as part of your CI/CD pipeline using our [PyTest integration](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_2_link&utm_campaign=opik).

## ⭐ Star Us on GitHub

If you find Opik useful, please consider giving us a star! Your support helps us grow our community and continue improving the product.

[![Star History Chart](https://api.star-history.com/svg?repos=comet-ml/opik&type=Date)](https://github.com/comet-ml/opik)



## 🤝 Contributing

There are many ways to contribute to Opik:

* Submit [bug reports](https://github.com/comet-ml/opik/issues) and [feature requests](https://github.com/comet-ml/opik/issues)
* Review the documentation and submit [Pull Requests](https://github.com/comet-ml/opik/pulls) to improve it
* Speaking or writing about Opik and [letting us know](https://chat.comet.com)
* Upvoting [popular feature requests](https://github.com/comet-ml/opik/issues?q=is%3Aissue+is%3Aopen+label%3A%22enhancement%22) to show your support

To learn more about how to contribute to Opik, please see our [contributing guidelines](CONTRIBUTING.md).
