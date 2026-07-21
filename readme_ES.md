<div align="center"><b><a href="README.md">English</a> | <a href="readme_CN.md">简体中文</a> | <a href="readme_ES.md">Español</a> | <a href="readme_FR.md">Français</a> | <a href="readme_DE.md">Deutsch</a></b></div>

> Nota: Este archivo fue traducido automáticamente. ¡Las mejoras de traducción son bienvenidas!

<h1 align="center" style="border-bottom: none">
    <div>
        <a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=header_img&utm_campaign=opik"><picture>
            <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/logo-dark-mode.svg">
            <source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg">
            <img alt="Logotipo de Comet Opik" src="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg" width="200" />
        </picture></a>
        <br>
        Opik: Observabilidad de LLM, Evaluación y Trazabilidad de Agentes de IA de Código Abierto
    </div>
</h1>
<p align="center">
<b>Opik es la plataforma de código abierto de observabilidad y evaluación de LLM para trazabilidad de agentes de IA, evaluación de LLM, gestión de prompts y monitoreo en producción.</b> Creada por <a href="https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=what_is_opik_link&utm_campaign=opik">Comet</a>. Con licencia Apache-2.0, gratuita para autoalojar la plataforma completa, y con más de 20 000 estrellas en GitHub.
</p>

<div align="center">

