> 注意：此文件为机器翻译版本。欢迎对翻译进行改进！
<div align="center"><b><a href="README.md">英文版</a> | <a href="readme_CN.md">简体中文</a> | <a href="readme_JP.md">日本語</a> | <a href="readme_KO.md">한국어</a></b></div>

<h1 align="center" style="border-bottom: none">
    <div>
        <a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=header_img&utm_campaign=opik">
            <picture>
                <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/logo-dark-mode.svg">
                <source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg">
                <img alt="Comet Opik logo" src="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg" width="200" />
            </picture>
        </a>
        <br>
        Opik
    </div>
</h1>
<h2 align="center" style="border-bottom: none">开源 LLM 评估平台</h2>
<p align="center">
Opik 帮助你构建、评估和优化更好、更快、更低成本的 LLM 系统。从 RAG 聊天机器人到代码助手再到复杂的代理流水线，Opik 提供全面的追踪、评估、仪表板，以及强大的 <b>Opik Agent Optimizer</b> 和 <b>Opik Guardrails</b>，助力你的 LLM 应用安全高效地上线。
</p>

## 🚀 什么是 Opik？

Opik（由 [Comet](https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=what_is_opik_link&utm_campaign=opik) 构建）是一个开源平台，旨在简化 LLM 应用的全生命周期。它帮助开发者评估、测试、监控和优化模型及智能体系统。主要功能包括：
* **全面可观测性**：深度追踪 LLM 调用、会话日志和智能体活动。
* **高级评估**：强大的提示评测、LLM 评审和实验管理。
* **生产级监控**：可扩展的监控仪表板和在线评测规则。
* **Opik Agent Optimizer**：专用 SDK 及优化器，提升提示和智能体表现。
* **Opik Guardrails**：助力安全、负责任地部署 AI。

主要能力包括：
* **开发与追踪：**
    * 在开发和生产中详细追踪所有 LLM 调用和上下文（[快速入门](https://www.comet.com/docs/opik/quickstart/?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_link&utm_campaign=opik)）。
    * 丰富的第三方集成，支持主流框架（如 **Google ADK**、**Autogen**、**Flowise AI** 等）（[集成文档](https://www.comet.com/docs/opik/tracing/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik)）。
    * 通过 [Python SDK](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link&utm_campaign=opik) 或 [UI](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-through-the-ui?from=llm&utm_source=opik&utm_medium=github&utm_content=ui_link&utm_campaign=opik) 为追踪和区段添加反馈分数。
    * 在 [Prompt Playground](https://www.comet.com/docs/opik/prompt_engineering/playground) 试验提示和模型。

* **评估与测试：**
    * 通过 [数据集](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_link&utm_campaign=opik) 和 [实验](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=eval_link&utm_campaign=opik) 自动化 LLM 应用评测。
    * 利用强大的 LLM 评审指标，处理如[幻觉检测](https://www.comet.com/docs/opik/evaluation/metrics/hallucination/?from=llm&utm_source=opik&utm_medium=github&utm_content=hallucination_link&utm_campaign=opik)、[内容审核](https://www.comet.com/docs/opik/evaluation/metrics/moderation/?from=llm&utm_source=opik&utm_medium=github&utm_content=moderation_link&utm_campaign=opik)、RAG 评估（[答案相关性](https://www.comet.com/docs/opik/evaluation/metrics/answer_relevance/?from=llm&utm_source=opik&utm_medium=github&utm_content=alex_link&utm_campaign=opik)、[上下文准确性](https://www.comet.com/docs/opik/evaluation/metrics/context_precision/?from=llm&utm_source=opik&utm_medium=github&utm_content=context_link&utm_campaign=opik)）等复杂任务。
    * 通过 [PyTest 集成](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_link&utm_campaign=opik) 集成到 CI/CD 流程。

* **生产监控与优化：**
    * 支持高并发生产追踪日志（单日 4000 万+ 追踪）。
    * 在 [Opik 仪表板](https://www.comet.com/docs/opik/production/production_monitoring/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik) 监控反馈分数、追踪数量和 Token 使用趋势。
    * 利用 [在线评测规则](https://www.comet.com/docs/opik/production/rules/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik) 和 LLM 评审指标识别生产问题。
    * 借助 **Opik Agent Optimizer** 和 **Opik Guardrails** 持续优化和保障 LLM 应用。

> [!TIP]
> 如果你有新的功能需求，欢迎提交 [Feature request](https://github.com/comet-ml/opik/issues/new/choose) 🚀

---

## 🛠️ Opik 服务器安装

快速启动你的 Opik 服务器，选择最适合你的方式：

### 选项 1：Comet.com 云端（最简单 & 推荐）
无需本地部署，立即体验。

👉 [免费注册 Comet 账号](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=install_create_link&utm_campaign=opik)

### 选项 2：自建 Opik（完全控制）
可选 Docker 或 Kubernetes。

#### Docker Compose（本地开发 & 测试）
Linux/Mac：
```bash
# 克隆仓库
git clone https://github.com/comet-ml/opik.git
cd opik
./opik.sh
```
Windows：
```powershell
git clone https://github.com/comet-ml/opik.git
cd opik
powershell -ExecutionPolicy ByPass -c ".\\opik.ps1"
```
启动后访问 [localhost:5173](http://localhost:5173)。[详细部署指南](https://www.comet.com/docs/opik/self-host/local_deployment?from=llm&utm_source=opik&utm_medium=github&utm_content=self_host_link&utm_campaign=opik)

#### Kubernetes & Helm（大规模生产）
参考 [Kubernetes 安装指南](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik)

---

## 💻 Opik 客户端 SDK

Opik 提供 Python、TypeScript、Ruby（OpenTelemetry）SDK 及 REST API。详见 [Opik 客户端文档](apps/opik-documentation/documentation/fern/docs/reference/overview.mdx)。

### Python SDK 快速上手
安装：
```bash
pip install opik
```
配置：
```bash
opik configure
```
或在代码中：
```python
opik.configure(use_local=True)
```
详见 [Python SDK 文档](apps/opik-documentation/documentation/fern/docs/reference/python-sdk/)

---

### 📝 集成追踪日志

Opik 支持多种主流框架集成，详见[集成文档](https://www.comet.com/docs/opik/tracing/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik)。

如未集成，可用 `track` 装饰器：
```python
import opik
opik.configure(use_local=True)
@opik.track
def my_llm_function(user_question: str) -> str:
    # 你的 LLM 代码
    return "Hello"
```

---

### 🧑‍⚖️ LLM 评审指标

Python SDK 内置多种 LLM 评审指标，详见[指标文档](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_2_link&utm_campaign=opik)。

示例：
```python
from opik.evaluation.metrics import Hallucination
metric = Hallucination()
score = metric.score(
    input="法国的首都是哪里?",
    output="巴黎",
    context=["法国是位于欧洲的国家."]
)
print(score)
```

---

### 🔍 LLM 应用评测

Opik 支持通过[数据集](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_2_link&utm_campaign=opik)和[实验](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=experiments_link&utm_campaign=opik)进行开发期评测，并可通过 [PyTest 集成](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_2_link&utm_campaign=opik)集成到 CI/CD。

---

## ⭐ GitHub 点赞支持

觉得有用请给我们 Star！你的支持是我们前进的动力。

---

## 🤝 贡献

欢迎通过 [Bug 报告](https://github.com/comet-ml/opik/issues)、[功能建议](https://github.com/comet-ml/opik/issues)、审阅文档和提交 PR、撰写文章、支持热门功能等方式参与贡献。详见 [贡献指南](CONTRIBUTING.md)。
