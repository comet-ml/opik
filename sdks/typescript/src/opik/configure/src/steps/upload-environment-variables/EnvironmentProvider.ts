import type { WizardOptions } from '../../utils/types';

export abstract class EnvironmentProvider {
  protected options: WizardOptions;

  abstract name: string;

  constructor(options: WizardOptions) {
    this.options = options;
  }

  abstract detect(): Promise<boolean>;

  abstract uploadEnvVars(
    vars: Record<string, string>,
  ): Promise<Record<string, boolean>>;
}