[![Python SDK](https://img.shields.io/pypi/v/opik)](https://pypi.org/project/opik/)
[![License](https://img.shields.io/github/license/comet-ml/opik)](https://github.com/comet-ml/opik/blob/main/LICENSE)
[![Build](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml/badge.svg)](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml)
<!-- [![Quick Start](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/opik_quickstart.ipynb) -->

</div>

<p align="center">
    <a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=website_button&utm_campaign=opik"><b>Sitio web</b></a> •
    <a href="https://chat.comet.com"><b>Comunidad de Slack</b></a> •
    <a href="https://x.com/Cometml"><b>Twitter</b></a> •
    <a href="https://www.comet.com/docs/opik/changelog"><b>Registro de cambios</b></a> •
    <a href="https://www.comet.com/docs/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=docs_button&utm_campaign=opik"><b>Documentación</b></a>
</p>

<p align="center"><sub>Última actualización: 2026-07-17</sub></p>

<div align="center" style="margin-top: 1em; margin-bottom: 1em;">
<a href="#-what-is-opik">🚀 ¿Qué es Opik?</a> • <a href="#-quick-start">⚡ Inicio rápido</a> • <a href="#-how-opik-compares">📊 ¿Cómo se compara Opik?</a> • <a href="#-frequently-asked-questions">❓ Preguntas frecuentes</a> • <a href="#%EF%B8%8F-opik-server-installation">🛠️ Instalación del servidor Opik</a> • <a href="#-opik-client-sdk">💻 SDK cliente de Opik</a> • <a href="#-logging-traces-with-integrations">📝 Registro de trazas</a><br>
<a href="#-llm-as-a-judge-metrics">🧑‍⚖️ LLM como juez</a> • <a href="#-evaluating-your-llm-application">🔍 Evaluación de tu aplicación</a> • <a href="#-star-us-on-github">⭐ Danos una estrella</a> • <a href="#-contributing">🤝 Contribuir</a>
</div>

<br>

[![Captura de pantalla de la plataforma Opik (miniatura)](readme-thumbnail-new.png)](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=readme_banner&utm_campaign=opik)

<a id="-what-is-opik"></a>
## 🚀 ¿Qué es Opik?

Opik cubre todo el ciclo de vida de las aplicaciones de LLM, desde la primera traza en desarrollo hasta el monitoreo en producción, para equipos que construyen aplicaciones de LLM y agentes de IA. Sus principales ofertas incluyen:

- **Trazabilidad y observabilidad de agentes de IA**: Trazabilidad profunda de llamadas a LLM, registro de conversaciones y actividad de agentes, con árboles de trazas completos para agentes multipaso y llamadas a herramientas.
- **Evaluación de LLM**: Conjuntos de datos, experimentos y métricas de LLM como juez para detección de alucinaciones, moderación y evaluación de RAG.
- **Optimización de prompts y agentes**: El SDK Opik Agent Optimizer para mejorar prompts y agentes.
- **Monitoreo listo para producción**: Paneles escalables y reglas de evaluación en línea.
- **Opik Guardrails**: Funciones que te ayudan a implementar prácticas de IA seguras y responsables.
- **Evaluación en CI/CD**: Una integración con PyTest para probar canalizaciones de LLM en cada commit.

<br>

Sus capacidades principales incluyen:

- **Desarrollo y trazabilidad:**
  - Registra todas las llamadas y trazas a LLM con contexto detallado durante el desarrollo y en producción ([Inicio rápido](https://www.comet.com/docs/opik/quickstart/?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_link&utm_campaign=opik)).
  - Amplias integraciones con terceros para una observabilidad sencilla: Intégrate sin problemas con una lista creciente de frameworks, con soporte nativo para muchos de los más grandes y populares (incluidas incorporaciones recientes como **Google ADK**, **Autogen** y **Flowise AI**). ([Integraciones](https://www.comet.com/docs/opik/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik))
  - Anota trazas y spans con puntuaciones de retroalimentación a través del [SDK de Python](https://www.comet.com/docs/opik/v1/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link&utm_campaign=opik) o la [interfaz de usuario](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-through-the-ui?from=llm&utm_source=opik&utm_medium=github&utm_content=ui_link&utm_campaign=opik).
  - Experimenta con prompts y modelos en el [Prompt Playground](https://www.comet.com/docs/opik/prompt_engineering/playground).

- **Evaluación y pruebas**:
  - Automatiza la evaluación de tu aplicación de LLM con [Conjuntos de datos](https://www.comet.com/docs/opik/v1/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_link&utm_campaign=opik) y [Experimentos](https://www.comet.com/docs/opik/v1/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=eval_link&utm_campaign=opik).
  - Aprovecha potentes métricas de LLM como juez para tareas complejas como [detección de alucinaciones](https://www.comet.com/docs/opik/evaluation/metrics/hallucination/?from=llm&utm_source=opik&utm_medium=github&utm_content=hallucination_link&utm_campaign=opik), [moderación](https://www.comet.com/docs/opik/evaluation/metrics/moderation/?from=llm&utm_source=opik&utm_medium=github&utm_content=moderation_link&utm_campaign=opik) y evaluación de RAG ([Relevancia de la respuesta](https://www.comet.com/docs/opik/evaluation/metrics/answer_relevance/?from=llm&utm_source=opik&utm_medium=github&utm_content=alex_link&utm_campaign=opik), [Precisión del contexto](https://www.comet.com/docs/opik/evaluation/metrics/context_precision/?from=llm&utm_source=opik&utm_medium=github&utm_content=context_link&utm_campaign=opik)).
  - Integra evaluaciones en tu canalización de CI/CD con nuestra [integración con PyTest](https://www.comet.com/docs/opik/v1/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_link&utm_campaign=opik).

- **Monitoreo y optimización en producción**:
  - Registra grandes volúmenes de trazas de producción: Opik está diseñado para escalar (más de 40 M de trazas/día).
  - Monitorea puntuaciones de retroalimentación, recuentos de trazas y uso de tokens a lo largo del tiempo en el [Panel de Opik](https://www.comet.com/docs/opik/v1/production/production_monitoring/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik).
  - Utiliza [Reglas de evaluación en línea](https://www.comet.com/docs/opik/v1/production/rules/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik) con métricas de LLM como juez para identificar problemas en producción.
  - Aprovecha **Opik Agent Optimizer** y **Opik Guardrails** para mejorar y proteger de forma continua tus aplicaciones de LLM en producción.

**Para quién es:** ingenieros de ML que construyen agentes impulsados por LLM, equipos de IA que pasan del prototipo a la producción y equipos de ingeniería que necesitan observabilidad de código abierto y autoalojable que puedan ejecutar en su propio entorno.

> **Por qué importa aquí el código abierto:** Opik tiene licencia Apache-2.0 y es gratuito para autoalojar: la plataforma completa, backend incluido, no solo un SDK cliente. El repositorio incluye el backend del servidor, la aplicación web, la trazabilidad, los conjuntos de datos, los experimentos, las evaluaciones, la gestión de prompts, la evaluación en línea y los componentes de optimización de agentes, todo bajo Apache-2.0. Puedes ejecutar la observabilidad de LLM dentro de tu propia infraestructura sin que ningún dato salga de tu entorno y sin necesidad de una conversación de ventas empresarial.

> [!TIP]
> Si buscas funciones que Opik no tiene actualmente, crea una nueva [solicitud de función](https://github.com/comet-ml/opik/issues/new/choose) 🚀

<br>

<a id="-quick-start"></a>
## ⚡ Inicio rápido

Instala el SDK de Python y configúralo:

```bash
pip install opik
opik configure
```

Envuelve cualquier función con el decorador `@track` para empezar a registrar trazas:

```python
from opik import track

@track
def my_function(input: str) -> str:
    return input
```

Cada llamada a `my_function` ahora se registra en Opik, incluidas las llamadas anidadas, por lo que esto funciona para trazas completas de agentes y canalizaciones, no solo para llamadas individuales a LLM. Consulta la [guía de inicio rápido](https://www.comet.com/docs/opik/quickstart?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_hero_link&utm_campaign=opik) para el SDK de TypeScript y otras opciones de configuración.

<br>

<a id="-how-opik-compares"></a>
## 📊 ¿Cómo se compara Opik?

Opik compite en la categoría de **observabilidad de LLM / evaluación de agentes de IA** junto a **LangSmith, Arize (Phoenix y Arize AX), Weights & Biases (Weave), Langfuse y Braintrust**.

| Capacidad | Opik | LangSmith | Phoenix | Arize AX | Weights & Biases (Weave) | Langfuse | Braintrust |
|---|---|---|---|---|---|---|---|
| Código abierto | Sí, Apache-2.0 (plataforma completa) | No | Fuente disponible (Elastic License 2.0, no aprobada por OSI) | No | SDK/kit de herramientas de código abierto; la plataforma autogestionada requiere una licencia comercial | Plataforma central con licencia MIT; módulos empresariales comerciales | No |
| Despliegue autoalojado | Sí | Solo empresarial | Sí | Solo empresarial | Solo empresarial para el propio Weave | Sí, núcleo | Solo empresarial |
| Nivel gratuito disponible (nube o autoalojado) | Sí, ambos | Sí, nube | Sí, autoalojado | Sí, nube | Sí, nube | Sí, ambos | Sí, nube |
| Trazabilidad de agentes / multipaso | Sí | Sí | Sí | Sí | Sí | Sí | Sí |
| Evaluación con LLM como juez | Sí | Sí | Sí | Sí | Sí | Sí | Sí |
| Gestión de prompts | Sí | Sí | Parcialmente | Parcialmente | Parcialmente | Sí | Sí |
| Independiente del framework | Sí | Parcialmente, construido en torno a LangChain | Sí | Sí | Sí | Sí | Sí |

**Cuándo los equipos eligen Opik:** La plataforma completa de observabilidad, evaluación y optimización de Opik tiene licencia Apache-2.0 y es gratuita para autoalojar. A diferencia de las plataformas cerradas cuyo despliegue autoalojado requiere un plan empresarial, Opik puede desplegarse sin una licencia comercial, y es independiente del framework, por lo que no te atará a un único ecosistema de agentes. Consulta la tabla anterior para ver en qué se diferencian el autoalojamiento y las licencias entre las alternativas.

<br>

<a id="-frequently-asked-questions"></a>
## ❓ Preguntas frecuentes

#### ¿Es Opik de código abierto?
Opik está licenciado bajo Apache 2.0. Su servidor, aplicación web y capacidades básicas de observabilidad y evaluación pueden autoalojarse sin una licencia comercial.

#### ¿Puedo autoalojar Opik?
Sí. Opik puede desplegarse localmente o en tu propia infraestructura utilizando las opciones de autoalojamiento documentadas.

#### ¿Opik admite la trazabilidad de agentes de IA?
Sí. Opik captura trazas multipaso que contienen llamadas a LLM, ejecuciones de herramientas, pasos de recuperación y otra actividad de agentes.

#### ¿Opik admite la evaluación de LLM?
Sí. Opik admite conjuntos de datos, experimentos, métricas basadas en código, evaluación con LLM como juez y evaluación en línea.

#### ¿Opik está ligado a un framework de agentes específico?
No. Opik es independiente del framework y admite su SDK, OpenTelemetry e integraciones específicas de frameworks.

<br>

<a id="%EF%B8%8F-opik-server-installation"></a>
## 🛠️ Instalación del servidor Opik

Pon en marcha tu servidor Opik en minutos. Elige la opción que mejor se adapte a tus necesidades:

### Opción 1: Comet.com Cloud (la más fácil y recomendada)

Accede a Opik al instante sin ninguna configuración. Ideal para arranques rápidos y un mantenimiento sin complicaciones.

👉 [Crea tu cuenta gratuita de Comet](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=install_create_link&utm_campaign=opik)

### Opción 2: Autoaloja Opik para tener control total

Despliega Opik en tu propio entorno. Elige entre Docker para configuraciones locales o Kubernetes para escalabilidad.

#### Autoalojamiento con Docker Compose (para desarrollo y pruebas locales)

Esta es la forma más sencilla de poner en marcha una instancia local de Opik. Fíjate en el nuevo script de instalación `./opik.sh`:

En entorno Linux o Mac:

```bash
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the repository
cd opik

# Start the Opik platform
./opik.sh
```

En entorno Windows:

```powershell
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the repository
cd opik

# Start the Opik platform
powershell -ExecutionPolicy ByPass -c ".\\opik.ps1"
```

**Perfiles de servicio para desarrollo**

Los scripts de instalación de Opik ahora admiten perfiles de servicio para diferentes escenarios de desarrollo:

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

Usa las opciones `--help` o `--info` para solucionar problemas. Los Dockerfiles ahora garantizan que los contenedores se ejecuten como usuarios no root para una mayor seguridad. Una vez que todo esté en marcha, ¡ya puedes visitar [localhost:5173](http://localhost:5173) en tu navegador! Para obtener instrucciones detalladas, consulta la [Guía de despliegue local](https://www.comet.com/docs/opik/self-host/local_deployment?from=llm&utm_source=opik&utm_medium=github&utm_content=self_host_link&utm_campaign=opik).

#### Autoalojamiento con Kubernetes y Helm (para despliegues escalables)

Para despliegues autoalojados en producción o a mayor escala, Opik puede instalarse en un clúster de Kubernetes utilizando nuestro chart de Helm. Haz clic en la insignia para ver la [Guía completa de instalación en Kubernetes con Helm](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik).

[![Kubernetes](https://img.shields.io/badge/Kubernetes-%23326ce5.svg?&logo=kubernetes&logoColor=white)](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik)

> [!IMPORTANT]
> **Cambios de la versión 1.7.0**: Consulta el [registro de cambios](https://github.com/comet-ml/opik/blob/main/CHANGELOG.md) para ver actualizaciones importantes y cambios incompatibles.

<a id="-opik-client-sdk"></a>
## 💻 SDK cliente de Opik

Opik proporciona un conjunto de bibliotecas cliente y una API REST para interactuar con el servidor Opik. Esto incluye SDK para Python, TypeScript y Ruby (a través de OpenTelemetry), lo que permite una integración fluida en tus flujos de trabajo. Para consultar referencias detalladas de la API y el SDK, consulta la [Documentación de referencia del cliente de Opik](https://www.comet.com/docs/opik/reference/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=reference_link&utm_campaign=opik).

### Inicio rápido del SDK de Python

Para empezar con el SDK de Python:

Instala el paquete:

```bash
# install using pip
pip install opik

# or install with uv
uv pip install opik
```

Configura el SDK de Python ejecutando el comando `opik configure`, que te pedirá la dirección de tu servidor Opik (para instancias autoalojadas) o tu clave de API y espacio de trabajo (para Comet.com):

```bash
opik configure
```

> [!TIP]
> También puedes llamar a `opik.configure(use_local=True)` desde tu código Python para configurar el SDK para que se ejecute en una instalación local autoalojada, o proporcionar directamente los detalles de clave de API y espacio de trabajo para Comet.com. Consulta la [documentación del SDK de Python](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=python_sdk_docs_link&utm_campaign=opik) para ver más opciones de configuración.

Ya estás listo para empezar a registrar trazas usando el [SDK de Python](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link2&utm_campaign=opik).

<a id="-logging-traces-with-integrations"></a>
### 📝 Registro de trazas con integraciones

La forma más sencilla de registrar trazas es usar una de nuestras integraciones directas. Opik admite una amplia variedad de frameworks, incluidas incorporaciones recientes como **Google ADK**, **Autogen**, **AG2** y **Flowise AI**:

| Integración           | Descripción                                             | Documentación                                                                                                                                                                  |
| --------------------- | ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| ADK                   | Registra trazas para Google Agent Development Kit (ADK)       | [Documentación](https://www.comet.com/docs/opik/integrations/adk?utm_source=opik&utm_medium=github&utm_content=google_adk_link&utm_campaign=opik)                              |
| AG2                   | Registra trazas para llamadas a LLM de AG2                            | [Documentación](https://www.comet.com/docs/opik/integrations/ag2?utm_source=opik&utm_medium=github&utm_content=ag2_link&utm_campaign=opik)                                     |
| Agent Spec            | Registra trazas para llamadas de Agent Spec                         | [Documentación](https://www.comet.com/docs/opik/integrations/agentspec?utm_source=opik&utm_medium=github&utm_content=agentspec_link&utm_campaign=opik)                         |
| AIsuite               | Registra trazas para llamadas a LLM de aisuite                        | [Documentación](https://www.comet.com/docs/opik/integrations/aisuite?utm_source=opik&utm_medium=github&utm_content=aisuite_link&utm_campaign=opik)                             |
| Agno                  | Registra trazas para llamadas del framework de orquestación de agentes Agno | [Documentación](https://www.comet.com/docs/opik/integrations/agno?utm_source=opik&utm_medium=github&utm_content=agno_link&utm_campaign=opik)                                   |
| Anthropic             | Registra trazas para llamadas a LLM de Anthropic                      | [Documentación](https://www.comet.com/docs/opik/integrations/anthropic?utm_source=opik&utm_medium=github&utm_content=anthropic_link&utm_campaign=opik)                         |
| Autogen               | Registra trazas para flujos de trabajo con agentes de Autogen                | [Documentación](https://www.comet.com/docs/opik/integrations/autogen?utm_source=opik&utm_medium=github&utm_content=autogen_link&utm_campaign=opik)                             |
| Bedrock               | Registra trazas para llamadas a LLM de Amazon Bedrock                 | [Documentación](https://www.comet.com/docs/opik/integrations/bedrock?utm_source=opik&utm_medium=github&utm_content=bedrock_link&utm_campaign=opik)                             |
| BeeAI (Python)        | Registra trazas para llamadas del framework de agentes BeeAI para Python       | [Documentación](https://www.comet.com/docs/opik/integrations/beeai?utm_source=opik&utm_medium=github&utm_content=beeai_link&utm_campaign=opik)                                 |
| BeeAI (TypeScript)    | Registra trazas para llamadas del framework de agentes BeeAI para TypeScript   | [Documentación](https://www.comet.com/docs/opik/integrations/beeai-typescript?utm_source=opik&utm_medium=github&utm_content=beeai_typescript_link&utm_campaign=opik)           |
| BytePlus              | Registra trazas para llamadas a LLM de BytePlus                       | [Documentación](https://www.comet.com/docs/opik/integrations/byteplus?utm_source=opik&utm_medium=github&utm_content=byteplus_link&utm_campaign=opik)                           |
| Cloudflare Workers AI | Registra trazas para llamadas a Cloudflare Workers AI              | [Documentación](https://www.comet.com/docs/opik/integrations/cloudflare-workers-ai?utm_source=opik&utm_medium=github&utm_content=cloudflare_workers_ai_link&utm_campaign=opik) |
| Cohere                | Registra trazas para llamadas a LLM de Cohere                         | [Documentación](https://www.comet.com/docs/opik/integrations/cohere?utm_source=opik&utm_medium=github&utm_content=cohere_link&utm_campaign=opik)                               |
| CrewAI                | Registra trazas para llamadas de CrewAI                             | [Documentación](https://www.comet.com/docs/opik/integrations/crewai?utm_source=opik&utm_medium=github&utm_content=crewai_link&utm_campaign=opik)                               |
| Cursor                | Registra trazas para conversaciones de Cursor                     | [Documentación](https://www.comet.com/docs/opik/integrations/cursor?utm_source=opik&utm_medium=github&utm_content=cursor_link&utm_campaign=opik)                               |
| DeepSeek              | Registra trazas para llamadas a LLM de DeepSeek                       | [Documentación](https://www.comet.com/docs/opik/integrations/deepseek?utm_source=opik&utm_medium=github&utm_content=deepseek_link&utm_campaign=opik)                           |
| Dify                  | Registra trazas para ejecuciones de agentes de Dify                          | [Documentación](https://www.comet.com/docs/opik/integrations/dify?utm_source=opik&utm_medium=github&utm_content=dify_link&utm_campaign=opik)                                   |
| DSPY                  | Registra trazas para ejecuciones de DSPy                                | [Documentación](https://www.comet.com/docs/opik/integrations/dspy?utm_source=opik&utm_medium=github&utm_content=dspy_link&utm_campaign=opik)                                   |
| Fireworks AI          | Registra trazas para llamadas a LLM de Fireworks AI                   | [Documentación](https://www.comet.com/docs/opik/integrations/fireworks-ai?utm_source=opik&utm_medium=github&utm_content=fireworks_ai_link&utm_campaign=opik)                   |
| Flowise AI            | Registra trazas para el constructor visual de LLM Flowise AI            | [Documentación](https://www.comet.com/docs/opik/integrations/flowise?utm_source=opik&utm_medium=github&utm_content=flowise_link&utm_campaign=opik)                             |
| Gemini (Python)       | Registra trazas para llamadas a LLM de Google Gemini                  | [Documentación](https://www.comet.com/docs/opik/integrations/gemini?utm_source=opik&utm_medium=github&utm_content=gemini_link&utm_campaign=opik)                               |
| Gemini (TypeScript)   | Registra trazas para llamadas del SDK de TypeScript de Google Gemini       | [Documentación](https://www.comet.com/docs/opik/integrations/gemini-typescript?utm_source=opik&utm_medium=github&utm_content=gemini_typescript_link&utm_campaign=opik)         |
| Groq                  | Registra trazas para llamadas a LLM de Groq                           | [Documentación](https://www.comet.com/docs/opik/integrations/groq?utm_source=opik&utm_medium=github&utm_content=groq_link&utm_campaign=opik)                                   |
| Guardrails            | Registra trazas para validaciones de Guardrails AI                | [Documentación](https://www.comet.com/docs/opik/integrations/guardrails-ai?utm_source=opik&utm_medium=github&utm_content=guardrails_link&utm_campaign=opik)                    |
| Haystack              | Registra trazas para llamadas de Haystack                           | [Documentación](https://www.comet.com/docs/opik/integrations/haystack?utm_source=opik&utm_medium=github&utm_content=haystack_link&utm_campaign=opik)                           |
| Harbor                | Registra trazas para pruebas de evaluación de benchmarks de Harbor       | [Documentación](https://www.comet.com/docs/opik/integrations/harbor?utm_source=opik&utm_medium=github&utm_content=harbor_link&utm_campaign=opik)                               |
| Instructor            | Registra trazas para llamadas a LLM realizadas con Instructor           | [Documentación](https://www.comet.com/docs/opik/integrations/instructor?utm_source=opik&utm_medium=github&utm_content=instructor_link&utm_campaign=opik)                       |
| LangChain (Python)    | Registra trazas para llamadas a LLM de LangChain                      | [Documentación](https://www.comet.com/docs/opik/integrations/langchain?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik)                         |
| LangChain (JS/TS)     | Registra trazas para llamadas de LangChain en JavaScript/TypeScript    | [Documentación](https://www.comet.com/docs/opik/integrations/langchainjs?utm_source=opik&utm_medium=github&utm_content=langchainjs_link&utm_campaign=opik)                     |
| LangGraph             | Registra trazas para ejecuciones de LangGraph                     | [Documentación](https://www.comet.com/docs/opik/integrations/langgraph?utm_source=opik&utm_medium=github&utm_content=langgraph_link&utm_campaign=opik)                         |
| Langflow              | Registra trazas para el constructor visual de IA Langflow               | [Documentación](https://www.comet.com/docs/opik/integrations/langflow?utm_source=opik&utm_medium=github&utm_content=langflow_link&utm_campaign=opik)                           |
| LiteLLM               | Registra trazas para llamadas a modelos de LiteLLM                      | [Documentación](https://www.comet.com/docs/opik/integrations/litellm?utm_source=opik&utm_medium=github&utm_content=litellm_link&utm_campaign=opik)                             |
| LiveKit Agents        | Registra trazas para llamadas del framework de agentes de IA LiveKit Agents  | [Documentación](https://www.comet.com/docs/opik/integrations/livekit?utm_source=opik&utm_medium=github&utm_content=livekit_link&utm_campaign=opik)                             |
| LlamaIndex            | Registra trazas para llamadas a LLM de LlamaIndex                     | [Documentación](https://www.comet.com/docs/opik/integrations/llama_index?utm_source=opik&utm_medium=github&utm_content=llama_index_link&utm_campaign=opik)                     |
| Mastra                | Registra trazas para llamadas del framework de flujos de trabajo de IA Mastra       | [Documentación](https://www.comet.com/docs/opik/integrations/mastra?utm_source=opik&utm_medium=github&utm_content=mastra_link&utm_campaign=opik)                               |
| Microsoft Agent Framework (Python) | Registra trazas para llamadas de Microsoft Agent Framework | [Documentación](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework?utm_source=opik&utm_medium=github&utm_content=agent_framework_link&utm_campaign=opik)              |
| Microsoft Agent Framework (.NET) | Registra trazas para llamadas de Microsoft Agent Framework en .NET | [Documentación](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework-dotnet?utm_source=opik&utm_medium=github&utm_content=agent_framework_dotnet_link&utm_campaign=opik) |
| Mistral AI            | Registra trazas para llamadas a LLM de Mistral AI                     | [Documentación](https://www.comet.com/docs/opik/integrations/mistral?utm_source=opik&utm_medium=github&utm_content=mistral_link&utm_campaign=opik)                             |
| n8n                   | Registra trazas para ejecuciones de flujos de trabajo de n8n                  | [Documentación](https://www.comet.com/docs/opik/integrations/n8n?utm_source=opik&utm_medium=github&utm_content=n8n_link&utm_campaign=opik)                                     |
| Novita AI             | Registra trazas para llamadas a LLM de Novita AI                      | [Documentación](https://www.comet.com/docs/opik/integrations/novita-ai?utm_source=opik&utm_medium=github&utm_content=novita_ai_link&utm_campaign=opik)                         |
| Ollama                | Registra trazas para llamadas a LLM de Ollama                         | [Documentación](https://www.comet.com/docs/opik/integrations/ollama?utm_source=opik&utm_medium=github&utm_content=ollama_link&utm_campaign=opik)                               |
| OpenAI (Python)       | Registra trazas para llamadas a LLM de OpenAI                         | [Documentación](https://www.comet.com/docs/opik/integrations/openai?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik)                               |
| OpenAI (JS/TS)        | Registra trazas para llamadas de OpenAI en JavaScript/TypeScript       | [Documentación](https://www.comet.com/docs/opik/integrations/openai-typescript?utm_source=opik&utm_medium=github&utm_content=openai_typescript_link&utm_campaign=opik)         |
| OpenAI Agents         | Registra trazas para llamadas del SDK de OpenAI Agents                  | [Documentación](https://www.comet.com/docs/opik/integrations/openai_agents?utm_source=opik&utm_medium=github&utm_content=openai_agents_link&utm_campaign=opik)                 |
| OpenClaw              | Registra trazas para ejecuciones de agentes de OpenClaw                  | [Documentación](https://www.comet.com/docs/opik/integrations/openclaw?utm_source=opik&utm_medium=github&utm_content=openclaw_link&utm_campaign=opik) |
| OpenRouter            | Registra trazas para llamadas a LLM de OpenRouter                     | [Documentación](https://www.comet.com/docs/opik/integrations/openrouter?utm_source=opik&utm_medium=github&utm_content=openrouter_link&utm_campaign=opik)                       |
| OpenTelemetry         | Registra trazas para llamadas compatibles con OpenTelemetry            | [Documentación](https://www.comet.com/docs/opik/tracing/opentelemetry/overview?utm_source=opik&utm_medium=github&utm_content=opentelemetry_link&utm_campaign=opik)             |
| OpenWebUI             | Registra trazas para conversaciones de OpenWebUI                  | [Documentación](https://www.comet.com/docs/opik/integrations/openwebui?utm_source=opik&utm_medium=github&utm_content=openwebui_link&utm_campaign=opik)                         |
| Pipecat               | Registra trazas para llamadas de agentes de voz en tiempo real de Pipecat      | [Documentación](https://www.comet.com/docs/opik/integrations/pipecat?utm_source=opik&utm_medium=github&utm_content=pipecat_link&utm_campaign=opik)                             |
| Predibase             | Registra trazas para llamadas a LLM de Predibase                      | [Documentación](https://www.comet.com/docs/opik/integrations/predibase?utm_source=opik&utm_medium=github&utm_content=predibase_link&utm_campaign=opik)                         |
| Pydantic AI           | Registra trazas para llamadas de agentes de PydanticAI                   | [Documentación](https://www.comet.com/docs/opik/integrations/pydantic-ai?utm_source=opik&utm_medium=github&utm_content=pydantic_ai_link&utm_campaign=opik)                     |
| Ragas                 | Registra trazas para evaluaciones de Ragas                        | [Documentación](https://www.comet.com/docs/opik/integrations/ragas?utm_source=opik&utm_medium=github&utm_content=ragas_link&utm_campaign=opik)                                 |
| Semantic Kernel       | Registra trazas para llamadas de Microsoft Semantic Kernel          | [Documentación](https://www.comet.com/docs/opik/integrations/semantic-kernel?utm_source=opik&utm_medium=github&utm_content=semantic_kernel_link&utm_campaign=opik)             |
| Smolagents            | Registra trazas para agentes de Smolagents                        | [Documentación](https://www.comet.com/docs/opik/integrations/smolagents?utm_source=opik&utm_medium=github&utm_content=smolagents_link&utm_campaign=opik)                       |
| Spring AI             | Registra trazas para llamadas del framework Spring AI                | [Documentación](https://www.comet.com/docs/opik/integrations/spring-ai?utm_source=opik&utm_medium=github&utm_content=spring_ai_link&utm_campaign=opik)                         |
| Strands Agents        | Registra trazas para llamadas de Strands agents                     | [Documentación](https://www.comet.com/docs/opik/integrations/strands-agents?utm_source=opik&utm_medium=github&utm_content=strands_agents_link&utm_campaign=opik)               |
| Together AI           | Registra trazas para llamadas a LLM de Together AI                    | [Documentación](https://www.comet.com/docs/opik/integrations/together-ai?utm_source=opik&utm_medium=github&utm_content=together_ai_link&utm_campaign=opik)                     |
| Vercel AI SDK         | Registra trazas para llamadas del Vercel AI SDK                      | [Documentación](https://www.comet.com/docs/opik/integrations/vercel-ai-sdk?utm_source=opik&utm_medium=github&utm_content=vercel_ai_sdk_link&utm_campaign=opik)                 |
| VoltAgent             | Registra trazas para llamadas del framework de agentes VoltAgent          | [Documentación](https://www.comet.com/docs/opik/integrations/voltagent?utm_source=opik&utm_medium=github&utm_content=voltagent_link&utm_campaign=opik)                         |
| WatsonX               | Registra trazas para llamadas a LLM de IBM watsonx                    | [Documentación](https://www.comet.com/docs/opik/integrations/watsonx?utm_source=opik&utm_medium=github&utm_content=watsonx_link&utm_campaign=opik)                             |
| xAI Grok              | Registra trazas para llamadas a LLM de xAI Grok                       | [Documentación](https://www.comet.com/docs/opik/integrations/xai-grok?utm_source=opik&utm_medium=github&utm_content=xai_grok_link&utm_campaign=opik)                           |

> [!TIP]
> Si el framework que utilizas no aparece en la lista anterior, no dudes en [abrir un issue](https://github.com/comet-ml/opik/issues) o enviar un PR con la integración.

Si no utilizas ninguno de los frameworks anteriores, también puedes usar el decorador de función `track` para [registrar trazas](https://www.comet.com/docs/opik/v1/tracing/log_traces/?from=llm&utm_source=opik&utm_medium=github&utm_content=traces_link&utm_campaign=opik):

```python
import opik

opik.configure(use_local=True) # Run locally

@opik.track
def my_llm_function(user_question: str) -> str:
    # Your LLM code here

    return "Hello"
```

> [!TIP]
> El decorador track puede usarse junto con cualquiera de nuestras integraciones y también puede utilizarse para rastrear llamadas a funciones anidadas.

<a id="-llm-as-a-judge-metrics"></a>
### 🧑‍⚖️ Métricas de LLM como juez

El SDK de Python de Opik incluye una serie de métricas de LLM como juez para ayudarte a evaluar tu aplicación de LLM. Obtén más información al respecto en la [documentación de métricas](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_2_link&utm_campaign=opik).

Para usarlas, simplemente importa la métrica correspondiente y usa la función `score`:

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

Opik también incluye una serie de métricas heurísticas prediseñadas, así como la capacidad de crear las tuyas propias. Obtén más información al respecto en la [documentación de métricas](https://www.comet.com/docs/opik/evaluation/metrics/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_3_link&utm_campaign=opik).

<a id="-evaluating-your-llm-application"></a>
### 🔍 Evaluación de tus aplicaciones de LLM

Opik te permite evaluar tu aplicación de LLM durante el desarrollo a través de [Conjuntos de datos](https://www.comet.com/docs/opik/v1/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_2_link&utm_campaign=opik) y [Experimentos](https://www.comet.com/docs/opik/v1/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=experiments_link&utm_campaign=opik). El Panel de Opik ofrece gráficos mejorados para experimentos y un mejor manejo de trazas grandes. También puedes ejecutar evaluaciones como parte de tu canalización de CI/CD usando nuestra [integración con PyTest](https://www.comet.com/docs/opik/v1/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_2_link&utm_campaign=opik).

<a id="-star-us-on-github"></a>
## ⭐ Danos una estrella en GitHub

Si Opik te resulta útil, ¡considera darnos una estrella! Tu apoyo nos ayuda a hacer crecer nuestra comunidad y a seguir mejorando el producto.

[![Gráfico del historial de estrellas](https://api.star-history.com/svg?repos=comet-ml/opik&type=Date)](https://github.com/comet-ml/opik)

<a id="-contributing"></a>
## 🤝 Contribuir

Hay muchas formas de contribuir a Opik:

- Envía [informes de errores](https://github.com/comet-ml/opik/issues) y [solicitudes de funciones](https://github.com/comet-ml/opik/issues)
- Revisa la documentación y envía [Pull Requests](https://github.com/comet-ml/opik/pulls) para mejorarla
- Habla o escribe sobre Opik y [háznoslo saber](https://chat.comet.com)
- Vota a favor de [solicitudes de funciones populares](https://github.com/comet-ml/opik/issues?q=is%3Aissue+is%3Aopen+label%3A%22enhancement%22) para mostrar tu apoyo

Para obtener más información sobre cómo contribuir a Opik, consulta nuestras [pautas de contribución](CONTRIBUTING.md).
