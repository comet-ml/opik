> 注意：このファイルはAIを使用して機械翻訳されています。翻訳の改善への貢献を歓迎します！

<div align="center"><b><a href="README.md">English</a> | <a href="readme_CN.md">简体中文</a> | <a href="readme_JP.md">日本語</a> | <a href="readme_PT_BR.md">Português (Brasil)</a> | <a href="readme_KO.md">한국어</a><br><a href="readme_ES.md">Español</a> | <a href="readme_FR.md">Français</a> | <a href="readme_DE.md">Deutsch</a> | <a href="readme_RU.md">Русский</a> | <a href="readme_AR.md">العربية</a> | <a href="readme_HI.md">हिन्दी</a> | <a href="readme_TR.md">Türkçe</a></b></div>

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
</h1>
<h2 align="center" style="border-bottom: none">AI向けオープンソースのオブザーバビリティ、評価、最適化プラットフォーム</h2>
<p align="center">
Opikは、プロトタイプから本番まで、より良く動作する生成AIアプリケーションの構築、テスト、最適化を支援します。RAGチャットボットやコードアシスタント、複雑なエージェンティックシステムに至るまで、Opikは包括的なトレース、評価、そしてプロンプトやツールの自動最適化を提供し、AI開発における試行錯誤を取り除きます。
</p>

<div align="center">

