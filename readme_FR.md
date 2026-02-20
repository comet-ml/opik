> Remarque : Ce fichier a été traduit automatiquement. Les améliorations de la traduction sont bienvenues !

<div align="center"><b><a href="README.md">English</a> | <a href="readme_CN.md">简体中文</a> | <a href="readme_JP.md">日本語</a> | <a href="readme_PT_BR.md">Português (Brasil)</a> | <a href="readme_KO.md">한국어</a> | <a href="readme_ES.md">Español</a> | <a href="readme_FR.md">Français</a> | <a href="readme_DE.md">Deutsch</a> | <a href="readme_RU.md">Русский</a> | <a href="readme_AR.md">العربية</a> | <a href="readme_HI.md">हिन्दी</a> | <a href="readme_TR.md">Türkçe</a></b></div>


<h1 align="center" style="border-bottom: none">
<div>
<a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=header_img&utm_campaign=opik"><picture>
<source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/logo-dark-mode.svg">
<source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg">
<img alt="Logo Comet Opik" src="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg" width="200" />
</image></a>
<br>
Opik
</div>
</h1>
<h2 align="center" style="border-bottom: none">Observabilité, évaluation et optimisation de l'IA open source</h2>
<p align="center">
Opik vous aide à créer, tester et optimiser des applications d'IA générative qui fonctionnent mieux, du prototype à la production.  Des chatbots RAG aux assistants de code en passant par les systèmes agentiques complexes, Opik fournit un traçage complet, une évaluation ainsi qu'une optimisation automatique des invites et des outils pour éliminer les incertitudes liées au développement de l'IA.
</p>

<div align="centre">

