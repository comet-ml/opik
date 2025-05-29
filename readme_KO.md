> 주의: 이 파일은 기계 번역되었습니다. 번역 개선에 기여해 주시기를 환영합니다!
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
</h1>
<h2 align="center" style="border-bottom: none">오픈 소스 LLM 평가 플랫폼</h2>
<p align="center">
Opik은 LLM 시스템을 더 나은 품질로, 더 빠르게, 더 저렴하게 실행하도록 구축(build), 평가(evaluate), 최적화(optimize)를 지원합니다. RAG 챗봇에서 코드 어시스턴트, 복잡한 에이전트 파이프라인에 이르기까지, Opik은 포괄적인 트레이싱, 평가, 대시보드와 함께 <b>Opik Agent Optimizer</b>, <b>Opik Guardrails</b> 같은 강력한 기능을 제공하여 프로덕션 환경의 LLM 애플리케이션 개선과 보안을 돕습니다.
</p>

<div align="center">

[![Python SDK](https://img.shields.io/pypi/v/opik)](https://pypi.org/project/opik/)
[![License](https://img.shields.io/github/license/comet-ml/opik)](https://github.com/comet-ml/opik/blob/main/LICENSE)
[![Build](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml/badge.svg)](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml)
[![Bounties](https://img.shields.io/endpoint?url=https%3A%2F%2Falgora.io%2Fapi%2Fshields%2Fcomet-ml%2Fbounties%3Fstatus%3Dopen)](https://algora.io/comet-ml/bounties?status=open)
<!-- [![Quick Start](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/opik_quickstart.ipynb) -->

</div>

<p align="center">
    <a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=website_button&utm_campaign=opik"><b>웹사이트</b></a> •
    <a href="https://chat.comet.com"><b>Slack 커뮤니티</b></a> •
    <a href="https://x.com/Cometml"><b>Twitter</b></a> •
    <a href="https://www.comet.com/docs/opik/changelog"><b>변경 로그</b></a> •
    <a href="https://www.comet.com/docs/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=docs_button&utm_campaign=opik"><b>문서</b></a>
</p>

<div align="center" style="margin-top: 1em; margin-bottom: 1em;">
<a href="#🚀-opik이란">🚀 Opik이란?</a> • <a href="#🛠️-opik-서버-설치">🛠️ Opik 서버 설치</a> • <a href="#💻-opik-클라이언트-sdk">💻 Opik 클라이언트 SDK</a> • <a href="#📝-트레이스-로깅-및-통합">📝 트레이스 로깅 및 통합</a><br>
<a href="#🧑‍⚖️-llm을-심판으로">🧑‍⚖️ LLM을 심판으로</a> • <a href="#🔍-애플리케이션-평가">🔍 애플리케이션 평가</a> • <a href="#⭐-github-스타">⭐ GitHub 스타</a> • <a href="#🤝-기여하기">🤝 기여하기</a>
</div>

<br>

[![Opik platform screenshot (thumbnail)](readme-thumbnail-new.png)](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=readme_banner&utm_campaign=opik)

## 🚀 Opik이란?

Opik(Comet에서 개발한 오픈 소스 플랫폼으로, LLM 애플리케이션의 전 수명 주기를 간소화합니다)은 개발자가 모델과 에이전시 시스템을 평가, 테스트, 모니터링 및 최적화할 수 있도록 지원합니다. 주요 기능은 다음과 같습니다:
* **포괄적 관찰성(Observability)**: LLM 호출의 상세 트레이싱, 대화 기록, 에이전트 활동 추적
* **고급 평가(Evaluation)**: 강력한 프롬프트 평가, LLM-as-a-judge, 실험 관리
* **프로덕션 대응**: 확장 가능한 모니터링 대시보드 및 온라인 평가 규칙
* **Opik Agent Optimizer**: 프롬프트와 에이전트를 개선하는 전용 SDK 및 최적화 도구
* **Opik Guardrails**: 안전하고 책임 있는 AI 실천을 위한 기능

<br>

### 주요 기능

#### 개발 & 트레이싱
* 개발 및 프로덕션 환경에서 상세 컨텍스트와 함께 모든 LLM 호출 및 트레이스를 추적 ([클릭하여 빠른 시작](https://www.comet.com/docs/opik/quickstart/?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_link&utm_campaign=opik))
* Google ADK, Autogen, Flowise AI 등 다양한 서드파티 프레임워크 통합 지원 ([통합 목록](https://www.comet.com/docs/opik/tracing/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik))
* Python SDK 또는 UI를 사용해 트레이스와 스팬에 피드백 점수 주석 ([SDK 안내](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link&utm_campaign=opik))
* [Prompt Playground](https://www.comet.com/docs/opik/prompt_engineering/playground)에서 프롬프트와 모델 실험

#### 평가 & 테스트
* [데이터셋](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_link&utm_campaign=opik) 및 [실험](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=eval_link&utm_campaign=opik)을 통한 평가 자동화
* 환각 감지(hallucination detection), 콘텐츠 조정(moderation), RAG 평가(답변 관련성, 맥락 정확도) 등 LLM-as-a-judge 메트릭
* PyTest 통합으로 CI/CD 파이프라인에 평가 포함 ([PyTest 안내](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_link&utm_campaign=opik))

#### 프로덕션 모니터링 & 최적화
* 대용량 프로덕션 트레이스 로깅: 하루 4천만 건 이상 지원
* Opik 대시보드에서 피드백 점수, 트레이스 수, 토큰 사용량 모니터링 ([대시보드](https://www.comet.com/docs/opik/production/production_monitoring/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik))
* LLM-as-a-judge 기반 온라인 평가 규칙으로 프로덕션 이슈 탐지 ([규칙 안내](https://www.comet.com/docs/opik/production/rules/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik))
* Opik Agent Optimizer 및 Guardrails로 애플리케이션 지속 개선 및 보호

> [!TIP]  
> 아직 Opik에 없는 기능이 필요하시다면 새로운 [기능 요청](https://github.com/comet-ml/opik/issues/new/choose)을 제출해주세요! 🚀

<br>

## 🛠️ Opik 서버 설치

몇 분 만에 Opik 서버를 실행할 수 있습니다. 필요에 맞는 옵션을 선택하세요:

### 옵션 1: Comet.com 클라우드 (가장 간편 & 추천)
설정 없이도 즉시 Opik에 접속 가능. 빠른 시작과 유지보수 부담 해소에 최적.

👉 [무료 Comet 계정 생성](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=install_create_link&utm_campaign=opik)

### 옵션 2: 셀프 호스팅 (완전 제어)
자체 환경에 Opik 배포. 로컬 테스트는 Docker Compose, 대규모 운영은 Kubernetes & Helm 사용.

#### Docker Compose (로컬 개발 & 테스트)
가장 간단하게 로컬 Opik 인스턴스를 실행하는 방법. 새 `.opik.sh` 설치 스크립트 사용:

On Linux or Mac Enviroment:  
```bash
# Opik 리포지토리 클론
git clone https://github.com/comet-ml/opik.git

# 리포지토리로 이동
cd opik

# Opik 플랫폼 실행
./opik.sh
```

On Windows Enviroment:  
```powershell
# Opik 리포지토리 클론
git clone https://github.com/comet-ml/opik.git

# 리포지토리로 이동
cd opik

# Opik 플랫폼 실행
powershell -ExecutionPolicy ByPass -c ".\\opik.ps1"
```

`--help` 또는 `--info` 옵션으로 문제 해결 가능. Docker 컨테이너는 비루트 사용자 실행을 보장하여 보안 강화. 실행 후 브라우저에서 [localhost:5173](http://localhost:5173) 접속. 자세한 내용은 [로컬 배포 가이드](https://www.comet.com/docs/opik/self-host/local_deployment?from=llm&utm_source=opik&utm_medium=github&utm_content=self_host_link&utm_campaign=opik) 참조.

#### Kubernetes & Helm (대규모 운영)
프로덕션 및 대규모 셀프 호스팅 환경에 Helm 차트를 사용하여 Kubernetes 클러스터에 설치:

[![Kubernetes](https://img.shields.io/badge/Kubernetes-%23326ce5.svg?&logo=kubernetes&logoColor=white)](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik)

## 💻 Opik 클라이언트 SDK

Opik은 Opik 서버와 상호작용하는 클라이언트 라이브러리와 REST API를 제공합니다. Python, TypeScript, Ruby(OpenTelemetry 사용) SDK를 지원하여 워크플로에 쉽게 통합할 수 있습니다. API 및 SDK 참조는 [클라이언트 리퍼런스](apps/opik-documentation/documentation/fern/docs/reference/overview.mdx)에서 확인하세요.

### Python SDK 빠른 시작

패키지 설치:  
```bash
# pip로 설치
pip install opik

# 또는 uv로 설치
uv pip install opik
```

`opik configure` 명령어로 셀프 호스팅 서버 주소 또는 Comet.com용 API 키·워크스페이스를 입력:  
```bash
opik configure
```

> [!TIP]  
> Python 코드에서 `opik.configure(use_local=True)` 호출로 로컬 셀프 호스트 설정 가능. 또는 API 키·워크스페이스를 코드 내에 직접 지정할 수 있습니다. 자세한 옵션은 [Python SDK 문서](apps/opik-documentation/documentation/fern/docs/reference/python-sdk/) 참조.

이제 [Python SDK](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link2&utm_campaign=opik)로 트레이스 로깅 준비 완료!

### 📝 트레이스 로깅 및 통합

Direct integration 사용이 가장 간편합니다. Opik은 다음 프레임워크를 지원합니다:

| 통합               | 설명                                             | 문서                                                                                                                                                                                 | Colab 실행                                                                                                                                                                                                                       |
|--------------------|--------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **AG2**            | AG2 LLM 호출 트레이스                            | [문서](https://www.comet.com/docs/opik/tracing/integrations/ag2?utm_source=opik&utm_medium=github&utm_content=anthropic_link&utm_campaign=opik)                                         | (준비 중)                                                                                                                                                                                                                        |
| **aisuite**        | aisuite LLM 호출 트레이스                        | [문서](https://www.comet.com/docs/opik/tracing/integrations/aisuite?utm_source=opik&utm_medium=github&utm_content=anthropic_link&utm_campaign=opik)                                     | [![Colab 실행](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/aisuite.ipynb)    |
| **Anthropic**      | Anthropic LLM 호출 트레이스                      | [문서](https://www.comet.com/docs/opik/tracing/integrations/anthropic?utm_source=opik&utm_medium=github&utm_content=anthropic_link&utm_campaign=opik)                                 | [![Colab 실행](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/anthropic.ipynb)|
| **Autogen**        | Autogen 에이전시 워크플로우 트레이스             | [문서](https://www.comet.com/docs/opik/tracing/integrations/autogen?utm_source=opik&utm_medium=github&utm_content=autogen_link&utm_campaign=opik)                                        | (준비 중)                                                                                                                                                                                                                        |
| **Bedrock**        | Amazon Bedrock LLM 호출 트레이스                 | [문서](https://www.comet.com/docs/opik/tracing/integrations/bedrock?utm_source=opik&utm_medium=github&utm_content=bedrock_link&utm_campaign=opik)                                     | [![Colab 실행](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/bedrock.ipynb) |
| **CrewAI**         | CrewAI 호출 트레이스                              | [문서](https://www.comet.com/docs/opik/tracing/integrations/crewai?utm_source=opik&utm_medium=github&utm_content=crewai_link&utm_campaign=opik)                                         | [![Colab 실행](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/crewai.ipynb) |
| **DeepSeek**       | DeepSeek LLM 호출 트레이스                      | [문서](https://www.comet.com/docs/opik/tracing/integrations/deepseek?utm_source=opik&utm_medium=github&utm_content=deepseek_link&utm_campaign=opik)                                     | (준비 중)                                                                                                                                                                                                                        |
| **Dify**           | Dify 에이전시 실행 트레이스                     | [문서](https://www.comet.com/docs/opik/tracing/integrations/dify?utm_source=opik&utm_medium=github&utm_content=dspy_link&utm_campaign=opik)                                            | (준비 중)                                                                                                                                                                                                                        |
| **DSPy**           | DSPy 실행 트레이스                               | [문서](https://www.comet.com/docs/opik/tracing/integrations/dspy?utm_source=opik&utm_medium=github&utm_content=dspy_link&utm_campaign=opik)                                            | [![Colab 실행](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/dspy.ipynb) |
| **Flowise AI**     | Flowise AI 비주얼 LLM 앱 트레이스               | [문서](https://www.comet.com/docs/opik/tracing/integrations/flowise?utm_source=opik&utm_medium=github&utm_content=flowise_link&utm_campaign=opik)                                     | (네이티브 UI 통합, 문서 참조)                                                                                                                                                                                                    |
| **Gemini**         | Google Gemini LLM 호출 트레이스                 | [문서](https://www.comet.com/docs/opik/tracing/integrations/gemini?utm_source=opik&utm_medium=github&utm_content=gemini_link&utm_campaign=opik)                                       | [![Colab 실행](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/gemini.ipynb)   |
| **Google ADK**     | Google Agent Development Kit(ADK) 트레이스      | [문서](https://www.comet.com/docs/opik/tracing/integrations/google_adk?utm_source=opik&utm_medium=github&utm_content=google_adk_link&utm_campaign=opik)                                 | (준비 중)                                                                                                                                                                                                                        |
| **Groq**           | Groq LLM 호출 트레이스                          | [문서](https://www.comet.com/docs/opik/tracing/integrations/groq?utm_source=opik&utm_medium=github&utm_content=groq_link&utm_campaign=opik)                                           | [![Colab 실행](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/groq.ipynb)     |
| **Guardrails**     | Guardrails AI 검증 트레이스                     | [문서](https://www.comet.com/docs/opik/tracing/integrations/guardrails/?utm_source=opik&utm_medium=github&utm_content=guardrails_link&utm_campaign=opik)                                | [![Colab 실행](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/guardrails-ai.ipynb) |
| **Haystack**       | Haystack 호출 트레이스                          | [문서](https://www.comet.com/docs/opik/tracing/integrations/haystack/?utm_source=opik&utm_medium=github&utm_content=haystack_link&utm_campaign=opik)                                 | [![Colab 실행](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/haystack.ipynb)  |
| **Instructor**     | Instructor LLM 호출 트레이스                  | [문서](https://www.comet.com/docs/opik/tracing/integrations/instructor/?utm_source=opik&utm_medium=github&utm_content=instructor_link&utm_campaign=opik)                              | [![Colab 실행](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/instructor.ipynb) |
| **LangChain**      | LangChain LLM 호출 트레이스                    | [문서](https://www.comet.com/docs/opik/tracing/integrations/langchain/?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik)                               | [![Colab 실행](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/langchain.ipynb) |
| **LangChain JS**   | LangChain JS 호출 트레이스                     | [문서](https://www.comet.com/docs/opik/tracing/integrations/langchainjs/?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik)                            | (준비 중)                                                                                                                                                                                                                        |
| **LangGraph**      | LangGraph 실행 트레이스                         | [문서](https://www.comet.com/docs/opik/tracing/integrations/langgraph/?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik)                             | [![Colab 실행](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/langgraph.ipynb) |
| **LiteLLM**        | LiteLLM 모델 호출 트레이스                     | [문서](https://www.comet.com/docs/opik/tracing/integrations/litellm/?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik)                                   | [![Colab 실행](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/litellm.ipynb)    |
| **LlamaIndex**     | LlamaIndex LLM 호출 트레이스                  | [문서](https://www.comet.com/docs/opik/tracing/integrations/llama_index?utm_source=opik&utm_medium=github&utm_content=llama_index_link&utm_campaign=opik)                           | [![Colab 실행](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/llama-index.ipynb)|
| **Ollama**         | Ollama LLM 호출 트레이스                       | [문서](https://www.comet.com/docs/opik/tracing/integrations/ollama?utm_source=opik&utm_medium=github&utm_content=ollama_link&utm_campaign=opik)                                   | [![Colab 실행](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/ollama.ipynb)    |
| **OpenAI**         | OpenAI LLM 호출 트레이스                      | [문서](https://www.comet.com/docs/opik/tracing/integrations/openai/?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik)                                    | [![Colab 실행](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/openai.ipynb)      |
| **OpenAI Agents**  | OpenAI Agents SDK 호출 트레이스               | [문서](https://www.comet.com/docs/opik/tracing/integrations/openai_agents/?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik)                           | (준비 중)                                                                                                                                                                                                                        |
| **OpenRouter**     | OpenRouter LLM 호출 트레이스                  | [문서](https://www.comet.com/docs/opik/tracing/openrouter/overview//?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik)                                    | (준비 중)                                                                                                                                                                                                                        |
| **OpenTelemetry**  | OpenTelemetry 지원 호출 트레이스              | [문서](https://www.comet.com/docs/opik/tracing/opentelemetry/overview//?from=llm&utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik)                       | (준비 중)                                                                                                                                                                                                                        |
| **Predibase**      | Predibase LLM 호출 트레이스                   | [문서](https://www.comet.com/docs/opik/tracing/integrations/predibase?utm_source=opik&utm_medium=github&utm_content=predibase_link&utm_campaign=opik)                             | [![Colab 실행](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/predibase.ipynb)       |
| **Pydantic AI**    | PydanticAI 에이전트 호출 트레이스             | [문서](https://www.comet.com/docs/opik/tracing/integrations/pydantic-ai?utm_source=opik&utm_medium=github&utm_content=predibase_link&utm_campaign=opik)                            | [![Colab 실행](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/pydantic-ai.ipynb) |
| **Ragas**          | Ragas 평가 트레이스                           | [문서](https://www.comet.com/docs/opik/tracing/integrations/ragas?utm_source=opik&utm_medium=github&utm_content=pydantic_ai_link&utm_campaign=opik)                                | [![Colab 실행](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/ragas.ipynb)        |
| **Smolagents**     | Smolagents 에이전트 호출 트레이스              | [문서](https://www.comet.com/docs/opik/tracing/integrations/smolagents?utm_source=opik&utm_medium=github&utm_content=smolagents_link&utm_campaign=opik)     | [![Open Quickstart In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/smolagents.ipynb)  |
| **Strands Agents** | Strands Agents 호출 트레이스                 | [문서](https://www.comet.com/docs/opik/tracing/integrations/strands-agents/?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik)                     | (준비 중)                                                                                                                                                                                                                        |
| **Vercel AI**      | Vercel AI SDK 호출 트레이스                   | [문서](https://www.comet.com/docs/opik/tracing/integrations/vercel-ai-sdk/?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik)                       | (준비 중)                                                                                                                                                                                                                        |
| **watsonx**        | IBM watsonx LLM 호출 트레이스                | [문서](https://www.comet.com/docs/opik/tracing/integrations/watsonx?utm_source=opik&utm_medium=github&utm_content=watsonx_link&utm_campaign=opik)                                 | [![Colab 실행](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/master/apps/opik-documentation/documentation/docs/cookbook/watsonx.ipynb)       |

> [!TIP]  
> 위 목록에 없는 프레임워크를 사용 중이라면 [Issue 열기](https://github.com/comet-ml/opik/issues) 또는 PR을 제출해주세요!

프레임워크 미사용 시 `track` 데코레이터로 트레이스 기록 가능 ([자세히 보기](https://www.comet.com/docs/opik/tracing/log_traces/?from=llm&utm_source=opik&utm_medium=github&utm_content=traces_link&utm_campaign=opik)):

```python
import opik

opik.configure(use_local=True)  # 로컬 실행

@opik.track
def my_llm_function(user_question: str) -> str:
    # LLM 코드 작성
    return "안녕하세요"
```

> [!TIP]  
> `track` 데코레이터는 통합과 함께 사용 가능하며, 중첩된 함수 호출도 추적합니다.

### 🧑‍⚖️ LLM을 심판으로

Python Opik SDK에는 LLM-as-a-judge 메트릭이 포함되어 있어 애플리케이션 평가에 유용합니다. 자세한 내용은 [메트릭 문서](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_2_link&utm_campaign=opik) 참조.

사용 예시:  
```python
from opik.evaluation.metrics import Hallucination

metric = Hallucination()
score = metric.score(
    input="프랑스의 수도는 어디인가요?",
    output="파리",
    context=["프랑스는 유럽의 국가입니다."]
)
print(score)
```

사전 구축된 휴리스틱 메트릭도 제공되며, 사용자 정의 메트릭 생성도 가능합니다. 자세한 내용은 [메트릭 문서](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_3_link&utm_campaign=opik) 확인.

### 🔍 애플리케이션 평가

개발 중 [데이터셋](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_2_link&utm_campaign=opik) 과 [실험](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=experiments_link&utm_campaign=opik)을 사용해 평가 가능. Opik 대시보드는 대규모 트레이스 처리와 실험 차트를 강화하며, CI/CD 통합에는 [PyTest](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_2_link&utm_campaign=opik)를 사용하세요.

## ⭐ GitHub 스타

Opik이 도움이 되었다면, GitHub에서 별을 눌러주세요! 커뮤니티 성장과 제품 개선에 큰 힘이 됩니다.

[![Star History Chart](https://api.star-history.com/svg?repos=comet-ml/opik&type=Date)](https://github.com/comet-ml/opik)

## 🤝 기여하기

Opik에 기여하는 방법:

* [버그 리포트](https://github.com/comet-ml/opik/issues) 및 [기능 요청](https://github.com/comet-ml/opik/issues) 제출  
* 문서 검토 및 [풀 리퀘스트](https://github.com/comet-ml/opik/pulls) 작성  
* Opik에 대한 발표나 글 작성 후 [알려주기](https://chat.comet.com)  
* 인기 있는 [기능 요청](https://github.com/comet-ml/opik/issues?q=is%3Aissue+is%3Aopen+label%3A%22enhancement%22)에 투표하기  

자세한 가이드는 [CONTRIBUTING.md](CONTRIBUTING.md)에서 확인하세요。
