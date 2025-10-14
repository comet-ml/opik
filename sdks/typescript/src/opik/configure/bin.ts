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
import { runDoctor } from './src/doctor';
import clack from './src/utils/clack';

yargs(hideBin(process.argv))
  .scriptName('opik-ts')
  .env('OPIK_TS')
  // global options
  .options({
    debug: {
      default: false,
      describe: 'Enable verbose logging\nenv: OPIK_TS_DEBUG',
      type: 'boolean',
    },
    default: {
      default: true,
      describe: 'Use default options for all prompts\nenv: OPIK_TS_DEFAULT',
      type: 'boolean',
    },
  })
  .command(
    'configure',
    'Run the Opik SDK setup configure',
    (yargs) => {
      return yargs.options({
        'force-install': {
          default: false,
          describe:
            'Force install packages even if peer dependency checks fail\nenv: OPIK_TS_FORCE_INSTALL',
          type: 'boolean',
        },
        'install-dir': {
          describe:
            'Directory to install Opik SDK in\nenv: OPIK_TS_INSTALL_DIR',
          type: 'string',
        },
        'use-local': {
          default: false,
          describe:
            'Configure for local deployment (skips API key/workspace setup)\nenv: OPIK_TS_USE_LOCAL',
          type: 'boolean',
        },
      });
    },
    (argv) => {
      // Check for interactive terminal for configure command
      if (isNonInteractiveEnvironment()) {
        clack.intro(chalk.inverse(`Opik TS`));

        clack.log.error(
          'This installer requires an interactive terminal (TTY) to run.\n' +
            'It appears you are running in a non-interactive environment.\n' +
            'Please run the CLI in an interactive terminal.',
        );
        process.exit(1);
      }

      const options = { ...argv };
      void runWizard(options as unknown as WizardOptions);
    },
  )
  .command(
    'doctor',
    'Run health checks for Opik SDK installation',
    () => {},
    () => {
      void runDoctor();
    },
  )
  .demandCommand(1, 'Error: command required')
  .showHelpOnFail(true)
  .help()
  .alias('help', 'h')
  .version()
  .alias('version', 'v')
  .wrap(process.stdout.isTTY ? yargs.terminalWidth() : 80)
  .parse();
