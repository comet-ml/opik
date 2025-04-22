> 注意：此文件为机器翻译版本。欢迎对翻译进行改进！
<div align="center"><b><a href="readme.md">英文版</a> | <a href="readme_CN.md">简体中文</a> | <a href="readme_JP.md">日本語</a> | <a href="readme_KO.md">한국어</a></b></div>

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
    开源 LLM 评估框架<br>
</h1>

<p align="center">
从 RAG 聊天机器人到代码助手，再到复杂的代理流水线等，构建高效、快速且低成本的 LLM 系统，具备追踪、评估和仪表板功能。
</p>

## 🚀 什么是 Opik？

Opik 是一个开源平台，用于评估、测试和监控 LLM 应用程序。由 [Comet](https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=what_is_opik_link&utm_campaign=opik) 构建。

<br>

您可以在以下方面使用 Opik：
* **开发：**
  * **追踪：** 在开发和生产过程中跟踪所有 LLM 调用和追踪 ([快速入门](https://www.comet.com/docs/opik/quickstart/?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_link&utm_campaign=opik)、[集成](https://www.comet.com/docs/opik/tracing/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik))
  * **注释：** 通过 [Python SDK](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link&utm_campaign=opik) 或 [UI](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-through-the-ui?from=llm&utm_source=opik&utm_medium=github&utm_content=ui_link&utm_campaign=opik) 来记录反馈分数，对 LLM 调用进行注释。
  * **沙盒：** 在 [提示沙盒](https://www.comet.com/docs/opik/evaluation/playground/?from=llm&utm_source=opik&utm_medium=github&utm_content=playground_link&utm_campaign=opik) 中试用不同的提示和模型。
* **评估：**
  * **数据集与实验：** 存储测试用例并运行实验 ([数据集](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_link&utm_campaign=opik)、[评估您的 LLM 应用](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=eval_link&utm_campaign=opik))
  * **LLM 评审指标：** 使用 Opik 提供的 LLM 评审指标来处理幻觉检测 ([幻觉检测](https://www.comet.com/docs/opik/evaluation/metrics/hallucination/?from=llm&utm_source=opik&utm_medium=github&utm_content=hallucination_link&utm_campaign=opik))、内容审核 ([审核](https://www.comet.com/docs/opik/evaluation/metrics/moderation/?from=llm&utm_source=opik&utm_medium=github&utm_content=moderation_link&utm_campaign=opik)) 和 RAG 评估 ([答案相关性](https://www.comet.com/docs/opik/evaluation/metrics/answer_relevance/?from=llm&utm_source=opik&utm_medium=github&utm_content=alex_link&utm_campaign=opik)、[上下文准确性](https://www.comet.com/docs/opik/evaluation/metrics/context_precision/?from=llm&utm_source=opik&utm_medium=github&utm_content=context_link&utm_campaign=opik)) 等复杂问题。
  * **CI/CD 集成：** 使用我们的 [PyTest 集成](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_link&utm_campaign=opik) 将评估过程集成到 CI/CD 流程中。

## 🛠️ 安装

Opik 可作为完全开源的本地安装版本获取，也可使用 Comet.com 提供的托管解决方案。最简单的入门方式是通过在 [comet.com](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=install&utm_campaign=opik) 创建一个免费的 Comet 账户。

如需自建 Opik，请克隆仓库并使用 Docker Compose 启动平台：

在 Linux 或 Mac 上运行：
```bash
# 克隆 Opik 仓库
git clone https://github.com/comet-ml/opik.git

# 进入仓库目录
cd opik

# 启动 Opik 平台
./opik.sh
```

在 Windows 上运行：
```powershell
# 克隆 Opik 仓库
git clone https://github.com/comet-ml/opik.git

# 进入仓库目录
cd opik

# 启动 Opik 平台
powershell -ExecutionPolicy ByPass -c ".\opik.ps1"
```

使用 `--help` 或 `--info` 选项来排查问题。

启动后，您可以在浏览器中访问 [localhost:5173](http://localhost:5173)！

更多安装选项，请参阅我们的部署指南：

| 安装方式 | 文档链接 |
| ------------------- | --------- |
| 本地部署 | [![本地部署](https://img.shields.io/badge/Local%20Deployments-%232496ED?style=flat&logo=docker&logoColor=white)](https://www.comet.com/docs/opik/self-host/local_deployment?from=llm&utm_source=opik&utm_medium=github&utm_content=self_host_link&utm_campaign=opik) |
| Kubernetes | [![Kubernetes](https://img.shields.io/badge/Kubernetes-%23326ce5.svg?&logo=kubernetes&logoColor=white)](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik) |

## 🏁 快速上手

首先安装 Python SDK：
```bash
pip install opik
```

安装 SDK 后，运行 `opik configure` 进行配置：
```bash
opik configure
```

您也可以在 Python 代码中调用 `opik.configure(use_local=True)` 来配置本地安装。

现在，您可以使用 [Python SDK](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link2&utm_campaign=opik) 开始记录追踪信息了。

### 📝 记录追踪信息

最简单的入门方式是使用我们的集成方式。Opik 支持：

| 集成方式   | 描述                           | 文档链接 | 在线试用 |
|------------|--------------------------------|---------|---------|
| OpenAI     | 记录所有 OpenAI LLM 调用追踪信息 | [文档](https://www.comet.com/docs/opik/tracing/integrations/openai/?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik) | [在线试用](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/openai.ipynb) |
| LiteLLM    | 使用 OpenAI 标准格式调用任意 LLM 模型 | [文档](https://www.comet.com/docs/opik/tracing/integrations/litellm/?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik) | [在线试用](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/litellm.ipynb) |
| LangChain  | 记录所有 LangChain LLM 调用追踪信息 | [文档](https://www.comet.com/docs/opik/tracing/integrations/langchain/?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik) | [在线试用](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/langchain.ipynb) |
| ...        | ...                            | ...     | ...     |

> 注意：如果您使用的框架不在上述列表中，请提交 [问题反馈](https://github.com/comet-ml/opik/issues) 或通过 PR 添加集成支持。

如果不使用上述框架，也可使用 `track` 装饰器记录追踪信息：
```python
import opik

opik.configure(use_local=True) # 本地运行

@opik.track
def my_llm_function(user_question: str) -> str:
    # 在此编写 LLM 代码
    return "Hello"
```

> 注意：`track` 装饰器既可与任何集成一起使用，也可用于嵌套函数调用的追踪记录。

### 🧑‍⚖️ LLM 评价指标

Opik 的 Python SDK 包含多种 LLM 评价指标，帮助您评估 LLM 应用。详情请参阅 [评价指标文档](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_2_link&utm_campaign=opik)。

例如：
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
Opik 还包括许多预构建的启发式指标，并且支持创建自定义指标。更多信息请参阅[评价指标文档](https://www.comet.com/docs/opik/evaluation/metrics/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_3_link&utm_campaign=opik)。

### 🔍 LLM 应用评估

Opik 允许您在开发过程中通过[数据集](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_2_link&utm_campaign=opik)和[实验](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=experiments_link&utm_campaign=opik)评估您的 LLM 应用程序。

您还可以通过我们的 [PyTest 集成](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_2_link&utm_campaign=opik)在 CI/CD 流水线中运行评估。

## ⭐ 在GitHub上关注我们

如果你觉得 Opik 有用，请考虑给我们一个 Star (点赞/加星标)！你的支持有助于我们壮大社区并持续改进产品。

<img src="https://github.com/user-attachments/assets/ffc208bb-3dc0-40d8-9a20-8513b5e4a59d" alt="Opik GitHub Star History" width="600"/>

## 🤝 贡献

以下是为 Opik 贡献的方法：

  * 提交 [Bug 报告](https://github.com/comet-ml/opik/issues) 和 [功能请求](https://github.com/comet-ml/opik/issues)
  * 通过审查文档和提交 [Pull Request](https://github.com/comet-ml/opik/pulls) 来改进文档
  * 撰写和发布与 Opik 相关的文章 ([联系我们](https://chat.comet.com))
  * 通过支持[受欢迎的功能请求](https://github.com/comet-ml/opik/issues?q=is%3Aissue+is%3Aopen+label%3A%22enhancement%22)来表达支持

有关详细的贡献方法，请参阅 [贡献指南](CONTRIBUTING.md)。
