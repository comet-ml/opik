> Примечание: Этот файл был переведен автоматически. Улучшения перевода приветствуются!

<div align="center"><b><a href="README.md">English</a> | <a href="readme_CN.md">简体中文</a> | <a href="readme_JP.md">日本語</a> | <a href="readme_PT_BR.md">Português (Brasil)</a> | <a href="readme_KO.md">한국어</a><br><a href="readme_ES.md">Español</a> | <a href="readme_FR.md">Français</a> | <a href="readme_DE.md">Deutsch</a> | <a href="readme_RU.md">Русский</a> | <a href="readme_AR.md">العربية</a> | <a href="readme_HI.md">हिन्दी</a> | <a href="readme_TR.md">Türkçe</a></b></div>


<h1 align="center" style="border-bottom: none">
<div>
<a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=header_img&utm_campaign=opik"><picture>
<source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/logo-dark-mode.svg">
<source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg">
<img alt="Логотип Comet Opik" src="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg" width="200" />
</picture></a>
<br>
Опик
</div>
</h1>
<h2 align="center" style="border-bottom: none">Наблюдение, оценка и оптимизация ИИ с открытым исходным кодом</h2>
<p align="center">
Opik помогает вам создавать, тестировать и оптимизировать приложения генеративного искусственного интеллекта, которые будут работать лучше, от прототипа до производства.  От чат-ботов RAG до помощников по написанию кода и сложных агентских систем — Opik обеспечивает комплексное отслеживание, оценку, а также автоматические подсказки и оптимизацию инструментов, чтобы исключить догадки при разработке ИИ.
</p>

<div align="center">

