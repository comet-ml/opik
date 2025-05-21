> 注意：このファイルは機械翻訳されています。翻訳の改善への貢献を歓迎します！
<div align="center"><b><a href="readme.md">English</a> | <a href="readme_CN.md">简体中文</a> | <a href="readme_JP.md">日本語</a> | <a href="readme_KO.md">한국어</a></b></div>

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
    オープンソース LLM 評価フレームワーク<br>
</h1>

<h2 align="center" style="border-bottom: none">オープンソース LLM 評価プラットフォーム</h2>

<p align="center">
Opikは、より良く、より速く、低コストなLLMシステムの構築・評価・最適化を支援します。RAGチャットボット、コードアシスタント、複雑なエージェントパイプラインまで、Opikは包括的なトレース、評価、ダッシュボード、<b>Opik Agent Optimizer</b>や<b>Opik Guardrails</b>などの強力な機能を提供し、LLMアプリケーションの安全性と生産性を高めます。
</p>

## 🚀 Opikとは？

Opik（[Comet](https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=what_is_opik_link&utm_campaign=opik)が開発）は、LLMアプリケーションのライフサイクル全体を効率化するオープンソースプラットフォームです。開発者がモデルやエージェントシステムを評価・テスト・監視・最適化できるよう支援します。主な機能：
* **包括的な可観測性**：LLM呼び出しの深いトレース、会話ログ、エージェント活動の記録
* **高度な評価**：強力なプロンプト評価、LLMジャッジ、実験管理
* **本番対応**：スケーラブルな監視ダッシュボードとオンライン評価ルール
* **Opik Agent Optimizer**：プロンプトやエージェントを最適化する専用SDKと最適化ツール
* **Opik Guardrails**：安全で責任あるAI運用を支援

主な機能詳細：
* **開発・トレース：**
    * 開発・本番環境でのLLM呼び出しやトレースを詳細に記録（[クイックスタート](https://www.comet.com/docs/opik/quickstart/?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_link&utm_campaign=opik)）
    * 豊富なサードパーティ統合（**Google ADK**、**Autogen**、**Flowise AI**など）（[統合ドキュメント](https://www.comet.com/docs/opik/tracing/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik)）
    * [Python SDK](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link&utm_campaign=opik)や[UI](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-through-the-ui?from=llm&utm_source=opik&utm_medium=github&utm_content=ui_link&utm_campaign=opik)でトレースやスパンにフィードバックスコアを付与
    * [Prompt Playground](https://www.comet.com/docs/opik/prompt_engineering/playground)でプロンプトやモデルを試行

* **評価・テスト：**
    * [データセット](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_link&utm_campaign=opik)や[実験](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=eval_link&utm_campaign=opik)によるLLMアプリの自動評価
    * 強力なLLMジャッジ指標で[幻覚検出](https://www.comet.com/docs/opik/evaluation/metrics/hallucination/?from=llm&utm_source=opik&utm_medium=github&utm_content=hallucination_link&utm_campaign=opik)、[モデレーション](https://www.comet.com/docs/opik/evaluation/metrics/moderation/?from=llm&utm_source=opik&utm_medium=github&utm_content=moderation_link&utm_campaign=opik)、RAG評価（[回答関連性](https://www.comet.com/docs/opik/evaluation/metrics/answer_relevance/?from=llm&utm_source=opik&utm_medium=github&utm_content=alex_link&utm_campaign=opik)、[コンテキスト精度](https://www.comet.com/docs/opik/evaluation/metrics/context_precision/?from=llm&utm_source=opik&utm_medium=github&utm_content=context_link&utm_campaign=opik)）など複雑な課題に対応
    * [PyTest統合](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_link&utm_campaign=opik)でCI/CDパイプラインに組み込み

* **本番監視・最適化：**
    * 大規模な本番トレース記録（1日4,000万件以上）
    * [Opikダッシュボード](https://www.comet.com/docs/opik/production/production_monitoring/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik)でフィードバックスコア・トレース数・トークン使用量を可視化
    * [オンライン評価ルール](https://www.comet.com/docs/opik/production/rules/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik)とLLMジャッジ指標で本番課題を特定
    * **Opik Agent Optimizer**や**Opik Guardrails**でLLMアプリを継続的に改善・保護

> [!TIP]
> 新機能のご要望は[Feature request](https://github.com/comet-ml/opik/issues/new/choose)でお知らせください 🚀

---

## 🛠️ Opikサーバーのインストール

数分でOpikサーバーを起動。用途に応じて選択：

### オプション1：Comet.comクラウド（最も簡単・推奨）
セットアップ不要ですぐ利用可能。

👉 [無料Cometアカウント作成](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=install_create_link&utm_campaign=opik)

### オプション2：セルフホスト（完全制御）
DockerまたはKubernetesを選択可能。

#### Docker Compose（ローカル開発・テスト向け）
Linux/Mac：
```bash
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
起動後 [localhost:5173](http://localhost:5173) へ。詳細は[ローカルデプロイガイド](https://www.comet.com/docs/opik/self-host/local_deployment?from=llm&utm_source=opik&utm_medium=github&utm_content=self_host_link&utm_campaign=opik)

#### Kubernetes & Helm（大規模運用向け）
[HelmによるKubernetesインストールガイド](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik)

---

## 💻 OpikクライアントSDK

OpikはPython、TypeScript、Ruby（OpenTelemetry）SDKとREST APIを提供。詳細は[クライアントリファレンス](apps/opik-documentation/documentation/fern/docs/reference/overview.mdx)参照。

### Python SDKクイックスタート
インストール：
```bash
pip install opik
```
設定：
```bash
opik configure
```
またはコード内で：
```python
opik.configure(use_local=True)
```
詳細は[Python SDKドキュメント](apps/opik-documentation/documentation/fern/docs/reference/python-sdk/)

---

### 📝 トレース記録の統合

Opikは主要なフレームワークと統合可能。詳細は[統合ドキュメント](https://www.comet.com/docs/opik/tracing/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik)。

未対応の場合は`track`デコレータを利用：
```python
import opik
opik.configure(use_local=True)
@opik.track
def my_llm_function(user_question: str) -> str:
    # LLMコード
    return "Hello"
```

---

### 🧑‍⚖️ LLMジャッジ指標

Python SDKには多様なLLMジャッジ指標を内蔵。[指標ドキュメント](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_2_link&utm_campaign=opik)参照。

例：
```python
from opik.evaluation.metrics import Hallucination
metric = Hallucination()
score = metric.score(
    input="フランスの首都はどこですか?",
    output="パリ",
    context=["フランスはヨーロッパの国です."]
)
print(score)
```

---

### 🔍 LLMアプリケーションの評価

Opikは[データセット](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_2_link&utm_campaign=opik)や[実験](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=experiments_link&utm_campaign=opik)による開発時評価、[PyTest統合](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_2_link&utm_campaign=opik)によるCI/CD組み込みが可能。

---

## ⭐ GitHubでスターを！

役立ったらぜひStarをお願いします。皆様の応援が開発の原動力です。

---

## 🤝 貢献方法

[バグ報告](https://github.com/comet-ml/opik/issues)、[機能要望](https://github.com/comet-ml/opik/issues)、ドキュメントレビューやPR、記事執筆、人気要望への投票などでご参加ください。詳細は[貢献ガイド](CONTRIBUTING.md)参照。
