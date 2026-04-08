import chalk from 'chalk';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import clack from '../utils/clack';

const OPIK_CONFIG_FILE = path.join(os.homedir(), '.opik.config');

/** Keys we read/write in the [opik] section */
interface OpikIniSection {
  api_key?: string;
  url_override?: string;
  workspace?: string;
  project_name?: string;
}

/** Parse the [opik] section from a raw INI string (no external deps). */
export function parseOpikSection(content: string): OpikIniSection {
  const result: OpikIniSection = {};
  let inOpikSection = false;

  for (const raw of content.split('\n')) {
    const line = raw.trim();

    if (line === '[opik]') {
      inOpikSection = true;
      continue;
    }

    if (line.startsWith('[')) {
      inOpikSection = false;
      continue;
    }

    if (!inOpikSection || !line || line.startsWith('#') || line.startsWith(';')) {
      continue;
    }

    const eqIdx = line.indexOf('=');
    if (eqIdx === -1) continue;

    const key = line.slice(0, eqIdx).trim() as keyof OpikIniSection;
    (result as Record<string, string>)[key] = line.slice(eqIdx + 1).trim().replace(/^["']|["']$/g, '');
  }

  return result;
}

/** Serialise an OpikIniSection back to an INI string with an [opik] section. */
export function serializeOpikSection(
  section: OpikIniSection,
  existingContent: string,
): string {
  const opikLines = ['[opik]'];
  for (const [key, value] of Object.entries(section)) {
    if (value !== undefined && value !== '') {
      opikLines.push(`${key} = ${value}`);
    }
  }
  const opikBlock = opikLines.join('\n');

  // Replace existing [opik] block if present, otherwise append
  const opikSectionRegex = /\[opik][^[]*/s;
  if (opikSectionRegex.test(existingContent)) {
    return existingContent.replace(opikSectionRegex, opikBlock + '\n');
  }

  const trimmed = existingContent.trimEnd();
  return trimmed ? `${trimmed}\n\n${opikBlock}\n` : `${opikBlock}\n`;
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

  try {
    let existingContent = '';
    if (fs.existsSync(OPIK_CONFIG_FILE)) {
      existingContent = fs.readFileSync(OPIK_CONFIG_FILE, 'utf8');
    }

    const existing = parseOpikSection(existingContent);

    const merged: OpikIniSection = {
      ...existing,
      url_override: urlOverride,
      project_name: projectName,
      ...(apiKey ? { api_key: apiKey } : {}),
      ...(workspace ? { workspace } : {}),
    };

    const newContent = serializeOpikSection(merged, existingContent);
    await fs.promises.writeFile(OPIK_CONFIG_FILE, newContent, {
      encoding: 'utf8',
      flag: 'w',
    });

    clack.log.success(
      `Saved Opik configuration to ${chalk.bold.cyan('~/.opik.config')}`,
    );
  } catch (error) {
    clack.log.warning(
      `Failed to save configuration to ${chalk.bold.cyan('~/.opik.config')}: ${error instanceof Error ? error.message : String(error)}`,
    );
  }
}