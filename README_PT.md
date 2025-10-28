<div class="center"><b><a href="README.md">English</a> | <a href="readme_CN.md">简体中文</a> | <a href="readme_JP.md">日本語</a> | <a href="readme_KO.md">한국어</a></b></div>

<h1 class="center" style="border-bottom: none">
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

<h2 class="center" style="border-bottom: none">Plataforma de avaliação de LLM de código aberto</h2>

<p class="center">
Opik ajuda você a construir, avaliar e otimizar sistemas LLM que funcionam melhor, mais rápido e com menor custo. De chatbots RAG a assistentes de código e pipelines agentes complexos, o Opik fornece rastreamento completo, avaliações, painéis e recursos poderosos como <b>Opik Agent Optimizer</b> e <b>Opik Guardrails</b> para melhorar e proteger suas aplicações LLM em produção.
</p>

<div class="center badges">

[![Python SDK](https://img.shields.io/pypi/v/opik)](https://pypi.org/project/opik/)
[![License](https://img.shields.io/github/license/comet-ml/opik)](https://github.com/comet-ml/opik/blob/main/LICENSE)
[![Build](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml/badge.svg)](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml)
[![Bounties](https://img.shields.io/endpoint?url=https%3A%2F%2Falgora.io%2Fapi%2Fshields%2Fcomet-ml%2Fbounties%3Fstatus%3Dopen)](https://algora.io/comet-ml/bounties?status=open)

</div>

<p class="center">
  <a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=website_button&utm_campaign=opik"><b>Website</b></a> •
  <a href="https://chat.comet.com"><b>Comunidade Slack</b></a> •
  <a href="https://x.com/Cometml"><b>Twitter</b></a> •
  <a href="https://www.comet.com/docs/opik/changelog"><b>Changelog</b></a> •
  <a href="https://www.comet.com/docs/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=docs_button&utm_campaign=opik"><b>Documentação</b></a>
</p>

<div class="center" style="margin-top: 1em; margin-bottom: 1em;">
<a href="#-o-que-e-opik">🚀 O que é o Opik?</a> • <a href="#%EF%B8%8F-instala%C3%A7%C3%A3o-do-servidor-opik">🛠️ Instalação do servidor Opik</a> • <a href="#-sdk-cliente-opik">💻 SDK Cliente Opik</a> • <a href="#-registro-de-traces-com-integra%C3%A7%C3%B5es">📝 Registro de Traces</a><br>
<a href="#-llm-como-um-juiz-m%C3%A9tricas">🧑‍⚖️ LLM como Juiz</a> • <a href="#-avaliando-sua-aplica%C3%A7%C3%A3o-llm">🔍 Avaliando sua Aplicação</a> • <a href="#-nos-deixe-uma-estrela-no-github">⭐ Dê-nos uma estrela</a> • <a href="#-contribuir">🤝 Contribuir</a>
</div>

<br>

<a href="https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=readme_banner&utm_campaign=opik"><img src="readme-thumbnail-new.png" alt="Opik platform screenshot (thumbnail)" class="screenshot"/></a>

## 🚀 O que é o Opik?

Opik (desenvolvido pela [Comet](https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=what_is_opik_link&utm_campaign=opik)) é uma plataforma open-source projetada para simplificar todo o ciclo de vida de aplicações LLM. Permite que desenvolvedores avaliem, testem, monitorem e otimizem seus modelos e sistemas agentes. Principais ofertas incluem:

- **Observabilidade abrangente**: rastreamento detalhado de chamadas LLM, registro de conversas e atividade de agentes.
- **Avaliação avançada**: avaliação robusta de prompts, LLM-como-juiz e gerenciamento de experimentos.
- **Pronto para produção**: painéis escaláveis e regras de avaliação online para produção.
- **Opik Agent Optimizer**: SDK dedicado e conjunto de otimizadores para melhorar prompts e agentes.
- **Opik Guardrails**: recursos para ajudar a implementar práticas de IA seguras e responsáveis.

<br>

### Capacidades principais incluem:

- **Desenvolvimento & Rastreamento:**
  - Rastreie todas as chamadas LLM e traces com contexto detalhado durante o desenvolvimento e em produção ([Quickstart](https://www.comet.com/docs/opik/quickstart/?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_link&utm_campaign=opik)).
  - Integrações extensas de terceiros para observabilidade: integre facilmente com uma lista crescente de frameworks, suportando muitos dos maiores e mais populares nativamente (incluindo adições recentes como **Google ADK**, **Autogen** e **Flowise AI**). ([Integrations](https://www.comet.com/docs/opik/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik))
  - Anote traces e spans com pontuações de feedback via [Python SDK](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link&utm_campaign=opik) ou pela [UI](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-through-the-ui?from=llm&utm_source=opik&utm_medium=github&utm_content=ui_link&utm_campaign=opik).
  - Experimente prompts e modelos no [Prompt Playground](https://www.comet.com/docs/opik/prompt_engineering/playground).

- **Avaliação & Testes**:
  - Automatize a avaliação de sua aplicação LLM com [Datasets](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_link&utm_campaign=opik) e [Experimentos](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=eval_link&utm_campaign=opik).
  - Aproveite métricas poderosas de LLM-como-juiz para tarefas complexas como [detecção de alucinações](https://www.comet.com/docs/opik/evaluation/metrics/hallucination/?from=llm&utm_source=opik&utm_medium=github&utm_content=hallucination_link&utm_campaign=opik), [moderação](https://www.comet.com/docs/opik/evaluation/metrics/moderation/?from=llm&utm_source=opik&utm_medium=github&utm_content=moderation_link&utm_campaign=opik) e avaliação RAG ([Relevância da Resposta](https://www.comet.com/docs/opik/evaluation/metrics/answer_relevance/?from=llm&utm_source=opik&utm_medium=github&utm_content=alex_link&utm_campaign=opik), [Precisão de Contexto](https://www.comet.com/docs/opik/evaluation/metrics/context_precision/?from=llm&utm_source=opik&utm_medium=github&utm_content=context_link&utm_campaign=opik)).
  - Integre avaliações ao seu pipeline CI/CD com nossa [integração PyTest](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_link&utm_campaign=opik).

- **Monitoramento de Produção & Otimização**:
  - Faça log de grandes volumes de traces de produção: o Opik é projetado para escala (40M+ traces/dia).
  - Monitore pontuações de feedback, contagem de traces e uso de tokens ao longo do tempo no [Painel Opik](https://www.comet.com/docs/opik/production/production_monitoring/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik).
  - Utilize [Regras de Avaliação Online](https://www.comet.com/docs/opik/production/rules/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik) com métricas de LLM-como-juiz para identificar problemas em produção.
  - Aproveite o **Opik Agent Optimizer** e o **Opik Guardrails** para melhorar continuamente e proteger suas aplicações LLM em produção.

> <div class="tip">Se você está procurando recursos que o Opik ainda não oferece, por favor abra uma nova <a href="https://github.com/comet-ml/opik/issues/new/choose">solicitação de recurso</a> 🚀</div>

<br>

## 🛠️ Instalação do Servidor Opik

Coloque seu servidor Opik em funcionamento em minutos. Escolha a opção que melhor atende às suas necessidades:

### Opção 1: Comet.com Cloud (Mais fácil e recomendado)

Acesse o Opik instantaneamente sem configuração. Ideal para começar rápido e sem manutenção.

👉 <a href="https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=install_create_link&utm_campaign=opik">Crie sua conta gratuita na Comet</a>

### Opção 2: Hospedagem própria (Self-Host) para controle total

Implemente o Opik em seu próprio ambiente. Escolha entre Docker para setups locais ou Kubernetes para escalabilidade.

#### Self-Hosting com Docker Compose (para desenvolvimento local e testes)

Esta é a forma mais simples de obter uma instância local do Opik em execução. Observe o novo script de instalação `./opik.sh`:

No ambiente Linux ou Mac:

<pre><code class="language-bash"># Clone o repositório Opik
git clone https://github.com/comet-ml/opik.git

# Navegue até o repositório
cd opik

# Inicie a plataforma Opik
./opik.sh
</code></pre>

No ambiente Windows:

<pre><code class="language-powershell"># Clone o repositório Opik
git clone https://github.com/comet-ml/opik.git

# Navegue até o repositório
cd opik

# Inicie a plataforma Opik
powershell -ExecutionPolicy ByPass -c ".\\opik.ps1"
</code></pre>

**Perfis de serviço para desenvolvimento**

O script de instalação do Opik agora suporta perfis de serviço para diferentes cenários de desenvolvimento:

<pre><code class="language-bash"># Iniciar suíte completa do Opik (comportamento padrão)
./opik.sh

# Iniciar apenas serviços de infraestrutura (bancos de dados, caches etc.)
./opik.sh --infra

# Infraestrutura + serviços backend
./opik.sh --backend

# Habilitar guardrails com qualquer perfil
./opik.sh --guardrails # Guardrails com a suíte completa do Opik
./opik.sh --backend --guardrails # Guardrails com infraestrutura + backend
</code></pre>

Use as opções `--help` ou `--info` para solucionar problemas. Os Dockerfiles agora garantem que os containers sejam executados como usuários não-root para maior segurança. Quando tudo estiver em execução, acesse <a href="http://localhost:5173">localhost:5173</a> no seu navegador! Para instruções detalhadas, veja o <a href="https://www.comet.com/docs/opik/self-host/local_deployment?from=llm&utm_source=opik&utm_medium=github&utm_content=self_host_link&utm_campaign=opik">Guia de Implantação Local</a>.

#### Self-Hosting com Kubernetes & Helm (para implantações escaláveis)

Para implantações de produção ou em maior escala, o Opik pode ser instalado em um cluster Kubernetes usando nosso chart Helm. Clique no badge para o <a href="https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik">Guia de Instalação Kubernetes via Helm</a>.

[![Kubernetes](https://img.shields.io/badge/Kubernetes-%23326ce5.svg?&logo=kubernetes&logoColor=white)](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik)

> <div class="tip"><b>Versão 1.7.0 — Alterações importantes</b>: Verifique o <a href="https://github.com/comet-ml/opik/blob/main/CHANGELOG.md">changelog</a> para atualizações importantes e mudanças que quebram compatibilidade.</div>

## 💻 SDK Cliente Opik

O Opik fornece um conjunto de bibliotecas cliente e uma API REST para interagir com o servidor Opik. Isso inclui SDKs para Python, TypeScript e Ruby (via OpenTelemetry), permitindo integração fluida em seus fluxos de trabalho. Para referências de API e SDK, consulte a <a href="apps/opik-documentation/documentation/fern/docs/reference/overview.mdx">Documentação de Referência do Cliente Opik</a>.

### Início rápido com o SDK Python

Para começar com o SDK Python:

Instale o pacote:

<pre><code class="language-bash"># instalar usando pip
pip install opik

# ou instalar com uv
uv pip install opik
</code></pre>

Configure o SDK Python executando o comando `opik configure`, que solicitará o endereço do servidor Opik (para instâncias self-hosted) ou sua chave de API e workspace (para Comet.com):

<pre><code class="language-bash">opik configure
</code></pre>

> <div class="tip">Você também pode chamar `opik.configure(use_local=True)` do seu código Python para configurar o SDK para execução local self-hosted, ou fornecer a chave de API e workspace diretamente para Comet.com. Consulte a <a href="apps/opik-documentation/documentation/fern/docs/reference/python-sdk/">documentação do SDK Python</a> para mais opções de configuração.</div>

Agora você está pronto para começar a registrar traces usando o <a href="https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link2&utm_campaign=opik">SDK Python</a>.

### 📝 Registro de Traces com Integrações

A forma mais fácil de registrar traces é usar uma de nossas integrações diretas. O Opik suporta uma ampla gama de frameworks, incluindo adições recentes como **Google ADK**, **Autogen**, **AG2** e **Flowise AI**:

<table>
  <thead>
    <tr><th>Integração</th><th>Descrição</th><th>Documentação</th></tr>
  </thead>
  <tbody>
    <tr><td>ADK</td><td>Registra traces para Google Agent Development Kit (ADK)</td><td><a href="https://www.comet.com/docs/opik/integrations/adk?utm_source=opik&utm_medium=github&utm_content=google_adk_link&utm_campaign=opik">Documentação</a></td></tr>
    <tr><td>AG2</td><td>Registra traces para chamadas AG2</td><td><a href="https://www.comet.com/docs/opik/integrations/ag2?utm_source=opik&utm_medium=github&utm_content=ag2_link&utm_campaign=opik">Documentação</a></td></tr>
    <!-- A tabela segue com as integrações listadas no README original -->
  </tbody>
</table>

> <div class="tip">Se o framework que você está usando não estiver na lista, abra uma <a href="https://github.com/comet-ml/opik/issues">issue</a> ou envie um PR com a integração.</div>

Se você não estiver usando nenhum dos frameworks acima, também pode usar o decorador `track` para <a href="https://www.comet.com/docs/opik/tracing/log_traces/?from=llm&utm_source=opik&utm_medium=github&utm_content=traces_link&utm_campaign=opik">registrar traces</a>:

<pre><code class="language-python">import opik

opik.configure(use_local=True) # Executar localmente

@opik.track
def my_llm_function(user_question: str) -> str:
    # Seu código LLM aqui

    return "Hello"
</code></pre>

> <div class="tip">O decorador track pode ser usado em conjunto com qualquer uma de nossas integrações e também para rastrear chamadas de função aninhadas.</div>

### 🧑‍⚖️ Métricas LLM como Juiz

O SDK Python do Opik inclui várias métricas LLM-como-juiz para ajudá-lo a avaliar sua aplicação LLM. Saiba mais na <a href="https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_2_link&utm_campaign=opik">documentação de métricas</a>.

Para usá-las, importe a métrica relevante e chame a função `score`:

<pre><code class="language-python">from opik.evaluation.metrics import Hallucination

metric = Hallucination()
score = metric.score(
    input="Qual é a capital da França?",
    output="Paris",
    context=["A França é um país na Europa."]
)
print(score)
</code></pre>

O Opik também inclui diversas métricas heurísticas pré-construídas, além da possibilidade de criar suas próprias métricas. Consulte a <a href="https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_3_link&utm_campaign=opik">documentação de métricas</a> para mais detalhes.

### 🔍 Avaliando sua Aplicação LLM

O Opik permite avaliar sua aplicação LLM durante o desenvolvimento por meio de <a href="https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_2_link&utm_campaign=opik">Datasets</a> e <a href="https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=experiments_link&utm_campaign=opik">Experimentos</a>. O Painel Opik oferece gráficos avançados para experimentos e melhor manuseio de traces grandes. Você também pode executar avaliações como parte do seu pipeline CI/CD usando nossa <a href="https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_2_link&utm_campaign=opik">integração PyTest</a>.

## ⭐ Dê-nos uma estrela no GitHub

Se você achar o Opik útil, considere nos dar uma estrela! Seu apoio nos ajuda a crescer a comunidade e a continuar melhorando o produto.

[![Star History Chart](https://api.star-history.com/svg?repos=comet-ml/opik&type=Date)](https://github.com/comet-ml/opik)

## 🤝 Contribuir

Há muitas maneiras de contribuir com o Opik:

- Enviar relatórios de bugs e solicitações de recurso: <a href="https://github.com/comet-ml/opik/issues">issues</a>
- Revisar a documentação e enviar <a href="https://github.com/comet-ml/opik/pulls">Pull Requests</a>
- Falar ou escrever sobre o Opik e nos avisar no <a href="https://chat.comet.com">chat</a>
- Apoiar solicitações de recurso populares: <a href="https://github.com/comet-ml/opik/issues?q=is%3Aissue+is%3Aopen+label%3A%22enhancement%22">upvote</a>

Para saber mais sobre como contribuir com o Opik, veja nossas <a href="CONTRIBUTING.md">diretrizes de contribuição</a>.

<footer class="center">
  Tradução para Português gerada — formato HTML/CSS preservado. Se quiser que eu gere um arquivo README_pt.md em markdown ou ajustar o tom (pt-PT vs pt-BR), diga qual preferência.
</footer>
