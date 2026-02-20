> Aviso: este arquivo foi traduzido automaticamente usando IA. Contribuições para melhorar a tradução são bem-vindas!

<div align="center"><b><a href="README.md">English</a> | <a href="readme_CN.md">简体中文</a> | <a href="readme_JP.md">日本語</a> | <a href="readme_PT_BR.md">Português (Brasil)</a> | <a href="readme_KO.md">한국어</a></b></div>


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
<h2 align="center" style="border-bottom: none">Plataforma de observabilidade, avaliação e otimização de IA de código aberto</h2>
<p align="center">
O Opik ajuda você a construir, testar e otimizar aplicações de IA generativa que funcionam melhor, do protótipo à produção. De chatbots RAG a assistentes de código e pipelines agentes complexos, o Opik fornece rastreamento abrangente, avaliação e otimização automática de prompts e ferramentas para eliminar as suposições no desenvolvimento de IA.
</p>

<div align="center">

[![Python SDK](https://img.shields.io/pypi/v/opik)](https://pypi.org/project/opik/)
[![License](https://img.shields.io/github/license/comet-ml/opik)](https://github.com/comet-ml/opik/blob/main/LICENSE)
[![Build](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml/badge.svg)](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml)
[![Bounties](https://img.shields.io/endpoint?url=https%3A%2F%2Falgora.io%2Fapi%2Fshields%2Fcomet-ml%2Fbounties%3Fstatus%3Dopen)](https://algora.io/comet-ml/bounties?status=open)

<!-- [![Quick Start](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/opik_quickstart.ipynb) -->

</div>

<p align="center">
    <a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=website_button&utm_campaign=opik"><b>Website</b></a> •
    <a href="https://chat.comet.com"><b>Comunidade Slack</b></a> •
    <a href="https://x.com/Cometml"><b>Twitter</b></a> •
    <a href="https://www.comet.com/docs/opik/changelog"><b>Changelog</b></a> •
    <a href="https://www.comet.com/docs/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=docs_button&utm_campaign=opik"><b>Documentação</b></a>
</p>

<div align="center" style="margin-top: 1em; margin-bottom: 1em;">
<a href="#-o-que-%C3%A9-o-opik">🚀 O que é o Opik?</a> • <a href="#%EF%B8%8F-instala%C3%A7%C3%A3o-do-servidor-opik">🛠️ Instalação do Servidor Opik</a> • <a href="#-sdk-cliente-opik">💻 SDK Cliente Opik</a> • <a href="#-registro-de-traces-com-integra%C3%A7%C3%B5es">📝 Registro de Traces</a><br>
<a href="#-m%C3%A9tricas-llm-como-juiz">🧑‍⚖️ LLM como Juiz</a> • <a href="#-avaliando-sua-aplica%C3%A7%C3%A3o-llm">🔍 Avaliando sua Aplicação</a> • <a href="#-d%C3%AA-nos-uma-estrela-no-github">⭐ Dê-nos uma estrela</a> • <a href="#-contribuir">🤝 Contribuir</a>
</div>

<br>

[![Opik platform screenshot (thumbnail)](readme-thumbnail-new.png)](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=readme_banner&utm_campaign=opik)

## 🚀 O que é o Opik?

Opik (desenvolvido pela [Comet](https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=what_is_opik_link&utm_campaign=opik)) é uma plataforma open-source projetada para simplificar todo o ciclo de vida de aplicações LLM. Ela capacita desenvolvedores a avaliar, testar, monitorar e otimizar seus modelos e sistemas agentes. As principais ofertas incluem:

- **Observabilidade abrangente**: rastreamento detalhado de chamadas LLM, registro de conversas e atividade de agentes.
- **Avaliação avançada**: avaliação robusta de prompts, LLM-como-juiz e gerenciamento de experimentos.
- **Pronto para produção**: painéis de monitoramento escaláveis e regras de avaliação online para produção.
- **Opik Agent Optimizer**: SDK dedicado e conjunto de otimizadores para melhorar prompts e agentes.
- **Opik Guardrails**: recursos para ajudar a implementar práticas de IA seguras e responsáveis.

<br>

Principais capacidades incluem:

- **Desenvolvimento & Rastreamento:**
  - Rastreie todas as chamadas LLM e traces com contexto detalhado durante o desenvolvimento e em produção ([Quickstart](https://www.comet.com/docs/opik/quickstart/?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_link&utm_campaign=opik)).
  - Integrações extensas de terceiros para observabilidade: integre facilmente com uma lista crescente de frameworks, suportando muitos dos maiores e mais populares nativamente (incluindo adições recentes como **Google ADK**, **Autogen** e **Flowise AI**). ([Integrations](https://www.comet.com/docs/opik/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik))
  - Anote traces e spans com pontuações de feedback via o [Python SDK](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link&utm_campaign=opik) ou a [UI](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-through-the-ui?from=llm&utm_source=opik&utm_medium=github&utm_content=ui_link&utm_campaign=opik).
  - Experimente prompts e modelos no [Prompt Playground](https://www.comet.com/docs/opik/prompt_engineering/playground).

- **Avaliação & Testes**:
  - Automatize a avaliação de sua aplicação LLM com [Datasets](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_link&utm_campaign=opik) e [Experimentos](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=eval_link&utm_campaign=opik).
  - Aproveite métricas poderosas de LLM-como-juiz para tarefas complexas como [detecção de alucinações](https://www.comet.com/docs/opik/evaluation/metrics/hallucination/?from=llm&utm_source=opik&utm_medium=github&utm_content=hallucination_link&utm_campaign=opik), [moderação](https://www.comet.com/docs/opik/evaluation/metrics/moderation/?from=llm&utm_source=opik&utm_medium=github&utm_content=moderation_link&utm_campaign=opik) e avaliação RAG ([Relevância da Resposta](https://www.comet.com/docs/opik/evaluation/metrics/answer_relevance/?from=llm&utm_source=opik&utm_medium=github&utm_content=alex_link&utm_campaign=opik), [Precisão de Contexto](https://www.comet.com/docs/opik/evaluation/metrics/context_precision/?from=llm&utm_source=opik&utm_medium=github&utm_content=context_link&utm_campaign=opik)).
  - Integre avaliações ao seu pipeline CI/CD com nossa [integração PyTest](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_link&utm_campaign=opik).

- **Monitoramento de Produção & Otimização**:
  - Faça log de grandes volumes de traces de produção: o Opik é projetado para escala (40M+ traces/dia).
  - Monitore pontuações de feedback, contagem de traces e uso de tokens ao longo do tempo no [Painel Opik](https://www.comet.com/docs/opik/production/production_monitoring/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik).
  - Utilize [Regras de Avaliação Online](https://www.comet.com/docs/opik/production/rules/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik) com métricas de LLM-como-juiz para identificar problemas em produção.
  - Utilize o **Opik Agent Optimizer** e o **Opik Guardrails** para melhorar continuamente e proteger suas aplicações LLM em produção.

> [!TIP]
> Se você está procurando recursos que o Opik ainda não oferece, por favor abra uma nova [solicitação de recurso](https://github.com/comet-ml/opik/issues/new/choose) 🚀

<br>

## 🛠️ Instalação do Servidor Opik

Coloque seu servidor Opik em funcionamento em minutos. Escolha a opção que melhor atende às suas necessidades:

### Opção 1: Comet.com Cloud (Mais fácil e recomendado)

Acesse o Opik instantaneamente sem qualquer configuração. Ideal para começar rápido e sem manutenção.

👉 [Crie sua conta gratuita na Comet](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=install_create_link&utm_campaign=opik)

### Opção 2: Hospede o Opik para ter Controle Total

Implemente o Opik em seu próprio ambiente. Escolha entre Docker para setups locais ou Kubernetes para escalabilidade.

#### Self-Hosting com Docker Compose (para Desenvolvimento Local e Testes)

Esta é a forma mais simples de obter uma instância local do Opik em execução. Observe o novo script de instalação `./opik.sh`:

No ambiente Linux ou Mac:

```bash
# Clone o repositório Opik
git clone https://github.com/comet-ml/opik.git

# Navegue até o repositório
cd opik

# Inicie a plataforma Opik
./opik.sh
```

No ambiente Windows:

```powershell
# Clone o repositório Opik
git clone https://github.com/comet-ml/opik.git

# Navegue até o repositório
cd opik

# Inicie a plataforma Opik
powershell -ExecutionPolicy ByPass -c ".\\opik.ps1"
```

**Perfis de serviço para desenvolvimento**

O script de instalação do Opik agora suporta perfis de serviço para diferentes cenários de desenvolvimento:

```bash
# Iniciar suíte completa do Opik (comportamento padrão)
./opik.sh

# Iniciar apenas serviços de infraestrutura (bancos de dados, caches etc.)
./opik.sh --infra

# Iniciar infraestrutura + serviços backend
./opik.sh --backend

# Habilitar guardrails com qualquer perfil
./opik.sh --guardrails # Guardrails com a suíte completa do Opik
./opik.sh --backend --guardrails # Guardrails com infraestrutura + backend
```

Use as opções `--help` ou `--info` para solucionar problemas. Os Dockerfiles agora garantem que os containers sejam executados como usuários não-root para maior segurança. Quando tudo estiver em execução, acesse [localhost:5173](http://localhost:5173) no seu navegador! Para instruções detalhadas, veja o [Guia de Implantação Local](https://www.comet.com/docs/opik/self-host/local_deployment?from=llm&utm_source=opik&utm_medium=github&utm_content=self_host_link&utm_campaign=opik).

#### Self-Hosting com Kubernetes & Helm (para Implantações Escaláveis)

Para implantações de produção ou em maior escala, o Opik pode ser instalado em um cluster Kubernetes usando nosso chart Helm. Clique no badge para o [Guia de Instalação Kubernetes via Helm](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik).

[![Kubernetes](https://img.shields.io/badge/Kubernetes-%23326ce5.svg?&logo=kubernetes&logoColor=white)](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik)

> [!IMPORTANT]
> **Versão 1.7.0 — Alterações**: Verifique o [changelog](https://github.com/comet-ml/opik/blob/main/CHANGELOG.md) para atualizações importantes e mudanças que quebram compatibilidade.

## 💻 SDK Cliente Opik

O Opik fornece um conjunto de bibliotecas cliente e uma API REST para interagir com o servidor Opik. Isso inclui SDKs para Python, TypeScript e Ruby (via OpenTelemetry), permitindo integração fluida em seus fluxos de trabalho. Para referências de API e SDK, consulte a [Documentação de Referência do Cliente Opik](https://www.comet.com/docs/opik/reference/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=reference_link&utm_campaign=opik).

### Início Rápido com o SDK Python

Para começar com o SDK Python:

Instale o pacote:

```bash
# instalar usando pip
pip install opik

# ou instalar com uv
uv pip install opik
```

Configure o SDK Python executando o comando `opik configure`, que solicitará o endereço do servidor Opik (para instâncias self-hosted) ou sua chave de API e workspace (para Comet.com):

```bash
opik configure
```

> [!TIP]
> Você também pode chamar `opik.configure(use_local=True)` do seu código Python para configurar o SDK para execução local self-hosted, ou fornecer a chave de API e workspace diretamente para Comet.com. Consulte a [documentação do SDK Python](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=python_sdk_docs_link&utm_campaign=opik) para mais opções de configuração.

Agora você está pronto para começar a registrar traces usando o [SDK Python](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link2&utm_campaign=opik).

### 📝 Registro de Traces com Integrações

A maneira mais fácil de registrar traces é usar uma de nossas integrações diretas. O Opik suporta uma ampla gama de frameworks, incluindo adições recentes como **Google ADK**, **Autogen**, **AG2** e **Flowise AI**:

| Integração            | Descrição                                                              | Documentação                                                                                                                                                                   |
| --------------------- | ---------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| ADK                   | Registra traces para Google Agent Development Kit (ADK)                | [Documentação](https://www.comet.com/docs/opik/integrations/adk?utm_source=opik&utm_medium=github&utm_content=google_adk_link&utm_campaign=opik)                              |
| AG2                   | Registra traces para chamadas LLM do AG2                               | [Documentação](https://www.comet.com/docs/opik/integrations/ag2?utm_source=opik&utm_medium=github&utm_content=ag2_link&utm_campaign=opik)                                     |
| AIsuite               | Registra traces para chamadas LLM do AIsuite                           | [Documentação](https://www.comet.com/docs/opik/integrations/aisuite?utm_source=opik&utm_medium=github&utm_content=aisuite_link&utm_campaign=opik)                             |
| Agno                  | Registra traces para chamadas do framework de orquestração Agno        | [Documentação](https://www.comet.com/docs/opik/integrations/agno?utm_source=opik&utm_medium=github&utm_content=agno_link&utm_campaign=opik)                                   |
| Anthropic             | Registra traces para chamadas LLM da Anthropic                         | [Documentação](https://www.comet.com/docs/opik/integrations/anthropic?utm_source=opik&utm_medium=github&utm_content=anthropic_link&utm_campaign=opik)                         |
| Autogen               | Registra traces para fluxos de trabalho agentic do Autogen             | [Documentação](https://www.comet.com/docs/opik/integrations/autogen?utm_source=opik&utm_medium=github&utm_content=autogen_link&utm_campaign=opik)                             |
| Bedrock               | Registra traces para chamadas LLM do Amazon Bedrock                    | [Documentação](https://www.comet.com/docs/opik/integrations/bedrock?utm_source=opik&utm_medium=github&utm_content=bedrock_link&utm_campaign=opik)                             |
| BeeAI (Python)        | Registra traces para chamadas do framework de agentes BeeAI (Python)   | [Documentação](https://www.comet.com/docs/opik/integrations/beeai?utm_source=opik&utm_medium=github&utm_content=beeai_link&utm_campaign=opik)                                 |
| BeeAI (TypeScript)    | Registra traces para chamadas do framework de agentes BeeAI (TS)       | [Documentação](https://www.comet.com/docs/opik/integrations/beeai-typescript?utm_source=opik&utm_medium=github&utm_content=beeai_typescript_link&utm_campaign=opik)           |
| BytePlus              | Registra traces para chamadas LLM do BytePlus                          | [Documentação](https://www.comet.com/docs/opik/integrations/byteplus?utm_source=opik&utm_medium=github&utm_content=byteplus_link&utm_campaign=opik)                           |
| Cloudflare Workers AI | Registra traces para chamadas do Cloudflare Workers AI                 | [Documentação](https://www.comet.com/docs/opik/integrations/cloudflare-workers-ai?utm_source=opik&utm_medium=github&utm_content=cloudflare_workers_ai_link&utm_campaign=opik) |
| Cohere                | Registra traces para chamadas LLM da Cohere                            | [Documentação](https://www.comet.com/docs/opik/integrations/cohere?utm_source=opik&utm_medium=github&utm_content=cohere_link&utm_campaign=opik)                               |
| CrewAI                | Registra traces para chamadas do CrewAI                                | [Documentação](https://www.comet.com/docs/opik/integrations/crewai?utm_source=opik&utm_medium=github&utm_content=crewai_link&utm_campaign=opik)                               |
| Cursor                | Registra traces para conversas do Cursor                               | [Documentação](https://www.comet.com/docs/opik/integrations/cursor?utm_source=opik&utm_medium=github&utm_content=cursor_link&utm_campaign=opik)                               |
| DeepSeek              | Registra traces para chamadas LLM do DeepSeek                          | [Documentação](https://www.comet.com/docs/opik/integrations/deepseek?utm_source=opik&utm_medium=github&utm_content=deepseek_link&utm_campaign=opik)                           |
| Dify                  | Registra traces para execuções de agentes do Dify                      | [Documentação](https://www.comet.com/docs/opik/integrations/dify?utm_source=opik&utm_medium=github&utm_content=dify_link&utm_campaign=opik)                                   |
| DSPY                  | Registra traces para execuções do DSPy                                 | [Documentação](https://www.comet.com/docs/opik/integrations/dspy?utm_source=opik&utm_medium=github&utm_content=dspy_link&utm_campaign=opik)                                   |
| Fireworks AI          | Registra traces para chamadas LLM do Fireworks AI                      | [Documentação](https://www.comet.com/docs/opik/integrations/fireworks-ai?utm_source=opik&utm_medium=github&utm_content=fireworks_ai_link&utm_campaign=opik)                   |
| Flowise AI            | Registra traces para o construtor visual Flowise AI                    | [Documentação](https://www.comet.com/docs/opik/integrations/flowise?utm_source=opik&utm_medium=github&utm_content=flowise_link&utm_campaign=opik)                             |
| Gemini (Python)       | Registra traces para chamadas LLM do Google Gemini                     | [Documentação](https://www.comet.com/docs/opik/integrations/gemini?utm_source=opik&utm_medium=github&utm_content=gemini_link&utm_campaign=opik)                               |
| Gemini (TypeScript)   | Registra traces para chamadas do SDK TypeScript do Google Gemini       | [Documentação](https://www.comet.com/docs/opik/integrations/gemini-typescript?utm_source=opik&utm_medium=github&utm_content=gemini_typescript_link&utm_campaign=opik)         |
| Groq                  | Registra traces para chamadas LLM do Groq                              | [Documentação](https://www.comet.com/docs/opik/integrations/groq?utm_source=opik&utm_medium=github&utm_content=groq_link&utm_campaign=opik)                                   |
| Guardrails            | Registra traces para validações do Guardrails AI                       | [Documentação](https://www.comet.com/docs/opik/integrations/guardrails-ai?utm_source=opik&utm_medium=github&utm_content=guardrails_link&utm_campaign=opik)                    |
| Haystack              | Registra traces para chamadas do Haystack                              | [Documentação](https://www.comet.com/docs/opik/integrations/haystack?utm_source=opik&utm_medium=github&utm_content=haystack_link&utm_campaign=opik)                           |
| Harbor                | Registra traces para avaliações de benchmark do Harbor                  | [Documentação](https://www.comet.com/docs/opik/integrations/harbor?utm_source=opik&utm_medium=github&utm_content=harbor_link&utm_campaign=opik)                               |
| Instructor            | Registra traces para chamadas LLM feitas com Instructor                 | [Documentação](https://www.comet.com/docs/opik/integrations/instructor?utm_source=opik&utm_medium=github&utm_content=instructor_link&utm_campaign=opik)                       |
| LangChain (Python)    | Registra traces para chamadas LLM do LangChain                         | [Documentação](https://www.comet.com/docs/opik/integrations/langchain?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik)                         |
| LangChain (JS/TS)     | Registra traces para chamadas do LangChain em JavaScript/TypeScript    | [Documentação](https://www.comet.com/docs/opik/integrations/langchainjs?utm_source=opik&utm_medium=github&utm_content=langchainjs_link&utm_campaign=opik)                     |
| LangGraph             | Registra traces para execuções do LangGraph                            | [Documentação](https://www.comet.com/docs/opik/integrations/langgraph?utm_source=opik&utm_medium=github&utm_content=langgraph_link&utm_campaign=opik)                         |
| Langflow              | Registra traces para o construtor visual Langflow                      | [Documentação](https://www.comet.com/docs/opik/integrations/langflow?utm_source=opik&utm_medium=github&utm_content=langflow_link&utm_campaign=opik)                           |
| LiteLLM               | Registra traces para chamadas de modelos via LiteLLM                   | [Documentação](https://www.comet.com/docs/opik/integrations/litellm?utm_source=opik&utm_medium=github&utm_content=litellm_link&utm_campaign=opik)                             |
| LiveKit Agents        | Registra traces para chamadas do framework de agentes LiveKit          | [Documentação](https://www.comet.com/docs/opik/integrations/livekit?utm_source=opik&utm_medium=github&utm_content=livekit_link&utm_campaign=opik)                             |
| LlamaIndex            | Registra traces para chamadas LLM do LlamaIndex                        | [Documentação](https://www.comet.com/docs/opik/integrations/llama_index?utm_source=opik&utm_medium=github&utm_content=llama_index_link&utm_campaign=opik)                     |
| Mastra                | Registra traces para chamadas do framework de workflows Mastra         | [Documentação](https://www.comet.com/docs/opik/integrations/mastra?utm_source=opik&utm_medium=github&utm_content=mastra_link&utm_campaign=opik)                               |
| Microsoft Agent Framework (Python) | Registra traces para chamadas do Microsoft Agent Framework | [Documentação](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework?utm_source=opik&utm_medium=github&utm_content=agent_framework_link&utm_campaign=opik)   |
| Microsoft Agent Framework (.NET)   | Registra traces para chamadas .NET do Microsoft Agent Framework | [Documentação](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework-dotnet?utm_source=opik&utm_medium=github&utm_content=agent_framework_dotnet_link&utm_campaign=opik) |
| Mistral AI            | Registra traces para chamadas LLM do Mistral AI                        | [Documentação](https://www.comet.com/docs/opik/integrations/mistral?utm_source=opik&utm_medium=github&utm_content=mistral_link&utm_campaign=opik)                             |
| n8n                   | Registra traces para execuções de workflows n8n                        | [Documentação](https://www.comet.com/docs/opik/integrations/n8n?utm_source=opik&utm_medium=github&utm_content=n8n_link&utm_campaign=opik)                                     |
| Novita AI             | Registra traces para chamadas LLM do Novita AI                         | [Documentação](https://www.comet.com/docs/opik/integrations/novita-ai?utm_source=opik&utm_medium=github&utm_content=novita_ai_link&utm_campaign=opik)                         |
| Ollama                | Registra traces para chamadas LLM do Ollama                            | [Documentação](https://www.comet.com/docs/opik/integrations/ollama?utm_source=opik&utm_medium=github&utm_content=ollama_link&utm_campaign=opik)                               |
| OpenAI (Python)       | Registra traces para chamadas LLM da OpenAI                            | [Documentação](https://www.comet.com/docs/opik/integrations/openai?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik)                               |
| OpenAI (JS/TS)        | Registra traces para chamadas da OpenAI em JavaScript/TypeScript       | [Documentação](https://www.comet.com/docs/opik/integrations/openai-typescript?utm_source=opik&utm_medium=github&utm_content=openai_typescript_link&utm_campaign=opik)         |
| OpenAI Agents         | Registra traces para chamadas do SDK OpenAI Agents                     | [Documentação](https://www.comet.com/docs/opik/integrations/openai_agents?utm_source=opik&utm_medium=github&utm_content=openai_agents_link&utm_campaign=opik)                 |
| OpenRouter            | Registra traces para chamadas LLM do OpenRouter                         | [Documentação](https://www.comet.com/docs/opik/integrations/openrouter?utm_source=opik&utm_medium=github&utm_content=openrouter_link&utm_campaign=opik)                       |
| OpenTelemetry         | Registra traces para chamadas compatíveis com OpenTelemetry            | [Documentação](https://www.comet.com/docs/opik/tracing/opentelemetry/overview?utm_source=opik&utm_medium=github&utm_content=opentelemetry_link&utm_campaign=opik)             |
| OpenWebUI             | Registra traces para conversas no OpenWebUI                            | [Documentação](https://www.comet.com/docs/opik/integrations/openwebui?utm_source=opik&utm_medium=github&utm_content=openwebui_link&utm_campaign=opik)                         |
| Pipecat               | Registra traces para chamadas de agentes de voz em tempo real do Pipecat | [Documentação](https://www.comet.com/docs/opik/integrations/pipecat?utm_source=opik&utm_medium=github&utm_content=pipecat_link&utm_campaign=opik)                           |
| Predibase             | Registra traces para chamadas LLM do Predibase                          | [Documentação](https://www.comet.com/docs/opik/integrations/predibase?utm_source=opik&utm_medium=github&utm_content=predibase_link&utm_campaign=opik)                         |
| Pydantic AI           | Registra traces para chamadas de agentes PydanticAI                    | [Documentação](https://www.comet.com/docs/opik/integrations/pydantic-ai?utm_source=opik&utm_medium=github&utm_content=pydantic_ai_link&utm_campaign=opik)                     |
| Ragas                 | Registra traces para avaliações Ragas                                  | [Documentação](https://www.comet.com/docs/opik/integrations/ragas?utm_source=opik&utm_medium=github&utm_content=ragas_link&utm_campaign=opik)                                 |
| Semantic Kernel       | Registra traces para chamadas do Microsoft Semantic Kernel              | [Documentação](https://www.comet.com/docs/opik/integrations/semantic-kernel?utm_source=opik&utm_medium=github&utm_content=semantic_kernel_link&utm_campaign=opik)             |
| Smolagents            | Registra traces para agentes Smolagents                                 | [Documentação](https://www.comet.com/docs/opik/integrations/smolagents?utm_source=opik&utm_medium=github&utm_content=smolagents_link&utm_campaign=opik)                       |
| Spring AI             | Registra traces para chamadas do framework Spring AI                    | [Documentação](https://www.comet.com/docs/opik/integrations/spring-ai?utm_source=opik&utm_medium=github&utm_content=spring_ai_link&utm_campaign=opik)                         |
| Strands Agents        | Registra traces para chamadas de agentes Strands                        | [Documentação](https://www.comet.com/docs/opik/integrations/strands-agents?utm_source=opik&utm_medium=github&utm_content=strands_agents_link&utm_campaign=opik)               |
| Together AI           | Registra traces para chamadas LLM do Together AI                        | [Documentação](https://www.comet.com/docs/opik/integrations/together-ai?utm_source=opik&utm_medium=github&utm_content=together_ai_link&utm_campaign=opik)                     |
| Vercel AI SDK         | Registra traces para chamadas via Vercel AI SDK                         | [Documentação](https://www.comet.com/docs/opik/integrations/vercel-ai-sdk?utm_source=opik&utm_medium=github&utm_content=vercel_ai_sdk_link&utm_campaign=opik)                 |
| VoltAgent             | Registra traces para chamadas do framework de agentes VoltAgent         | [Documentação](https://www.comet.com/docs/opik/integrations/voltagent?utm_source=opik&utm_medium=github&utm_content=voltagent_link&utm_campaign=opik)                         |
| WatsonX               | Registra traces para chamadas LLM do IBM watsonx                        | [Documentação](https://www.comet.com/docs/opik/integrations/watsonx?utm_source=opik&utm_medium=github&utm_content=watsonx_link&utm_campaign=opik)                             |
| xAI Grok              | Registra traces para chamadas LLM do xAI Grok                           | [Documentação](https://www.comet.com/docs/opik/integrations/xai-grok?utm_source=opik&utm_medium=github&utm_content=xai_grok_link&utm_campaign=opik)                           |

> [!TIP]
> Se o framework que você está usando não estiver na lista, sinta-se à vontade para [abrir uma issue](https://github.com/comet-ml/opik/issues) ou enviar um PR com a integração.

Se você não estiver usando nenhum dos frameworks acima, também pode usar o decorador `track` para [registrar traces](https://www.comet.com/docs/opik/tracing/log_traces/?from=llm&utm_source=opik&utm_medium=github&utm_content=traces_link&utm_campaign=opik):

```python
import opik

opik.configure(use_local=True) # Executar localmente

@opik.track
def my_llm_function(user_question: str) -> str:
    # Seu código LLM aqui

    return "Hello"
```

> [!TIP]
> O decorador track pode ser usado em conjunto com qualquer uma de nossas integrações e também pode ser usado para rastrear chamadas de função aninhadas.

### 🧑‍⚖️ Métricas LLM como Juiz

O SDK Python do Opik inclui diversas métricas LLM-como-juiz para ajudá-lo a avaliar sua aplicação LLM. Saiba mais na [documentação de métricas](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_2_link&utm_campaign=opik).

Para usá-las, importe a métrica relevante e use a função `score`:

```python
from opik.evaluation.metrics import Hallucination

metric = Hallucination()
score = metric.score(
    input="Qual é a capital da França?",
    output="Paris",
    context=["A França é um país na Europa."]
)
print(score)
```

O Opik também inclui diversas métricas heurísticas pré-construídas, além da possibilidade de criar suas próprias. Saiba mais na [documentação de métricas](https://www.comet.com/docs/opik/evaluation/metrics/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_3_link&utm_campaign=opik).

### 🔍 Avaliando sua Aplicação LLM

O Opik permite avaliar sua aplicação LLM durante o desenvolvimento por meio de [Datasets](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_2_link&utm_campaign=opik) e [Experimentos](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=experiments_link&utm_campaign=opik). O Painel Opik oferece gráficos aprimorados para experimentos e melhor manuseio de traces grandes. Você também pode executar avaliações como parte do seu pipeline CI/CD usando nossa [integração PyTest](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_2_link&utm_campaign=opik).

## ⭐ Dê-nos uma estrela no GitHub

Se você achar o Opik útil, considere nos dar uma estrela! Seu apoio nos ajuda a crescer nossa comunidade e continuar melhorando o produto.

[![Star History Chart](https://api.star-history.com/svg?repos=comet-ml/opik&type=Date)](https://github.com/comet-ml/opik)

## 🤝 Contribuir

Há muitas maneiras de contribuir com o Opik:

- Envie [relatórios de bugs](https://github.com/comet-ml/opik/issues) e [solicitações de recursos](https://github.com/comet-ml/opik/issues)
- Revise a documentação e envie [Pull Requests](https://github.com/comet-ml/opik/pulls) para melhorá-la
- Fale ou escreva sobre o Opik e [avise-nos](https://chat.comet.com)
- Apoie [solicitações de recursos populares](https://github.com/comet-ml/opik/issues?q=is%3Aissue+is%3Aopen+label%3A%22enhancement%22) para mostrar seu apoio

Para saber mais sobre como contribuir com o Opik, veja nossas [diretrizes de contribuição](CONTRIBUTING.md).
