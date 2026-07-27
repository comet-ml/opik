<div align="center"><b><a href="README.md">English</a> | <a href="readme_CN.md">简体中文</a> | <a href="readme_ES.md">Español</a> | <a href="readme_FR.md">Français</a> | <a href="readme_DE.md">Deutsch</a></b></div>

> Remarque : Ce fichier a été traduit automatiquement. Les améliorations de la traduction sont bienvenues !


<h1 align="center" style="border-bottom: none">
    <div>
        <a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=header_img&utm_campaign=opik"><picture>
            <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/logo-dark-mode.svg">
            <source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg">
            <img alt="Logo Comet Opik" src="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg" width="200" />
        </picture></a>
        <br>
        Opik : Observabilité, évaluation et traçage d'agents IA pour LLM en open source
    </div>
</h1>
<p align="center">
<b>Opik est la plateforme open source d'observabilité et d'évaluation des LLM pour le traçage d'agents IA, l'évaluation des LLM, la gestion des prompts et la surveillance en production.</b> Développée par <a href="https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=what_is_opik_link&utm_campaign=opik">Comet</a>. Sous licence Apache-2.0, gratuite à héberger vous-même sur l'ensemble de la plateforme, avec plus de 20 000 étoiles sur GitHub.
</p>

<div align="center">