[![SDK Python](https://img.shields.io/pypi/v/opik)](https://pypi.org/project/opik/)
[![Licence](https://img.shields.io/github/license/comet-ml/opik)](https://github.com/comet-ml/opik/blob/main/LICENSE)
[![Build](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml/badge.svg)](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml)
[![Bounties](https://img.shields.io/endpoint?url=https%3A%2F%2Falgora.io%2Fapi%2Fshields%2Fcomet-ml%2Fbounties%3Fstatus%3Dopen)](https://algora.io/comet-ml/bounties?status=open)

<!-- [![Démarrage rapide](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/opik_quickstart.ipynb) -->

</div>

<p align="center">
<a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=website_button&utm_campaign=opik"><b>Site Web</b></a> •
<a href="https://chat.comet.com"><b>Communauté Slack</b></a> •
<a href="https://x.com/Cometml"><b>Twitter</b></a> •
<a href="https://www.comet.com/docs/opik/changelog"><b>Journal des modifications</b></a> •
<a href="https://www.comet.com/docs/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=docs_button&utm_campaign=opik"><b>Documentation</b></a>
</p>

<div align="center" style="margin-top: 1em; margin-bottom: 1em;">
<a href="#-what-is-opik">🚀 Qu'est-ce qu'Opik ?</a> • <a href="#%EF%B8%8F-opik-server-installation">🛠️ Installation du serveur Opik</a> • <a href="#-opik-client-sdk">💻 SDK client Opik</a> • <a href="#-logging-traces-with-integrations">📝 Journalisation des traces</a><br>
<a href="#-llm-as-a-judge-metrics">🧑‍⚖️ LLM en tant que juge</a> • <a href="#-evaluating-your-llm-application">🔍 Évaluation de votre candidature</a> • <a href="#-star-us-on-github">⭐ Star Us</a> • <a href="#-contributing">🤝 Contribuer</a>
</div>

<br>

[![Capture d'écran de la plateforme Opik (vignette)](readme-thumbnail-new.png)](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=readme_banner&utm_campaign=opik)

<a id="-what-is-opik"></a>
## 🚀 Qu'est-ce qu'Opik ?

Opik (construit par [Comet](https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=what_is_opik_link&utm_campaign=opik)) est une plate-forme open source conçue pour rationaliser l'ensemble du cycle de vie des applications LLM. Il permet aux développeurs d'évaluer, de tester, de surveiller et d'optimiser leurs modèles et systèmes agents. Les offres clés incluent :

- **Observabilité complète** : traçage approfondi des appels LLM, journalisation des conversations et activité des agents.
- **Évaluation avancée** : évaluation rapide et robuste, LLM en tant que juge et gestion des expériences.
- **Production-Ready** : tableaux de bord de surveillance évolutifs et règles d'évaluation en ligne pour la production.
- **Opik Agent Optimizer** : SDK dédié et ensemble d'optimiseurs pour améliorer les invites et les agents.
- **Opik Guardrails** : fonctionnalités pour vous aider à mettre en œuvre des pratiques d'IA sûres et responsables.

<br>

Les fonctionnalités clés incluent :

- **Développement et traçage :**
- Suivez tous les appels et traces LLM avec un contexte détaillé pendant le développement et en production ([Quickstart](https://www.comet.com/docs/opik/quickstart/?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_link&utm_campaign=opik)).
- Intégrations tierces étendues pour une observabilité facile : intégration transparente à une liste croissante de frameworks, prenant en charge nativement bon nombre des frameworks les plus importants et les plus populaires (y compris les ajouts récents tels que **Google ADK**, **Autogen** et **Flowise AI**). ([Intégrations](https://www.comet.com/docs/opik/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik))
- Annotez les traces et les étendues avec les scores de feedback via le [SDK Python](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link&utm_campaign=opik) ou le [UI](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-through-the-ui?from=llm&utm_source=opik&utm_medium=github&utm_content=ui_link&utm_campaign=opik).
- Expérimentez avec des invites et des modèles dans [Prompt Playground](https://www.comet.com/docs/opik/prompt_engineering/playground).

- **Évaluation et tests** :
- Automatisez l'évaluation de votre candidature LLM avec [Datasets](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_link&utm_campaign=opik) et [Expériences](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=eval_link&utm_campaign=opik).
- Tirez parti de puissantes métriques LLM-as-a-judge pour des tâches complexes telles que la [détection d'hallucinations](https://www.comet.com/docs/opik/evaluation/metrics/hallucination/?from=llm&utm_source=opik&utm_medium=github&utm_content=hallucination_link&utm_campaign=opik), [modération](https://www.comet.com/docs/opik/evaluation/metrics/moderation/?from=llm&utm_source=opik&utm_medium=github&utm_content=moderation_link&utm_campaign=opik) et évaluation RAG ([Réponse Pertinence](https://www.comet.com/docs/opik/evaluation/metrics/answer_relevance/?from=llm&utm_source=opik&utm_medium=github&utm_content=alex_link&utm_campaign=opik), [Contexte Précision](https://www.comet.com/docs/opik/evaluation/metrics/context_precision/?from=llm&utm_source=opik&utm_medium=github&utm_content=context_link&utm_campaign=opik)).
- Intégrez des évaluations dans votre pipeline CI/CD avec notre [intégration PyTest](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_link&utm_campaign=opik).

- **Suivi et optimisation de la production** :
- Enregistrez des volumes élevés de traces de production : Opik est conçu pour une échelle (plus de 40 millions de traces/jour).
- Surveillez les scores de commentaires, le nombre de traces et l'utilisation des jetons au fil du temps dans le [Opik Dashboard](https://www.comet.com/docs/opik/production/production_monitoring/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik).
- Utiliser les [règles d'évaluation en ligne](https://www.comet.com/docs/opik/production/rules/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik) avec les métriques LLM-as-a-Judge pour identifier les problèmes de production.
- Tirez parti de **Opik Agent Optimizer** et **Opik Guardrails** pour améliorer et sécuriser continuellement vos applications LLM en production.

> [!TIP]
> Si vous recherchez des fonctionnalités qu'Opik n'a pas aujourd'hui, veuillez créer une nouvelle [Demande de fonctionnalité](https://github.com/comet-ml/opik/issues/new/choose) 🚀

<br>

<a id="%EF%B8%8F-opik-server-installation"></a>
## 🛠️ Installation du serveur Opik

Faites fonctionner votre serveur Opik en quelques minutes. Choisissez l'option qui correspond le mieux à vos besoins :

### Option 1 : Comet.com Cloud (le plus simple et recommandé)

Accédez à Opik instantanément sans aucune configuration. Idéal pour des démarrages rapides et un entretien sans tracas.

👉 [Créez votre compte Comet gratuit](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=install_create_link&utm_campaign=opik)

### Option 2 : Opik auto-hébergé pour un contrôle total

Déployez Opik dans votre propre environnement. Choisissez entre Docker pour les configurations locales ou Kubernetes pour l'évolutivité.

#### Auto-hébergement avec Docker Compose (pour le développement et les tests locaux)

C'est le moyen le plus simple de faire fonctionner une instance Opik locale. Notez le nouveau script d'installation `./opik.sh` :

Sous environnement Linux ou Mac :

```bash
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the repository
cd opik

# Start the Opik platform
./opik.sh
```

Sur l'environnement Windows :

```powershell
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the repository
cd opik

# Start the Opik platform
powershell -ExecutionPolicy ByPass -c ".\\opik.ps1"
```

**Profils de services pour le développement**

Les scripts d'installation Opik prennent désormais en charge les profils de service pour différents scénarios de développement :

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

Utilisez les options `--help` ou `--info` pour résoudre les problèmes. Les Dockerfiles garantissent désormais que les conteneurs s'exécutent en tant qu'utilisateurs non root pour une sécurité renforcée. Une fois que tout est opérationnel, vous pouvez maintenant visiter [localhost:5173](http://localhost:5173) sur votre navigateur ! Pour des instructions détaillées, consultez le [Guide de déploiement local](https://www.comet.com/docs/opik/self-host/local_deployment?from=llm&utm_source=opik&utm_medium=github&utm_content=self_host_link&utm_campaign=opik).

#### Auto-hébergement avec Kubernetes et Helm (pour des déploiements évolutifs)

Pour la production ou les déploiements auto-hébergés à plus grande échelle, Opik peut être installé sur un cluster Kubernetes à l'aide de notre charte Helm. Cliquez sur le badge pour accéder au [Guide d'installation de Kubernetes à l'aide de Helm](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik) complet.

[![Kubernetes](https://img.shields.io/badge/Kubernetes-%23326ce5.svg?&logo=kubernetes&logoColor=white)](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik)

> [!IMPORTANT]
> **Modifications de la version 1.7.0** : veuillez consulter le [changelog](https://github.com/comet-ml/opik/blob/main/CHANGELOG.md) pour les mises à jour importantes et les modifications importantes.

<a id="-opik-client-sdk"></a>
## 💻 SDK client Opik

Opik fournit une suite de bibliothèques clientes et une API REST pour interagir avec le serveur Opik. Cela inclut des SDK pour Python, TypeScript et Ruby (via OpenTelemetry), permettant une intégration transparente dans vos flux de travail. Pour des références détaillées sur l'API et le SDK, consultez la [Documentation de référence du client Opik](https://www.comet.com/docs/opik/reference/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=reference_link&utm_campaign=opik).

### Démarrage rapide du SDK Python

Pour démarrer avec le SDK Python :

Installez le paquet :

```bash
# install using pip
pip install opik

# or install with uv
uv pip install opik
```

Configurez le SDK python en exécutant la commande « opik configure », qui vous demandera l'adresse de votre serveur Opik (pour les instances auto-hébergées) ou votre clé API et votre espace de travail (pour Comet.com) :

```bash
opik configure
```

> [!TIP]
> Vous pouvez également appeler « opik.configure(use_local=True) » à partir de votre code Python pour configurer le SDK afin qu'il s'exécute sur une installation locale auto-hébergée, ou fournir la clé API et les détails de l'espace de travail directement pour Comet.com. Reportez-vous à la [documentation du SDK Python](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=python_sdk_docs_link&utm_campaign=opik) pour plus d'options de configuration.

Vous êtes maintenant prêt à commencer à enregistrer des traces à l'aide du [SDK Python](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link2&utm_campaign=opik).

<a id="-logging-traces-with-integrations"></a>
### 📝 Journalisation des traces avec intégrations

Le moyen le plus simple d’enregistrer des traces est d’utiliser l’une de nos intégrations directes. Opik prend en charge un large éventail de frameworks, y compris des ajouts récents tels que **Google ADK**, **Autogen**, **AG2** et **Flowise AI** :

| Intégration | Descriptif | Documents |
| ------------------------------------ | ------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ADK | Traces de journaux pour le kit de développement d'agent Google (ADK) | [Documentation](https://www.comet.com/docs/opik/integrations/adk?utm_source=opik&utm_medium=github&utm_content=google_adk_link&utm_campaign=opik) |
| AG2 | Consigner les traces des appels AG2 LLM | [Documentation](https://www.comet.com/docs/opik/integrations/ag2?utm_source=opik&utm_medium=github&utm_content=ag2_link&utm_campaign=opik) |
| Suite IA | Traces de journaux pour les appels aisuite LLM | [Documentation](https://www.comet.com/docs/opik/integrations/aisuite?utm_source=opik&utm_medium=github&utm_content=aisuite_link&utm_campaign=opik) |
| Agno | Traces de journaux pour les appels du framework d'orchestration d'agent Agno | [Documentation](https://www.comet.com/docs/opik/integrations/agno?utm_source=opik&utm_medium=github&utm_content=agno_link&utm_campaign=opik) |
| Anthropique | Consigner les traces des appels Anthropic LLM | [Documentation](https://www.comet.com/docs/opik/integrations/anthropic?utm_source=opik&utm_medium=github&utm_content=anthropic_link&utm_campaign=opik) |
| Autogène | Traces de journaux pour les flux de travail agents Autogen | [Documentation](https://www.comet.com/docs/opik/integrations/autogen?utm_source=opik&utm_medium=github&utm_content=autogen_link&utm_campaign=opik) |
| Substrat rocheux | Consigner les traces des appels Amazon Bedrock LLM | [Documentation](https://www.comet.com/docs/opik/integrations/bedrock?utm_source=opik&utm_medium=github&utm_content=bedrock_link&utm_campaign=opik) |
| BeeAI (Python) | Consigner les traces des appels du framework d'agent BeeAI Python | [Documentation](https://www.comet.com/docs/opik/integrations/beeai?utm_source=opik&utm_medium=github&utm_content=beeai_link&utm_campaign=opik) |
| BeeAI (TypeScript) | Consigner les traces des appels du framework d'agent BeeAI TypeScript | [Documentation](https://www.comet.com/docs/opik/integrations/beeai-typescript?utm_source=opik&utm_medium=github&utm_content=beeai_typescript_link&utm_campaign=opik) |
| OctetPlus | Traces de journaux pour les appels BytePlus LLM | [Documentation](https://www.comet.com/docs/opik/integrations/byteplus?utm_source=opik&utm_medium=github&utm_content=byteplus_link&utm_campaign=opik) |
| IA des travailleurs Cloudflare | Consigner les traces des appels Cloudflare Workers AI | [Documentation](https://www.comet.com/docs/opik/integrations/cloudflare-workers-ai?utm_source=opik&utm_medium=github&utm_content=cloudflare_workers_ai_link&utm_campaign=opik) |
| Cohérer | Journaliser les traces des appels Cohere LLM | [Documentation](https://www.comet.com/docs/opik/integrations/cohere?utm_source=opik&utm_medium=github&utm_content=cohere_link&utm_campaign=opik) |
| CrewAI | Consigner les traces des appels CrewAI | [Documentation](https://www.comet.com/docs/opik/integrations/crewai?utm_source=opik&utm_medium=github&utm_content=crewai_link&utm_campaign=opik) |
| Curseur | Consigner les traces des conversations avec le curseur | [Documentation](https://www.comet.com/docs/opik/integrations/cursor?utm_source=opik&utm_medium=github&utm_content=cursor_link&utm_campaign=opik) |
| Recherche profonde | Consigner les traces des appels DeepSeek LLM | [Documentation](https://www.comet.com/docs/opik/integrations/deepseek?utm_source=opik&utm_medium=github&utm_content=deepseek_link&utm_campaign=opik) |
| Difier | Consigner les traces des exécutions de l'agent Dify | [Documentation](https://www.comet.com/docs/opik/integrations/dify?utm_source=opik&utm_medium=github&utm_content=dify_link&utm_campaign=opik) |
| DSPY | Traces de journaux pour les exécutions DSPy | [Documentation](https://www.comet.com/docs/opik/integrations/dspy?utm_source=opik&utm_medium=github&utm_content=dspy_link&utm_campaign=opik) |
| IA de feux d'artifice | Consigner les traces des appels Fireworks AI LLM | [Documentation](https://www.comet.com/docs/opik/integrations/fireworks-ai?utm_source=opik&utm_medium=github&utm_content=fireworks_ai_link&utm_campaign=opik) |
| Flowise IA | Traces de journaux pour le générateur visuel LLM Flowise AI | [Documentation](https://www.comet.com/docs/opik/integrations/flowise?utm_source=opik&utm_medium=github&utm_content=flowise_link&utm_campaign=opik) |
| Gémeaux (Python) | Consigner les traces des appels Google Gemini LLM | [Documentation](https://www.comet.com/docs/opik/integrations/gemini?utm_source=opik&utm_medium=github&utm_content=gemini_link&utm_campaign=opik) |
| Gémeaux (TypeScript) | Consigner les traces des appels du SDK Google Gemini TypeScript | [Documentation](https://www.comet.com/docs/opik/integrations/gemini-typescript?utm_source=opik&utm_medium=github&utm_content=gemini_typescript_link&utm_campaign=opik) |
| Groq | Consigner les traces des appels Groq LLM | [Documentation](https://www.comet.com/docs/opik/integrations/groq?utm_source=opik&utm_medium=github&utm_content=groq_link&utm_campaign=opik) |
| Garde-corps | Traces de journaux pour les validations Guardrails AI | [Documentation](https://www.comet.com/docs/opik/integrations/guardrails-ai?utm_source=opik&utm_medium=github&utm_content=guardrails_link&utm_campaign=opik) |
| Botte de foin | Consigner les traces des appels Haystack | [Documentation](https://www.comet.com/docs/opik/integrations/haystack?utm_source=opik&utm_medium=github&utm_content=haystack_link&utm_campaign=opik) |
| Port | Traces de journaux pour les essais d'évaluation de référence Harbour | [Documentation](https://www.comet.com/docs/opik/integrations/harbor?utm_source=opik&utm_medium=github&utm_content=harbor_link&utm_campaign=opik) |
| Instructeur | Consigner les traces des appels LLM effectués avec Instructor | [Documentation](https://www.comet.com/docs/opik/integrations/instructor?utm_source=opik&utm_medium=github&utm_content=instructor_link&utm_campaign=opik) |
| LangChain (Python) | Consigner les traces des appels LangChain LLM | [Documentation](https://www.comet.com/docs/opik/integrations/langchain?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik) |
| LangChain (JS/TS) | Consigner les traces des appels LangChain JavaScript/TypeScript | [Documentation](https://www.comet.com/docs/opik/integrations/langchainjs?utm_source=opik&utm_medium=github&utm_content=langchainjs_link&utm_campaign=opik) |
| LangGraph | Traces de journaux pour les exécutions de LangGraph | [Documentation](https://www.comet.com/docs/opik/integrations/langgraph?utm_source=opik&utm_medium=github&utm_content=langgraph_link&utm_campaign=opik) |
| Flux de langage | Traces de journaux pour le générateur d'IA visuel Langflow | [Documentation](https://www.comet.com/docs/opik/integrations/langflow?utm_source=opik&utm_medium=github&utm_content=langflow_link&utm_campaign=opik) |
| LiteLLM | Traces de journal pour les appels de modèle LiteLLM | [Documentation](https://www.comet.com/docs/opik/integrations/litellm?utm_source=opik&utm_medium=github&utm_content=litellm_link&utm_campaign=opik) |
| Agents LiveKit | Consigner les traces des appels du cadre d'agent IA des agents LiveKit | [Documentation](https://www.comet.com/docs/opik/integrations/livekit?utm_source=opik&utm_medium=github&utm_content=livekit_link&utm_campaign=opik) |
| LamaIndex | Traces de journal pour les appels LlamaIndex LLM | [Documentation](https://www.comet.com/docs/opik/integrations/llama_index?utm_source=opik&utm_medium=github&utm_content=llama_index_link&utm_campaign=opik) |
| Mastra | Consigner les traces des appels du framework de workflow Mastra AI | [Documentation](https://www.comet.com/docs/opik/integrations/mastra?utm_source=opik&utm_medium=github&utm_content=mastra_link&utm_campaign=opik) |
| Cadre d'agent Microsoft (Python) | Consigner les traces des appels Microsoft Agent Framework | [Documentation](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework?utm_source=opik&utm_medium=github&utm_content=agent_framework_link&utm_campaign=opik) |
| Cadre d'agent Microsoft (.NET) | Consigner les traces des appels Microsoft Agent Framework .NET | [Documentation](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework-dotnet?utm_source=opik&utm_medium=github&utm_content=agent_framework_dotnet_link&utm_campaign=opik) |
| Mistral IA | Journaliser les traces des appels Mistral AI LLM | [Documentation](https://www.comet.com/docs/opik/integrations/mistral?utm_source=opik&utm_medium=github&utm_content=mistral_link&utm_campaign=opik) |
| n8n | Consigner les traces pour les exécutions de flux de travail n8n | [Documentation](https://www.comet.com/docs/opik/integrations/n8n?utm_source=opik&utm_medium=github&utm_content=n8n_link&utm_campaign=opik) |
| Novita IA | Consigner les traces des appels Novita AI LLM | [Documentation](https://www.comet.com/docs/opik/integrations/novita-ai?utm_source=opik&utm_medium=github&utm_content=novita_ai_link&utm_campaign=opik) |
| Ollama | Consigner les traces des appels Ollama LLM | [Documentation](https://www.comet.com/docs/opik/integrations/ollama?utm_source=opik&utm_medium=github&utm_content=ollama_link&utm_campaign=opik) |
| OpenAI (Python) | Consigner les traces des appels OpenAI LLM | [Documentation](https://www.comet.com/docs/opik/integrations/openai?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik) |
| OpenAI (JS/TS) | Consigner les traces pour les appels OpenAI JavaScript/TypeScript | [Documentation](https://www.comet.com/docs/opik/integrations/openai-typescript?utm_source=opik&utm_medium=github&utm_content=openai_typescript_link&utm_campaign=opik) |
| Agents OpenAI | Traces de journaux pour les appels du SDK OpenAI Agents | [Documentation](https://www.comet.com/docs/opik/integrations/openai_agents?utm_source=opik&utm_medium=github&utm_content=openai_agents_link&utm_campaign=opik) |
| OuvrirRouter | Consigner les traces des appels OpenRouter LLM | [Documentation](https://www.comet.com/docs/opik/integrations/openrouter?utm_source=opik&utm_medium=github&utm_content=openrouter_link&utm_campaign=opik) |
| OpenTélémétrie | Consigner les traces des appels pris en charge par OpenTelemetry | [Documentation](https://www.comet.com/docs/opik/tracing/opentelemetry/overview?utm_source=opik&utm_medium=github&utm_content=opentelemetry_link&utm_campaign=opik) |
| OpenWebUI | Consigner les traces des conversations OpenWebUI | [Documentation](https://www.comet.com/docs/opik/integrations/openwebui?utm_source=opik&utm_medium=github&utm_content=openwebui_link&utm_campaign=opik) |
| Pipecat | Consigner les traces des appels de l'agent vocal en temps réel Pipecat | [Documentation](https://www.comet.com/docs/opik/integrations/pipecat?utm_source=opik&utm_medium=github&utm_content=pipecat_link&utm_campaign=opik) |
| Prédibase | Consigner les traces des appels Predibase LLM | [Documentation](https://www.comet.com/docs/opik/integrations/predibase?utm_source=opik&utm_medium=github&utm_content=predibase_link&utm_campaign=opik) |
| IA pydantique | Consigner les traces des appels d'agent PydanticAI | [Documentation](https://www.comet.com/docs/opik/integrations/pydantic-ai?utm_source=opik&utm_medium=github&utm_content=pydantic_ai_link&utm_campaign=opik) |
| Ragas | Traces de journaux pour les évaluations Ragas | [Documentation](https://www.comet.com/docs/opik/integrations/ragas?utm_source=opik&utm_medium=github&utm_content=ragas_link&utm_campaign=opik) |
| Noyau sémantique | Consigner les traces des appels du noyau sémantique Microsoft | [Documentation](https://www.comet.com/docs/opik/integrations/semantic-kernel?utm_source=opik&utm_medium=github&utm_content=semantic_kernel_link&utm_campaign=opik) |
| Smolagents | Traces de journaux pour les agents Smolagents | [Documentation](https://www.comet.com/docs/opik/integrations/smolagents?utm_source=opik&utm_medium=github&utm_content=smolagents_link&utm_campaign=opik) |
| IA du printemps | Consigner les traces des appels du framework Spring AI | [Documentation](https://www.comet.com/docs/opik/integrations/spring-ai?utm_source=opik&utm_medium=github&utm_content=spring_ai_link&utm_campaign=opik) |
| Agents de brins | Consigner les traces des appels des agents Strands | [Documentation](https://www.comet.com/docs/opik/integrations/strands-agents?utm_source=opik&utm_medium=github&utm_content=strands_agents_link&utm_campaign=opik) |
| Ensemble IA | Consigner les traces des appels Together AI LLM | [Documentation](https://www.comet.com/docs/opik/integrations/together-ai?utm_source=opik&utm_medium=github&utm_content=together_ai_link&utm_campaign=opik) |
| SDK IA Vercel | Traces de journaux pour les appels du SDK Vercel AI | [Documentation](https://www.comet.com/docs/opik/integrations/vercel-ai-sdk?utm_source=opik&utm_medium=github&utm_content=vercel_ai_sdk_link&utm_campaign=opik) |
| VoltAgent | Traces de journaux pour les appels du framework d'agent VoltAgent | [Documentation](https://www.comet.com/docs/opik/integrations/voltagent?utm_source=opik&utm_medium=github&utm_content=voltagent_link&utm_campaign=opik) |
| WatsonX | Traces de journaux pour les appels IBM Watsonx LLM | [Documentation](https://www.comet.com/docs/opik/integrations/watsonx?utm_source=opik&utm_medium=github&utm_content=watsonx_link&utm_campaign=opik) |
| xAI Grok | Consigner les traces des appels xAI Grok LLM | [Documentation](https://www.comet.com/docs/opik/integrations/xai-grok?utm_source=opik&utm_medium=github&utm_content=xai_grok_link&utm_campaign=opik) |

> [!TIP]
> Si le framework que vous utilisez n'est pas répertorié ci-dessus, n'hésitez pas à [ouvrir un problème](https://github.com/comet-ml/opik/issues) ou soumettre un PR avec l'intégration.

Si vous n'utilisez aucun des frameworks ci-dessus, vous pouvez également utiliser le décorateur de fonction « track » pour [enregistrer les traces](https://www.comet.com/docs/opik/tracing/log_traces/?from=llm&utm_source=opik&utm_medium=github&utm_content=traces_link&utm_campaign=opik) :

```python
import opik

opik.configure(use_local=True) # Run locally

@opik.track
def my_llm_function(user_question: str) -> str:
    # Your LLM code here

    return "Hello"
```

> [!TIP]
> Le décorateur de piste peut être utilisé avec n'importe laquelle de nos intégrations et peut également être utilisé pour suivre les appels de fonctions imbriqués.

<a id="-llm-as-a-judge-metrics"></a>
### 🧑‍⚖️ LLM en tant que juge métriques

Le SDK Python Opik comprend un certain nombre de LLM comme mesures de jugement pour vous aider à évaluer votre application LLM. Apprenez-en plus dans la [documentation sur les métriques](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_2_link&utm_campaign=opik).

Pour les utiliser, importez simplement la métrique appropriée et utilisez la fonction `score` :

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

Opik comprend également un certain nombre de métriques heuristiques prédéfinies ainsi que la possibilité de créer les vôtres. Apprenez-en plus dans la [documentation sur les métriques](https://www.comet.com/docs/opik/evaluation/metrics/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_3_link&utm_campaign=opik).

<a id="-evaluating-your-llm-application"></a>
### 🔍 Évaluation de vos candidatures LLM

Opik vous permet d'évaluer votre application LLM pendant le développement via des [Datasets](https://www.comet.com/docs/opik/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_2_link&utm_campaign=opik) et [Expériences](https://www.comet.com/docs/opik/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=experiments_link&utm_campaign=opik). Le tableau de bord Opik propose des graphiques améliorés pour les expériences et une meilleure gestion des traces volumineuses. Vous pouvez également exécuter des évaluations dans le cadre de votre pipeline CI/CD à l'aide de notre [intégration PyTest](https://www.comet.com/docs/opik/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_2_link&utm_campaign=opik).

<a id="-star-us-on-github"></a>
## ⭐ Star Us on GitHub

Si vous trouvez Opik utile, pensez à nous attribuer une étoile ! Votre soutien nous aide à développer notre communauté et à continuer d'améliorer le produit.

[![Graphique de l'historique des étoiles](https://api.star-history.com/svg?repos=comet-ml/opik&type=Date)](https://github.com/comet-ml/opik)

<a id="-contributing"></a>
## 🤝 Contribuer

Il existe de nombreuses façons de contribuer à Opik :

- Soumettre des [rapports de bogues](https://github.com/comet-ml/opik/issues) et des [demandes de fonctionnalités](https://github.com/comet-ml/opik/issues)
- Examiner la documentation et soumettre des [Pull Requests](https://github.com/comet-ml/opik/pulls) pour l'améliorer
- Parler ou écrire sur Opik et [nous faire savoir](https://chat.comet.com)
- Vote positif [demandes de fonctionnalités populaires](https://github.com/comet-ml/opik/issues?q=is%3Aissue+is%3Aopen+label%3A%22enhancement%22) pour montrer votre soutien

Pour en savoir plus sur la façon de contribuer à Opik, veuillez consulter nos [directives de contribution](CONTRIBUTING.md).
