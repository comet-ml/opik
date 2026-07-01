# External Integration References

Known external Opik integrations, to seed the research phase. When the target is (or resembles) one of these, start from its repo and docs instead of investigating from scratch — and use the closest one as the "clone the sibling" source.

> Verify before relying on specifics: repos change. Treat the **paths** below as starting hints, not guarantees, and confirm against the live repo. Add new entries here whenever you build or discover one.

## Standalone `opik-*` packages & plugins (comet-ml org)

For the **standalone package** shape — its own repo depending on the published Opik SDK.

| Repo | What it is | URL |
|---|---|---|
| `opik-project-template` | Cookiecutter scaffold for a new Opik project — **the starting point for a brand-new standalone package** | https://github.com/comet-ml/opik-project-template |
| `opik-openclaw` | Official OpenClaw plugin exporting agent traces to Opik (traces, cost, tokens, errors) | https://github.com/comet-ml/opik-openclaw |
| `opik-claude-code-plugin` | Opik plugin for Claude Code | https://github.com/comet-ml/opik-claude-code-plugin |
| `opik-codex-plugin` | Opik plugin for Codex | https://github.com/comet-ml/opik-codex-plugin |
| `opik-kong-plugin` | Opik plugin for the Kong AI Gateway | https://github.com/comet-ml/opik-kong-plugin |

## Upstream contributions (Opik support inside a third-party repo)

For the **upstream contribution** shape — Opik logging/callback added into a third-party project that owns its own plugin/observability system. Host repo (where the Opik code lives) + the Opik-side docs page.

| Host | Host repo | Opik docs |
|---|---|---|
| LiteLLM | https://github.com/BerriAI/litellm (Opik logger under `litellm/integrations/opik/`) | https://www.comet.com/docs/opik/integrations/litellm |
| Dify | https://github.com/langgenius/dify | https://www.comet.com/docs/opik/integrations/dify |
| n8n | https://github.com/n8n-io/n8n | https://www.comet.com/docs/opik/integrations/n8n |
| Flowise | https://github.com/FlowiseAI/Flowise | https://www.comet.com/docs/opik/integrations/flowise |
| Langflow | https://github.com/langflow-ai/langflow | https://www.comet.com/docs/opik/integrations/langflow |

## Discovering others

- **Opik docs integrations index** — the canonical list of published integrations (many are external/host-side): https://www.comet.com/docs/opik/integrations/overview
- **comet-ml GitHub org** — search for `opik-` repos (plugins, templates): https://github.com/orgs/comet-ml/repositories?q=opik-
- In this repo, the docs sources under `apps/opik-documentation/documentation/fern/docs-v2/integrations/` list every integration slug; host-side ones (e.g. `litellm`, `dify`, `n8n`, `flowise`, `langflow`) map to the docs URLs above.
