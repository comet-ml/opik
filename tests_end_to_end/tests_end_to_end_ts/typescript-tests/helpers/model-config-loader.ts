import * as fs from 'fs';
import * as path from 'path';
import * as yaml from 'js-yaml';

export interface ModelConfig {
  name: string;
  ui_selector: string;
  enabled: boolean;
  test_playground: boolean;
  test_online_scoring: boolean;
}

export interface ProviderConfig {
  display_name: string;
  api_key_env_var: string;
  models: ModelConfig[];
  additional_env_vars?: string[];
}

export interface TestConfig {
  skip_missing_api_keys: boolean;
  response_timeout: number;
  test_prompt: string;
  only_test_enabled: boolean;
}

export interface ModelsConfig {
  providers: Record<string, ProviderConfig>;
  test_config: TestConfig;
}

export class ModelConfigLoader {
  private config: ModelsConfig;
  private configPath: string;

  constructor(configPath?: string) {
    if (!configPath) {
      this.configPath = path.join(__dirname, '..', 'models_config.yaml');
    } else {
      this.configPath = configPath;
    }

    this.config = this.loadConfig();
  }

  private loadConfig(): ModelsConfig {
    try {
      const fileContents = fs.readFileSync(this.configPath, 'utf8');
      return yaml.load(fileContents) as ModelsConfig;
    } catch (error) {
      throw new Error(`Failed to load configuration file: ${this.configPath}. Error: ${error}`);
    }
  }

  getProviders(): Record<string, ProviderConfig> {
    return this.config.providers;
  }

  getEnabledModelsForPlayground(): Array<{
    providerName: string;
    modelConfig: ModelConfig;
    providerConfig: ProviderConfig;
  }> {
    const models: Array<{
      providerName: string;
      modelConfig: ModelConfig;
      providerConfig: ProviderConfig;
    }> = [];

    const providers = this.getProviders();

    for (const [providerName, providerConfig] of Object.entries(providers)) {
      if (!this.providerHasApiKeys(providerConfig)) {
        if (this.config.test_config.skip_missing_api_keys) {
          console.log(`Skipping ${providerName} - missing API keys`);
          continue;
        }
      }

      for (const model of providerConfig.models) {
        if (model.enabled && model.test_playground && this.config.test_config.only_test_enabled) {
          models.push({
            providerName,
            modelConfig: model,
            providerConfig,
          });
        }
      }
    }

    return models;
  }

  getEnabledModelsForOnlineScoring(): Array<{
    providerName: string;
    modelConfig: ModelConfig;
    providerConfig: ProviderConfig;
  }> {
    const models: Array<{
      providerName: string;
      modelConfig: ModelConfig;
      providerConfig: ProviderConfig;
    }> = [];

    const providers = this.getProviders();

    for (const [providerName, providerConfig] of Object.entries(providers)) {
      if (!this.providerHasApiKeys(providerConfig)) {
        if (this.config.test_config.skip_missing_api_keys) {
          console.log(`Skipping ${providerName} - missing API keys`);
          continue;
        }
      }

      for (const model of providerConfig.models) {
        if (model.enabled && model.test_online_scoring && this.config.test_config.only_test_enabled) {
          models.push({
            providerName,
            modelConfig: model,
            providerConfig,
          });
        }
      }
    }

    return models;
  }

  private providerHasApiKeys(providerConfig: ProviderConfig): boolean {
    const apiKey = process.env[providerConfig.api_key_env_var];
    if (!apiKey) {
      return false;
    }

    if (providerConfig.additional_env_vars) {
      for (const envVar of providerConfig.additional_env_vars) {
        if (!process.env[envVar]) {
          return false;
        }
      }
    }

    return true;
  }

  getTestConfig(): TestConfig {
    return this.config.test_config;
  }

  getTestPrompt(): string {
    return this.config.test_config.test_prompt || 'Explain what is an LLM in one paragraph.';
  }

  getResponseTimeout(): number {
    return this.config.test_config.response_timeout || 30;
  }
}

export const modelConfigLoader = new ModelConfigLoader();
