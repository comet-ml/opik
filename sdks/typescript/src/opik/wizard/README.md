# Opik Wizard ✨

The Opik wizard helps you quickly add Opik SDK to your Node.js project for LLM
observability and tracing.

> **⚠️ Experimental:** This wizard is still in an experimental phase.

# Usage

```bash
npx @opik/wizard
```

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

# Development

To develop the wizard locally:

```bash
pnpm try --install-dir=[a path]
```

To build and use the tool globally:

```bash
pnpm build
pnpm link --global
opik-wizard [options]
```

## Contributing

This wizard is part of the Opik project. For contributing guidelines, please see
the main Opik repository.