[![Python SDK](https://img.shields.io/pypi/v/opik)](https://pypi.org/project/opik/)
[![License](https://img.shields.io/github/license/comet-ml/opik)](https://github.com/comet-ml/opik/blob/main/LICENSE)
[![Build](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml/badge.svg)](https://github.com/comet-ml/opik/actions/workflows/build_apps.yml)
<!-- [![Quick Start](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/opik_quickstart.ipynb) -->

</div>

<p align="center">
    <a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=website_button&utm_campaign=opik"><b>Site web</b></a> •
    <a href="https://chat.comet.com"><b>Communauté Slack</b></a> •
    <a href="https://x.com/Cometml"><b>Twitter</b></a> •
    <a href="https://www.comet.com/docs/opik/changelog"><b>Journal des modifications</b></a> •
    <a href="https://www.comet.com/docs/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=docs_button&utm_campaign=opik"><b>Documentation</b></a>
</p>

<p align="center"><sub>Dernière mise à jour : 2026-07-17</sub></p>

<div align="center" style="margin-top: 1em; margin-bottom: 1em;">
<a href="#-what-is-opik">🚀 Qu'est-ce qu'Opik ?</a> • <a href="#-quick-start">⚡ Démarrage rapide</a> • <a href="#-how-opik-compares">📊 Comment Opik se compare-t-il ?</a> • <a href="#-frequently-asked-questions">❓ FAQ</a> • <a href="#%EF%B8%8F-opik-server-installation">🛠️ Installation du serveur Opik</a> • <a href="#-opik-client-sdk">💻 SDK client Opik</a> • <a href="#-logging-traces-with-integrations">📝 Journalisation des traces</a><br>
<a href="#-llm-as-a-judge-metrics">🧑‍⚖️ LLM comme juge</a> • <a href="#-evaluating-your-llm-application">🔍 Évaluer votre application</a> • <a href="#-star-us-on-github">⭐ Ajoutez une étoile</a> • <a href="#-contributing">🤝 Contribuer</a>
</div>

<br>

[![Capture d'écran de la plateforme Opik (miniature)](readme-thumbnail-new.png)](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=readme_banner&utm_campaign=opik)

<a id="-what-is-opik"></a>
## 🚀 Qu'est-ce qu'Opik ?

Opik couvre l'ensemble du cycle de vie des applications LLM, de la première trace en développement jusqu'à la surveillance en production, pour les équipes qui créent des applications LLM et des agents IA. Les principales offres incluent :

- **Traçage et observabilité des agents IA** : traçage approfondi des appels LLM, journalisation des conversations et de l'activité des agents, avec des arbres de traces complets pour les agents multi-étapes et les appels d'outils.
- **Évaluation des LLM** : jeux de données, expériences et métriques LLM-comme-juge pour la détection des hallucinations, la modération et l'évaluation RAG.
- **Optimisation des prompts et des agents** : le SDK Opik Agent Optimizer pour améliorer les prompts et les agents.
- **Surveillance prête pour la production** : tableaux de bord évolutifs et règles d'évaluation en ligne.
- **Opik Guardrails** : des fonctionnalités pour vous aider à mettre en œuvre des pratiques d'IA sûres et responsables.
- **Évaluation CI/CD** : une intégration PyTest pour tester les pipelines LLM à chaque commit.

<br>

Les principales capacités incluent :

- **Développement et traçage :**
  - Suivez tous les appels et traces LLM avec un contexte détaillé pendant le développement et en production ([Démarrage rapide](https://www.comet.com/docs/opik/quickstart/?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_link&utm_campaign=opik)).
  - De nombreuses intégrations tierces pour une observabilité facile : intégrez-vous en toute transparence avec une liste croissante de frameworks, dont beaucoup parmi les plus grands et les plus populaires sont pris en charge nativement (y compris des ajouts récents comme **Google ADK**, **Autogen** et **Flowise AI**). ([Intégrations](https://www.comet.com/docs/opik/integrations/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=integrations_link&utm_campaign=opik))
  - Annotez les traces et les spans avec des scores de feedback via le [SDK Python](https://www.comet.com/docs/opik/v1/tracing/annotate_traces/#annotating-traces-and-spans-using-the-sdk?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link&utm_campaign=opik) ou l'[interface](https://www.comet.com/docs/opik/tracing/annotate_traces/#annotating-traces-through-the-ui?from=llm&utm_source=opik&utm_medium=github&utm_content=ui_link&utm_campaign=opik).
  - Expérimentez avec des prompts et des modèles dans le [Prompt Playground](https://www.comet.com/docs/opik/prompt_engineering/playground).

- **Évaluation et test** :
  - Automatisez l'évaluation de votre application LLM avec les [Jeux de données](https://www.comet.com/docs/opik/v1/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_link&utm_campaign=opik) et les [Expériences](https://www.comet.com/docs/opik/v1/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=eval_link&utm_campaign=opik).
  - Tirez parti de puissantes métriques LLM-comme-juge pour des tâches complexes comme la [détection des hallucinations](https://www.comet.com/docs/opik/evaluation/metrics/hallucination/?from=llm&utm_source=opik&utm_medium=github&utm_content=hallucination_link&utm_campaign=opik), la [modération](https://www.comet.com/docs/opik/evaluation/metrics/moderation/?from=llm&utm_source=opik&utm_medium=github&utm_content=moderation_link&utm_campaign=opik) et l'évaluation RAG ([Pertinence de la réponse](https://www.comet.com/docs/opik/evaluation/metrics/answer_relevance/?from=llm&utm_source=opik&utm_medium=github&utm_content=alex_link&utm_campaign=opik), [Précision du contexte](https://www.comet.com/docs/opik/evaluation/metrics/context_precision/?from=llm&utm_source=opik&utm_medium=github&utm_content=context_link&utm_campaign=opik)).
  - Intégrez les évaluations dans votre pipeline CI/CD grâce à notre [intégration PyTest](https://www.comet.com/docs/opik/v1/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_link&utm_campaign=opik).

- **Surveillance et optimisation en production** :
  - Journalisez de grands volumes de traces de production : Opik est conçu pour le passage à l'échelle (plus de 40 M de traces/jour).
  - Surveillez les scores de feedback, le nombre de traces et l'utilisation des tokens au fil du temps dans le [Tableau de bord Opik](https://www.comet.com/docs/opik/v1/production/production_monitoring/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik).
  - Utilisez les [Règles d'évaluation en ligne](https://www.comet.com/docs/opik/v1/production/rules/?from=llm&utm_source=opik&utm_medium=github&utm_content=dashboard_link&utm_campaign=opik) avec des métriques LLM-comme-juge pour identifier les problèmes de production.
  - Tirez parti d'**Opik Agent Optimizer** et d'**Opik Guardrails** pour améliorer et sécuriser en continu vos applications LLM en production.

**À qui cela s'adresse :** aux ingénieurs ML qui construisent des agents alimentés par LLM, aux équipes IA qui passent du prototype à la production, et aux équipes d'ingénierie qui ont besoin d'une observabilité open source et auto-hébergeable qu'elles peuvent exécuter dans leur propre environnement.

> **Pourquoi l'open source compte ici :** Opik est sous licence Apache-2.0 et gratuit à auto-héberger : la plateforme complète, backend inclus, pas seulement un SDK client. Le dépôt inclut le backend serveur, l'application web, le traçage, les jeux de données, les expériences, les évaluations, la gestion des prompts, l'évaluation en ligne et les composants d'optimisation d'agents, le tout sous licence Apache-2.0. Vous pouvez exécuter l'observabilité des LLM au sein de votre propre infrastructure, sans qu'aucune donnée ne quitte votre environnement et sans avoir à passer par une conversation commerciale Enterprise.

> [!TIP]
> Si vous recherchez des fonctionnalités qu'Opik ne propose pas aujourd'hui, veuillez soumettre une nouvelle [demande de fonctionnalité](https://github.com/comet-ml/opik/issues/new/choose) 🚀

<br>

<a id="-quick-start"></a>
## ⚡ Démarrage rapide

Installez le SDK Python et configurez-le :

```bash
pip install opik
opik configure
```

Enveloppez n'importe quelle fonction avec le décorateur `@track` pour commencer à journaliser les traces :

```python
from opik import track

@track
def my_function(input: str) -> str:
    return input
```

Chaque appel à `my_function` est désormais journalisé dans Opik, y compris les appels imbriqués, ce qui fonctionne donc pour des traces complètes d'agents et de pipelines, et pas seulement pour des appels LLM isolés. Consultez le [guide de démarrage rapide](https://www.comet.com/docs/opik/quickstart?from=llm&utm_source=opik&utm_medium=github&utm_content=quickstart_hero_link&utm_campaign=opik) pour le SDK TypeScript et d'autres options de configuration.

<br>

<a id="-how-opik-compares"></a>
## 📊 Comment Opik se compare-t-il ?

Opik est en concurrence dans la catégorie **observabilité des LLM / évaluation des agents IA** aux côtés de **LangSmith, Arize (Phoenix et Arize AX), Weights & Biases (Weave), Langfuse et Braintrust**.

| Capacité | Opik | LangSmith | Phoenix | Arize AX | Weights & Biases (Weave) | Langfuse | Braintrust |
|---|---|---|---|---|---|---|---|
| Open source | Oui, Apache-2.0 (plateforme complète) | Non | Source disponible (Elastic License 2.0, non approuvée par l'OSI) | Non | SDK/boîte à outils open source ; la plateforme auto-gérée requiert une licence commerciale | Cœur de plateforme sous licence MIT ; modules d'entreprise commerciaux | Non |
| Déploiement auto-hébergé | Oui | Enterprise uniquement | Oui | Enterprise uniquement | Enterprise uniquement pour Weave lui-même | Oui, le cœur | Enterprise uniquement |
| Offre gratuite disponible (cloud ou auto-hébergé) | Oui, les deux | Oui, cloud | Oui, auto-hébergé | Oui, cloud | Oui, cloud | Oui, les deux | Oui, cloud |
| Traçage d'agents / multi-étapes | Oui | Oui | Oui | Oui | Oui | Oui | Oui |
| Évaluation LLM-comme-juge | Oui | Oui | Oui | Oui | Oui | Oui | Oui |
| Gestion des prompts | Oui | Oui | En partie | En partie | En partie | Oui | Oui |
| Indépendant du framework | Oui | En partie, conçu autour de LangChain | Oui | Oui | Oui | Oui | Oui |

**Quand les équipes choisissent Opik :** la plateforme complète d'observabilité, d'évaluation et d'optimisation d'Opik est sous licence Apache-2.0 et gratuite à auto-héberger. Contrairement aux plateformes fermées dont le déploiement auto-hébergé requiert un plan Enterprise, Opik peut être déployé sans licence commerciale, et il est indépendant du framework, de sorte qu'il ne vous enferme pas dans un écosystème d'agents unique. Consultez le tableau ci-dessus pour voir où l'auto-hébergement et les licences diffèrent selon les alternatives.

<br>

<a id="-frequently-asked-questions"></a>
## ❓ Foire aux questions

#### Opik est-il open source ?
Opik est sous licence Apache 2.0. Son serveur, son application web et ses capacités fondamentales d'observabilité et d'évaluation peuvent être auto-hébergés sans licence commerciale.

#### Puis-je auto-héberger Opik ?
Oui. Opik peut être déployé localement ou dans votre propre infrastructure à l'aide des options d'auto-hébergement documentées.

#### Opik prend-il en charge le traçage des agents IA ?
Oui. Opik capture des traces multi-étapes contenant des appels LLM, des exécutions d'outils, des étapes de récupération et d'autres activités d'agents.

#### Opik prend-il en charge l'évaluation des LLM ?
Oui. Opik prend en charge les jeux de données, les expériences, les métriques basées sur le code, l'évaluation LLM-comme-juge et l'évaluation en ligne.

#### Opik est-il lié à un framework d'agents spécifique ?
Non. Opik est indépendant du framework et prend en charge son SDK, OpenTelemetry et des intégrations propres à chaque framework.

<br>

<a id="%EF%B8%8F-opik-server-installation"></a>
## 🛠️ Installation du serveur Opik

Faites fonctionner votre serveur Opik en quelques minutes. Choisissez l'option qui correspond le mieux à vos besoins :

### Option 1 : Comet.com Cloud (le plus simple et recommandé)

Accédez à Opik instantanément sans aucune configuration. Idéal pour des démarrages rapides et une maintenance sans tracas.

👉 [Créez votre compte Comet gratuit](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=github&utm_content=install_create_link&utm_campaign=opik)

### Option 2 : Auto-héberger Opik pour un contrôle total

Déployez Opik dans votre propre environnement. Choisissez entre Docker pour les configurations locales ou Kubernetes pour l'évolutivité.

#### Auto-hébergement avec Docker Compose (pour le développement et les tests locaux)

C'est la façon la plus simple d'obtenir une instance Opik locale opérationnelle. Notez le nouveau script d'installation `./opik.sh` :

Sur un environnement Linux ou Mac :

```bash
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the repository
cd opik

# Start the Opik platform
./opik.sh
```

Sur un environnement Windows :

```powershell
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the repository
cd opik

# Start the Opik platform
powershell -ExecutionPolicy ByPass -c ".\\opik.ps1"
```

**Profils de service pour le développement**

Les scripts d'installation d'Opik prennent désormais en charge des profils de service pour différents scénarios de développement :

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

Utilisez les options `--help` ou `--info` pour résoudre les problèmes. Les Dockerfiles garantissent désormais que les conteneurs s'exécutent en tant qu'utilisateurs non-root pour une sécurité renforcée. Une fois que tout est opérationnel, vous pouvez désormais visiter [localhost:5173](http://localhost:5173) dans votre navigateur ! Pour des instructions détaillées, consultez le [Guide de déploiement local](https://www.comet.com/docs/opik/self-host/local_deployment?from=llm&utm_source=opik&utm_medium=github&utm_content=self_host_link&utm_campaign=opik).

#### Auto-hébergement avec Kubernetes et Helm (pour les déploiements évolutifs)

Pour les déploiements auto-hébergés en production ou à plus grande échelle, Opik peut être installé sur un cluster Kubernetes à l'aide de notre chart Helm. Cliquez sur le badge pour consulter le [Guide d'installation Kubernetes avec Helm](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik) complet.

[![Kubernetes](https://img.shields.io/badge/Kubernetes-%23326ce5.svg?&logo=kubernetes&logoColor=white)](https://www.comet.com/docs/opik/self-host/kubernetes/#kubernetes-installation?from=llm&utm_source=opik&utm_medium=github&utm_content=kubernetes_link&utm_campaign=opik)

> [!IMPORTANT]
> **Modifications de la version 1.7.0** : veuillez consulter le [journal des modifications](https://github.com/comet-ml/opik/blob/main/CHANGELOG.md) pour les mises à jour importantes et les changements incompatibles.

<a id="-opik-client-sdk"></a>
## 💻 SDK client Opik

Opik fournit une suite de bibliothèques clientes et une API REST pour interagir avec le serveur Opik. Cela inclut des SDK pour Python, TypeScript et Ruby (via OpenTelemetry), permettant une intégration transparente dans vos workflows. Pour des références détaillées sur l'API et les SDK, consultez la [Documentation de référence du client Opik](https://www.comet.com/docs/opik/reference/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=reference_link&utm_campaign=opik).

### Démarrage rapide du SDK Python

Pour commencer avec le SDK Python :

Installez le paquet :

```bash
# install using pip
pip install opik

# or install with uv
uv pip install opik
```

Configurez le SDK Python en exécutant la commande `opik configure`, qui vous demandera l'adresse de votre serveur Opik (pour les instances auto-hébergées) ou votre clé d'API et votre espace de travail (pour Comet.com) :

```bash
opik configure
```

> [!TIP]
> Vous pouvez également appeler `opik.configure(use_local=True)` depuis votre code Python pour configurer le SDK afin qu'il s'exécute sur une installation locale auto-hébergée, ou fournir directement la clé d'API et les détails de l'espace de travail pour Comet.com. Reportez-vous à la [documentation du SDK Python](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=python_sdk_docs_link&utm_campaign=opik) pour d'autres options de configuration.

Vous êtes maintenant prêt à commencer à journaliser des traces à l'aide du [SDK Python](https://www.comet.com/docs/opik/python-sdk-reference/?from=llm&utm_source=opik&utm_medium=github&utm_content=sdk_link2&utm_campaign=opik).

<a id="-logging-traces-with-integrations"></a>
### 📝 Journalisation des traces avec les intégrations

La façon la plus simple de journaliser des traces est d'utiliser l'une de nos intégrations directes. Opik prend en charge un large éventail de frameworks, y compris des ajouts récents comme **Google ADK**, **Autogen**, **AG2** et **Flowise AI** :

| Intégration           | Description                                             | Documentation                                                                                                                                                                  |
| --------------------- | ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| ADK                   | Journalise les traces pour Google Agent Development Kit (ADK) | [Documentation](https://www.comet.com/docs/opik/integrations/adk?utm_source=opik&utm_medium=github&utm_content=google_adk_link&utm_campaign=opik)                              |
| AG2                   | Journalise les traces des appels LLM AG2                | [Documentation](https://www.comet.com/docs/opik/integrations/ag2?utm_source=opik&utm_medium=github&utm_content=ag2_link&utm_campaign=opik)                                     |
| Agent Spec            | Journalise les traces des appels Agent Spec            | [Documentation](https://www.comet.com/docs/opik/integrations/agentspec?utm_source=opik&utm_medium=github&utm_content=agentspec_link&utm_campaign=opik)                         |
| AIsuite               | Journalise les traces des appels LLM aisuite           | [Documentation](https://www.comet.com/docs/opik/integrations/aisuite?utm_source=opik&utm_medium=github&utm_content=aisuite_link&utm_campaign=opik)                             |
| Agno                  | Journalise les traces des appels du framework d'orchestration d'agents Agno | [Documentation](https://www.comet.com/docs/opik/integrations/agno?utm_source=opik&utm_medium=github&utm_content=agno_link&utm_campaign=opik)                                   |
| Anthropic             | Journalise les traces des appels LLM Anthropic         | [Documentation](https://www.comet.com/docs/opik/integrations/anthropic?utm_source=opik&utm_medium=github&utm_content=anthropic_link&utm_campaign=opik)                         |
| Autogen               | Journalise les traces des workflows agentiques Autogen | [Documentation](https://www.comet.com/docs/opik/integrations/autogen?utm_source=opik&utm_medium=github&utm_content=autogen_link&utm_campaign=opik)                             |
| Bedrock               | Journalise les traces des appels LLM Amazon Bedrock    | [Documentation](https://www.comet.com/docs/opik/integrations/bedrock?utm_source=opik&utm_medium=github&utm_content=bedrock_link&utm_campaign=opik)                             |
| BeeAI (Python)        | Journalise les traces des appels du framework d'agents BeeAI Python | [Documentation](https://www.comet.com/docs/opik/integrations/beeai?utm_source=opik&utm_medium=github&utm_content=beeai_link&utm_campaign=opik)                                 |
| BeeAI (TypeScript)    | Journalise les traces des appels du framework d'agents BeeAI TypeScript | [Documentation](https://www.comet.com/docs/opik/integrations/beeai-typescript?utm_source=opik&utm_medium=github&utm_content=beeai_typescript_link&utm_campaign=opik)           |
| BytePlus              | Journalise les traces des appels LLM BytePlus          | [Documentation](https://www.comet.com/docs/opik/integrations/byteplus?utm_source=opik&utm_medium=github&utm_content=byteplus_link&utm_campaign=opik)                           |
| Cloudflare Workers AI | Journalise les traces des appels Cloudflare Workers AI | [Documentation](https://www.comet.com/docs/opik/integrations/cloudflare-workers-ai?utm_source=opik&utm_medium=github&utm_content=cloudflare_workers_ai_link&utm_campaign=opik) |
| Cohere                | Journalise les traces des appels LLM Cohere            | [Documentation](https://www.comet.com/docs/opik/integrations/cohere?utm_source=opik&utm_medium=github&utm_content=cohere_link&utm_campaign=opik)                               |
| CrewAI                | Journalise les traces des appels CrewAI                | [Documentation](https://www.comet.com/docs/opik/integrations/crewai?utm_source=opik&utm_medium=github&utm_content=crewai_link&utm_campaign=opik)                               |
| Cursor                | Journalise les traces des conversations Cursor         | [Documentation](https://www.comet.com/docs/opik/integrations/cursor?utm_source=opik&utm_medium=github&utm_content=cursor_link&utm_campaign=opik)                               |
| DeepSeek              | Journalise les traces des appels LLM DeepSeek          | [Documentation](https://www.comet.com/docs/opik/integrations/deepseek?utm_source=opik&utm_medium=github&utm_content=deepseek_link&utm_campaign=opik)                           |
| Dify                  | Journalise les traces des exécutions d'agents Dify     | [Documentation](https://www.comet.com/docs/opik/integrations/dify?utm_source=opik&utm_medium=github&utm_content=dify_link&utm_campaign=opik)                                   |
| DSPY                  | Journalise les traces des exécutions DSPy              | [Documentation](https://www.comet.com/docs/opik/integrations/dspy?utm_source=opik&utm_medium=github&utm_content=dspy_link&utm_campaign=opik)                                   |
| Fireworks AI          | Journalise les traces des appels LLM Fireworks AI      | [Documentation](https://www.comet.com/docs/opik/integrations/fireworks-ai?utm_source=opik&utm_medium=github&utm_content=fireworks_ai_link&utm_campaign=opik)                   |
| Flowise AI            | Journalise les traces du constructeur LLM visuel Flowise AI | [Documentation](https://www.comet.com/docs/opik/integrations/flowise?utm_source=opik&utm_medium=github&utm_content=flowise_link&utm_campaign=opik)                             |
| Gemini (Python)       | Journalise les traces des appels LLM Google Gemini     | [Documentation](https://www.comet.com/docs/opik/integrations/gemini?utm_source=opik&utm_medium=github&utm_content=gemini_link&utm_campaign=opik)                               |
| Gemini (TypeScript)   | Journalise les traces des appels du SDK TypeScript Google Gemini | [Documentation](https://www.comet.com/docs/opik/integrations/gemini-typescript?utm_source=opik&utm_medium=github&utm_content=gemini_typescript_link&utm_campaign=opik)         |
| Groq                  | Journalise les traces des appels LLM Groq              | [Documentation](https://www.comet.com/docs/opik/integrations/groq?utm_source=opik&utm_medium=github&utm_content=groq_link&utm_campaign=opik)                                   |
| Guardrails            | Journalise les traces des validations Guardrails AI    | [Documentation](https://www.comet.com/docs/opik/integrations/guardrails-ai?utm_source=opik&utm_medium=github&utm_content=guardrails_link&utm_campaign=opik)                    |
| Haystack              | Journalise les traces des appels Haystack              | [Documentation](https://www.comet.com/docs/opik/integrations/haystack?utm_source=opik&utm_medium=github&utm_content=haystack_link&utm_campaign=opik)                           |
| Harbor                | Journalise les traces des essais d'évaluation de benchmark Harbor | [Documentation](https://www.comet.com/docs/opik/integrations/harbor?utm_source=opik&utm_medium=github&utm_content=harbor_link&utm_campaign=opik)                               |
| Instructor            | Journalise les traces des appels LLM effectués avec Instructor | [Documentation](https://www.comet.com/docs/opik/integrations/instructor?utm_source=opik&utm_medium=github&utm_content=instructor_link&utm_campaign=opik)                       |
| LangChain (Python)    | Journalise les traces des appels LLM LangChain         | [Documentation](https://www.comet.com/docs/opik/integrations/langchain?utm_source=opik&utm_medium=github&utm_content=langchain_link&utm_campaign=opik)                         |
| LangChain (JS/TS)     | Journalise les traces des appels LangChain JavaScript/TypeScript | [Documentation](https://www.comet.com/docs/opik/integrations/langchainjs?utm_source=opik&utm_medium=github&utm_content=langchainjs_link&utm_campaign=opik)                     |
| LangGraph             | Journalise les traces des exécutions LangGraph         | [Documentation](https://www.comet.com/docs/opik/integrations/langgraph?utm_source=opik&utm_medium=github&utm_content=langgraph_link&utm_campaign=opik)                         |
| Langflow              | Journalise les traces du constructeur d'IA visuel Langflow | [Documentation](https://www.comet.com/docs/opik/integrations/langflow?utm_source=opik&utm_medium=github&utm_content=langflow_link&utm_campaign=opik)                           |
| LiteLLM               | Journalise les traces des appels de modèles LiteLLM    | [Documentation](https://www.comet.com/docs/opik/integrations/litellm?utm_source=opik&utm_medium=github&utm_content=litellm_link&utm_campaign=opik)                             |
| LiveKit Agents        | Journalise les traces des appels du framework d'agents IA LiveKit Agents | [Documentation](https://www.comet.com/docs/opik/integrations/livekit?utm_source=opik&utm_medium=github&utm_content=livekit_link&utm_campaign=opik)                             |
| LlamaIndex            | Journalise les traces des appels LLM LlamaIndex        | [Documentation](https://www.comet.com/docs/opik/integrations/llama_index?utm_source=opik&utm_medium=github&utm_content=llama_index_link&utm_campaign=opik)                     |
| Mastra                | Journalise les traces des appels du framework de workflow IA Mastra | [Documentation](https://www.comet.com/docs/opik/integrations/mastra?utm_source=opik&utm_medium=github&utm_content=mastra_link&utm_campaign=opik)                               |
| Microsoft Agent Framework (Python) | Journalise les traces des appels Microsoft Agent Framework | [Documentation](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework?utm_source=opik&utm_medium=github&utm_content=agent_framework_link&utm_campaign=opik)              |
| Microsoft Agent Framework (.NET) | Journalise les traces des appels Microsoft Agent Framework .NET | [Documentation](https://www.comet.com/docs/opik/integrations/microsoft-agent-framework-dotnet?utm_source=opik&utm_medium=github&utm_content=agent_framework_dotnet_link&utm_campaign=opik) |
| Mistral AI            | Journalise les traces des appels LLM Mistral AI        | [Documentation](https://www.comet.com/docs/opik/integrations/mistral?utm_source=opik&utm_medium=github&utm_content=mistral_link&utm_campaign=opik)                             |
| n8n                   | Journalise les traces des exécutions de workflow n8n   | [Documentation](https://www.comet.com/docs/opik/integrations/n8n?utm_source=opik&utm_medium=github&utm_content=n8n_link&utm_campaign=opik)                                     |
| Novita AI             | Journalise les traces des appels LLM Novita AI         | [Documentation](https://www.comet.com/docs/opik/integrations/novita-ai?utm_source=opik&utm_medium=github&utm_content=novita_ai_link&utm_campaign=opik)                         |
| Ollama                | Journalise les traces des appels LLM Ollama            | [Documentation](https://www.comet.com/docs/opik/integrations/ollama?utm_source=opik&utm_medium=github&utm_content=ollama_link&utm_campaign=opik)                               |
| OpenAI (Python)       | Journalise les traces des appels LLM OpenAI            | [Documentation](https://www.comet.com/docs/opik/integrations/openai?utm_source=opik&utm_medium=github&utm_content=openai_link&utm_campaign=opik)                               |
| OpenAI (JS/TS)        | Journalise les traces des appels OpenAI JavaScript/TypeScript | [Documentation](https://www.comet.com/docs/opik/integrations/openai-typescript?utm_source=opik&utm_medium=github&utm_content=openai_typescript_link&utm_campaign=opik)         |
| OpenAI Agents         | Journalise les traces des appels du SDK OpenAI Agents  | [Documentation](https://www.comet.com/docs/opik/integrations/openai_agents?utm_source=opik&utm_medium=github&utm_content=openai_agents_link&utm_campaign=opik)                 |
| OpenClaw              | Journalise les traces des exécutions d'agents OpenClaw | [Documentation](https://www.comet.com/docs/opik/integrations/openclaw?utm_source=opik&utm_medium=github&utm_content=openclaw_link&utm_campaign=opik) |
| OpenRouter            | Journalise les traces des appels LLM OpenRouter        | [Documentation](https://www.comet.com/docs/opik/integrations/openrouter?utm_source=opik&utm_medium=github&utm_content=openrouter_link&utm_campaign=opik)                       |
| OpenTelemetry         | Journalise les traces des appels pris en charge par OpenTelemetry | [Documentation](https://www.comet.com/docs/opik/tracing/opentelemetry/overview?utm_source=opik&utm_medium=github&utm_content=opentelemetry_link&utm_campaign=opik)             |
| OpenWebUI             | Journalise les traces des conversations OpenWebUI      | [Documentation](https://www.comet.com/docs/opik/integrations/openwebui?utm_source=opik&utm_medium=github&utm_content=openwebui_link&utm_campaign=opik)                         |
| Pipecat               | Journalise les traces des appels d'agents vocaux en temps réel Pipecat | [Documentation](https://www.comet.com/docs/opik/integrations/pipecat?utm_source=opik&utm_medium=github&utm_content=pipecat_link&utm_campaign=opik)                             |
| Predibase             | Journalise les traces des appels LLM Predibase         | [Documentation](https://www.comet.com/docs/opik/integrations/predibase?utm_source=opik&utm_medium=github&utm_content=predibase_link&utm_campaign=opik)                         |
| Pydantic AI           | Journalise les traces des appels d'agents PydanticAI   | [Documentation](https://www.comet.com/docs/opik/integrations/pydantic-ai?utm_source=opik&utm_medium=github&utm_content=pydantic_ai_link&utm_campaign=opik)                     |
| Ragas                 | Journalise les traces des évaluations Ragas            | [Documentation](https://www.comet.com/docs/opik/integrations/ragas?utm_source=opik&utm_medium=github&utm_content=ragas_link&utm_campaign=opik)                                 |
| Semantic Kernel       | Journalise les traces des appels Microsoft Semantic Kernel | [Documentation](https://www.comet.com/docs/opik/integrations/semantic-kernel?utm_source=opik&utm_medium=github&utm_content=semantic_kernel_link&utm_campaign=opik)             |
| Smolagents            | Journalise les traces des agents Smolagents            | [Documentation](https://www.comet.com/docs/opik/integrations/smolagents?utm_source=opik&utm_medium=github&utm_content=smolagents_link&utm_campaign=opik)                       |
| Spring AI             | Journalise les traces des appels du framework Spring AI | [Documentation](https://www.comet.com/docs/opik/integrations/spring-ai?utm_source=opik&utm_medium=github&utm_content=spring_ai_link&utm_campaign=opik)                         |
| Strands Agents        | Journalise les traces des appels Strands agents        | [Documentation](https://www.comet.com/docs/opik/integrations/strands-agents?utm_source=opik&utm_medium=github&utm_content=strands_agents_link&utm_campaign=opik)               |
| Together AI           | Journalise les traces des appels LLM Together AI        | [Documentation](https://www.comet.com/docs/opik/integrations/together-ai?utm_source=opik&utm_medium=github&utm_content=together_ai_link&utm_campaign=opik)                     |
| Vercel AI SDK         | Journalise les traces des appels Vercel AI SDK         | [Documentation](https://www.comet.com/docs/opik/integrations/vercel-ai-sdk?utm_source=opik&utm_medium=github&utm_content=vercel_ai_sdk_link&utm_campaign=opik)                 |
| VoltAgent             | Journalise les traces des appels du framework d'agents VoltAgent | [Documentation](https://www.comet.com/docs/opik/integrations/voltagent?utm_source=opik&utm_medium=github&utm_content=voltagent_link&utm_campaign=opik)                         |
| WatsonX               | Journalise les traces des appels LLM IBM watsonx       | [Documentation](https://www.comet.com/docs/opik/integrations/watsonx?utm_source=opik&utm_medium=github&utm_content=watsonx_link&utm_campaign=opik)                             |
| xAI Grok              | Journalise les traces des appels LLM xAI Grok          | [Documentation](https://www.comet.com/docs/opik/integrations/xai-grok?utm_source=opik&utm_medium=github&utm_content=xai_grok_link&utm_campaign=opik)                           |

> [!TIP]
> Si le framework que vous utilisez ne figure pas dans la liste ci-dessus, n'hésitez pas à [ouvrir un ticket](https://github.com/comet-ml/opik/issues) ou à soumettre une PR avec l'intégration.

Si vous n'utilisez aucun des frameworks ci-dessus, vous pouvez également utiliser le décorateur de fonction `track` pour [journaliser les traces](https://www.comet.com/docs/opik/v1/tracing/log_traces/?from=llm&utm_source=opik&utm_medium=github&utm_content=traces_link&utm_campaign=opik) :

```python
import opik

opik.configure(use_local=True) # Run locally

@opik.track
def my_llm_function(user_question: str) -> str:
    # Your LLM code here

    return "Hello"
```

> [!TIP]
> Le décorateur track peut être utilisé conjointement avec n'importe laquelle de nos intégrations et peut également servir à suivre les appels de fonctions imbriqués.

<a id="-llm-as-a-judge-metrics"></a>
### 🧑‍⚖️ Métriques LLM comme juge

Le SDK Python d'Opik inclut un certain nombre de métriques LLM-comme-juge pour vous aider à évaluer votre application LLM. Apprenez-en davantage dans la [documentation des métriques](https://www.comet.com/docs/opik/evaluation/metrics/overview/?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_2_link&utm_campaign=opik).

Pour les utiliser, importez simplement la métrique pertinente et utilisez la fonction `score` :

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

Opik inclut également un certain nombre de métriques heuristiques prédéfinies ainsi que la possibilité de créer les vôtres. Apprenez-en davantage dans la [documentation des métriques](https://www.comet.com/docs/opik/evaluation/metrics/overview?from=llm&utm_source=opik&utm_medium=github&utm_content=metrics_3_link&utm_campaign=opik).

<a id="-evaluating-your-llm-application"></a>
### 🔍 Évaluer vos applications LLM

Opik vous permet d'évaluer votre application LLM pendant le développement grâce aux [Jeux de données](https://www.comet.com/docs/opik/v1/evaluation/manage_datasets/?from=llm&utm_source=opik&utm_medium=github&utm_content=datasets_2_link&utm_campaign=opik) et aux [Expériences](https://www.comet.com/docs/opik/v1/evaluation/evaluate_your_llm/?from=llm&utm_source=opik&utm_medium=github&utm_content=experiments_link&utm_campaign=opik). Le Tableau de bord Opik propose des graphiques améliorés pour les expériences et une meilleure gestion des grandes traces. Vous pouvez également exécuter des évaluations dans le cadre de votre pipeline CI/CD à l'aide de notre [intégration PyTest](https://www.comet.com/docs/opik/v1/testing/pytest_integration/?from=llm&utm_source=opik&utm_medium=github&utm_content=pytest_2_link&utm_campaign=opik).

<a id="-star-us-on-github"></a>
## ⭐ Ajoutez-nous une étoile sur GitHub

Si vous trouvez Opik utile, envisagez de nous donner une étoile ! Votre soutien nous aide à faire grandir notre communauté et à continuer d'améliorer le produit.

[![Graphique de l'historique des étoiles](https://api.star-history.com/svg?repos=comet-ml/opik&type=Date)](https://github.com/comet-ml/opik)

<a id="-contributing"></a>
## 🤝 Contribuer

Il existe de nombreuses façons de contribuer à Opik :

- Soumettre des [rapports de bugs](https://github.com/comet-ml/opik/issues) et des [demandes de fonctionnalités](https://github.com/comet-ml/opik/issues)
- Relire la documentation et soumettre des [Pull Requests](https://github.com/comet-ml/opik/pulls) pour l'améliorer
- Parler ou écrire à propos d'Opik et [nous en informer](https://chat.comet.com)
- Voter pour les [demandes de fonctionnalités populaires](https://github.com/comet-ml/opik/issues?q=is%3Aissue+is%3Aopen+label%3A%22enhancement%22) afin de montrer votre soutien

Pour en savoir plus sur la façon de contribuer à Opik, veuillez consulter nos [directives de contribution](CONTRIBUTING.md).
