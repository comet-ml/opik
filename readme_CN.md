<div align="center"><b><a href="README.md">English</a> | <a href="readme_CN.md">简体中文</a> | <a href="readme_ES.md">Español</a> | <a href="readme_FR.md">Français</a> | <a href="readme_DE.md">Deutsch</a></b></div>

> 注意：此文件使用AI进行机器翻译。欢迎对翻译进行改进！


<h1 align="center" style="border-bottom: none">
    <div>
        <a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=header_img&utm_campaign=opik"><picture>
            <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/logo-dark-mode.svg">
            <source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg">
            <img alt="Comet Opik 徽标" src="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg" width="200" />
        </picture></a>
        <br>
        Opik：开源的 LLM 可观测性、评估与 AI 智能体追踪
    </div>
</h1>
<p align="center">
<b>Opik 是面向 AI 智能体追踪、LLM 评估、提示管理和生产监控的开源 LLM 可观测性与评估平台。</b>由 <a href="https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=what_is_opik_link&utm_campaign=opik">Comet</a> 打造。采用 Apache-2.0 许可，可免费自托管完整平台，已获得 20,000+ 个 GitHub star。
</p>

<div align="center">

[![Python SDK](https://img.shields.io/pypi/v/opik)](https://pypi.org/project/opik/)
[![License](https://img.shields.io/github/license/comet-ml/opik)](https://github.com/comet-ml/opik/blob/main/LICENSE)
[![Build](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml/badge.svg)](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml)
<!-- [![Quick Start](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/opik_quickstart.ipynb) -->

</div>

<p align="center">
    <a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=website_button&utm_campaign=opik"><b>官网</b></a> •
    <a href="https://chat.comet.com"><b>Slack 社区</b></a> •
    <a href="https://x.com/Cometml"><b>Twitter</b></a> •
    <a href="https://www.comet.com/docs/opik/changelog"><b>更新日志</b></a> •
    <a href="https://www.comet.com/docs/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=docs_button&utm_campaign=opik"><b>文档</b></a>
</p>

<p align="center"><sub>最后更新：2026-07-17</sub></p>

<div align="center" style="margin-top: 1em; margin-bottom: 1em;">
<a href="#-what-is-opik">🚀 什么是 Opik？</a> • <a href="#-quick-start">⚡ 快速开始</a> • <a href="#-how-opik-compares">📊 Opik 如何对比？</a> • <a href="#-frequently-asked-questions">❓ 常见问题</a> • <a href="#%EF%B8%8F-opik-server-installation">🛠️ Opik 服务器安装</a> • <a href="#-opik-client-sdk">💻 Opik 客户端 SDK</a> • <a href="#-logging-traces-with-integrations">📝 记录追踪</a><br>
<a href="#-llm-as-a-judge-metrics">🧑‍⚖️ LLM 作为评判者</a> • <a href="#-evaluating-your-llm-application">🔍 评估你的应用</a> • <a href="#-star-us-on-github">⭐ 为我们点亮 Star</a> • <a href="#-contributing">🤝 参与贡献</a>
</div>

<br>

[![Opik 平台截图（缩略图）](readme-thumbnail-new.png)](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=readme_banner&utm_campaign=opik)

<a id="-what-is-opik"></a>
## 🚀 什么是 Opik？

Opik 覆盖了 LLM 应用的完整生命周期，从开发阶段的第一条追踪到生产监控，服务于构建 LLM 应用和 AI 智能体的团队。核心能力包括：

- **AI 智能体追踪与可观测性**：对 LLM 调用、对话日志和智能体活动进行深度追踪，为多步骤智能体和工具调用提供完整的追踪树。
- **LLM 评估**：提供数据集、实验以及 LLM 作为评判者的指标，用于幻觉检测、内容审核和 RAG 评估。
- **提示与智能体优化**：Opik Agent Optimizer SDK，用于改进提示和智能体。
- **生产就绪的监控**：可扩展的仪表盘和在线评估规则。
- **Opik Guardrails**：帮助你实施安全且负责任的 AI 实践的功能。
- **CI/CD 评估**：PyTest 集成，可在每次提交时测试 LLM 流水线。

<br>

核心功能包括：

- **开发与追踪：**
  - 在开发和生产环境中跟踪所有 LLM 调用和追踪，并附带详细上下文（[快速开始](https://www.comet.com/docs/opik/quickstart/?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_link&utm_campaign=opik)）。
  - 丰富的第三方集成，便于实现可观测性：可无缝集成不断增长的框架列表，并原生支持其中许多最大和最流行的框架（包括近期新增的 **Google ADK**、**Autogen** 和 **Flowise AI**）。（[集成](https://www.comet.com/docs/opik/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik)）
  - 通过 [Python SDK](https://www.comet.com/docs/opik/v1/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link&utm_campaign=opik) 或 [UI](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-through-the-ui?from=llm&utm_source=opik&utm_medium=github&utm_content=ui_link&utm_campaign=opik) 为追踪和 span 添加反馈评分注解。
  - 在[提示 Playground](https://www.comet.com/docs/opik/prompt_engineering/playground) 中试验提示和模型。

- **评估与测试**：
  - 使用[数据集](https://www.comet.com/docs/opik/v1/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_link&utm_campaign=opik)和[实验](https://www.comet.com/docs/opik/v1/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=eval_link&utm_campaign=opik)自动化你的 LLM 应用评估。
  - 利用强大的 LLM 作为评判者的指标处理复杂任务，例如[幻觉检测](https://www.comet.com/docs/opik/evaluation/metrics/hallucination/?from=llm&utm_source=opik&utm_medium=github&utm_content=hallucination_link&utm_campaign=opik)、[内容审核](https://www.comet.com/docs/opik/evaluation/metrics/moderation/?from=llm&utm_source=opik&utm_medium=github&utm_content=moderation_link&utm_campaign=opik)以及 RAG 评估（[答案相关性](https://www.comet.com/docs/opik/evaluation/metrics/answer_relevance/?from=llm&utm_source=opik&utm_medium=github&utm_content=alex_link&utm_campaign=opik)、[上下文精确度](https://www.comet.com/docs/opik/evaluation/metrics/context_precision/?from=llm&utm_source=opik&utm_medium=github&utm_content=context_link&utm_campaign=opik)）。
  - 通过我们的 [PyTest 集成](https://www.comet.com/docs/opik/v1/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_link&utm_campaign=opik)将评估集成到你的 CI/CD 流水线中。

- **生产监控与优化**：
  - 记录大量生产追踪：Opik 专为规模化设计（每天 4000 万+ 条追踪）。
  - 在 [Opik 仪表盘](https://www.comet.com/docs/opik/v1/production/production_monitoring/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik)中随时间监控反馈评分、追踪数量和 token 用量。
  - 利用带有 LLM 作为评判者指标的[在线评估规则](https://www.comet.com/docs/opik/v1/production/rules/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik)来识别生产问题。
  - 借助 **Opik Agent Optimizer** 和 **Opik Guardrails**，在生产环境中持续改进并保护你的 LLM 应用。

**适用人群：** 构建 LLM 驱动智能体的机器学习工程师、从原型走向生产的 AI 团队，以及需要可在自有环境中运行的开源、可自托管可观测性的工程团队。

> **为什么开源在这里很重要：** Opik 采用 Apache-2.0 许可，可免费自托管：是完整平台，包含后端，而不仅仅是客户端 SDK。该仓库包含服务器后端、Web 应用、追踪、数据集、实验、评估、提示管理、在线评估和智能体优化等组件，全部采用 Apache-2.0 许可。你可以在自己的基础设施内运行 LLM 可观测性，数据不会离开你的环境，也无需进行企业销售洽谈。

> [!TIP]
> 如果你需要 Opik 目前尚未提供的功能，请提交新的[功能请求](https://github.com/comet-ml/opik/issues/new/choose) 🚀

<br>

<a id="-quick-start"></a>
## ⚡ 快速开始

安装 Python SDK 并进行配置：

```bash
pip install opik
opik configure
```

用 `@track` 装饰器包裹任意函数即可开始记录追踪：

```python
from opik import track

@track
def my_function(input: str) -> str:
    return input
```

现在，每次对 `my_function` 的调用都会被记录到 Opik，包括嵌套调用，因此它适用于完整的智能体和流水线追踪，而不仅仅是单次 LLM 调用。有关 TypeScript SDK 和其他设置选项，请参阅[快速开始指南](https://www.comet.com/docs/opik/quickstart?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_hero_link&utm_campaign=opik)。

<br>

<a id="-how-opik-compares"></a>
## 📊 Opik 如何对比？

Opik 与 **LangSmith、Arize（Phoenix 和 Arize AX）、Weights & Biases（Weave）、Langfuse 和 Braintrust** 一同竞争于 **LLM 可观测性 / AI 智能体评估** 这一领域。

| 能力 | Opik | LangSmith | Phoenix | Arize AX | Weights & Biases (Weave) | Langfuse | Braintrust |
|---|---|---|---|---|---|---|---|
| 开源 | 是，Apache-2.0（完整平台） | 否 | 源码可用（Elastic License 2.0，未获 OSI 批准） | 否 | 开源 SDK/工具包；自管平台需商业许可 | MIT 许可的核心平台；商业企业模块 | 否 |
| 自托管部署 | 是 | 仅企业版 | 是 | 仅企业版 | Weave 本身仅企业版 | 是，核心版 | 仅企业版 |
| 提供免费层（云端或自托管） | 是，两者皆有 | 是，云端 | 是，自托管 | 是，云端 | 是，云端 | 是，两者皆有 | 是，云端 |
| 智能体 / 多步骤追踪 | 是 | 是 | 是 | 是 | 是 | 是 | 是 |
| LLM 作为评判者评估 | 是 | 是 | 是 | 是 | 是 | 是 | 是 |
| 提示管理 | 是 | 是 | 部分支持 | 部分支持 | 部分支持 | 是 | 是 |
| 框架无关 | 是 | 部分，围绕 LangChain 构建 | 是 | 是 | 是 | 是 | 是 |

**团队为何选择 Opik：** Opik 完整的可观测性、评估和优化平台采用 Apache-2.0 许可，可免费自托管。与那些自托管部署需要企业版计划的封闭平台不同，Opik 无需商业许可即可部署，而且它框架无关，不会将你锁定到单一的智能体生态系统中。有关自托管和许可在各替代方案之间的差异，请参阅上表。

<br>

<a id="-frequently-asked-questions"></a>
## ❓ 常见问题

#### Opik 是开源的吗？
Opik 采用 Apache 2.0 许可。其服务器、Web 应用以及核心的可观测性和评估功能均可在无需商业许可的情况下自托管。

#### 我可以自托管 Opik 吗？
可以。你可以按照文档中的自托管选项在本地或你自己的基础设施中部署 Opik。

#### Opik 支持 AI 智能体追踪吗？
支持。Opik 可捕获包含 LLM 调用、工具执行、检索步骤和其他智能体活动的多步骤追踪。

#### Opik 支持 LLM 评估吗？
支持。Opik 支持数据集、实验、基于代码的指标、LLM 作为评判者的评估以及在线评估。

#### Opik 是否绑定到某个特定的智能体框架？
否。Opik 框架无关，支持其自有 SDK、OpenTelemetry 以及针对特定框架的集成。

<br>

<a id="%EF%B8%8F-opik-server-installation"></a>
## 🛠️ Opik 服务器安装

几分钟内即可让你的 Opik 服务器运行起来。选择最适合你需求的方式：

### 方式 1：Comet.com 云端（最简单且推荐）

无需任何设置即可立即访问 Opik。适合快速上手和省心维护。

👉 [创建你的免费 Comet 账户](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=install_create_link&utm_campaign=opik)

### 方式 2：自托管 Opik 以获得完全掌控

在你自己的环境中部署 Opik。可在用于本地设置的 Docker 与用于可扩展性的 Kubernetes 之间进行选择。

#### 使用 Docker Compose 自托管（用于本地开发与测试）

这是让本地 Opik 实例运行起来的最简单方式。请注意新的 `./opik.sh` 安装脚本：

在 Linux 或 Mac 环境下：

```bash
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the repository
cd opik

# Start the Opik platform
./opik.sh
```

在 Windows 环境下：

```powershell
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the repository
cd opik

# Start the Opik platform
powershell -ExecutionPolicy ByPass -c ".\\opik.ps1"
```

**用于开发的服务配置文件（Service Profiles）**

Opik 安装脚本现已支持针对不同开发场景的服务配置文件：

```bash
# Start full Opik suite (default behavior)
./opik.sh

# Start only infrastructure services (databases, caches etc.)
./opik.sh --infra

# Start infrastructure + backend services
./opik.sh --backend

# Enable guardrails with any profile
./opik.sh --guardrails # Guardrails with full Opik suite
./opik.sh --backend --guardrails # Guardrails with infrastructure + backend
```

使用 `--help` 或 `--info` 选项来排查问题。Dockerfile 现已确保容器以非 root 用户运行，以增强安全性。一切启动并运行后，你现在就可以在浏览器中访问 [localhost:5173](http://localhost:5173) 了！有关详细说明，请参阅[本地部署指南](https://www.comet.com/docs/opik/self-host/local_deployment?from=llm&utm_source=opik&utm_medium=github&utm_content=self_host_link&utm_campaign=opik)。

#### 使用 Kubernetes 和 Helm 自托管（用于可扩展部署）

对于生产环境或更大规模的自托管部署，可以使用我们的 Helm chart 将 Opik 安装到 Kubernetes 集群上。点击徽章查看完整的[使用 Helm 的 Kubernetes 安装指南](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik)。

[![Kubernetes](https://img.shields.io/badge/Kubernetes-%23326ce5.svg?&logo=kubernetes&logoColor=white)](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik)

> [!IMPORTANT]
> **1.7.0 版本变更**：请查看[更新日志](https://github.com/comet-ml/opik/blob/main/CHANGELOG.md)，了解重要更新和破坏性变更。

<a id="-opik-client-sdk"></a>
## 💻 Opik 客户端 SDK

Opik 提供一套客户端库和一个 REST API 用于与 Opik 服务器交互。这包括面向 Python、TypeScript 和 Ruby（通过 OpenTelemetry）的 SDK，可无缝集成到你的工作流中。有关详细的 API 和 SDK 参考，请参阅 [Opik 客户端参考文档](https://www.comet.com/docs/opik/reference/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=reference_link&utm_campaign=opik)。

### Python SDK 快速开始

要开始使用 Python SDK：

安装该软件包：

```bash
# install using pip
pip install opik

# or install with uv
uv pip install opik
```

运行 `opik configure` 命令配置 Python SDK，它会提示你输入 Opik 服务器地址（用于自托管实例）或你的 API 密钥和工作区（用于 Comet.com）：

```bash
opik configure
```

> [!TIP]
> 你也可以在 Python 代码中调用 `opik.configure(use_local=True)`，将 SDK 配置为在本地自托管安装上运行，或直接提供 Comet.com 的 API 密钥和工作区详情。有关更多配置选项，请参阅 [Python SDK 文档](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=python_sdk_docs_link&utm_campaign=opik)。

现在你已准备好使用 [Python SDK](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link2&utm_campaign=opik) 开始记录追踪了。

<a id="-logging-traces-with-integrations"></a>
### 📝 通过集成记录追踪

记录追踪最简单的方式是使用我们的某个直接集成。Opik 支持种类繁多的框架，包括近期新增的 **Google ADK**、**Autogen**、**AG2** 和 **Flowise AI**：

| 集成           | 描述                                             | 文档                                                                                                                                                                  |
| --------------------- | ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| ADK                   | 记录 Google Agent Development Kit (ADK) 的追踪       | [文档](https://www.comet.com/docs/opik/integrations/adk?utm_source=opik&utm_medium=github&utm_content=google_adk_link&utm_campaign=opik)                              |
| AG2                   | 记录 AG2 LLM 调用的追踪                            | [文档](https://www.comet.com/docs/opik/integrations/ag2?utm_source=opik&utm_medium=github&utm_content=ag2_link&utm_campaign=opik)                                     |
| Agent Spec            | 记录 Agent Spec 调用的追踪                         | [文档](https://www.comet.com/docs/opik/integrations/agentspec?utm_source=opik&utm_medium=github&utm_content=agentspec_link&utm_campaign=opik)                         |
| AIsuite               | 记录 aisuite LLM 调用的追踪                        | [文档](https://www.comet.com/docs/opik/integrations/aisuite?utm_source=opik&utm_medium=github&utm_content=aisuite_link&utm_campaign=opik)                             |
| Agno                  | 记录 Agno 智能体编排框架调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/agno?utm_source=opik&utm_medium=github&utm_content=agno_link&utm_campaign=opik)                                   |
| Anthropic             | 记录 Anthropic LLM 调用的追踪                      | [文档](https://www.comet.com/docs/opik/integrations/anthropic?utm_source=opik&utm_medium=github&utm_content=anthropic_link&utm_campaign=opik)                         |
| Autogen               | 记录 Autogen 智能体化工作流的追踪                | [文档](https://www.comet.com/docs/opik/integrations/autogen?utm_source=opik&utm_medium=github&utm_content=autogen_link&utm_campaign=opik)                             |
| Bedrock               | 记录 Amazon Bedrock LLM 调用的追踪                 | [文档](https://www.comet.com/docs/opik/integrations/bedrock?utm_source=opik&utm_medium=github&utm_content=bedrock_link&utm_campaign=opik)                             |
| BeeAI (Python)        | 记录 BeeAI Python 智能体框架调用的追踪       | [文档](https://www.comet.com/docs/opik/integrations/beeai?utm_source=opik&utm_medium=github&utm_content=beeai_link&utm_campaign=opik)                                 |
| BeeAI (TypeScript)    | 记录 BeeAI TypeScript 智能体框架调用的追踪   | [文档](https://www.comet.com/docs/opik/integrations/beeai-typescript?utm_source=opik&utm_medium=github&utm_content=beeai_typescript_link&utm_campaign=opik)           |
| BytePlus              | 记录 BytePlus LLM 调用的追踪                       | [文档](https://www.comet.com/docs/opik/integrations/byteplus?utm_source=opik&utm_medium=github&utm_content=byteplus_link&utm_campaign=opik)                           |
| Cloudflare Workers AI | 记录 Cloudflare Workers AI 调用的追踪              | [文档](https://www.comet.com/docs/opik/integrations/cloudflare-workers-ai?utm_source=opik&utm_medium=github&utm_content=cloudflare_workers_ai_link&utm_campaign=opik) |
| Cohere                | 记录 Cohere LLM 调用的追踪                         | [文档](https://www.comet.com/docs/opik/integrations/cohere?utm_source=opik&utm_medium=github&utm_content=cohere_link&utm_campaign=opik)                               |
| CrewAI                | 记录 CrewAI 调用的追踪                             | [文档](https://www.comet.com/docs/opik/integrations/crewai?utm_source=opik&utm_medium=github&utm_content=crewai_link&utm_campaign=opik)                               |
| Cursor                | 记录 Cursor 对话的追踪                     | [文档](https://www.comet.com/docs/opik/integrations/cursor?utm_source=opik&utm_medium=github&utm_content=cursor_link&utm_campaign=opik)                               |
| DeepSeek              | 记录 DeepSeek LLM 调用的追踪                       | [文档](https://www.comet.com/docs/opik/integrations/deepseek?utm_source=opik&utm_medium=github&utm_content=deepseek_link&utm_campaign=opik)                           |
| Dify                  | 记录 Dify 智能体运行的追踪                          | [文档](https://www.comet.com/docs/opik/integrations/dify?utm_source=opik&utm_medium=github&utm_content=dify_link&utm_campaign=opik)                                   |
| DSPY                  | 记录 DSPy 运行的追踪                                | [文档](https://www.comet.com/docs/opik/integrations/dspy?utm_source=opik&utm_medium=github&utm_content=dspy_link&utm_campaign=opik)                                   |
| Fireworks AI          | 记录 Fireworks AI LLM 调用的追踪                   | [文档](https://www.comet.com/docs/opik/integrations/fireworks-ai?utm_source=opik&utm_medium=github&utm_content=fireworks_ai_link&utm_campaign=opik)                   |
| Flowise AI            | 记录 Flowise AI 可视化 LLM 构建器的追踪            | [文档](https://www.comet.com/docs/opik/integrations/flowise?utm_source=opik&utm_medium=github&utm_content=flowise_link&utm_campaign=opik)                             |
| Gemini (Python)       | 记录 Google Gemini LLM 调用的追踪                  | [文档](https://www.comet.com/docs/opik/integrations/gemini?utm_source=opik&utm_medium=github&utm_content=gemini_link&utm_campaign=opik)                               |
| Gemini (TypeScript)   | 记录 Google Gemini TypeScript SDK 调用的追踪       | [文档](https://www.comet.com/docs/opik/integrations/gemini-typescript?utm_source=opik&utm_medium=github&utm_content=gemini_typescript_link&utm_campaign=opik)         |
| Groq                  | 记录 Groq LLM 调用的追踪                           | [文档](https://www.comet.com/docs/opik/integrations/groq?utm_source=opik&utm_medium=github&utm_content=groq_link&utm_campaign=opik)                                   |
| Guardrails            | 记录 Guardrails AI 校验的追踪                | [文档](https://www.comet.com/docs/opik/integrations/guardrails-ai?utm_source=opik&utm_medium=github&utm_content=guardrails_link&utm_campaign=opik)                    |
| Haystack              | 记录 Haystack 调用的追踪                           | [文档](https://www.comet.com/docs/opik/integrations/haystack?utm_source=opik&utm_medium=github&utm_content=haystack_link&utm_campaign=opik)                           |
| Harbor                | 记录 Harbor 基准评估试验的追踪       | [文档](https://www.comet.com/docs/opik/integrations/harbor?utm_source=opik&utm_medium=github&utm_content=harbor_link&utm_campaign=opik)                               |
| Instructor            | 记录使用 Instructor 进行的 LLM 调用的追踪           | [文档](https://www.comet.com/docs/opik/integrations/instructor?utm_source=opik&utm_medium=github&utm_content=instructor_link&utm_campaign=opik)                       |
| LangChain (Python)    | 记录 LangChain LLM 调用的追踪                      | [文档](https://www.comet.com/docs/opik/integrations/langchain?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik)                         |
| LangChain (JS/TS)     | 记录 LangChain JavaScript/TypeScript 调用的追踪    | [文档](https://www.comet.com/docs/opik/integrations/langchainjs?utm_source=opik&utm_medium=github&utm_content=langchainjs_link&utm_campaign=opik)                     |
| LangGraph             | 记录 LangGraph 执行的追踪                     | [文档](https://www.comet.com/docs/opik/integrations/langgraph?utm_source=opik&utm_medium=github&utm_content=langgraph_link&utm_campaign=opik)                         |
| Langflow              | 记录 Langflow 可视化 AI 构建器的追踪               | [文档](https://www.comet.com/docs/opik/integrations/langflow?utm_source=opik&utm_medium=github&utm_content=langflow_link&utm_campaign=opik)                           |
| LiteLLM               | 记录 LiteLLM 模型调用的追踪                      | [文档](https://www.comet.com/docs/opik/integrations/litellm?utm_source=opik&utm_medium=github&utm_content=litellm_link&utm_campaign=opik)                             |
| LiveKit Agents        | 记录 LiveKit Agents AI 智能体框架调用的追踪  | [文档](https://www.comet.com/docs/opik/integrations/livekit?utm_source=opik&utm_medium=github&utm_content=livekit_link&utm_campaign=opik)                             |
| LlamaIndex            | 记录 LlamaIndex LLM 调用的追踪                     | [文档](https://www.comet.com/docs/opik/integrations/llama_index?utm_source=opik&utm_medium=github&utm_content=llama_index_link&utm_campaign=opik)                     |
| Mastra                | 记录 Mastra AI 工作流框架调用的追踪       | [文档](https://www.comet.com/docs/opik/integrations/mastra?utm_source=opik&utm_medium=github&utm_content=mastra_link&utm_campaign=opik)                               |
| Microsoft Agent Framework (Python) | 记录 Microsoft Agent Framework 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework?utm_source=opik&utm_medium=github&utm_content=agent_framework_link&utm_campaign=opik)              |
| Microsoft Agent Framework (.NET) | 记录 Microsoft Agent Framework .NET 调用的追踪 | [文档](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework-dotnet?utm_source=opik&utm_medium=github&utm_content=agent_framework_dotnet_link&utm_campaign=opik) |
| Mistral AI            | 记录 Mistral AI LLM 调用的追踪                     | [文档](https://www.comet.com/docs/opik/integrations/mistral?utm_source=opik&utm_medium=github&utm_content=mistral_link&utm_campaign=opik)                             |
| n8n                   | 记录 n8n 工作流执行的追踪                  | [文档](https://www.comet.com/docs/opik/integrations/n8n?utm_source=opik&utm_medium=github&utm_content=n8n_link&utm_campaign=opik)                                     |
| Novita AI             | 记录 Novita AI LLM 调用的追踪                      | [文档](https://www.comet.com/docs/opik/integrations/novita-ai?utm_source=opik&utm_medium=github&utm_content=novita_ai_link&utm_campaign=opik)                         |
| Ollama                | 记录 Ollama LLM 调用的追踪                         | [文档](https://www.comet.com/docs/opik/integrations/ollama?utm_source=opik&utm_medium=github&utm_content=ollama_link&utm_campaign=opik)                               |
| OpenAI (Python)       | 记录 OpenAI LLM 调用的追踪                         | [文档](https://www.comet.com/docs/opik/integrations/openai?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik)                               |
| OpenAI (JS/TS)        | 记录 OpenAI JavaScript/TypeScript 调用的追踪       | [文档](https://www.comet.com/docs/opik/integrations/openai-typescript?utm_source=opik&utm_medium=github&utm_content=openai_typescript_link&utm_campaign=opik)         |
| OpenAI Agents         | 记录 OpenAI Agents SDK 调用的追踪                  | [文档](https://www.comet.com/docs/opik/integrations/openai_agents?utm_source=opik&utm_medium=github&utm_content=openai_agents_link&utm_campaign=opik)                 |
| OpenClaw              | 记录 OpenClaw 智能体运行的追踪                  | [文档](https://www.comet.com/docs/opik/integrations/openclaw?utm_source=opik&utm_medium=github&utm_content=openclaw_link&utm_campaign=opik) |
| OpenRouter            | 记录 OpenRouter LLM 调用的追踪                     | [文档](https://www.comet.com/docs/opik/integrations/openrouter?utm_source=opik&utm_medium=github&utm_content=openrouter_link&utm_campaign=opik)                       |
| OpenTelemetry         | 记录 OpenTelemetry 支持的调用的追踪            | [文档](https://www.comet.com/docs/opik/tracing/opentelemetry/overview?utm_source=opik&utm_medium=github&utm_content=opentelemetry_link&utm_campaign=opik)             |
| OpenWebUI             | 记录 OpenWebUI 对话的追踪                  | [文档](https://www.comet.com/docs/opik/integrations/openwebui?utm_source=opik&utm_medium=github&utm_content=openwebui_link&utm_campaign=opik)                         |
| Pipecat               | 记录 Pipecat 实时语音智能体调用的追踪      | [文档](https://www.comet.com/docs/opik/integrations/pipecat?utm_source=opik&utm_medium=github&utm_content=pipecat_link&utm_campaign=opik)                             |
| Predibase             | 记录 Predibase LLM 调用的追踪                      | [文档](https://www.comet.com/docs/opik/integrations/predibase?utm_source=opik&utm_medium=github&utm_content=predibase_link&utm_campaign=opik)                         |
| Pydantic AI           | 记录 PydanticAI 智能体调用的追踪                   | [文档](https://www.comet.com/docs/opik/integrations/pydantic-ai?utm_source=opik&utm_medium=github&utm_content=pydantic_ai_link&utm_campaign=opik)                     |
| Ragas                 | 记录 Ragas 评估的追踪                        | [文档](https://www.comet.com/docs/opik/integrations/ragas?utm_source=opik&utm_medium=github&utm_content=ragas_link&utm_campaign=opik)                                 |
| Semantic Kernel       | 记录 Microsoft Semantic Kernel 调用的追踪          | [文档](https://www.comet.com/docs/opik/integrations/semantic-kernel?utm_source=opik&utm_medium=github&utm_content=semantic_kernel_link&utm_campaign=opik)             |
| Smolagents            | 记录 Smolagents 智能体的追踪                        | [文档](https://www.comet.com/docs/opik/integrations/smolagents?utm_source=opik&utm_medium=github&utm_content=smolagents_link&utm_campaign=opik)                       |
| Spring AI             | 记录 Spring AI 框架调用的追踪                | [文档](https://www.comet.com/docs/opik/integrations/spring-ai?utm_source=opik&utm_medium=github&utm_content=spring_ai_link&utm_campaign=opik)                         |
| Strands Agents        | 记录 Strands agents 调用的追踪                     | [文档](https://www.comet.com/docs/opik/integrations/strands-agents?utm_source=opik&utm_medium=github&utm_content=strands_agents_link&utm_campaign=opik)               |
| Together AI           | 记录 Together AI LLM 调用的追踪                    | [文档](https://www.comet.com/docs/opik/integrations/together-ai?utm_source=opik&utm_medium=github&utm_content=together_ai_link&utm_campaign=opik)                     |
| Vercel AI SDK         | 记录 Vercel AI SDK 调用的追踪                      | [文档](https://www.comet.com/docs/opik/integrations/vercel-ai-sdk?utm_source=opik&utm_medium=github&utm_content=vercel_ai_sdk_link&utm_campaign=opik)                 |
| VoltAgent             | 记录 VoltAgent 智能体框架调用的追踪          | [文档](https://www.comet.com/docs/opik/integrations/voltagent?utm_source=opik&utm_medium=github&utm_content=voltagent_link&utm_campaign=opik)                         |
| WatsonX               | 记录 IBM watsonx LLM 调用的追踪                    | [文档](https://www.comet.com/docs/opik/integrations/watsonx?utm_source=opik&utm_medium=github&utm_content=watsonx_link&utm_campaign=opik)                             |
| xAI Grok              | 记录 xAI Grok LLM 调用的追踪                       | [文档](https://www.comet.com/docs/opik/integrations/xai-grok?utm_source=opik&utm_medium=github&utm_content=xai_grok_link&utm_campaign=opik)                           |

> [!TIP]
> 如果你使用的框架未列在上表中，欢迎[提交 issue](https://github.com/comet-ml/opik/issues) 或提交一个包含该集成的 PR。

如果你没有使用上述任何框架，也可以使用 `track` 函数装饰器来[记录追踪](https://www.comet.com/docs/opik/v1/tracing/log_traces/?from=llm&utm_source=opik&utm_medium=github&utm_content=traces_link&utm_campaign=opik)：

```python
import opik

opik.configure(use_local=True) # Run locally

@opik.track
def my_llm_function(user_question: str) -> str:
    # Your LLM code here

    return "Hello"
```

> [!TIP]
> track 装饰器可以与我们的任何集成结合使用，也可用于追踪嵌套的函数调用。

<a id="-llm-as-a-judge-metrics"></a>
### 🧑‍⚖️ LLM 作为评判者的指标

Python Opik SDK 包含许多 LLM 作为评判者的指标，帮助你评估 LLM 应用。请在[指标文档](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_2_link&utm_campaign=opik)中了解更多。

要使用它们，只需导入相关指标并使用 `score` 函数：

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

Opik 还包含许多预构建的启发式指标，并支持创建你自己的指标。请在[指标文档](https://www.comet.com/docs/opik/evaluation/metrics/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_3_link&utm_campaign=opik)中了解更多。

<a id="-evaluating-your-llm-application"></a>
### 🔍 评估你的 LLM 应用

Opik 允许你在开发阶段通过[数据集](https://www.comet.com/docs/opik/v1/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_2_link&utm_campaign=opik)和[实验](https://www.comet.com/docs/opik/v1/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=experiments_link&utm_campaign=opik)评估你的 LLM 应用。Opik 仪表盘为实验提供了增强的图表，并能更好地处理大型追踪。你还可以使用我们的 [PyTest 集成](https://www.comet.com/docs/opik/v1/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_2_link&utm_campaign=opik)将评估作为 CI/CD 流水线的一部分运行。

<a id="-star-us-on-github"></a>
## ⭐ 在 GitHub 上为我们点亮 Star

如果你觉得 Opik 有用，请考虑给我们点个 star！你的支持将帮助我们壮大社区并持续改进产品。

[![Star History Chart](https://api.star-history.com/svg?repos=comet-ml/opik&type=Date)](https://github.com/comet-ml/opik)

<a id="-contributing"></a>
## 🤝 参与贡献

为 Opik 做贡献的方式有很多：

- 提交[错误报告](https://github.com/comet-ml/opik/issues)和[功能请求](https://github.com/comet-ml/opik/issues)
- 审阅文档并提交[Pull Request](https://github.com/comet-ml/opik/pulls) 来改进它
- 谈论或撰写有关 Opik 的内容，并[告诉我们](https://chat.comet.com)
- 为[热门功能请求](https://github.com/comet-ml/opik/issues?q=is%3Aissue+is%3Aopen+label%3A%22enhancement%22)投票以表达你的支持

要了解有关如何为 Opik 做贡献的更多信息，请参阅我们的[贡献指南](CONTRIBUTING.md)。
