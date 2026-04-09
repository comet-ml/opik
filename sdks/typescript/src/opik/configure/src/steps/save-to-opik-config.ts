import chalk from 'chalk';
import * as fs from 'fs';
import ini from 'ini';
import * as os from 'os';
import * as path from 'path';
import clack from '../utils/clack';

const OPIK_CONFIG_FILE_DEFAULT = path.join(os.homedir(), '.opik.config');

function expandPath(filePath: string): string {
  return filePath.replace(/^~(?=$|\/|\\)/, os.homedir());
}

function resolveConfigFilePath(): string {
  if (!process.env.OPIK_CONFIG_PATH) {
    return OPIK_CONFIG_FILE_DEFAULT;
  }

  const customPath = expandPath(process.env.OPIK_CONFIG_PATH);
  const parentDir = path.dirname(customPath);

  if (!fs.existsSync(parentDir)) {
    try {
      fs.mkdirSync(parentDir, { recursive: true });
    } catch (error) {
      clack.log.warning(
        `OPIK_CONFIG_PATH parent directory ${chalk.bold.cyan(parentDir)} could not be created: ${error instanceof Error ? error.message : String(error)}. Falling back to ${chalk.bold.cyan(OPIK_CONFIG_FILE_DEFAULT)}.`,
      );
      return OPIK_CONFIG_FILE_DEFAULT;
    }
  }

  return customPath;
}

export interface SaveToOpikConfigOptions {
  projectName: string;
  urlOverride: string;
  apiKey?: string;
  workspace?: string;
}

/**
 * Write Opik configuration values to ~/.opik.config (INI format).
 * Merges with any existing content so unrelated sections are preserved.
 */
export async function saveToOpikConfigStep(
  options: SaveToOpikConfigOptions,
): Promise<void> {
  const { projectName, urlOverride, apiKey, workspace } = options;
  const configFilePath = resolveConfigFilePath();

  try {
    let parsed: Record<string, unknown> = {};
    if (fs.existsSync(configFilePath)) {
      parsed = ini.parse(fs.readFileSync(configFilePath, 'utf8'));
    }

    const existing = (parsed['opik'] as Record<string, string> | undefined) ?? {};

    parsed['opik'] = {
      ...existing,
      url_override: urlOverride,
      project_name: projectName,
      ...(apiKey ? { api_key: apiKey } : {}),
      ...(workspace ? { workspace } : {}),
    };

    await fs.promises.writeFile(configFilePath, ini.stringify(parsed), {
      encoding: 'utf8',
      flag: 'w',
    });

    clack.log.success(
      `Saved Opik configuration to ${chalk.bold.cyan(configFilePath)}`,
    );
  } catch (error) {
    clack.log.warning(
      `Failed to save configuration to ${chalk.bold.cyan(configFilePath)}: ${error instanceof Error ? error.message : String(error)}`,
    );
  }
}