[![Python SDK](https://img.shields.io/pypi/v/opik)](https://pypi.org/project/opik/)
[![License](https://img.shields.io/github/license/comet-ml/opik)](https://github.com/comet-ml/opik/blob/main/LICENSE)
[![Build](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml/badge.svg)](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml)
<!-- [![Quick Start](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/opik_quickstart.ipynb) -->

</div>

<p align="center">
    <a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=website_button&utm_campaign=opik"><b>Webサイト</b></a> •
    <a href="https://chat.comet.com"><b>Slack コミュニティ</b></a> •
    <a href="https://x.com/Cometml"><b>Twitter</b></a> •
    <a href="https://www.comet.com/docs/opik/changelog"><b>変更履歴</b></a> •
    <a href="https://www.comet.com/docs/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=docs_button&utm_campaign=opik"><b>ドキュメント</b></a>
</p>

<div align="center" style="margin-top: 1em; margin-bottom: 1em;">
<a href="#-opikとは">🚀 Opikとは？</a> • <a href="#🛠-opikサーバーのインストール">🛠️ Opikサーバーのインストール</a> • <a href="#-opikクライアントsdk">💻 OpikクライアントSDK</a> • <a href="#-トレースのログ記録と統合">📝 トレースのログ記録と統合</a><br>
<a href="#🧑‍⚖-llmをジャッジとして">🧑‍⚖️ LLMをジャッジとして</a> • <a href="#-アプリケーションの評価">🔍 アプリケーションの評価</a> • <a href="#-githubでスターを">⭐ GitHubでスターを</a> • <a href="#-貢献">🤝 貢献</a>
</div>

<br>

[![Opik platform screenshot (thumbnail)](readme-thumbnail-new.png)](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=readme_banner&utm_campaign=opik)

<a id="-opikとは"></a>
## 🚀 Opikとは？

Opik（[Comet](https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=what_is_opik_link&utm_campaign=opik)による開発）は、LLMアプリケーションのライフサイクル全体を効率化するためのオープンソースプラットフォームです。開発者がモデルやエージェンシーシステムを評価、テスト、監視、最適化できるようにします。主な提供機能は次のとおりです：

- **包括的なオブザーバビリティ**：LLM呼び出しの詳細なトレーシング、会話ログ、エージェントアクティビティの記録。
- **高度な評価**：プロンプト評価、LLM-as-a-judge、実験管理の強力な機能。
- **本番環境対応**：スケーラブルな監視ダッシュボードとオンライン評価ルール。
- **Opik Agent Optimizer**：プロンプトやエージェントを強化する専用SDKとオプティマイザー。
- **Opik Guardrails**：安全で責任あるAI実践を支援する機能。

<br>

主な機能：

- **開発 & トレーシング：**
  - 開発中および本番環境での詳細コンテキスト付きLLM呼び出しとトレースの追跡（[クイックスタート](https://www.comet.com/docs/opik/quickstart/?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_link&utm_campaign=opik)）。
  - 幅広いサードパーティ統合によるオブザーバビリティ：Google ADK、Autogen、Flowise AIなどの最新フレームワークをネイティブサポート（[統合一覧](https://www.comet.com/docs/opik/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik)）。
  - [Python SDK](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link&utm_campaign=opik)や[UI](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-through-the-ui?from=llm&utm_source=opik&utm_medium=github&utm_content=ui_link&utm_campaign=opik)を使ったトレースやスパンへのフィードバックスコアの注釈。
  - [Prompt Playground](https://www.comet.com/docs/opik/prompt_engineering/playground)でプロンプトやモデルを試行。

- **評価 & テスト：**
  - [データセット](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_link&utm_campaign=opik)と[実験](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=eval_link&utm_campaign=opik)を使ったLLMアプリ評価の自動化。
  - [ハルシネーション検出](https://www.comet.com/docs/opik/evaluation/metrics/hallucination/?from=llm&utm_source=opik&utm_medium=github&utm_content=hallucination_link&utm_campaign=opik)、[モデレーション](https://www.comet.com/docs/opik/evaluation/metrics/moderation/?from=llm&utm_source=opik&utm_medium=github&utm_content=moderation_link&utm_campaign=opik)、RAG評価（[回答の関連性](https://www.comet.com/docs/opik/evaluation/metrics/answer_relevance/?from=llm&utm_source=opik&utm_medium=github&utm_content=alex_link&utm_campaign=opik)、[コンテキスト精度](https://www.comet.com/docs/opik/evaluation/metrics/context_precision/?from=llm&utm_source=opik&utm_medium=github&utm_content=context_link&utm_campaign=opik)）などのLLM-as-a-judgeメトリクス。
  - [PyTest統合](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_link&utm_campaign=opik)を使ったCI/CDパイプラインへの評価組み込み。

- **本番監視 & 最適化：**
  - 高ボリュームの本番トレース記録：Opikはスケールを重視（1日あたり4,000万以上のトレース）。
  - Opikダッシュボードでフィードバックスコア、トレース数、トークン使用量を時間推移で監視（[ダッシュボード](https://www.comet.com/docs/opik/production/production_monitoring/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik)）。
  - [オンライン評価ルール](https://www.comet.com/docs/opik/production/rules/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik)を使った本番問題の検出。
  - **Opik Agent Optimizer** と **Opik Guardrails** で本番環境のLLMアプリを継続的に改善・保護。

> [!TIP]
> 現在Opikにない機能が必要な場合は、ぜひ新しい[機能リクエスト](https://github.com/comet-ml/opik/issues/new/choose)を提出してください 🚀

<br>

<a id="🛠-opikサーバーのインストール"></a>
## 🛠️ Opikサーバーのインストール

数分でOpikサーバーを起動できます。ニーズに合ったオプションを選んでください：

### オプション1：Comet.comクラウド（最も簡単 & 推奨）

セットアップ不要で即時にOpikにアクセスできます。クイックスタートやメンテナンス不要の方に最適。

👉 [無料のCometアカウントを作成](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=install_create_link&utm_campaign=opik)

### オプション2：セルフホストによる完全管理

独自環境にOpikをデプロイ。ローカルはDocker、スケーラブル環境はKubernetesを選択。

#### Docker Composeでのセルフホスト（ローカル開発 & テスト向け）

最も簡単にローカルOpikインスタンスを起動する方法です。新しい `.opik.sh` インストールスクリプトに注目：

On Linux or Mac Environment:

```bash
# Opikリポジトリをクローン
git clone https://github.com/comet-ml/opik.git

# リポジトリへ移動
cd opik

# Opikプラットフォームを起動
./opik.sh
```

On Windows Environment:

```powershell
# Opikリポジトリをクローン
git clone https://github.com/comet-ml/opik.git

# リポジトリへ移動
cd opik

# Opikプラットフォームを起動
powershell -ExecutionPolicy ByPass -c ".\\opik.ps1"
```

**開発用サービスプロファイル**

Opikインストールスクリプトは、異なる開発シナリオ向けのサービスプロファイルをサポートしています：

```bash
# フルOpikスイート（デフォルト動作）
./opik.sh

# インフラストラクチャサービスのみ（データベース、キャッシュなど）
./opik.sh --infra

# インフラストラクチャ + バックエンドサービス
./opik.sh --backend

# 任意のプロファイルでガードレールを有効化
./opik.sh --guardrails # フルOpikスイート + ガードレール
./opik.sh --backend --guardrails # インフラストラクチャ + バックエンド + ガードレール
```

`--help` または `--info` オプションでトラブルシューティングが可能。Dockerfileは非rootユーザー実行を保証し、セキュリティを強化。起動後、ブラウザで [localhost:5173](http://localhost:5173) を開くだけです。詳細は [ローカルデプロイガイド](https://www.comet.com/docs/opik/self-host/local_deployment?from=llm&utm_source=opik&utm_medium=github&utm_content=self_host_link&utm_campaign=opik) をご覧ください。

#### Kubernetes & Helmでのセルフホスト（大規模デプロイ向け）

本番や大規模セルフホスト環境では、Helmチャートを使ってKubernetesクラスタにインストールできます。詳しくはバッジをクリックして [Kubernetesインストールガイド (Helm)](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik) をご参照ください。

[![Kubernetes](https://img.shields.io/badge/Kubernetes-%23326ce5.svg?&logo=kubernetes&logoColor=white)](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik)

> [!IMPORTANT]
> **バージョン 1.7.0 の変更**：重要な更新と破壊的変更については [変更履歴](https://github.com/comet-ml/opik/blob/main/CHANGELOG.md) をご確認ください。

<a id="-opikクライアントsdk"></a>
## 💻 OpikクライアントSDK

Opikは、Opikサーバーとやり取りするためのクライアントライブラリ群とREST APIを提供します。Python、TypeScript、Ruby（OpenTelemetry経由）のSDKがあり、ワークフローへのシームレスな統合が可能です。詳細は [Opikクライアントリファレンス](https://www.comet.com/docs/opik/reference/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=reference_link&utm_campaign=opik) をご覧ください。

### Python SDKクイックスタート

Python SDKを始めるには：

パッケージをインストール：

```bash
# pipでインストール
pip install opik

# または uv を使ってインストール
uv pip install opik
```

`opik configure` コマンドを実行して、Opikサーバーアドレス（セルフホストの場合）またはAPIキーとワークスペース（Comet.comの場合）を入力します：

```bash
opik configure
```

> [!TIP]
> Pythonコード内で `opik.configure(use_local=True)` を呼び出してローカルセルフホスト構成にしたり、APIキーとワークスペースを直接指定することも可能です。詳細は [Python SDKドキュメント](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=python_sdk_docs_link&utm_campaign=opik) を参照してください。

これで [Python SDK](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link2&utm_campaign=opik) を使ったトレースのログ記録が可能になります。

<a id="-トレースのログ記録と統合"></a>
### 📝 トレースのログ記録と統合

最も簡単なトレース記録方法は、直接統合を使うことです。Opikは以下を含む多種多様なフレームワークをサポートしています：

| 統合                      | 説明                                                                        | ドキュメント                                                                                                                                                                  |
| ------------------------- | --------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **ADK**                   | Google Agent Development Kit (ADK) のトレースを記録                         | [ドキュメント](https://www.comet.com/docs/opik/integrations/adk?utm_source=opik&utm_medium=github&utm_content=google_adk_link&utm_campaign=opik)                              |
| **AG2**                   | AG2 LLM 呼び出しのトレースを記録                                            | [ドキュメント](https://www.comet.com/docs/opik/integrations/ag2?utm_source=opik&utm_medium=github&utm_content=ag2_link&utm_campaign=opik)                                     |
| **aisuite**               | aisuite LLM 呼び出しのトレースを記録                                        | [ドキュメント](https://www.comet.com/docs/opik/integrations/aisuite?utm_source=opik&utm_medium=github&utm_content=aisuite_link&utm_campaign=opik)                             |
| **Agno**                  | Agno エージェントオーケストレーションフレームワーク呼び出しのトレースを記録 | [ドキュメント](https://www.comet.com/docs/opik/integrations/agno?utm_source=opik&utm_medium=github&utm_content=agno_link&utm_campaign=opik)                                   |
| **Anthropic**             | Anthropic LLM 呼び出しのトレースを記録                                      | [ドキュメント](https://www.comet.com/docs/opik/integrations/anthropic?utm_source=opik&utm_medium=github&utm_content=anthropic_link&utm_campaign=opik)                         |
| **Autogen**               | Autogen エージェンシーワークフローのトレースを記録                          | [ドキュメント](https://www.comet.com/docs/opik/integrations/autogen?utm_source=opik&utm_medium=github&utm_content=autogen_link&utm_campaign=opik)                             |
| **Bedrock**               | Amazon Bedrock LLM 呼び出しのトレースを記録                                 | [ドキュメント](https://www.comet.com/docs/opik/integrations/bedrock?utm_source=opik&utm_medium=github&utm_content=bedrock_link&utm_campaign=opik)                             |
| **BeeAI (Python)**        | BeeAI Python エージェントフレームワーク呼び出しのトレースを記録             | [ドキュメント](https://www.comet.com/docs/opik/integrations/beeai?utm_source=opik&utm_medium=github&utm_content=beeai_link&utm_campaign=opik)                                 |
| **BeeAI (TypeScript)**    | BeeAI TypeScript エージェントフレームワーク呼び出しのトレースを記録         | [ドキュメント](https://www.comet.com/docs/opik/integrations/beeai-typescript?utm_source=opik&utm_medium=github&utm_content=beeai_typescript_link&utm_campaign=opik)           |
| **BytePlus**              | BytePlus LLM 呼び出しのトレースを記録                                       | [ドキュメント](https://www.comet.com/docs/opik/integrations/byteplus?utm_source=opik&utm_medium=github&utm_content=byteplus_link&utm_campaign=opik)                           |
| **CrewAI**                | CrewAI 呼び出しのトレースを記録                                             | [ドキュメント](https://www.comet.com/docs/opik/integrations/crewai?utm_source=opik&utm_medium=github&utm_content=crewai_link&utm_campaign=opik)                               |
| **Cloudflare Workers AI** | Cloudflare Workers AI 呼び出しのトレースを記録                              | [ドキュメント](https://www.comet.com/docs/opik/integrations/cloudflare-workers-ai?utm_source=opik&utm_medium=github&utm_content=cloudflare_workers_ai_link&utm_campaign=opik) |
| **Cohere**                | Cohere LLM 呼び出しのトレースを記録                                         | [ドキュメント](https://www.comet.com/docs/opik/integrations/cohere?utm_source=opik&utm_medium=github&utm_content=cohere_link&utm_campaign=opik)                               |
| **Cursor**                | Cursor 会話のトレースを記録                                                 | [ドキュメント](https://www.comet.com/docs/opik/integrations/cursor?utm_source=opik&utm_medium=github&utm_content=cursor_link&utm_campaign=opik)                               |
| **DeepSeek**              | DeepSeek LLM 呼び出しのトレースを記録                                       | [ドキュメント](https://www.comet.com/docs/opik/integrations/deepseek?utm_source=opik&utm_medium=github&utm_content=deepseek_link&utm_campaign=opik)                           |
| **Dify**                  | Dify エージェンシー実行のトレースを記録                                     | [ドキュメント](https://www.comet.com/docs/opik/integrations/dify?utm_source=opik&utm_medium=github&utm_content=dify_link&utm_campaign=opik)                                   |
| **DSPy**                  | DSPy 実行のトレースを記録                                                   | [ドキュメント](https://www.comet.com/docs/opik/integrations/dspy?utm_source=opik&utm_medium=github&utm_content=dspy_link&utm_campaign=opik)                                   |
| **Fireworks AI**          | Fireworks AI LLM 呼び出しのトレースを記録                                   | [ドキュメント](https://www.comet.com/docs/opik/integrations/fireworks-ai?utm_source=opik&utm_medium=github&utm_content=fireworks_ai_link&utm_campaign=opik)                   |
| **Flowise AI**            | Flowise AI ビジュアルLLMアプリのトレースを記録                              | [ドキュメント](https://www.comet.com/docs/opik/integrations/flowise?utm_source=opik&utm_medium=github&utm_content=flowise_link&utm_campaign=opik)                             |
| **Gemini (Python)**       | Google Gemini LLM 呼び出しのトレースを記録                                  | [ドキュメント](https://www.comet.com/docs/opik/integrations/gemini?utm_source=opik&utm_medium=github&utm_content=gemini_link&utm_campaign=opik)                               |
| **Gemini (TypeScript)**   | Google Gemini TypeScript SDK 呼び出しのトレースを記録                       | [ドキュメント](https://www.comet.com/docs/opik/integrations/gemini-typescript?utm_source=opik&utm_medium=github&utm_content=gemini_typescript_link&utm_campaign=opik)         |
| **Groq**                  | Groq LLM 呼び出しのトレースを記録                                           | [ドキュメント](https://www.comet.com/docs/opik/integrations/groq?utm_source=opik&utm_medium=github&utm_content=groq_link&utm_campaign=opik)                                   |
| **Guardrails**            | Guardrails AI 検証のトレースを記録                                          | [ドキュメント](https://www.comet.com/docs/opik/integrations/guardrails-ai?utm_source=opik&utm_medium=github&utm_content=guardrails_link&utm_campaign=opik)                    |
| **Haystack**              | Haystack 呼び出しのトレースを記録                                           | [ドキュメント](https://www.comet.com/docs/opik/integrations/haystack?utm_source=opik&utm_medium=github&utm_content=haystack_link&utm_campaign=opik)                           |
| **Harbor**                | Harbor ベンチマーク評価トライアルのトレースを記録                             | [ドキュメント](https://www.comet.com/docs/opik/integrations/harbor?utm_source=opik&utm_medium=github&utm_content=harbor_link&utm_campaign=opik)                               |
| **Instructor**            | Instructor 経由のLLM呼び出しトレースを記録                                  | [ドキュメント](https://www.comet.com/docs/opik/integrations/instructor?utm_source=opik&utm_medium=github&utm_content=instructor_link&utm_campaign=opik)                       |
| **LangChain (Python)**    | LangChain LLM 呼び出しのトレースを記録                                      | [ドキュメント](https://www.comet.com/docs/opik/integrations/langchain?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik)                         |
| **LangChain (JS/TS)**     | LangChain JavaScript/TypeScript 呼び出しのトレースを記録                    | [ドキュメント](https://www.comet.com/docs/opik/integrations/langchainjs?utm_source=opik&utm_medium=github&utm_content=langchainjs_link&utm_campaign=opik)                     |
| **LangGraph**             | LangGraph 実行のトレースを記録                                              | [ドキュメント](https://www.comet.com/docs/opik/integrations/langgraph?utm_source=opik&utm_medium=github&utm_content=langgraph_link&utm_campaign=opik)                         |
| **Langflow**              | Langflow ビジュアルAIビルダーのトレースを記録                               | [ドキュメント](https://www.comet.com/docs/opik/integrations/langflow?utm_source=opik&utm_medium=github&utm_content=langflow_link&utm_campaign=opik)                           |
| **LiteLLM**               | LiteLLM モデル呼び出しのトレースを記録                                      | [ドキュメント](https://www.comet.com/docs/opik/integrations/litellm?utm_source=opik&utm_medium=github&utm_content=litellm_link&utm_campaign=opik)                             |
| **LiveKit Agents**        | LiveKit Agents AI エージェントフレームワーク呼び出しのトレースを記録        | [ドキュメント](https://www.comet.com/docs/opik/integrations/livekit?utm_source=opik&utm_medium=github&utm_content=livekit_link&utm_campaign=opik)                             |
| **Mastra**                | Mastra AI ワークフローフレームワーク呼び出しのトレースを記録                | [ドキュメント](https://www.comet.com/docs/opik/integrations/mastra?utm_source=opik&utm_medium=github&utm_content=mastra_link&utm_campaign=opik)                               |
| **Microsoft Agent Framework (Python)** | Microsoft Agent Framework 呼び出しのトレースを記録 | [ドキュメント](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework?utm_source=opik&utm_medium=github&utm_content=agent_framework_link&utm_campaign=opik)              |
| **Microsoft Agent Framework (.NET)** | Microsoft Agent Framework .NET 呼び出しのトレースを記録 | [ドキュメント](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework-dotnet?utm_source=opik&utm_medium=github&utm_content=agent_framework_dotnet_link&utm_campaign=opik) |
| **Mistral AI**            | Mistral AI LLM 呼び出しのトレースを記録                                     | [ドキュメント](https://www.comet.com/docs/opik/integrations/mistral?utm_source=opik&utm_medium=github&utm_content=mistral_link&utm_campaign=opik)                             |
| **n8n**                   | n8n ワークフロー実行のトレースを記録                                        | [ドキュメント](https://www.comet.com/docs/opik/integrations/n8n?utm_source=opik&utm_medium=github&utm_content=n8n_link&utm_campaign=opik)                                     |
| **LlamaIndex**            | LlamaIndex LLM 呼び出しのトレースを記録                                     | [ドキュメント](https://www.comet.com/docs/opik/integrations/llama_index?utm_source=opik&utm_medium=github&utm_content=llama_index_link&utm_campaign=opik)                     |
| **Ollama**                | Ollama LLM 呼び出しのトレースを記録                                         | [ドキュメント](https://www.comet.com/docs/opik/integrations/ollama?utm_source=opik&utm_medium=github&utm_content=ollama_link&utm_campaign=opik)                               |
| **OpenAI (Python)**       | OpenAI LLM 呼び出しのトレースを記録                                         | [ドキュメント](https://www.comet.com/docs/opik/integrations/openai?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik)                               |
| **OpenAI (JS/TS)**        | OpenAI JavaScript/TypeScript 呼び出しのトレースを記録                       | [ドキュメント](https://www.comet.com/docs/opik/integrations/openai-typescript?utm_source=opik&utm_medium=github&utm_content=openai_typescript_link&utm_campaign=opik)         |
| **OpenAI Agents**         | OpenAI Agents SDK 呼び出しのトレースを記録                                  | [ドキュメント](https://www.comet.com/docs/opik/integrations/openai_agents?utm_source=opik&utm_medium=github&utm_content=openai_agents_link&utm_campaign=opik)                 |
| **OpenClaw**              | OpenClaw エージェント実行のトレースを記録                  | [ドキュメント](https://www.comet.com/docs/opik/integrations/openclaw?utm_source=opik&utm_medium=github&utm_content=openclaw_link&utm_campaign=opik) |
| **Novita AI**             | Novita AI LLM 呼び出しのトレースを記録                                      | [ドキュメント](https://www.comet.com/docs/opik/integrations/novita-ai?utm_source=opik&utm_medium=github&utm_content=novita_ai_link&utm_campaign=opik)                         |
| **OpenRouter**            | OpenRouter LLM 呼び出しのトレースを記録                                     | [ドキュメント](https://www.comet.com/docs/opik/integrations/openrouter?utm_source=opik&utm_medium=github&utm_content=openrouter_link&utm_campaign=opik)                       |
| **OpenTelemetry**         | OpenTelemetry 対応呼び出しのトレースを記録                                  | [ドキュメント](https://www.comet.com/docs/opik/tracing/opentelemetry/overview?utm_source=opik&utm_medium=github&utm_content=opentelemetry_link&utm_campaign=opik)             |
| **OpenWebUI**             | OpenWebUI の会話のトレースを記録                                            | [ドキュメント](https://www.comet.com/docs/opik/integrations/openwebui?utm_source=opik&utm_medium=github&utm_content=openwebui_link&utm_campaign=opik)                         |
| **Pipecat**               | Pipecat リアルタイム音声エージェント呼び出しのトレースを記録                | [ドキュメント](https://www.comet.com/docs/opik/integrations/pipecat?utm_source=opik&utm_medium=github&utm_content=pipecat_link&utm_campaign=opik)                             |
| **Predibase**             | Predibase LLM 呼び出しのトレースを記録                                      | [ドキュメント](https://www.comet.com/docs/opik/integrations/predibase?utm_source=opik&utm_medium=github&utm_content=predibase_link&utm_campaign=opik)                         |
| **Pydantic AI**           | PydanticAI エージェント呼び出しのトレースを記録                             | [ドキュメント](https://www.comet.com/docs/opik/integrations/pydantic-ai?utm_source=opik&utm_medium=github&utm_content=pydantic_ai_link&utm_campaign=opik)                     |
| **Ragas**                 | Ragas 評価のトレースを記録                                                  | [ドキュメント](https://www.comet.com/docs/opik/integrations/ragas?utm_source=opik&utm_medium=github&utm_content=ragas_link&utm_campaign=opik)                                 |
| **Smolagents**            | Smolagents エージェント呼び出しのトレースを記録                             | [ドキュメント](https://www.comet.com/docs/opik/integrations/smolagents?utm_source=opik&utm_medium=github&utm_content=smolagents_link&utm_campaign=opik)                       |
| **Semantic Kernel**       | Microsoft Semantic Kernel 呼び出しのトレースを記録                          | [ドキュメント](https://www.comet.com/docs/opik/integrations/semantic-kernel?utm_source=opik&utm_medium=github&utm_content=semantic_kernel_link&utm_campaign=opik)             |
| **Spring AI**             | Spring AI フレームワーク呼び出しのトレースを記録                            | [ドキュメント](https://www.comet.com/docs/opik/integrations/spring-ai?utm_source=opik&utm_medium=github&utm_content=spring_ai_link&utm_campaign=opik)                         |
| **Strands Agents**        | Strands Agents 呼び出しのトレースを記録                                     | [ドキュメント](https://www.comet.com/docs/opik/integrations/strands-agents?utm_source=opik&utm_medium=github&utm_content=strands_agents_link&utm_campaign=opik)               |
| **Together AI**           | Together AI LLM 呼び出しのトレースを記録                                    | [ドキュメント](https://www.comet.com/docs/opik/integrations/together-ai?utm_source=opik&utm_medium=github&utm_content=together_ai_link&utm_campaign=opik)                     |
| **Vercel AI SDK**         | Vercel AI SDK 呼び出しのトレースを記録                                      | [ドキュメント](https://www.comet.com/docs/opik/integrations/vercel-ai-sdk?utm_source=opik&utm_medium=github&utm_content=vercel_ai_sdk_link&utm_campaign=opik)                 |
| **VoltAgent**             | VoltAgent エージェントフレームワーク呼び出しのトレースを記録                | [ドキュメント](https://www.comet.com/docs/opik/integrations/voltagent?utm_source=opik&utm_medium=github&utm_content=voltagent_link&utm_campaign=opik)                         |
| **watsonx**               | IBM watsonx LLM 呼び出しのトレースを記録                                    | [ドキュメント](https://www.comet.com/docs/opik/integrations/watsonx?utm_source=opik&utm_medium=github&utm_content=watsonx_link&utm_campaign=opik)                             |
| **xAI Grok**              | xAI Grok LLM 呼び出しのトレースを記録                                       | [ドキュメント](https://www.comet.com/docs/opik/integrations/xai-grok?utm_source=opik&utm_medium=github&utm_content=xai_grok_link&utm_campaign=opik)                           |

> [!TIP]
> リストにないフレームワークを使用している場合は、[Issueを開く](https://github.com/comet-ml/opik/issues)かPRを提出してください。

フレームワークを使用しない場合は、`track` デコレータを使ってトレースを記録できます（[詳細](https://www.comet.com/docs/opik/tracing/log_traces/?from=llm&utm_source=opik&utm_medium=github&utm_content=traces_link&utm_campaign=opik)）：

```python
import opik

opik.configure(use_local=True) # ローカル実行

@opik.track
def my_llm_function(user_question: str) -> str:
    # あなたのLLMコードをここに記述
    return "こんにちは"
```

> [!TIP]
> trackデコレータは統合と併用でき、ネストされた関数呼び出しのトラッキングにも対応します。

<a id="🧑‍⚖-llmをジャッジとして"></a>
### 🧑‍⚖️ LLMをジャッジとして

Python Opik SDKにはLLM-as-a-judge用メトリクスが多数含まれており、LLMアプリの評価に役立ちます。詳細は [メトリクスドキュメント](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_2_link&utm_campaign=opik) をご覧ください。

使用例：

```python
from opik.evaluation.metrics import Hallucination

metric = Hallucination()
score = metric.score(
    input="フランスの首都は何ですか？",
    output="パリ",
    context=["フランスはヨーロッパの国です。"]
)
print(score)
```

Opikには事前定義のヒューリスティックメトリクスも多数含まれており、独自メトリクスの作成も可能です。詳細は同じく [メトリクスドキュメント](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_3_link&utm_campaign=opik) をご覧ください。

<a id="-アプリケーションの評価"></a>
### 🔍 アプリケーションの評価

開発中の評価には [データセット](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_2_link&utm_campaign=opik) と [実験](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=experiments_link&utm_campaign=opik) を活用できます。Opikダッシュボードは実験のチャートを強化し、大規模トレースの扱いを改善します。CI/CDへの組み込みには [PyTest統合](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_2_link&utm_campaign=opik) をご利用ください。

<a id="-githubでスターを"></a>
## ⭐ GitHubでスターを

Opikがお役に立ちましたら、ぜひスターをお願いします！コミュニティの拡大と製品改善の励みになります。

[![Star History Chart](https://api.star-history.com/svg?repos=comet-ml/opik&type=Date)](https://github.com/comet-ml/opik)

<a id="-貢献"></a>
## 🤝 貢献

Opikへの貢献方法は多数あります：

- [バグ報告](https://github.com/comet-ml/opik/issues) や [機能リクエスト](https://github.com/comet-ml/opik/issues) を提出
- ドキュメントをレビューし、[プルリクエスト](https://github.com/comet-ml/opik/pulls) を送信
- Opikについて講演や記事執筆を行い、[連絡](https://chat.comet.com)
- [人気の機能リクエスト](https://github.com/comet-ml/opik/issues?q=is%3Aissue+is%3Aopen+label%3A%22enhancement%22) に投票してサポートを示す

詳細は [貢献ガイドライン](CONTRIBUTING.md) をご覧ください。
