# Opik TS ✨

The Opik TS tool helps you quickly add Opik SDK to your Node.js project for LLM
observability and tracing.

> **⚠️ Experimental:** This tool is still in an experimental phase.

# Usage

## Quick Start

```bash
npx opik-ts configure
```

This will guide you through the setup process interactively.

## Quick Local Setup

For local development with a local Opik instance:

```bash
npx opik-ts configure --use-local
```

This skips API key and workspace prompts and automatically configures for local
deployment (http://localhost:5173/api).

# Options

The following CLI arguments are available:

| Option            | Description                                                            | Type    | Default | Environment Variable    |
| ----------------- | ---------------------------------------------------------------------- | ------- | ------- | ----------------------- |
| `--help`          | Show help                                                              | boolean |         |                         |
| `--version`       | Show version number                                                    | boolean |         |                         |
| `--debug`         | Enable verbose logging                                                 | boolean | `false` | `OPIK_TS_DEBUG`         |
| `--default`       | Use default options for all prompts                                    | boolean | `true`  | `OPIK_TS_DEFAULT`       |
| `--force-install` | Force install packages even if peer dependency checks fail             | boolean | `false` | `OPIK_TS_FORCE_INSTALL` |
| `--install-dir`   | Directory to install Opik SDK in                                       | string  |         | `OPIK_TS_INSTALL_DIR`   |
| `--use-local`     | Configure for local deployment (skips API key/workspace setup prompts) | boolean | `false` | `OPIK_TS_USE_LOCAL`     |

# Development

To develop the CLI locally:

```bash
pnpm try --install-dir=[a path]
```

To build and use the tool globally:

```bash
pnpm build
pnpm link --global
opik-ts [options]
```

## Contributing

This CLI is part of the Opik project. For contributing guidelines, please see
the main Opik repository.
