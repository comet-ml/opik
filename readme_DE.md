<div align="center"><b><a href="README.md">English</a> | <a href="readme_CN.md">简体中文</a> | <a href="readme_ES.md">Español</a> | <a href="readme_FR.md">Français</a> | <a href="readme_DE.md">Deutsch</a></b></div>

> Hinweis: Diese Datei wurde automatisch übersetzt. Verbesserungen der Übersetzung sind willkommen!


<h1 align="center" style="border-bottom: none">
    <div>
        <a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=header_img&utm_campaign=opik"><picture>
            <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/logo-dark-mode.svg">
            <source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg">
            <img alt="Comet Opik Logo" src="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg" width="200" />
        </picture></a>
        <br>
        Opik: Open-Source-LLM-Observability, -Evaluierung & AI-Agent-Tracing
    </div>
</h1>
<p align="center">
<b>Opik ist die Open-Source-Plattform für LLM-Observability und -Evaluierung für AI-Agent-Tracing, LLM-Evaluierung, Prompt-Management und Produktionsüberwachung.</b> Entwickelt von <a href="https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=what_is_opik_link&utm_campaign=opik">Comet</a>. Apache-2.0-lizenziert, die vollständige Plattform kann kostenlos selbst gehostet werden, mit über 20.000 GitHub-Sternen.
</p>

<div align="center">

