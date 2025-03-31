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

<p align="center">
RAGチャットボット、コードアシスタント、複雑なエージェントパイプライン等、トレース、評価、ダッシュボード機能を備えた、より良く、より速く、低コストなLLMシステムを構築します。
</p>

## 🚀 Opik とは？

Opik は、LLM アプリケーションの評価、テスト、監視のためのオープンソースプラットフォームです。これは [Comet](https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=what_is_opik_link&utm_campaign=opik) により構築されました。

<br>

Opik を使用すると、以下が可能です:
* **開発:**
  * **トレース:** 開発および本番環境での全LLM呼び出しとトレースを追跡します ([クイックスタート](https://www.comet.com/docs/opik/quickstart/?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_link&utm_campaign=opik)、[統合](https://www.comet.com/docs/opik/tracing/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik))
  * **注釈:** [Python SDK](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link&utm_campaign=opik) または [UI](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-through-the-ui?from=llm&utm_source=opik&utm_medium=github&utm_content=ui_link&utm_campaign=opik) を使用し、LLM 呼び出しにフィードバックスコアを記録します。
  * **プレイグラウンド:** [プロンプトプレイグラウンド](https://www.comet.com/docs/opik/evaluation/playground/?from=llm&utm_source=opik&utm_medium=github&utm_content=playground_link&utm_campaign=opik) で様々なプロンプトやモデルを試すことができます。
* **評価:**
  * **データセットと実験:** テストケースを保存し、実験を実行します ([データセット](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_link&utm_campaign=opik)、[LLM アプリケーションの評価](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=eval_link&utm_campaign=opik))
  * **LLM ジャッジ指標:** Opik の LLM ジャッジ指標を利用して、幻覚検出、モデレーション、及び RAG 評価などの複雑な問題を処理します。
  * **CI/CD 統合:** [PyTest 統合](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_link&utm_campaign=opik) により、CI/CD パイプライン内で評価を実行可能です。

## 🛠️ インストール

Opik は、完全にオープンソースのローカルインストール版または Comet.com によるホスト型ソリューションとして提供されます。最も簡単な方法は、[comet.com](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=install&utm_campaign=opik) で無料の Comet アカウントを作成することです。

自前で Opik をホストする場合、リポジトリをクローンし、Docker Compose を使用してプラットフォームを起動してください:

Linux または Macの場合:
```bash
# Opik リポジトリをクローン
git clone https://github.com/comet-ml/opik.git

# リポジトリディレクトリに移動
cd opik

# Opik プラットフォームを起動
./opik.sh
```

Windowsの場合:
```powershell
# Opik リポジトリをクローン
git clone https://github.com/comet-ml/opik.git

# リポジトリディレクトリに移動
cd opik

# Opik プラットフォームを起動
powershell -ExecutionPolicy ByPass -c ".\opik.ps1"
```

`--help` または `--info` オプションで問題解決を行ってください。

起動後、ブラウザで [localhost:5173](http://localhost:5173) にアクセスできます！

詳細なインストール情報は、当社のデプロイガイドをご参照ください。

## 🏁 クイックスタート

まず Python SDK をインストールします:
```bash
pip install opik
```

SDK のインストール後、`opik configure` コマンドを実行して設定を行います:
```bash
opik configure
```

また、Python コード内で `opik.configure(use_local=True)` を呼び出してローカル設定も可能です。

これで、[Python SDK](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link2&utm_campaign=opik) を使用してトレース情報を記録できます。

### 📝 トレース情報の記録

最も簡単な方法は、公式統合を使うことです。Opik は以下の統合をサポートしています:

| 統合         | 説明                                               | ドキュメント                                                                                                                    | Colab で試す                                                  |
|--------------|----------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------|
| OpenAI       | 全ての OpenAI LLM 呼び出しのトレース記録                | [ドキュメント](https://www.comet.com/docs/opik/tracing/integrations/openai/?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik)         | [Colabリンク](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/openai.ipynb)       |
| LiteLLM      | OpenAI フォーマットに準拠した任意のLLMモデル呼び出し      | [ドキュメント](https://www.comet.com/docs/opik/tracing/integrations/litellm/?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik)         | [Colabリンク](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/litellm.ipynb)      |
| LangChain    | 全ての LangChain LLM 呼び出しのトレース記録              | [ドキュメント](https://www.comet.com/docs/opik/tracing/integrations/langchain/?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik)        | [Colabリンク](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/langchain.ipynb)    |
| ...          | ...                                                | ...                                                                                                                            | ...                                                          |

> 注意: リストにないフレームワークをご利用の場合は、[Issue](https://github.com/comet-ml/opik/issues) を提出するか、PR を通じて統合を追加してください。

また、上記フレームワークを使用しない場合は、`track` デコレータを用いてトレースを記録可能です:
```python
import opik

opik.configure(use_local=True)  # ローカルで実行

@opik.track
def my_llm_function(user_question: str) -> str:
    # LLM コードをここに記述
    return "Hello"
```

> 注意: `track` デコレータは、どの統合とも併用可能で、ネストされた関数呼び出しのトレース記録にも使用できます。

### 🧑‍⚖️ LLM 評価指標

Opik の Python SDK には様々な LLM 評価指標が含まれており、LLM アプリケーションの評価に役立ちます。詳細は [評価指標ドキュメント](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_2_link&utm_campaign=opik) を参照してください。

例:
```python
from opik.evaluation.metrics import Hallucination

metric = Hallucination()
score = metric.score(
    input="프랑스의 수도는 무엇입니까?",
    output="파리",
    context=["프랑스는 유럽에 있는 국가입니다."]
)
print(score)
```

Opik は、あらかじめ構築された評価指標を多数提供しており、ユーザー独自の指標も作成可能です。詳細は [評価指標ドキュメント](https://www.comet.com/docs/opik/evaluation/metrics/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_3_link&utm_campaign=opik) をご確認ください。

### 🔍 LLM アプリケーションの評価

Opik を使用すると、[データセット](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_2_link&utm_campaign=opik) や [実験](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=experiments_link&utm_campaign=opik) を通じてLLM アプリケーションの評価が可能です。

また、[PyTest 統合](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_2_link&utm_campaign=opik) を使って、CI/CD パイプラインの一環として評価を実行できます。

## ⭐ GitHub でスターを押してください

Opik が役立つと感じた場合は、ぜひスターを押してください。皆様の支援は、コミュニティの成長および製品改善に大いに貢献します。

<img src="https://github.com/user-attachments/assets/ffc208bb-3dc0-40d8-9a20-8513b5e4a59d" alt="Opik GitHub Star History" width="600"/>

## 🤝 貢献

Opik への貢献方法:
* [バグ報告](https://github.com/comet-ml/opik/issues) および [機能リクエスト](https://github.com/comet-ml/opik/issues) の提出
* ドキュメントのレビュー及び [Pull Request](https://github.com/comet-ml/opik/pulls) による改善
* Opik に関する記事作成やプレゼンテーションの実施 ([お問い合わせ](https://chat.comet.com))
* 人気機能リクエストへの投票によるサポート

詳細は [Contributing ガイドライン](CONTRIBUTING.md) をご参照ください。
