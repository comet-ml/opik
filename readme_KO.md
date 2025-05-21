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
</h1>
<h2 align="center" style="border-bottom: none">오픈 소스 LLM 평가 플랫폼</h2>
<p align="center">
Opik은 더 좋고, 더 빠르며, 더 저렴한 LLM 시스템을 구축, 평가 및 최적화할 수 있도록 도와줍니다. RAG 챗봇, 코드 어시스턴트, 복잡한 에이전트 파이프라인까지, Opik은 포괄적인 추적, 평가, 대시보드, <b>Opik Agent Optimizer</b> 및 <b>Opik Guardrails</b>와 같은 강력한 기능을 제공하여 LLM 기반 애플리케이션의 생산성과 안전성을 높입니다.
</p>

## 🚀 Opik란?

Opik([Comet](https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=what_is_opik_link&utm_campaign=opik) 개발)는 LLM 애플리케이션의 전체 라이프사이클을 간소화하는 오픈 소스 플랫폼입니다. 개발자가 모델 및 에이전트 시스템을 평가, 테스트, 모니터링, 최적화할 수 있도록 지원합니다. 주요 기능:
* **포괄적 관측성**: LLM 호출, 대화 로그, 에이전트 활동의 심층 추적
* **고급 평가**: 강력한 프롬프트 평가, LLM 판정, 실험 관리
* **프로덕션 준비**: 확장 가능한 모니터링 대시보드 및 온라인 평가 규칙
* **Opik Agent Optimizer**: 프롬프트 및 에이전트 최적화를 위한 전용 SDK 및 최적화 도구
* **Opik Guardrails**: 안전하고 책임감 있는 AI 운영 지원