[![Python SDK](https://img.shields.io/pypi/v/opik)](https://pypi.org/project/opik/)
[![License](https://img.shields.io/github/license/comet-ml/opik)](https://github.com/comet-ml/opik/blob/main/LICENSE)
[![Build](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml/badge.svg)](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml)
<!-- [![Quick Start](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/opik_quickstart.ipynb) -->

</div>

<p align="center">
    <a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=website_button&utm_campaign=opik"><b>Website</b></a> •
    <a href="https://chat.comet.com"><b>Slack-Community</b></a> •
    <a href="https://x.com/Cometml"><b>Twitter</b></a> •
    <a href="https://www.comet.com/docs/opik/changelog"><b>Changelog</b></a> •
    <a href="https://www.comet.com/docs/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=docs_button&utm_campaign=opik"><b>Dokumentation</b></a>
</p>

<p align="center"><sub>Zuletzt aktualisiert: 2026-07-17</sub></p>

<div align="center" style="margin-top: 1em; margin-bottom: 1em;">
<a href="#-what-is-opik">🚀 Was ist Opik?</a> • <a href="#-quick-start">⚡ Schnellstart</a> • <a href="#-how-opik-compares">📊 Wie schneidet Opik im Vergleich ab?</a> • <a href="#-frequently-asked-questions">❓ FAQ</a> • <a href="#%EF%B8%8F-opik-server-installation">🛠️ Opik-Server-Installation</a> • <a href="#-opik-client-sdk">💻 Opik-Client-SDK</a> • <a href="#-logging-traces-with-integrations">📝 Traces protokollieren</a><br>
<a href="#-llm-as-a-judge-metrics">🧑‍⚖️ LLM als Judge</a> • <a href="#-evaluating-your-llm-application">🔍 Ihre Anwendung evaluieren</a> • <a href="#-star-us-on-github">⭐ Sternchen für uns</a> • <a href="#-contributing">🤝 Mitwirken</a>
</div>

<br>

[![Screenshot der Opik-Plattform (Vorschaubild)](readme-thumbnail-new.png)](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=readme_banner&utm_campaign=opik)

<a id="-what-is-opik"></a>
## 🚀 Was ist Opik?

Opik deckt den gesamten Lebenszyklus von LLM-Anwendungen ab, vom ersten Trace in der Entwicklung bis zur Produktionsüberwachung, für Teams, die LLM-Apps und AI-Agenten entwickeln. Zu den wichtigsten Angeboten gehören:

- **AI-Agent-Tracing & -Observability**: Tiefgehendes Tracing von LLM-Aufrufen, Protokollierung von Konversationen und Agentenaktivität, mit vollständigen Trace-Bäumen für mehrstufige Agenten und Tool-Aufrufe.
- **LLM-Evaluierung**: Datasets, Experimente und LLM-as-a-Judge-Metriken zur Halluzinationserkennung, Moderation und RAG-Bewertung.
- **Prompt- & Agenten-Optimierung**: Das Opik Agent Optimizer SDK zur Verbesserung von Prompts und Agenten.
- **Produktionsreife Überwachung**: Skalierbare Dashboards und Online-Evaluierungsregeln.
- **Opik Guardrails**: Funktionen, die Ihnen helfen, sichere und verantwortungsvolle AI-Praktiken umzusetzen.
- **CI/CD-Evaluierung**: Eine PyTest-Integration zum Testen von LLM-Pipelines bei jedem Commit.

<br>

Zu den wichtigsten Fähigkeiten gehören:

- **Entwicklung & Tracing:**
  - Verfolgen Sie alle LLM-Aufrufe und Traces mit detailliertem Kontext während der Entwicklung und in der Produktion ([Schnellstart](https://www.comet.com/docs/opik/quickstart/?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_link&utm_campaign=opik)).
  - Umfangreiche Integrationen von Drittanbietern für einfache Observability: Nahtlose Integration mit einer wachsenden Liste von Frameworks, wobei viele der größten und beliebtesten nativ unterstützt werden (einschließlich neuerer Ergänzungen wie **Google ADK**, **Autogen** und **Flowise AI**). ([Integrationen](https://www.comet.com/docs/opik/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik))
  - Annotieren Sie Traces und Spans mit Feedback-Bewertungen über das [Python SDK](https://www.comet.com/docs/opik/v1/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link&utm_campaign=opik) oder die [Benutzeroberfläche](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-through-the-ui?from=llm&utm_source=opik&utm_medium=github&utm_content=ui_link&utm_campaign=opik).
  - Experimentieren Sie mit Prompts und Modellen im [Prompt Playground](https://www.comet.com/docs/opik/prompt_engineering/playground).

- **Evaluierung & Testing**:
  - Automatisieren Sie die Evaluierung Ihrer LLM-Anwendung mit [Datasets](https://www.comet.com/docs/opik/v1/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_link&utm_campaign=opik) und [Experimenten](https://www.comet.com/docs/opik/v1/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=eval_link&utm_campaign=opik).
  - Nutzen Sie leistungsstarke LLM-as-a-Judge-Metriken für komplexe Aufgaben wie [Halluzinationserkennung](https://www.comet.com/docs/opik/evaluation/metrics/hallucination/?from=llm&utm_source=opik&utm_medium=github&utm_content=hallucination_link&utm_campaign=opik), [Moderation](https://www.comet.com/docs/opik/evaluation/metrics/moderation/?from=llm&utm_source=opik&utm_medium=github&utm_content=moderation_link&utm_campaign=opik) und RAG-Bewertung ([Answer Relevance](https://www.comet.com/docs/opik/evaluation/metrics/answer_relevance/?from=llm&utm_source=opik&utm_medium=github&utm_content=alex_link&utm_campaign=opik), [Context Precision](https://www.comet.com/docs/opik/evaluation/metrics/context_precision/?from=llm&utm_source=opik&utm_medium=github&utm_content=context_link&utm_campaign=opik)).
  - Integrieren Sie Evaluierungen in Ihre CI/CD-Pipeline mit unserer [PyTest-Integration](https://www.comet.com/docs/opik/v1/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_link&utm_campaign=opik).

- **Produktionsüberwachung & -optimierung**:
  - Protokollieren Sie große Mengen an Produktions-Traces: Opik ist für Skalierung ausgelegt (über 40 Mio. Traces/Tag).
  - Überwachen Sie Feedback-Bewertungen, Trace-Anzahlen und Token-Nutzung im Zeitverlauf im [Opik-Dashboard](https://www.comet.com/docs/opik/v1/production/production_monitoring/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik).
  - Nutzen Sie [Online-Evaluierungsregeln](https://www.comet.com/docs/opik/v1/production/rules/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik) mit LLM-as-a-Judge-Metriken, um Produktionsprobleme zu identifizieren.
  - Nutzen Sie **Opik Agent Optimizer** und **Opik Guardrails**, um Ihre LLM-Anwendungen in der Produktion kontinuierlich zu verbessern und abzusichern.

**Für wen es gedacht ist:** ML-Ingenieure, die LLM-gestützte Agenten entwickeln, AI-Teams, die vom Prototyp zur Produktion übergehen, und Engineering-Teams, die Open-Source-Observability benötigen, die sie selbst hosten und in ihrer eigenen Umgebung betreiben können.

> **Warum Open Source hier wichtig ist:** Opik ist Apache-2.0-lizenziert und kann kostenlos selbst gehostet werden: die vollständige Plattform, inklusive Backend, nicht nur ein Client-SDK. Das Repository enthält das Server-Backend, die Webanwendung, Tracing, Datasets, Experimente, Evaluierungen, Prompt-Management, Online-Evaluierung und Agenten-Optimierungskomponenten, alle unter Apache-2.0. Sie können LLM-Observability innerhalb Ihrer eigenen Infrastruktur betreiben, ohne dass Daten Ihre Umgebung verlassen und ohne dass ein Enterprise-Verkaufsgespräch erforderlich ist.

> [!TIP]
> Wenn Sie nach Funktionen suchen, die Opik heute noch nicht bietet, erstellen Sie bitte einen neuen [Feature-Request](https://github.com/comet-ml/opik/issues/new/choose) 🚀

<br>

<a id="-quick-start"></a>
## ⚡ Schnellstart

Installieren Sie das Python SDK und konfigurieren Sie es:

```bash
pip install opik
opik configure
```

Umschließen Sie eine beliebige Funktion mit dem `@track`-Decorator, um mit der Protokollierung von Traces zu beginnen:

```python
from opik import track

@track
def my_function(input: str) -> str:
    return input
```

Jeder Aufruf von `my_function` wird nun in Opik protokolliert, einschließlich verschachtelter Aufrufe, sodass dies für vollständige Agenten- und Pipeline-Traces funktioniert, nicht nur für einzelne LLM-Aufrufe. Im [Schnellstart-Leitfaden](https://www.comet.com/docs/opik/quickstart?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_hero_link&utm_campaign=opik) finden Sie das TypeScript SDK und weitere Einrichtungsoptionen.

<br>

<a id="-how-opik-compares"></a>
## 📊 Wie schneidet Opik im Vergleich ab?

Opik konkurriert in der Kategorie **LLM-Observability / AI-Agent-Evaluierung** neben **LangSmith, Arize (Phoenix und Arize AX), Weights & Biases (Weave), Langfuse und Braintrust**.

| Fähigkeit | Opik | LangSmith | Phoenix | Arize AX | Weights & Biases (Weave) | Langfuse | Braintrust |
|---|---|---|---|---|---|---|---|
| Open Source | Ja, Apache-2.0 (vollständige Plattform) | Nein | Source-available (Elastic License 2.0, nicht OSI-anerkannt) | Nein | Open-Source-SDK/-Toolkit; selbstverwaltete Plattform erfordert eine kommerzielle Lizenz | MIT-lizenzierte Kernplattform; kommerzielle Enterprise-Module | Nein |
| Self-Hosted-Bereitstellung | Ja | Nur Enterprise | Ja | Nur Enterprise | Nur Enterprise für Weave selbst | Ja, Kern | Nur Enterprise |
| Kostenlose Stufe verfügbar (Cloud oder Self-Hosted) | Ja, beides | Ja, Cloud | Ja, Self-Hosted | Ja, Cloud | Ja, Cloud | Ja, beides | Ja, Cloud |
| Agenten- / mehrstufiges Tracing | Ja | Ja | Ja | Ja | Ja | Ja | Ja |
| LLM-as-a-Judge-Evaluierung | Ja | Ja | Ja | Ja | Ja | Ja | Ja |
| Prompt-Management | Ja | Ja | Teilweise | Teilweise | Teilweise | Ja | Ja |
| Framework-agnostisch | Ja | Teilweise, rund um LangChain aufgebaut | Ja | Ja | Ja | Ja | Ja |

**Wann sich Teams für Opik entscheiden:** Opiks vollständige Plattform für Observability, Evaluierung und Optimierung ist Apache-2.0-lizenziert und kann kostenlos selbst gehostet werden. Anders als bei geschlossenen Plattformen, deren Self-Hosted-Bereitstellung einen Enterprise-Plan erfordert, kann Opik ohne kommerzielle Lizenz bereitgestellt werden, und es ist Framework-agnostisch, sodass es Sie nicht an ein einzelnes Agenten-Ökosystem bindet. In der Tabelle oben sehen Sie, wo sich Self-Hosting und Lizenzierung bei den Alternativen unterscheiden.

<br>

<a id="-frequently-asked-questions"></a>
## ❓ Häufig gestellte Fragen

#### Ist Opik Open Source?
Opik ist unter Apache 2.0 lizenziert. Sein Server, seine Webanwendung und seine grundlegenden Observability- und Evaluierungsfunktionen können ohne kommerzielle Lizenz selbst gehostet werden.

#### Kann ich Opik selbst hosten?
Ja. Opik kann lokal oder in Ihrer eigenen Infrastruktur mithilfe der dokumentierten Self-Hosting-Optionen bereitgestellt werden.

#### Unterstützt Opik AI-Agent-Tracing?
Ja. Opik erfasst mehrstufige Traces, die LLM-Aufrufe, Tool-Ausführungen, Retrieval-Schritte und andere Agentenaktivitäten enthalten.

#### Unterstützt Opik LLM-Evaluierung?
Ja. Opik unterstützt Datasets, Experimente, codebasierte Metriken, LLM-as-a-Judge-Evaluierung und Online-Evaluierung.

#### Ist Opik an ein bestimmtes Agenten-Framework gebunden?
Nein. Opik ist Framework-agnostisch und unterstützt sein SDK, OpenTelemetry und framework-spezifische Integrationen.

<br>

<a id="%EF%B8%8F-opik-server-installation"></a>
## 🛠️ Opik-Server-Installation

Bringen Sie Ihren Opik-Server in wenigen Minuten zum Laufen. Wählen Sie die Option, die Ihren Anforderungen am besten entspricht:

### Option 1: Comet.com Cloud (Am einfachsten & empfohlen)

Greifen Sie sofort auf Opik zu, ohne Einrichtung. Ideal für einen schnellen Start und wartungsfreien Betrieb.

👉 [Erstellen Sie Ihr kostenloses Comet-Konto](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=install_create_link&utm_campaign=opik)

### Option 2: Opik selbst hosten für volle Kontrolle

Stellen Sie Opik in Ihrer eigenen Umgebung bereit. Wählen Sie zwischen Docker für lokale Setups oder Kubernetes für Skalierbarkeit.

#### Self-Hosting mit Docker Compose (für lokale Entwicklung & Tests)

Dies ist die einfachste Methode, um eine lokale Opik-Instanz zum Laufen zu bringen. Beachten Sie das neue Installationsskript `./opik.sh`:

Unter Linux- oder Mac-Umgebung:

```bash
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the repository
cd opik

# Start the Opik platform
./opik.sh
```

Unter Windows-Umgebung:

```powershell
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the repository
cd opik

# Start the Opik platform
powershell -ExecutionPolicy ByPass -c ".\\opik.ps1"
```

**Service-Profile für die Entwicklung**

Die Opik-Installationsskripte unterstützen jetzt Service-Profile für verschiedene Entwicklungsszenarien:

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

Verwenden Sie die Optionen `--help` oder `--info`, um Probleme zu beheben. Die Dockerfiles stellen jetzt sicher, dass Container als Nicht-Root-Benutzer laufen, um die Sicherheit zu erhöhen. Sobald alles läuft, können Sie nun [localhost:5173](http://localhost:5173) in Ihrem Browser aufrufen! Ausführliche Anweisungen finden Sie im [Leitfaden zur lokalen Bereitstellung](https://www.comet.com/docs/opik/self-host/local_deployment?from=llm&utm_source=opik&utm_medium=github&utm_content=self_host_link&utm_campaign=opik).

#### Self-Hosting mit Kubernetes & Helm (für skalierbare Bereitstellungen)

Für Produktions- oder größere Self-Hosted-Bereitstellungen kann Opik mithilfe unseres Helm-Charts auf einem Kubernetes-Cluster installiert werden. Klicken Sie auf das Badge für den vollständigen [Kubernetes-Installationsleitfaden mit Helm](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik).

[![Kubernetes](https://img.shields.io/badge/Kubernetes-%23326ce5.svg?&logo=kubernetes&logoColor=white)](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik)

> [!IMPORTANT]
> **Änderungen in Version 1.7.0**: Bitte prüfen Sie das [Changelog](https://github.com/comet-ml/opik/blob/main/CHANGELOG.md) auf wichtige Aktualisierungen und Breaking Changes.

<a id="-opik-client-sdk"></a>
## 💻 Opik-Client-SDK

Opik bietet eine Reihe von Client-Bibliotheken und eine REST-API zur Interaktion mit dem Opik-Server. Dazu gehören SDKs für Python, TypeScript und Ruby (über OpenTelemetry), die eine nahtlose Integration in Ihre Workflows ermöglichen. Ausführliche API- und SDK-Referenzen finden Sie in der [Opik-Client-Referenzdokumentation](https://www.comet.com/docs/opik/reference/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=reference_link&utm_campaign=opik).

### Python-SDK-Schnellstart

So beginnen Sie mit dem Python SDK:

Installieren Sie das Paket:

```bash
# install using pip
pip install opik

# or install with uv
uv pip install opik
```

Konfigurieren Sie das Python SDK, indem Sie den Befehl `opik configure` ausführen, der Sie nach der Adresse Ihres Opik-Servers (für Self-Hosted-Instanzen) oder nach Ihrem API-Schlüssel und Workspace (für Comet.com) fragt:

```bash
opik configure
```

> [!TIP]
> Sie können auch `opik.configure(use_local=True)` aus Ihrem Python-Code aufrufen, um das SDK für die Ausführung auf einer lokalen Self-Hosted-Installation zu konfigurieren oder API-Schlüssel und Workspace-Details direkt für Comet.com anzugeben. Weitere Konfigurationsoptionen finden Sie in der [Python-SDK-Dokumentation](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=python_sdk_docs_link&utm_campaign=opik).

Sie sind nun bereit, mit der Protokollierung von Traces über das [Python SDK](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link2&utm_campaign=opik) zu beginnen.

<a id="-logging-traces-with-integrations"></a>
### 📝 Traces mit Integrationen protokollieren

Der einfachste Weg, Traces zu protokollieren, ist die Verwendung einer unserer direkten Integrationen. Opik unterstützt eine breite Palette von Frameworks, einschließlich neuerer Ergänzungen wie **Google ADK**, **Autogen**, **AG2** und **Flowise AI**:

| Integration           | Beschreibung                                             | Dokumentation                                                                                                                                                                  |
| --------------------- | ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| ADK                   | Traces für Google Agent Development Kit (ADK) protokollieren       | [Dokumentation](https://www.comet.com/docs/opik/integrations/adk?utm_source=opik&utm_medium=github&utm_content=google_adk_link&utm_campaign=opik)                              |
| AG2                   | Traces für AG2-LLM-Aufrufe protokollieren                            | [Dokumentation](https://www.comet.com/docs/opik/integrations/ag2?utm_source=opik&utm_medium=github&utm_content=ag2_link&utm_campaign=opik)                                     |
| Agent Spec            | Traces für Agent-Spec-Aufrufe protokollieren                         | [Dokumentation](https://www.comet.com/docs/opik/integrations/agentspec?utm_source=opik&utm_medium=github&utm_content=agentspec_link&utm_campaign=opik)                         |
| AIsuite               | Traces für aisuite-LLM-Aufrufe protokollieren                        | [Dokumentation](https://www.comet.com/docs/opik/integrations/aisuite?utm_source=opik&utm_medium=github&utm_content=aisuite_link&utm_campaign=opik)                             |
| Agno                  | Traces für Aufrufe des Agno-Agenten-Orchestrierungs-Frameworks protokollieren | [Dokumentation](https://www.comet.com/docs/opik/integrations/agno?utm_source=opik&utm_medium=github&utm_content=agno_link&utm_campaign=opik)                                   |
| Anthropic             | Traces für Anthropic-LLM-Aufrufe protokollieren                      | [Dokumentation](https://www.comet.com/docs/opik/integrations/anthropic?utm_source=opik&utm_medium=github&utm_content=anthropic_link&utm_campaign=opik)                         |
| Autogen               | Traces für agentische Autogen-Workflows protokollieren                | [Dokumentation](https://www.comet.com/docs/opik/integrations/autogen?utm_source=opik&utm_medium=github&utm_content=autogen_link&utm_campaign=opik)                             |
| Bedrock               | Traces für Amazon-Bedrock-LLM-Aufrufe protokollieren                 | [Dokumentation](https://www.comet.com/docs/opik/integrations/bedrock?utm_source=opik&utm_medium=github&utm_content=bedrock_link&utm_campaign=opik)                             |
| BeeAI (Python)        | Traces für Aufrufe des BeeAI-Python-Agenten-Frameworks protokollieren       | [Dokumentation](https://www.comet.com/docs/opik/integrations/beeai?utm_source=opik&utm_medium=github&utm_content=beeai_link&utm_campaign=opik)                                 |
| BeeAI (TypeScript)    | Traces für Aufrufe des BeeAI-TypeScript-Agenten-Frameworks protokollieren   | [Dokumentation](https://www.comet.com/docs/opik/integrations/beeai-typescript?utm_source=opik&utm_medium=github&utm_content=beeai_typescript_link&utm_campaign=opik)           |
| BytePlus              | Traces für BytePlus-LLM-Aufrufe protokollieren                       | [Dokumentation](https://www.comet.com/docs/opik/integrations/byteplus?utm_source=opik&utm_medium=github&utm_content=byteplus_link&utm_campaign=opik)                           |
| Cloudflare Workers AI | Traces für Cloudflare-Workers-AI-Aufrufe protokollieren              | [Dokumentation](https://www.comet.com/docs/opik/integrations/cloudflare-workers-ai?utm_source=opik&utm_medium=github&utm_content=cloudflare_workers_ai_link&utm_campaign=opik) |
| Cohere                | Traces für Cohere-LLM-Aufrufe protokollieren                         | [Dokumentation](https://www.comet.com/docs/opik/integrations/cohere?utm_source=opik&utm_medium=github&utm_content=cohere_link&utm_campaign=opik)                               |
| CrewAI                | Traces für CrewAI-Aufrufe protokollieren                             | [Dokumentation](https://www.comet.com/docs/opik/integrations/crewai?utm_source=opik&utm_medium=github&utm_content=crewai_link&utm_campaign=opik)                               |
| Cursor                | Traces für Cursor-Konversationen protokollieren                     | [Dokumentation](https://www.comet.com/docs/opik/integrations/cursor?utm_source=opik&utm_medium=github&utm_content=cursor_link&utm_campaign=opik)                               |
| DeepSeek              | Traces für DeepSeek-LLM-Aufrufe protokollieren                       | [Dokumentation](https://www.comet.com/docs/opik/integrations/deepseek?utm_source=opik&utm_medium=github&utm_content=deepseek_link&utm_campaign=opik)                           |
| Dify                  | Traces für Dify-Agenten-Läufe protokollieren                          | [Dokumentation](https://www.comet.com/docs/opik/integrations/dify?utm_source=opik&utm_medium=github&utm_content=dify_link&utm_campaign=opik)                                   |
| DSPY                  | Traces für DSPy-Läufe protokollieren                                | [Dokumentation](https://www.comet.com/docs/opik/integrations/dspy?utm_source=opik&utm_medium=github&utm_content=dspy_link&utm_campaign=opik)                                   |
| Fireworks AI          | Traces für Fireworks-AI-LLM-Aufrufe protokollieren                   | [Dokumentation](https://www.comet.com/docs/opik/integrations/fireworks-ai?utm_source=opik&utm_medium=github&utm_content=fireworks_ai_link&utm_campaign=opik)                   |
| Flowise AI            | Traces für den visuellen LLM-Builder Flowise AI protokollieren            | [Dokumentation](https://www.comet.com/docs/opik/integrations/flowise?utm_source=opik&utm_medium=github&utm_content=flowise_link&utm_campaign=opik)                             |
| Gemini (Python)       | Traces für Google-Gemini-LLM-Aufrufe protokollieren                  | [Dokumentation](https://www.comet.com/docs/opik/integrations/gemini?utm_source=opik&utm_medium=github&utm_content=gemini_link&utm_campaign=opik)                               |
| Gemini (TypeScript)   | Traces für Google-Gemini-TypeScript-SDK-Aufrufe protokollieren       | [Dokumentation](https://www.comet.com/docs/opik/integrations/gemini-typescript?utm_source=opik&utm_medium=github&utm_content=gemini_typescript_link&utm_campaign=opik)         |
| Groq                  | Traces für Groq-LLM-Aufrufe protokollieren                           | [Dokumentation](https://www.comet.com/docs/opik/integrations/groq?utm_source=opik&utm_medium=github&utm_content=groq_link&utm_campaign=opik)                                   |
| Guardrails            | Traces für Guardrails-AI-Validierungen protokollieren                | [Dokumentation](https://www.comet.com/docs/opik/integrations/guardrails-ai?utm_source=opik&utm_medium=github&utm_content=guardrails_link&utm_campaign=opik)                    |
| Haystack              | Traces für Haystack-Aufrufe protokollieren                           | [Dokumentation](https://www.comet.com/docs/opik/integrations/haystack?utm_source=opik&utm_medium=github&utm_content=haystack_link&utm_campaign=opik)                           |
| Harbor                | Traces für Harbor-Benchmark-Evaluierungsläufe protokollieren       | [Dokumentation](https://www.comet.com/docs/opik/integrations/harbor?utm_source=opik&utm_medium=github&utm_content=harbor_link&utm_campaign=opik)                               |
| Instructor            | Traces für mit Instructor getätigte LLM-Aufrufe protokollieren           | [Dokumentation](https://www.comet.com/docs/opik/integrations/instructor?utm_source=opik&utm_medium=github&utm_content=instructor_link&utm_campaign=opik)                       |
| LangChain (Python)    | Traces für LangChain-LLM-Aufrufe protokollieren                      | [Dokumentation](https://www.comet.com/docs/opik/integrations/langchain?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik)                         |
| LangChain (JS/TS)     | Traces für LangChain-JavaScript/TypeScript-Aufrufe protokollieren    | [Dokumentation](https://www.comet.com/docs/opik/integrations/langchainjs?utm_source=opik&utm_medium=github&utm_content=langchainjs_link&utm_campaign=opik)                     |
| LangGraph             | Traces für LangGraph-Ausführungen protokollieren                     | [Dokumentation](https://www.comet.com/docs/opik/integrations/langgraph?utm_source=opik&utm_medium=github&utm_content=langgraph_link&utm_campaign=opik)                         |
| Langflow              | Traces für den visuellen AI-Builder Langflow protokollieren               | [Dokumentation](https://www.comet.com/docs/opik/integrations/langflow?utm_source=opik&utm_medium=github&utm_content=langflow_link&utm_campaign=opik)                           |
| LiteLLM               | Traces für LiteLLM-Modellaufrufe protokollieren                      | [Dokumentation](https://www.comet.com/docs/opik/integrations/litellm?utm_source=opik&utm_medium=github&utm_content=litellm_link&utm_campaign=opik)                             |
| LiveKit Agents        | Traces für Aufrufe des LiveKit-Agents-AI-Agenten-Frameworks protokollieren  | [Dokumentation](https://www.comet.com/docs/opik/integrations/livekit?utm_source=opik&utm_medium=github&utm_content=livekit_link&utm_campaign=opik)                             |
| LlamaIndex            | Traces für LlamaIndex-LLM-Aufrufe protokollieren                     | [Dokumentation](https://www.comet.com/docs/opik/integrations/llama_index?utm_source=opik&utm_medium=github&utm_content=llama_index_link&utm_campaign=opik)                     |
| Mastra                | Traces für Aufrufe des Mastra-AI-Workflow-Frameworks protokollieren       | [Dokumentation](https://www.comet.com/docs/opik/integrations/mastra?utm_source=opik&utm_medium=github&utm_content=mastra_link&utm_campaign=opik)                               |
| Microsoft Agent Framework (Python) | Traces für Microsoft-Agent-Framework-Aufrufe protokollieren | [Dokumentation](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework?utm_source=opik&utm_medium=github&utm_content=agent_framework_link&utm_campaign=opik)              |
| Microsoft Agent Framework (.NET) | Traces für Microsoft-Agent-Framework-.NET-Aufrufe protokollieren | [Dokumentation](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework-dotnet?utm_source=opik&utm_medium=github&utm_content=agent_framework_dotnet_link&utm_campaign=opik) |
| Mistral AI            | Traces für Mistral-AI-LLM-Aufrufe protokollieren                     | [Dokumentation](https://www.comet.com/docs/opik/integrations/mistral?utm_source=opik&utm_medium=github&utm_content=mistral_link&utm_campaign=opik)                             |
| n8n                   | Traces für n8n-Workflow-Ausführungen protokollieren                  | [Dokumentation](https://www.comet.com/docs/opik/integrations/n8n?utm_source=opik&utm_medium=github&utm_content=n8n_link&utm_campaign=opik)                                     |
| Novita AI             | Traces für Novita-AI-LLM-Aufrufe protokollieren                      | [Dokumentation](https://www.comet.com/docs/opik/integrations/novita-ai?utm_source=opik&utm_medium=github&utm_content=novita_ai_link&utm_campaign=opik)                         |
| Ollama                | Traces für Ollama-LLM-Aufrufe protokollieren                         | [Dokumentation](https://www.comet.com/docs/opik/integrations/ollama?utm_source=opik&utm_medium=github&utm_content=ollama_link&utm_campaign=opik)                               |
| OpenAI (Python)       | Traces für OpenAI-LLM-Aufrufe protokollieren                         | [Dokumentation](https://www.comet.com/docs/opik/integrations/openai?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik)                               |
| OpenAI (JS/TS)        | Traces für OpenAI-JavaScript/TypeScript-Aufrufe protokollieren       | [Dokumentation](https://www.comet.com/docs/opik/integrations/openai-typescript?utm_source=opik&utm_medium=github&utm_content=openai_typescript_link&utm_campaign=opik)         |
| OpenAI Agents         | Traces für OpenAI-Agents-SDK-Aufrufe protokollieren                  | [Dokumentation](https://www.comet.com/docs/opik/integrations/openai_agents?utm_source=opik&utm_medium=github&utm_content=openai_agents_link&utm_campaign=opik)                 |
| OpenClaw              | Traces für OpenClaw-Agenten-Läufe protokollieren                  | [Dokumentation](https://www.comet.com/docs/opik/integrations/openclaw?utm_source=opik&utm_medium=github&utm_content=openclaw_link&utm_campaign=opik) |
| OpenRouter            | Traces für OpenRouter-LLM-Aufrufe protokollieren                     | [Dokumentation](https://www.comet.com/docs/opik/integrations/openrouter?utm_source=opik&utm_medium=github&utm_content=openrouter_link&utm_campaign=opik)                       |
| OpenTelemetry         | Traces für von OpenTelemetry unterstützte Aufrufe protokollieren            | [Dokumentation](https://www.comet.com/docs/opik/tracing/opentelemetry/overview?utm_source=opik&utm_medium=github&utm_content=opentelemetry_link&utm_campaign=opik)             |
| OpenWebUI             | Traces für OpenWebUI-Konversationen protokollieren                  | [Dokumentation](https://www.comet.com/docs/opik/integrations/openwebui?utm_source=opik&utm_medium=github&utm_content=openwebui_link&utm_campaign=opik)                         |
| Pipecat               | Traces für Pipecat-Echtzeit-Sprachagenten-Aufrufe protokollieren      | [Dokumentation](https://www.comet.com/docs/opik/integrations/pipecat?utm_source=opik&utm_medium=github&utm_content=pipecat_link&utm_campaign=opik)                             |
| Predibase             | Traces für Predibase-LLM-Aufrufe protokollieren                      | [Dokumentation](https://www.comet.com/docs/opik/integrations/predibase?utm_source=opik&utm_medium=github&utm_content=predibase_link&utm_campaign=opik)                         |
| Pydantic AI           | Traces für PydanticAI-Agenten-Aufrufe protokollieren                   | [Dokumentation](https://www.comet.com/docs/opik/integrations/pydantic-ai?utm_source=opik&utm_medium=github&utm_content=pydantic_ai_link&utm_campaign=opik)                     |
| Ragas                 | Traces für Ragas-Evaluierungen protokollieren                        | [Dokumentation](https://www.comet.com/docs/opik/integrations/ragas?utm_source=opik&utm_medium=github&utm_content=ragas_link&utm_campaign=opik)                                 |
| Semantic Kernel       | Traces für Microsoft-Semantic-Kernel-Aufrufe protokollieren          | [Dokumentation](https://www.comet.com/docs/opik/integrations/semantic-kernel?utm_source=opik&utm_medium=github&utm_content=semantic_kernel_link&utm_campaign=opik)             |
| Smolagents            | Traces für Smolagents-Agenten protokollieren                        | [Dokumentation](https://www.comet.com/docs/opik/integrations/smolagents?utm_source=opik&utm_medium=github&utm_content=smolagents_link&utm_campaign=opik)                       |
| Spring AI             | Traces für Spring-AI-Framework-Aufrufe protokollieren                | [Dokumentation](https://www.comet.com/docs/opik/integrations/spring-ai?utm_source=opik&utm_medium=github&utm_content=spring_ai_link&utm_campaign=opik)                         |
| Strands Agents        | Traces für Strands-Agents-Aufrufe protokollieren                     | [Dokumentation](https://www.comet.com/docs/opik/integrations/strands-agents?utm_source=opik&utm_medium=github&utm_content=strands_agents_link&utm_campaign=opik)               |
| Together AI           | Traces für Together-AI-LLM-Aufrufe protokollieren                    | [Dokumentation](https://www.comet.com/docs/opik/integrations/together-ai?utm_source=opik&utm_medium=github&utm_content=together_ai_link&utm_campaign=opik)                     |
| Vercel AI SDK         | Traces für Vercel-AI-SDK-Aufrufe protokollieren                      | [Dokumentation](https://www.comet.com/docs/opik/integrations/vercel-ai-sdk?utm_source=opik&utm_medium=github&utm_content=vercel_ai_sdk_link&utm_campaign=opik)                 |
| VoltAgent             | Traces für Aufrufe des VoltAgent-Agenten-Frameworks protokollieren          | [Dokumentation](https://www.comet.com/docs/opik/integrations/voltagent?utm_source=opik&utm_medium=github&utm_content=voltagent_link&utm_campaign=opik)                         |
| WatsonX               | Traces für IBM-watsonx-LLM-Aufrufe protokollieren                    | [Dokumentation](https://www.comet.com/docs/opik/integrations/watsonx?utm_source=opik&utm_medium=github&utm_content=watsonx_link&utm_campaign=opik)                             |
| xAI Grok              | Traces für xAI-Grok-LLM-Aufrufe protokollieren                       | [Dokumentation](https://www.comet.com/docs/opik/integrations/xai-grok?utm_source=opik&utm_medium=github&utm_content=xai_grok_link&utm_campaign=opik)                           |

> [!TIP]
> Falls das von Ihnen verwendete Framework oben nicht aufgeführt ist, können Sie gerne ein [Issue eröffnen](https://github.com/comet-ml/opik/issues) oder einen PR mit der Integration einreichen.

Wenn Sie keines der oben genannten Frameworks verwenden, können Sie auch den Funktions-Decorator `track` verwenden, um [Traces zu protokollieren](https://www.comet.com/docs/opik/v1/tracing/log_traces/?from=llm&utm_source=opik&utm_medium=github&utm_content=traces_link&utm_campaign=opik):

```python
import opik

opik.configure(use_local=True) # Run locally

@opik.track
def my_llm_function(user_question: str) -> str:
    # Your LLM code here

    return "Hello"
```

> [!TIP]
> Der track-Decorator kann in Verbindung mit jeder unserer Integrationen verwendet werden und lässt sich auch zur Verfolgung verschachtelter Funktionsaufrufe einsetzen.

<a id="-llm-as-a-judge-metrics"></a>
### 🧑‍⚖️ LLM-as-a-Judge-Metriken

Das Python Opik SDK enthält eine Reihe von LLM-as-a-Judge-Metriken, die Ihnen bei der Evaluierung Ihrer LLM-Anwendung helfen. Erfahren Sie mehr darüber in der [Metrik-Dokumentation](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_2_link&utm_campaign=opik).

Um sie zu verwenden, importieren Sie einfach die relevante Metrik und nutzen Sie die `score`-Funktion:

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

Opik enthält außerdem eine Reihe vorgefertigter heuristischer Metriken sowie die Möglichkeit, eigene zu erstellen. Erfahren Sie mehr darüber in der [Metrik-Dokumentation](https://www.comet.com/docs/opik/evaluation/metrics/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_3_link&utm_campaign=opik).

<a id="-evaluating-your-llm-application"></a>
### 🔍 Ihre LLM-Anwendungen evaluieren

Mit Opik können Sie Ihre LLM-Anwendung während der Entwicklung anhand von [Datasets](https://www.comet.com/docs/opik/v1/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_2_link&utm_campaign=opik) und [Experimenten](https://www.comet.com/docs/opik/v1/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=experiments_link&utm_campaign=opik) evaluieren. Das Opik-Dashboard bietet verbesserte Diagramme für Experimente und einen besseren Umgang mit großen Traces. Sie können Evaluierungen auch als Teil Ihrer CI/CD-Pipeline mithilfe unserer [PyTest-Integration](https://www.comet.com/docs/opik/v1/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_2_link&utm_campaign=opik) ausführen.

<a id="-star-us-on-github"></a>
## ⭐ Geben Sie uns einen Stern auf GitHub

Wenn Sie Opik nützlich finden, geben Sie uns bitte einen Stern! Ihre Unterstützung hilft uns, unsere Community wachsen zu lassen und das Produkt weiter zu verbessern.

[![Star History Chart](https://api.star-history.com/svg?repos=comet-ml/opik&type=Date)](https://github.com/comet-ml/opik)

<a id="-contributing"></a>
## 🤝 Mitwirken

Es gibt viele Möglichkeiten, zu Opik beizutragen:

- Reichen Sie [Fehlerberichte](https://github.com/comet-ml/opik/issues) und [Feature-Requests](https://github.com/comet-ml/opik/issues) ein
- Prüfen Sie die Dokumentation und reichen Sie [Pull-Requests](https://github.com/comet-ml/opik/pulls) ein, um sie zu verbessern
- Sprechen oder schreiben Sie über Opik und [lassen Sie es uns wissen](https://chat.comet.com)
- Stimmen Sie für [beliebte Feature-Requests](https://github.com/comet-ml/opik/issues?q=is%3Aissue+is%3Aopen+label%3A%22enhancement%22) ab, um Ihre Unterstützung zu zeigen

Um mehr darüber zu erfahren, wie Sie zu Opik beitragen können, lesen Sie bitte unsere [Beitragsrichtlinien](CONTRIBUTING.md).
