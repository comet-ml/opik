> 주의: 이 파일은 기계 번역되었습니다. 번역 개선에 기여해 주시기를 환영합니다!
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
    오픈 소스 LLM 평가 프레임워크<br>
</h1>

<p align="center">
RAG 챗봇, 코드 어시스턴트, 그리고 복잡한 에이전트 파이프라인 등, 추적, 평가 및 대시보드를 제공하는 더 좋고, 빠르며, 저렴한 LLM 시스템을 구축할 수 있습니다.
</p>

## 🚀 Opik이란?

Opik은 LLM 애플리케이션의 평가, 테스트 및 모니터링을 위한 오픈 소스 플랫폼입니다. 이는 [Comet](https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=what_is_opik_link&utm_campaign=opik)에 의해 개발되었습니다.

<br>

Opik을 사용하면 다음과 같은 작업을 수행할 수 있습니다:
* **개발:**
  * **추적:** 개발 및 프로덕션 환경에서 모든 LLM 호출과 추적을 기록합니다 ([빠른 시작](https://www.comet.com/docs/opik/quickstart/?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_link&utm_campaign=opik), [통합](https://www.comet.com/docs/opik/tracing/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik))
  * **주석:** [Python SDK](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link&utm_campaign=opik) 또는 [UI](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-through-the-ui?from=llm&utm_source=opik&utm_medium=github&utm_content=ui_link&utm_campaign=opik)를 사용하여 LLM 호출에 피드백 점수를 기록합니다.
  * **플레이그라운드:** [프롬프트 플레이그라운드](https://www.comet.com/docs/opik/evaluation/playground/?from=llm&utm_source=opik&utm_medium=github&utm_content=playground_link&utm_campaign=opik)에서 다양한 프롬프트와 모델을 시험해 볼 수 있습니다.
* **평가:**
  * **데이터셋 및 실험:** 테스트 케이스를 저장하고 실험을 진행합니다 ([데이터셋](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_link&utm_campaign=opik), [LLM 애플리케이션 평가](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=eval_link&utm_campaign=opik))
  * **LLM 판정 지표:** Opik의 LLM 판정 지표를 사용하여 환각 검출, 모더레이션 및 RAG 평가 등의 복잡한 문제를 처리합니다.
  * **CI/CD 통합:** [PyTest 통합](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_link&utm_campaign=opik)을 통해 CI/CD 파이프라인 내에서 평가를 실행할 수 있습니다.

## 🛠️ 설치

Opik은 완전히 오픈 소스인 로컬 설치 버전 또는 Comet.com의 호스팅 솔루션으로 제공됩니다. 가장 쉬운 방법은 [comet.com](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=install&utm_campaign=opik)에서 무료 Comet 계정을 만드는 것입니다.

자체적으로 Opik을 호스팅하려면, 저장소를 클론하고 Docker Compose를 사용하여 플랫폼을 시작하십시오:

Linux 또는 Mac의 경우:
```bash
# Opik 저장소를 클론
git clone https://github.com/comet-ml/opik.git

# 저장소 디렉터리로 이동
cd opik

# Opik 플랫폼 시작
./opik.sh
```

Windows의 경우:
```powershell
# Opik 저장소를 클론
git clone https://github.com/comet-ml/opik.git

# 저장소 디렉터리로 이동
cd opik

# Opik 플랫폼 시작
powershell -ExecutionPolicy ByPass -c ".\opik.ps1"
```

`--help` 또는 `--info` 옵션을 사용하여 문제를 해결하십시오.

시작 후, 브라우저에서 [localhost:5173](http://localhost:5173)에 접속하십시오!

추가 설치 옵션은 당사 배포 가이드를 참조하십시오.

## 🏁 빠른 시작

먼저 Python SDK를 설치합니다:
```bash
pip install opik
```

SDK 설치 후, `opik configure` 명령어를 실행하여 설정을 진행합니다:
```bash
opik configure
```

Python 코드 내에서 `opik.configure(use_local=True)`를 호출하여 로컬 설치를 구성할 수도 있습니다.

이제 [Python SDK](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link2&utm_campaign=opik)를 사용하여 추적 정보를 기록할 수 있습니다.

### 📝 추적 정보 기록

가장 쉬운 방법은 통합 기능을 사용하는 것입니다. Opik은 다음과 같은 통합 기능을 지원합니다:

| 통합           | 설명                                                  | 문서                                                                                                                    | Colab에서 실행                                         |
|----------------|-------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------|
| OpenAI         | 모든 OpenAI LLM 호출의 추적 정보를 기록합니다           | [문서](https://www.comet.com/docs/opik/tracing/integrations/openai/?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik)         | [Colab 링크](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/openai.ipynb)       |
| LiteLLM        | OpenAI 형식에 따른 임의의 LLM 모델 호출               | [문서](https://www.comet.com/docs/opik/tracing/integrations/litellm/?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik)         | [Colab 링크](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/litellm.ipynb)      |
| LangChain      | 모든 LangChain LLM 호출의 추적 정보를 기록합니다        | [문서](https://www.comet.com/docs/opik/tracing/integrations/langchain/?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik)        | [Colab 링크](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/langchain.ipynb)    |
| ...            | ...                                                   | ...                                                                                                                    | ...                                                    |

> 주의: 위 목록에 없는 프레임워크를 사용하는 경우, [이슈](https://github.com/comet-ml/opik/issues)를 제출하거나 PR을 통해 통합 기능을 추가해 주십시오.

또한, 위 프레임워크를 사용하지 않는 경우 `track` 데코레이터를 통해 추적 정보를 기록할 수 있습니다:
```python
import opik

opik.configure(use_local=True)  # 로컬 실행

@opik.track
def my_llm_function(user_question: str) -> str:
    # LLM 코드를 여기 작성합니다
    return "Hello"
```

> 주의: `track` 데코레이터는 모든 통합과 함께 사용할 수 있으며, 중첩 함수 호출의 추적 기록에도 사용 가능합니다.

### 🧑‍⚖️ LLM 평가 지표

Opik의 Python SDK에는 다양한 LLM 평가 지표가 포함되어 있어, LLM 애플리케이션 평가에 도움이 됩니다. 자세한 내용은 [평가 지표 문서](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_2_link&utm_campaign=opik)를 참조하십시오.

예:
```python
from opik.evaluation.metrics import Hallucination

metric = Hallucination()
score = metric.score(
    input="프랑스의 수도는 무엇입니까?",
    output="파리",
    context=["프랑스는 유럽에 위치한 국가입니다."]
)
print(score)
```

Opik은 사전에 구성된 다양한 평가 지표를 제공하며, 사용자가 직접 평가 지표를 생성할 수도 있습니다. 자세한 내용은 [평가 지표 문서](https://www.comet.com/docs/opik/evaluation/metrics/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_3_link&utm_campaign=opik)를 확인하십시오.

### 🔍 LLM 애플리케이션 평가

Opik을 사용하면 [데이터셋](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_2_link&utm_campaign=opik) 및 [실험](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=experiments_link&utm_campaign=opik)을 통해 LLM 애플리케이션을 평가할 수 있습니다.

또한, [PyTest 통합](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_2_link&utm_campaign=opik)을 사용하여 CI/CD 파이프라인 내에서 평가를 실행할 수 있습니다.

## ⭐ GitHub에서 Star를 눌러주세요

Opik이 유용하다고 느끼신다면, Star를 눌러 주세요. 여러분의 지원은 커뮤니티의 성장과 제품 개선에 큰 도움이 됩니다.

<img src="https://github.com/user-attachments/assets/ffc208bb-3dc0-40d8-9a20-8513b5e4a59d" alt="Opik GitHub Star History" width="600"/>

## 🤝 기여

Opik에 기여하는 방법:
* [버그 보고](https://github.com/comet-ml/opik/issues) 및 [기능 요청](https://github.com/comet-ml/opik/issues) 제출
* 문서 리뷰 및 [Pull Request](https://github.com/comet-ml/opik/pulls)를 통한 개선
* Opik 관련 글 작성 및 발표 ([문의하기](https://chat.comet.com))
* 인기 기능 요청에 대한 지지

자세한 기여 방법은 [Contributing 가이드라인](CONTRIBUTING.md)을 참조하십시오.