[![Python SDK](https://img.shields.io/pypi/v/opik)](https://pypi.org/project/opik/)
[![Лицензия](https://img.shields.io/github/license/comet-ml/opik)](https://github.com/comet-ml/opik/blob/main/LICENSE)
[![Build](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml/badge.svg)](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml)

<!-- [![Быстрый старт](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/opik_quickstart.ipynb) -->

</div>

<p align="center">
<a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=website_button&utm_campaign=opik"><b>Веб-сайт</b></a> •
<a href="https://chat.comet.com"><b>Сообщество Slack</b></a> •
<a href="https://x.com/Cometml"><b>Твиттер</b></a> •
<a href="https://www.comet.com/docs/opik/changelog"><b>Журнал изменений</b></a> •
<a href="https://www.comet.com/docs/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=docs_button&utm_campaign=opik"><b>Документация</b></a>
</p>

<div align="center" style="margin-top: 1em; Margin-bottom: 1em;">
<a href="#-what-is-opik">🚀 Что такое Opik?</a> • <a href="#%EF%B8%8F-opik-server-installation">🛠️ Установка сервера Opik</a> • <a href="#-opik-client-sdk">💻 Opik Client SDK</a> • <a href="#-logging-traces-with-integrations">📝 Регистрация трассировок</a><br>
<a href="#-llm-as-a-judge-metrics">🧑‍⚖️ LLM как судья</a> • <a href="#-evaluating-your-llm-application">🔍 Оценка вашей заявки</a> • <a href="#-star-us-on-github">⭐ Отметьте нас</a> • <a href="#-contributing">🤝 Содействие</a>
</div>

<br>

[![Скриншот платформы Opik (миниатюра)](readme-thumbnail-new.png)](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=readme_banner&utm_campaign=opik)

<a id="-what-is-opik"></a>
## 🚀 Что такое Опик?

Opik (созданный [Comet](https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=what_is_opik_link&utm_campaign=opik)) — это платформа с открытым исходным кодом, предназначенная для оптимизации всего жизненного цикла приложений LLM. Это дает разработчикам возможность оценивать, тестировать, отслеживать и оптимизировать свои модели и агентные системы. Ключевые предложения включают в себя:

- **Комплексное наблюдение**: глубокое отслеживание звонков LLM, регистрация разговоров и активности агентов.
- **Расширенная оценка**: надежная оперативная оценка, LLM в качестве судьи и управление экспериментом.
- **Готовность к производству**: масштабируемые панели мониторинга и онлайн-правила оценки для производства.
- **Оптимизатор агента Opik**: специальный SDK и набор оптимизаторов для улучшения подсказок и агентов.
- **Opik Guardrails**: функции, которые помогут вам реализовать безопасные и ответственные методы работы с искусственным интеллектом.

<br>

Ключевые возможности включают в себя:

- **Разработка и отслеживание:**
- Отслеживайте все вызовы и трассировки LLM с подробным контекстом во время разработки и производства ([Quickstart](https://www.comet.com/docs/opik/quickstart/?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_link&utm_campaign=opik)).
- Обширная интеграция сторонних разработчиков для удобства наблюдения: плавная интеграция с постоянно растущим списком платформ, встроенная поддержка многих крупнейших и самых популярных из них (включая недавние дополнения, такие как **Google ADK**, **Autogen** и **Flowise AI**). ([Интеграции](https://www.comet.com/docs/opik/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik))
- Аннотируйте трассировки и интервалы с помощью оценок обратной связи с помощью [Python SDK](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link&utm_campaign=opik) или [UI](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-through-the-ui?from=llm&utm_source=opik&utm_medium=github&utm_content=ui_link&utm_campaign=opik).
- Экспериментируйте с подсказками и моделями на [Площадке подсказок](https://www.comet.com/docs/opik/prompt_engineering/playground).

- **Оценка и тестирование**:
- Автоматизируйте оценку заявки на получение LLM с помощью [Datasets](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_link&utm_campaign=opik) и [Эксперименты](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=eval_link&utm_campaign=opik).
- Используйте мощные показатели LLM в качестве судьи для сложных задач, таких как [обнаружение галлюцинаций](https://www.comet.com/docs/opik/evaluation/metrics/hallucination/?from=llm&utm_source=opik&utm_medium=github&utm_content=hallucination_link&utm_campaign=opik), [модерация](https://www.comet.com/docs/opik/evaluation/metrics/moderation/?from=llm&utm_source=opik&utm_medium=github&utm_content=moderation_link&utm_campaign=opik) и оценка RAG ([Ответить Релевантность](https://www.comet.com/docs/opik/evaluation/metrics/answer_relevance/?from=llm&utm_source=opik&utm_medium=github&utm_content=alex_link&utm_campaign=opik), [Контекст Точность](https://www.comet.com/docs/opik/evaluation/metrics/context_precision/?from=llm&utm_source=opik&utm_medium=github&utm_content=context_link&utm_campaign=opik)).
- Интегрируйте оценки в свой конвейер CI/CD с помощью нашей [интеграции PyTest](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_link&utm_campaign=opik).

- **Мониторинг и оптимизация производства**:
- Регистрируйте большие объемы производственных трассировок: Opik рассчитан на масштабирование (более 40 млн трассировок в день).
- Отслеживайте оценки отзывов, количество трассировок и использование токенов с течением времени на [панели управления Opik](https://www.comet.com/docs/opik/production/production_monitoring/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik).
- Используйте [Правила онлайн-оценки](https://www.comet.com/docs/opik/production/rules/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik) с показателями LLM-as-a-Judge для выявления производственных проблем.
- Используйте **Opik Agent Optimizer** и **Opik Guardrails** для постоянного улучшения и защиты ваших приложений LLM в производстве.

> [!TIP]
> Если вы ищете функции, которых сегодня нет в Opik, создайте новый [Запрос на функцию](https://github.com/comet-ml/opik/issues/new/choose) 🚀

<br>

<a id="%EF%B8%8F-opik-server-installation"></a>
## 🛠️ Установка сервера Opik

Запустите свой сервер Opik за считанные минуты. Выберите вариант, который лучше всего соответствует вашим потребностям:

### Вариант 1: Облако Comet.com (самый простой и рекомендуемый)

Получите доступ к Opik мгновенно, без какой-либо настройки. Идеально подходит для быстрого запуска и простого обслуживания.

👉 [Создайте бесплатную учетную запись Comet](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=install_create_link&utm_campaign=opik)

### Вариант 2: Самостоятельное размещение Opik для полного контроля

Разверните Opik в своей среде. Выбирайте между Docker для локальных настроек или Kubernetes для масштабируемости.

#### Самостоятельный хостинг с Docker Compose (для локальной разработки и тестирования)

Это самый простой способ запустить локальный экземпляр Opik. Обратите внимание на новый скрипт установки `./opik.sh`:

В среде Linux или Mac:

```bash
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the repository
cd opik

# Start the Opik platform
./opik.sh
```

В среде Windows:

```powershell
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the repository
cd opik

# Start the Opik platform
powershell -ExecutionPolicy ByPass -c ".\\opik.ps1"
```

**Профили услуг для разработки**

Скрипты установки Opik теперь поддерживают профили сервисов для разных сценариев разработки:

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

Используйте параметры `--help` или `--info` для устранения проблем. Dockerfiles теперь обеспечивает запуск контейнеров от имени пользователя без полномочий root для повышения безопасности. Когда все будет готово, вы можете посетить [localhost:5173](http://localhost:5173) в своем браузере! Подробные инструкции см. в [Руководстве по локальному развертыванию](https://www.comet.com/docs/opik/self-host/local_deployment?from=llm&utm_source=opik&utm_medium=github&utm_content=self_host_link&utm_campaign=opik).

#### Самостоятельный хостинг с Kubernetes и Helm (для масштабируемых развертываний)

Для производственных или крупномасштабных локальных развертываний Opik можно установить в кластере Kubernetes с помощью нашей диаграммы Helm. Нажмите на значок, чтобы просмотреть полное [Руководство по установке Kubernetes с помощью Helm](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik).

[![Kubernetes](https://img.shields.io/badge/Kubernetes-%23326ce5.svg?&logo=kubernetes&logoColor=white)](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik)

> [!IMPORTANT]
> **Изменения в версии 1.7.0**: проверьте [журнал изменений](https://github.com/comet-ml/opik/blob/main/CHANGELOG.md) на наличие важных обновлений и критических изменений.

<a id="-opik-client-sdk"></a>
## 💻 Клиентский SDK Opik

Opik предоставляет набор клиентских библиотек и REST API для взаимодействия с сервером Opik. Сюда входят SDK для Python, TypeScript и Ruby (через OpenTelemetry), обеспечивающие плавную интеграцию в ваши рабочие процессы. Подробные ссылки на API и SDK см. в [Справочной документации по клиенту Opik](https://www.comet.com/docs/opik/reference/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=reference_link&utm_campaign=opik).

### Python SDK: быстрое начало работы

Чтобы начать работу с Python SDK:

Установите пакет:

```bash
# install using pip
pip install opik

# or install with uv
uv pip install opik
```

Настройте SDK Python, выполнив команду opik configure, которая запросит у вас адрес сервера Opik (для автономных экземпляров) или ключ API и рабочую область (для Comet.com):

```bash
opik configure
```

> [!TIP]
> Вы также можете вызвать `opik.configure(use_local=True)` из своего кода Python, чтобы настроить SDK для запуска на локальной локальной установке, или предоставить ключ API и сведения о рабочей области непосредственно для Comet.com. Дополнительные параметры конфигурации см. в [документации Python SDK](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=python_sdk_docs_link&utm_campaign=opik).

Теперь вы готовы начать регистрацию трассировок с помощью [Python SDK](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link2&utm_campaign=opik).

<a id="-logging-traces-with-integrations"></a>
### 📝 Регистрация трассировок с помощью интеграций

Самый простой способ регистрировать трассировки — использовать одну из наших прямых интеграций. Opik поддерживает широкий спектр платформ, включая недавние дополнения, такие как **Google ADK**, **Autogen**, **AG2** и **Flowise AI**:

| Интеграция | Описание | Документация |
| --------------------- | --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| АДК | Журнал трассировки для пакета разработки агента Google (ADK) | [Документация](https://www.comet.com/docs/opik/integrations/adk?utm_source=opik&utm_medium=github&utm_content=google_adk_link&utm_campaign=opik) |
| АГ2 | Журнал трассировки вызовов AG2 LLM | [Документация](https://www.comet.com/docs/opik/integrations/ag2?utm_source=opik&utm_medium=github&utm_content=ag2_link&utm_campaign=opik) |
| AIлюкс | Журнал трассировки вызовов aisuite LLM | [Документация](https://www.comet.com/docs/opik/integrations/aisuite?utm_source=opik&utm_medium=github&utm_content=aisuite_link&utm_campaign=opik) |
| Агно | Записывать в журнал вызовы инфраструктуры оркестрации агентов Agno | [Документация](https://www.comet.com/docs/opik/integrations/agno?utm_source=opik&utm_medium=github&utm_content=agno_link&utm_campaign=opik) |
| Антропный | Журнал трассировки вызовов Anthropic LLM | [Документация](https://www.comet.com/docs/opik/integrations/anthropic?utm_source=opik&utm_medium=github&utm_content=anthropic_link&utm_campaign=opik) |
| Автоген | Журнал трассировки для агентских рабочих процессов Autogen | [Документация](https://www.comet.com/docs/opik/integrations/autogen?utm_source=opik&utm_medium=github&utm_content=autogen_link&utm_campaign=opik) |
| Коренная порода | Журнал трассировки вызовов Amazon Bedrock LLM | [Документация](https://www.comet.com/docs/opik/integrations/bedrock?utm_source=opik&utm_medium=github&utm_content=bedrock_link&utm_campaign=opik) |
| BeeAI (Python) | Журнал трассировки вызовов среды агента BeeAI Python | [Документация](https://www.comet.com/docs/opik/integrations/beeai?utm_source=opik&utm_medium=github&utm_content=beeai_link&utm_campaign=opik) |
| BeeAI (TypeScript) | Журнал трассировки вызовов структуры агента BeeAI TypeScript | [Документация](https://www.comet.com/docs/opik/integrations/beeai-typescript?utm_source=opik&utm_medium=github&utm_content=beeai_typescript_link&utm_campaign=opik) |
| БайтПлюс | Журнал трассировки вызовов BytePlus LLM | [Документация](https://www.comet.com/docs/opik/integrations/byteplus?utm_source=opik&utm_medium=github&utm_content=byteplus_link&utm_campaign=opik) |
| Рабочие Cloudflare AI | Журнал трассировки вызовов AI Cloudflare Workers | [Документация](https://www.comet.com/docs/opik/integrations/cloudflare-workers-ai?utm_source=opik&utm_medium=github&utm_content=cloudflare_workers_ai_link&utm_campaign=opik) |
| Согласовано | Журнал трассировки вызовов Cohere LLM | [Документация](https://www.comet.com/docs/opik/integrations/cohere?utm_source=opik&utm_medium=github&utm_content=cohere_link&utm_campaign=opik) |
| CrewAI | Журнал трассировки вызовов CrewAI | [Документация](https://www.comet.com/docs/opik/integrations/crewai?utm_source=opik&utm_medium=github&utm_content=crewai_link&utm_campaign=opik) |
| Курсор | Журнал трассировки разговоров с курсором | [Документация](https://www.comet.com/docs/opik/integrations/cursor?utm_source=opik&utm_medium=github&utm_content=cursor_link&utm_campaign=opik) |
| ДипСик | Журнал трассировки вызовов DeepSeek LLM | [Документация](https://www.comet.com/docs/opik/integrations/deepseek?utm_source=opik&utm_medium=github&utm_content=deepseek_link&utm_campaign=opik) |
| Диди | Журнал трассировки запусков агента Dify | [Документация](https://www.comet.com/docs/opik/integrations/dify?utm_source=opik&utm_medium=github&utm_content=dify_link&utm_campaign=opik) |
| ДСПИ | Журнал трассировки запусков DSPy | [Документация](https://www.comet.com/docs/opik/integrations/dspy?utm_source=opik&utm_medium=github&utm_content=dspy_link&utm_campaign=opik) |
| Фейерверк ИИ | Журнал трассировки вызовов Fireworks AI LLM | [Документация](https://www.comet.com/docs/opik/integrations/fireworks-ai?utm_source=opik&utm_medium=github&utm_content=fireworks_ai_link&utm_campaign=opik) |
| Флоуиз ИИ | Журнал трассировки для визуального конструктора LLM Flowise AI | [Документация](https://www.comet.com/docs/opik/integrations/flowise?utm_source=opik&utm_medium=github&utm_content=flowise_link&utm_campaign=opik) |
| Близнецы (Питон) | Журнал отслеживания звонков Google Gemini LLM | [Документация](https://www.comet.com/docs/opik/integrations/gemini?utm_source=opik&utm_medium=github&utm_content=gemini_link&utm_campaign=opik) |
| Близнецы (TypeScript) | Журнал трассировки вызовов Google Gemini TypeScript SDK | [Документация](https://www.comet.com/docs/opik/integrations/gemini-typescript?utm_source=opik&utm_medium=github&utm_content=gemini_typescript_link&utm_campaign=opik) |
| Грок | Журнал отслеживания вызовов Groq LLM | [Документация](https://www.comet.com/docs/opik/integrations/groq?utm_source=opik&utm_medium=github&utm_content=groq_link&utm_campaign=opik) |
| Ограждения | Журнал трассировки проверок Guardrails AI | [Документация](https://www.comet.com/docs/opik/integrations/guardrails-ai?utm_source=opik&utm_medium=github&utm_content=guardrails_link&utm_campaign=opik) |
| стог сена | Журнал трассировки вызовов Haystack | [Документация](https://www.comet.com/docs/opik/integrations/haystack?utm_source=opik&utm_medium=github&utm_content=haystack_link&utm_campaign=opik) |
| гавань | Журналы оценочных испытаний производительности Harbour | [Документация](https://www.comet.com/docs/opik/integrations/harbor?utm_source=opik&utm_medium=github&utm_content=harbor_link&utm_campaign=opik) |
| Инструктор | Записывайте журналы вызовов LLM, сделанных с помощью Instructor | [Документация](https://www.comet.com/docs/opik/integrations/instructor?utm_source=opik&utm_medium=github&utm_content=instructor_link&utm_campaign=opik) |
| Лангчейн (Python) | Журнал трассировки вызовов LangChain LLM | [Документация](https://www.comet.com/docs/opik/integrations/langchain?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik) |
| Лангчейн (JS/TS) | Журнал трассировки вызовов LangChain JavaScript/TypeScript | [Документация](https://www.comet.com/docs/opik/integrations/langchainjs?utm_source=opik&utm_medium=github&utm_content=langchainjs_link&utm_campaign=opik) |
| Лангграф | Журнал трассировки выполнения LangGraph | [Документация](https://www.comet.com/docs/opik/integrations/langgraph?utm_source=opik&utm_medium=github&utm_content=langgraph_link&utm_campaign=opik) |
| Лангфлоу | Трассировки журналов для визуального конструктора искусственного интеллекта Langflow | [Документация](https://www.comet.com/docs/opik/integrations/langflow?utm_source=opik&utm_medium=github&utm_content=langflow_link&utm_campaign=opik) |
| ЛайтLLM | Журнал трассировки вызовов модели LiteLLM | [Документация](https://www.comet.com/docs/opik/integrations/litellm?utm_source=opik&utm_medium=github&utm_content=litellm_link&utm_campaign=opik) |
| Агенты LiveKit | Журнал трассировки вызовов среды AI-агента LiveKit Agents | [Документация](https://www.comet.com/docs/opik/integrations/livekit?utm_source=opik&utm_medium=github&utm_content=livekit_link&utm_campaign=opik) |
| ЛамаИндекс | Журнал трассировки вызовов LlamaIndex LLM | [Документация](https://www.comet.com/docs/opik/integrations/llama_index?utm_source=opik&utm_medium=github&utm_content=llama_index_link&utm_campaign=opik) |
| Мастра | Журнал трассировки вызовов среды рабочих процессов Mastra AI | [Документация](https://www.comet.com/docs/opik/integrations/mastra?utm_source=opik&utm_medium=github&utm_content=mastra_link&utm_campaign=opik) |
| Microsoft Agent Framework (Python) | Журнал трассировки вызовов Microsoft Agent Framework | [Документация](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework?utm_source=opik&utm_medium=github&utm_content=agent_framework_link&utm_campaign=opik) |
| Microsoft Agent Framework (.NET) | Журнал трассировки вызовов Microsoft Agent Framework .NET | [Документация](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework-dotnet?utm_source=opik&utm_medium=github&utm_content=agent_framework_dotnet_link&utm_campaign=opik) |
| Мистраль ИИ | Журнал отслеживания вызовов Mistral AI LLM | [Документация](https://www.comet.com/docs/opik/integrations/mistral?utm_source=opik&utm_medium=github&utm_content=mistral_link&utm_campaign=opik) |
| н8н | Трассировки журналов выполнения рабочих процессов n8n | [Документация](https://www.comet.com/docs/opik/integrations/n8n?utm_source=opik&utm_medium=github&utm_content=n8n_link&utm_campaign=opik) |
| Новита АИ | Журнал отслеживания звонков Novita AI LLM | [Документация](https://www.comet.com/docs/opik/integrations/novita-ai?utm_source=opik&utm_medium=github&utm_content=novita_ai_link&utm_campaign=opik) |
| Оллама | Журнал отслеживания звонков Ollama LLM | [Документация](https://www.comet.com/docs/opik/integrations/ollama?utm_source=opik&utm_medium=github&utm_content=ollama_link&utm_campaign=opik) |
| OpenAI (Python) | Журнал трассировки вызовов OpenAI LLM | [Документация](https://www.comet.com/docs/opik/integrations/openai?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik) |
| OpenAI (JS/TS) | Журнал трассировки вызовов OpenAI JavaScript/TypeScript | [Документация](https://www.comet.com/docs/opik/integrations/openai-typescript?utm_source=opik&utm_medium=github&utm_content=openai_typescript_link&utm_campaign=opik) |
| Агенты OpenAI | Журнал трассировки вызовов OpenAI Agents SDK | [Документация](https://www.comet.com/docs/opik/integrations/openai_agents?utm_source=opik&utm_medium=github&utm_content=openai_agents_link&utm_campaign=opik) |
| OpenClaw              | Log traces for OpenClaw agent runs                  | [Documentation](https://www.comet.com/docs/opik/integrations/openclaw?utm_source=opik&utm_medium=github&utm_content=openclaw_link&utm_campaign=opik) |
| OpenRouter | Журнал трассировки вызовов OpenRouter LLM | [Документация](https://www.comet.com/docs/opik/integrations/openrouter?utm_source=opik&utm_medium=github&utm_content=openrouter_link&utm_campaign=opik) |
| Открытая телеметрия | Журнал трассировки вызовов, поддерживаемых OpenTelemetry | [Документация](https://www.comet.com/docs/opik/tracing/opentelemetry/overview?utm_source=opik&utm_medium=github&utm_content=opentelemetry_link&utm_campaign=opik) |
| OpenWebUI | Журнал трассировки диалогов OpenWebUI | [Документация](https://www.comet.com/docs/opik/integrations/openwebui?utm_source=opik&utm_medium=github&utm_content=openwebui_link&utm_campaign=opik) |
| Трубка | Журнал трассировки вызовов голосового агента Pipecat в реальном времени | [Документация](https://www.comet.com/docs/opik/integrations/pipecat?utm_source=opik&utm_medium=github&utm_content=pipecat_link&utm_campaign=opik) |
| Предибаза | Журнал трассировки вызовов Predibase LLM | [Документация](https://www.comet.com/docs/opik/integrations/predibase?utm_source=opik&utm_medium=github&utm_content=predibase_link&utm_campaign=opik) |
| Пидантический ИИ | Журнал трассировки вызовов агента PydanticAI | [Документация](https://www.comet.com/docs/opik/integrations/pydantic-ai?utm_source=opik&utm_medium=github&utm_content=pydantic_ai_link&utm_campaign=opik) |
| Раги | Журнал трассировки оценок Ragas | [Документация](https://www.comet.com/docs/opik/integrations/ragas?utm_source=opik&utm_medium=github&utm_content=ragas_link&utm_campaign=opik) |
| Семантическое ядро ​​| Журнал трассировки вызовов семантического ядра Microsoft | [Документация](https://www.comet.com/docs/opik/integrations/semantic-kernel?utm_source=opik&utm_medium=github&utm_content=semantic_kernel_link&utm_campaign=opik) |
| Смолагенты | Следы журналов для агентов Смолагентс | [Документация](https://www.comet.com/docs/opik/integrations/smolagents?utm_source=opik&utm_medium=github&utm_content=smolagents_link&utm_campaign=opik) |
| Весенний ИИ | Журнал трассировки вызовов среды Spring AI | [Документация](https://www.comet.com/docs/opik/integrations/spring-ai?utm_source=opik&utm_medium=github&utm_content=spring_ai_link&utm_campaign=opik) |
| Агенты прядей | Журнал трассировки вызовов агентов Strands | [Документация](https://www.comet.com/docs/opik/integrations/strands-agents?utm_source=opik&utm_medium=github&utm_content=strands_agents_link&utm_campaign=opik) |
| Вместе ИИ | Записывать в журнал звонки Together AI LLM | [Документация](https://www.comet.com/docs/opik/integrations/together-ai?utm_source=opik&utm_medium=github&utm_content=together_ai_link&utm_campaign=opik) |
| Vercel AI SDK | Журнал трассировки вызовов Vercel AI SDK | [Документация](https://www.comet.com/docs/opik/integrations/vercel-ai-sdk?utm_source=opik&utm_medium=github&utm_content=vercel_ai_sdk_link&utm_campaign=opik) |
| ВольтАгент | Журнал трассировки вызовов инфраструктуры агента VoltAgent | [Документация](https://www.comet.com/docs/opik/integrations/voltagent?utm_source=opik&utm_medium=github&utm_content=voltagent_link&utm_campaign=opik) |
| ВатсонX | Журнал трассировки вызовов IBM watsonx LLM | [Документация](https://www.comet.com/docs/opik/integrations/watsonx?utm_source=opik&utm_medium=github&utm_content=watsonx_link&utm_campaign=opik) |
| xAI Грок | Журнал трассировки вызовов xAI Grok LLM | [Документация](https://www.comet.com/docs/opik/integrations/xai-grok?utm_source=opik&utm_medium=github&utm_content=xai_grok_link&utm_campaign=opik) |

> [!TIP]
> Если используемая вами платформа не указана выше, смело [откройте вопрос](https://github.com/comet-ml/opik/issues) или отправьте запрос на интеграцию.

Если вы не используете ни одну из вышеперечисленных платформ, вы также можете использовать декоратор функции track для [регистрации трассировок](https://www.comet.com/docs/opik/tracing/log_traces/?from=llm&utm_source=opik&utm_medium=github&utm_content=traces_link&utm_campaign=opik):

```python
import opik

opik.configure(use_local=True) # Run locally

@opik.track
def my_llm_function(user_question: str) -> str:
    # Your LLM code here

    return "Hello"
```

> [!TIP]
> Декоратор треков можно использовать в сочетании с любой из наших интеграций, а также для отслеживания вызовов вложенных функций.

<a id="-llm-as-a-judge-metrics"></a>
### 🧑‍⚖️ LLM как метрика судьи

Python Opik SDK включает в себя ряд показателей LLM в качестве оценочных показателей, которые помогут вам оценить ваше приложение LLM. Подробную информацию об этом можно найти в [документации по метрикам](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_2_link&utm_campaign=opik).

Чтобы использовать их, просто импортируйте соответствующую метрику и используйте функцию «score»:

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

Opik также включает в себя ряд готовых эвристических показателей, а также возможность создавать свои собственные. Подробную информацию об этом можно найти в [документации по метрикам](https://www.comet.com/docs/opik/evaluation/metrics/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_3_link&utm_campaign=opik).

<a id="-evaluating-your-llm-application"></a>
### 🔍 Оценка ваших заявок на получение степени LLM

Opik позволяет вам оценить ваше приложение LLM во время разработки через [Datasets](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_2_link&utm_campaign=opik) и [Эксперименты](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=experiments_link&utm_campaign=opik). Панель мониторинга Opik предлагает расширенные диаграммы для экспериментов и улучшенную обработку больших кривых. Вы также можете запускать оценки в рамках своего конвейера CI/CD, используя нашу [интеграцию PyTest](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_2_link&utm_campaign=opik).

<a id="-star-us-on-github"></a>
## ⭐ Отметьте нас на GitHub

Если вы найдете Opik полезным, поставьте нам звезду! Ваша поддержка помогает нам расширять наше сообщество и продолжать совершенствовать продукт.

[![Диаграмма звездной истории](https://api.star-history.com/svg?repos=comet-ml/opik&type=Date)](https://github.com/comet-ml/opik)

<a id="-contributing"></a>
## 🤝 Вносим свой вклад

Есть много способов внести свой вклад в Opik:

- Отправьте [отчеты об ошибках](https://github.com/comet-ml/opik/issues) и [запросы функций](https://github.com/comet-ml/opik/issues).
– Просмотрите документацию и отправьте [запросы на включение](https://github.com/comet-ml/opik/pulls), чтобы улучшить ее.
- Говорить или писать об Опике и [сообщать нам об этом](https://chat.comet.com)
– Голосование за [запросы популярных функций](https://github.com/comet-ml/opik/issues?q=is%3Aissue+is%3Aopen+label%3A%22enhancement%22), чтобы выразить свою поддержку.

Чтобы узнать больше о том, как внести свой вклад в Opik, ознакомьтесь с нашими [рекомендациями по участию](CONTRIBUTING.md).
