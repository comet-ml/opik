#!/usr/bin/env node
import { satisfies } from 'semver';
import { red } from './src/utils/logging';

import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';
import chalk from 'chalk';

const NODE_VERSION_RANGE = '>=18.17.0';

// Have to run this above the other imports because they are importing clack that
// has the problematic imports.
if (!satisfies(process.version, NODE_VERSION_RANGE)) {
  red(
    `Opik CLI requires Node.js ${NODE_VERSION_RANGE}. You are using Node.js ${process.version}. Please upgrade your Node.js version.`,
  );
  process.exit(1);
}

import type { WizardOptions } from './src/utils/types';
import { runWizard } from './src/run';
import { isNonInteractiveEnvironment } from './src/utils/environment';
import clack from './src/utils/clack';

if (isNonInteractiveEnvironment()) {
  clack.intro(chalk.inverse(`Opik Configure`));

  clack.log.error(
    'This installer requires an interactive terminal (TTY) to run.\n' +
      'It appears you are running in a non-interactive environment.\n' +
      'Please run the CLI in an interactive terminal.',
  );
  process.exit(1);
}

yargs(hideBin(process.argv))
  .env('OPIK_CONFIGURE')
  // global options
  .options({
    debug: {
      default: false,
      describe: 'Enable verbose logging\nenv: OPIK_CONFIGURE_DEBUG',
      type: 'boolean',
    },
    default: {
      default: true,
      describe:
        'Use default options for all prompts\nenv: OPIK_CONFIGURE_DEFAULT',
      type: 'boolean',
    },
  })
  .command(
    ['$0'],
    'Run the Opik SDK setup CLI',
    (yargs) => {
      return yargs.options({
        'force-install': {
          default: false,
          describe:
            'Force install packages even if peer dependency checks fail\nenv: OPIK_CONFIGURE_FORCE_INSTALL',
          type: 'boolean',
        },
        'install-dir': {
          describe:
            'Directory to install Opik SDK in\nenv: OPIK_CONFIGURE_INSTALL_DIR',
          type: 'string',
        },
      });
    },
    (argv) => {
      const options = { ...argv };
      void runWizard(options as unknown as WizardOptions);
    },
  )
  .help()
  .alias('help', 'h')
  .version()
  .alias('version', 'v')
  .wrap(process.stdout.isTTY ? yargs.terminalWidth() : 80)
  .parse();
