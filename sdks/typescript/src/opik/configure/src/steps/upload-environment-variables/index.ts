import type { Integration } from '../../lib/constants';
import clack from '../../utils/clack';
import { abortIfCancelled } from '../../utils/clack-utils';
import { EnvironmentProvider } from './EnvironmentProvider';

export const uploadEnvironmentVariablesStep = async (
  envVars: Record<string, string>,
  {
    integration,
  }: {
    integration: Integration;
  },
): Promise<string[]> => {
  const providers: EnvironmentProvider[] = [];

  let provider: EnvironmentProvider | null = null;

  for (const p of providers) {
    if (await p.detect()) {
      provider = p;
      break;
    }
  }

  if (!provider) {
    return [];
  }

  const upload: boolean = await abortIfCancelled(
    clack.select({
      message: `It looks like you are using ${provider.name}. Would you like to upload the environment variables?`,
      options: [
        {
          value: true,
          label: 'Yes',
          hint: `Upload the environment variables to ${provider.name}`,
        },
        {
          value: false,
          label: 'No',
          hint: `Skip uploading environment variables to ${provider.name} - you can do this later`,
        },
      ],
    }),
    integration,
  );

  if (!upload) {
    return [];
  }

  const results = await provider.uploadEnvVars(envVars);

  return Object.keys(results).filter((key) => results[key]);
};