주요 기능 상세:
* **개발 및 추적:**
    * 개발 및 프로덕션 환경에서 LLM 호출과 추적을 상세하게 기록([빠른 시작](https://www.comet.com/docs/opik/quickstart/?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_link&utm_campaign=opik))
    * 다양한 서드파티 통합 지원(**Google ADK**, **Autogen**, **Flowise AI** 등) ([통합 문서](https://www.comet.com/docs/opik/tracing/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik))
    * [Python SDK](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link&utm_campaign=opik) 또는 [UI](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-through-the-ui?from=llm&utm_source=opik&utm_medium=github&utm_content=ui_link&utm_campaign=opik)로 추적 및 스팬에 피드백 점수 부여
    * [Prompt Playground](https://www.comet.com/docs/opik/prompt_engineering/playground)에서 프롬프트와 모델 실험

* **평가 및 테스트:**
    * [데이터셋](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_link&utm_campaign=opik) 및 [실험](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=eval_link&utm_campaign=opik)으로 LLM 애플리케이션 자동 평가
    * 강력한 LLM 판정 지표로 [환각 탐지](https://www.comet.com/docs/opik/evaluation/metrics/hallucination/?from=llm&utm_source=opik&utm_medium=github&utm_content=hallucination_link&utm_campaign=opik), [콘텐츠 모더레이션](https://www.comet.com/docs/opik/evaluation/metrics/moderation/?from=llm&utm_source=opik&utm_medium=github&utm_content=moderation_link&utm_campaign=opik), RAG 평가([답변 관련성](https://www.comet.com/docs/opik/evaluation/metrics/answer_relevance/?from=llm&utm_source=opik&utm_medium=github&utm_content=alex_link&utm_campaign=opik), [컨텍스트 정밀도](https://www.comet.com/docs/opik/evaluation/metrics/context_precision/?from=llm&utm_source=opik&utm_medium=github&utm_content=context_link&utm_campaign=opik)) 등 복잡한 과제 지원
    * [PyTest 통합](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_link&utm_campaign=opik)으로 CI/CD 파이프라인 연동

* **프로덕션 모니터링 및 최적화:**
    * 대규모 프로덕션 추적 기록(일 4천만 건 이상)
    * [Opik 대시보드](https://www.comet.com/docs/opik/production/production_monitoring/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik)에서 피드백 점수, 추적 수, 토큰 사용량 모니터링
    * [온라인 평가 규칙](https://www.comet.com/docs/opik/production/rules/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik)과 LLM 판정 지표로 프로덕션 이슈 식별
    * **Opik Agent Optimizer** 및 **Opik Guardrails**로 LLM 애플리케이션 지속 개선 및 보호

> [!TIP]
> 새로운 기능이 필요하다면 [Feature request](https://github.com/comet-ml/opik/issues/new/choose)를 남겨주세요 🚀

---

## 🛠️ Opik 서버 설치

몇 분 만에 Opik 서버를 시작하세요. 환경에 맞는 옵션을 선택하세요:

### 옵션 1: Comet.com 클라우드(가장 쉽고 추천)
설정 없이 즉시 사용 가능.

👉 [무료 Comet 계정 만들기](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=install_create_link&utm_campaign=opik)

### 옵션 2: 직접 호스팅(완전 제어)
Docker 또는 Kubernetes 선택 가능.

#### Docker Compose(로컬 개발 및 테스트)
Linux/Mac:
```bash
git clone https://github.com/comet-ml/opik.git
cd opik
./opik.sh
```
Windows:
```powershell
git clone https://github.com/comet-ml/opik.git
cd opik
powershell -ExecutionPolicy ByPass -c ".\\opik.ps1"
```
실행 후 [localhost:5173](http://localhost:5173) 접속. [자세한 배포 가이드](https://www.comet.com/docs/opik/self-host/local_deployment?from=llm&utm_source=opik&utm_medium=github&utm_content=self_host_link&utm_campaign=opik)

#### Kubernetes & Helm(대규모 운영)
[Kubernetes 설치 가이드](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik)

---

## 💻 Opik 클라이언트 SDK

Opik은 Python, TypeScript, Ruby(OpenTelemetry) SDK 및 REST API를 제공합니다. 자세한 내용은 [클라이언트 문서](apps/opik-documentation/documentation/fern/docs/reference/overview.mdx) 참고.

### Python SDK 빠른 시작
설치:
```bash
pip install opik
```
설정:
```bash
opik configure
```
또는 코드에서:
```python
opik.configure(use_local=True)
```
자세한 내용은 [Python SDK 문서](apps/opik-documentation/documentation/fern/docs/reference/python-sdk/)

---

### 📝 통합 추적 기록

Opik은 다양한 주요 프레임워크와 통합됩니다. [통합 문서](https://www.comet.com/docs/opik/tracing/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik) 참고.

미지원 프레임워크는 `track` 데코레이터 사용:
```python
import opik
opik.configure(use_local=True)
@opik.track
def my_llm_function(user_question: str) -> str:
    # LLM 코드
    return "Hello"
```

---

### 🧑‍⚖️ LLM 판정 지표

Python SDK에는 다양한 LLM 판정 지표가 내장되어 있습니다. [지표 문서](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_2_link&utm_campaign=opik) 참고.

예시:
```python
from opik.evaluation.metrics import Hallucination
metric = Hallucination()
score = metric.score(
    input="프랑스의 수도는 어디입니까?",
    output="파리",
    context=["프랑스는 유럽에 위치한 국가입니다."]
)
print(score)
```

---

### 🔍 LLM 애플리케이션 평가

Opik은 [데이터셋](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_2_link&utm_campaign=opik) 및 [실험](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=experiments_link&utm_campaign=opik) 기반 개발 평가, [PyTest 통합](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_2_link&utm_campaign=opik) 기반 CI/CD 연동을 지원합니다.

---

## ⭐ GitHub에 Star를 눌러주세요

유용하다면 Star를 눌러주세요! 여러분의 응원이 큰 힘이 됩니다.

---

## 🤝 기여 방법

[버그 제보](https://github.com/comet-ml/opik/issues), [기능 요청](https://github.com/comet-ml/opik/issues), 문서 리뷰 및 PR, 글 작성, 인기 기능 요청 지지 등 다양한 방식으로 기여할 수 있습니다. 자세한 내용은 [기여 가이드](CONTRIBUTING.md) 참고.
