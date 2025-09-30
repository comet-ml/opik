# Opik Wizard ✨

The Opik wizard helps you quickly add Opik SDK to your Node.js project for LLM
observability and tracing.

> **⚠️ Experimental:** This wizard is still in an experimental phase.

# Usage

To use the wizard, you can run it directly using:

```bash
npx @opik/wizard
```

The wizard is designed specifically for **Node.js** projects and will help you
integrate the Opik TypeScript SDK for LLM tracing and observability.

# Options

The following CLI arguments are available:

| Option            | Description                                                | Type    | Default | Environment Variable        |
| ----------------- | ---------------------------------------------------------- | ------- | ------- | --------------------------- |
| `--help`          | Show help                                                  | boolean |         |                             |
| `--version`       | Show version number                                        | boolean |         |                             |
| `--debug`         | Enable verbose logging                                     | boolean | `false` | `OPIK_WIZARD_DEBUG`         |
| `--default`       | Use default options for all prompts                        | boolean | `true`  | `OPIK_WIZARD_DEFAULT`       |
| `--force-install` | Force install packages even if peer dependency checks fail | boolean | `false` | `OPIK_WIZARD_FORCE_INSTALL` |
| `--install-dir`   | Directory to install Opik SDK in                           | string  |         | `OPIK_WIZARD_INSTALL_DIR`   |

> Note: A large amount of the scaffolding for this came from the amazing Sentry
> wizard, which you can find [here](https://github.com/getsentry/sentry-wizard)
> 💖

# Architecture

This wizard is built with a modular architecture to make it easy to understand
and extend:

## Entrypoint: `bin.ts`

The main entry point for the CLI tool. Handles argument parsing and routes to
the appropriate wizard flow.

## Core Wizard: `src/run.ts`

The main wizard logic that guides users through setting up Opik SDK in their
Node.js projects.

## Configuration Detection

The wizard automatically detects your project type and suggests appropriate
integration patterns for Node.js applications.

## Development

To develop the wizard locally:

```bash
pnpm try --install-dir=[a path]
```

To build and use the tool locally:

```bash
pnpm build
```

This compiles the TypeScript code and prepares the `dist` directory.

```bash
pnpm link --global
```

This makes your local version available system-wide.

Then:

```bash
opik-wizard [options]
```

## Testing

To run tests:

```bash
pnpm test
```

## Contributing

This wizard is part of the Opik project. For contributing guidelines, please see
the main Opik repository.
