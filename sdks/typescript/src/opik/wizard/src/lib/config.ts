// import { getPackageDotJson } from '../utils/clack-utils';
// import { hasPackageInstalled } from '../utils/package-json';
import type { WizardOptions } from '../utils/types';
import { Integration } from './constants';

type IntegrationConfig = {
  name: string;
  filterPatterns: string[];
  ignorePatterns: string[];
  detect: (options: Pick<WizardOptions, 'installDir'>) => Promise<boolean>;
  generateFilesRules: string;
  filterFilesRules: string;
  docsUrl: string;
  nextSteps: string;
  defaultChanges: string;
};

export const INTEGRATION_CONFIG = {
  [Integration.nodejs]: {
    name: 'Node.js',
    filterPatterns: ['**/*.{tsx,ts,js,mjs,cjs}'],
    ignorePatterns: [
      'node_modules',
      'dist',
      'build',
      'public',
      'static',
      'node-env.d.*',
    ],
    detect: async (options) => {
      // const packageJson = await getPackageDotJson(options);
      // return hasPackageInstalled('nodejs', packageJson);

      return true;
    },
    generateFilesRules: `
- Use \`new Opik({ apiKey, environment })\` for client initialization, NOT a provider pattern
- Import from 'opik', not 'opik-js/react' or similar
- Use \`@track()\` decorator for automatic tracing
- Use \`client.trace()\` for manual trace creation
- Always include \`await client.flush()\` for short-lived scripts
- For integrations, use \`trackOpenAI()\`, \`OpikTracer\`, etc.
- Configuration uses \`apiKey\` and \`environment\`, not \`api_host\` or \`defaults\`
- Opik uses non-blocking batched writes by default`,
    filterFilesRules: `
- Look for main entry points (index.ts, main.ts, app.ts, server.ts)
- Look for files with LLM calls (OpenAI, Anthropic, etc.)
- Look for API route handlers
- Look for service/utility files with AI logic
- Avoid test files, config files, and type definition files
- If there's a setup or initialization file, include it`,
    docsUrl:
      'https://www.comet.com/docs/opik/reference/typescript-sdk/overview',
    defaultChanges:
      '• Installed opik package\n• Initialized Opik client\n• Set up environment variables (OPIK_API_KEY, OPIK_URL_OVERRIDE)',
    nextSteps:
      '• Use @track() decorator to automatically trace functions\n• Use client.trace() for manual trace creation\n• For short-lived scripts: Add await client.flush() before exit\n• Integrate with LLM libraries using trackOpenAI() or OpikTracer\n• Check the documentation for advanced usage: https://www.comet.com/docs/opik/reference/typescript-sdk/overview',
  },
} as const satisfies Record<Integration, IntegrationConfig>;

export const INTEGRATION_ORDER = [Integration.nodejs] as const;
