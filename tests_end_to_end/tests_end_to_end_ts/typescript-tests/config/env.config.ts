/**
 * Environment Configuration
 * Uses same environment variables as Python tests for seamless migration
 */

export interface EnvConfig {
  baseUrl: string;
  workspace: string;
  projectName: string;
  apiKey?: string;
  testUserEmail?: string;
  testUserName?: string;
  testUserPassword?: string;
}

export class EnvConfigManager {
  private config: EnvConfig;

  constructor() {
    this.config = this.loadConfig();
    this.validate();
  }

  private loadConfig(): EnvConfig {
    const baseUrl = process.env.OPIK_BASE_URL || 'http://localhost:5173';

    return {
      baseUrl,
      workspace: process.env.OPIK_TEST_USER_NAME || 'default',
      projectName: process.env.OPIK_TEST_PROJECT_NAME || 'automated_tests_project',
      apiKey: process.env.OPIK_API_KEY,
      testUserEmail: process.env.OPIK_TEST_USER_EMAIL,
      testUserName: process.env.OPIK_TEST_USER_NAME,
      testUserPassword: process.env.OPIK_TEST_USER_PASSWORD,
    };
  }

  private validate(): void {
    const isLocal = this.config.baseUrl.startsWith('http://localhost');

    if (!isLocal) {
      const missingVars: string[] = [];

      if (!this.config.testUserEmail) {
        missingVars.push('OPIK_TEST_USER_EMAIL');
      }
      if (!this.config.testUserName) {
        missingVars.push('OPIK_TEST_USER_NAME');
      }
      if (!this.config.testUserPassword) {
        missingVars.push('OPIK_TEST_USER_PASSWORD');
      }

      if (missingVars.length > 0) {
        throw new Error(
          `Missing required environment variables for non-local environment: ${missingVars.join(', ')}`
        );
      }
    }
  }

  getConfig(): EnvConfig {
    return { ...this.config };
  }

  getApiUrl(): string {
    return `${this.config.baseUrl}/api`;
  }

  getWebUrl(): string {
    return this.config.baseUrl;
  }

  isLocal(): boolean {
    return this.config.baseUrl.startsWith('http://localhost');
  }

  getTestHelperUrl(): string {
    return process.env.TEST_HELPER_URL || 'http://localhost:5555';
  }
}

let configInstance: EnvConfigManager | null = null;

export function getEnvironmentConfig(): EnvConfigManager {
  if (!configInstance) {
    configInstance = new EnvConfigManager();
  }
  return configInstance;
}
