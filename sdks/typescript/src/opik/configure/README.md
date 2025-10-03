# Opik Configure ✨

The Opik configure tool helps you quickly add Opik SDK to your Node.js project
for LLM observability and tracing.

> **⚠️ Experimental:** This tool is still in an experimental phase.

# Usage

```bash
npx opik-configure
```

# Options

The following CLI arguments are available:

| Option            | Description                                                | Type    | Default | Environment Variable           |
| ----------------- | ---------------------------------------------------------- | ------- | ------- | ------------------------------ |
| `--help`          | Show help                                                  | boolean |         |                                |
| `--version`       | Show version number                                        | boolean |         |                                |
| `--debug`         | Enable verbose logging                                     | boolean | `false` | `OPIK_CONFIGURE_DEBUG`         |
| `--default`       | Use default options for all prompts                        | boolean | `true`  | `OPIK_CONFIGURE_DEFAULT`       |
| `--force-install` | Force install packages even if peer dependency checks fail | boolean | `false` | `OPIK_CONFIGURE_FORCE_INSTALL` |
| `--install-dir`   | Directory to install Opik SDK in                           | string  |         | `OPIK_CONFIGURE_INSTALL_DIR`   |

# Development

To develop the CLI locally:

```bash
pnpm try --install-dir=[a path]
```

To build and use the tool globally:

```bash
pnpm build
pnpm link --global
opik-configure [options]
```

## Contributing

This CLI is part of the Opik project. For contributing guidelines, please see
the main Opik repository.
