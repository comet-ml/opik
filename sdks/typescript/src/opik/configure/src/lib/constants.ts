export enum Integration {
  nodejs = 'nodejs',
}

export function getIntegrationDescription(type: string): string {
  switch (type) {
    case Integration.nodejs:
      return 'Node.js';
    default:
      throw new Error(`Unknown integration ${type}`);
  }
}

type IntegrationChoice = {
  name: string;
  value: string;
};

export function getIntegrationChoices(): IntegrationChoice[] {
  return Object.keys(Integration).map((type: string) => ({
    name: getIntegrationDescription(type),
    value: type,
  }));
}

export interface Args {
  debug: boolean;
  integration: Integration;
}

export const IS_DEV = ['test', 'development'].includes(
  process.env.NODE_ENV ?? '',
);

export const DEBUG = false;

export const DEFAULT_URL = IS_DEV
  ? 'http://localhost:5173'
  : 'https://www.comet.com/opik';
export const ISSUES_URL = 'https://github.com/comet-ml/opik/issues';
export const DEFAULT_HOST_URL = IS_DEV
  ? 'http://localhost:5173'
  : 'https://www.comet.com';
export const DUMMY_PROJECT_API_KEY = '_YOUR_OPIK_API_KEY_';
export const ANALYTICS_POSTHOG_PUBLIC_PROJECT_WRITE_KEY =
  'phc_NAfd7RuhI3CPTaYZ7wnjStOgyvlseao9JHp5fWkTkzM';
export const ANALYTICS_HOST_URL = 'https://us.i.posthog.com';
