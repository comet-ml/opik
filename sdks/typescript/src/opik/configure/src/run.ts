import { abortIfCancelled } from './utils/clack-utils';

import { runNodejsWizard } from './nodejs/node-wizard';
import type { WizardOptions } from './utils/types';

import { getIntegrationDescription, Integration } from './lib/constants';
import { readEnvironment } from './utils/environment';
import clack from './utils/clack';
import path from 'path';
import { INTEGRATION_CONFIG, INTEGRATION_ORDER } from './lib/config';
import { EventEmitter } from 'events';
import chalk from 'chalk';
import { RateLimitError } from './utils/errors';
import { analytics } from './utils/analytics';

EventEmitter.defaultMaxListeners = 50;

type Args = {
  integration?: Integration;
  debug?: boolean;
  forceInstall?: boolean;
  installDir?: string;
  default?: boolean;
  useLocal?: boolean;
};

export async function runWizard(argv: Args) {
  // Collect system information for analytics
  analytics.setTag('os', process.platform);
  analytics.setTag('nodeVersion', process.version);
  analytics.setTag('arch', process.arch);

  analytics.capture('wizard started');

  const finalArgs = {
    ...argv,
    ...readEnvironment(),
  };

  let resolvedInstallDir: string;
  if (finalArgs.installDir) {
    if (path.isAbsolute(finalArgs.installDir)) {
      resolvedInstallDir = finalArgs.installDir;
    } else {
      resolvedInstallDir = path.join(process.cwd(), finalArgs.installDir);
    }
  } else {
    resolvedInstallDir = process.cwd();
  }

  const wizardOptions: WizardOptions = {
    debug: finalArgs.debug ?? false,
    forceInstall: finalArgs.forceInstall ?? false,
    installDir: resolvedInstallDir,
    default: finalArgs.default ?? false,
    useLocal: finalArgs.useLocal ?? false,
  };

  analytics.setTag('debug', wizardOptions.debug);
  analytics.setTag('forceInstall', wizardOptions.forceInstall);
  analytics.setTag('default', wizardOptions.default);

  clack.intro(`Welcome to the Opik configure tool ✨`);

  const integration = finalArgs.integration ?? (await getIntegrationForSetup());

  analytics.setTag('integration', integration);
  analytics.capture('integration selected', { integration });

  try {
    switch (integration) {
      case Integration.nodejs:
        await runNodejsWizard(wizardOptions);
        break;
      default:
        clack.log.error('No setup CLI selected!');
        analytics.capture('wizard error', {
          error: 'No setup CLI selected',
        });
        await analytics.shutdown('error');
        process.exit(1);
    }

    await analytics.shutdown('success');
  } catch (error) {
    const errorMessage =
      error instanceof Error ? error.message : 'Unknown error';
    analytics.capture('wizard error', {
      error: errorMessage,
      errorType: error instanceof RateLimitError ? 'rate_limit' : 'unknown',
    });

    if (error instanceof RateLimitError) {
      clack.log.error(
        'Opik configure CLI usage limit reached. Please try again later.',
      );
    } else {
      if (error instanceof Error) {
        analytics.captureException(error, {
          integration,
          arguments: JSON.stringify(finalArgs),
        });
      }
      clack.log.error(
        `Something went wrong. You can read the documentation at ${chalk.cyan(
          `${INTEGRATION_CONFIG[integration].docsUrl}`,
        )} to set up Opik manually.`,
      );
    }

    await analytics.shutdown('error');
    process.exit(1);
  }
}
async function detectIntegration(): Promise<Integration | undefined> {
  const integrationConfigs = Object.entries(INTEGRATION_CONFIG).sort(
    ([a], [b]) =>
      INTEGRATION_ORDER.indexOf(a as Integration) -
      INTEGRATION_ORDER.indexOf(b as Integration),
  );

  for (const [integration, config] of integrationConfigs) {
    const detected = await config.detect();
    if (detected) {
      return integration as Integration;
    }
  }
}

async function getIntegrationForSetup() {
  const detectedIntegration = await detectIntegration();

  if (detectedIntegration) {
    clack.log.success(
      `Detected integration: ${getIntegrationDescription(detectedIntegration)}`,
    );
    return detectedIntegration;
  }

  const integration: Integration = await abortIfCancelled(
    clack.select({
      message: 'What do you want to set up?',
      options: [{ value: Integration.nodejs, label: 'Node.js' }],
    }),
  );

  return integration;
}
