import readEnv from 'read-env';
import { existsSync, readFileSync } from 'fs';
import { join } from 'path';
import { IS_DEV } from '../lib/constants';

export function isNonInteractiveEnvironment(): boolean {
  if (IS_DEV) {
    return false;
  }

  if (!process.stdout.isTTY || !process.stderr.isTTY) {
    return true;
  }

  return false;
}

export function readEnvironment(): Record<string, unknown> {
  const result = readEnv('OPIK_CONFIGURE');

  return result;
}

export interface EnvFileCache {
  envFilePath: string | undefined;
  envContent: string;
}

export interface EnvVarSource {
  value: string;
  source: 'process.env' | '.env.local' | '.env' | 'not-found';
}

/**
 * Reads .env files from the current working directory.
 * Prioritizes .env.local over .env if both exist.
 * This should be called once and the result cached to avoid repeated file reads.
 */
export function readEnvFiles(): EnvFileCache {
  const cwd = process.cwd();
  const dotEnvLocalFilePath = join(cwd, '.env.local');
  const dotEnvFilePath = join(cwd, '.env');

  if (existsSync(dotEnvLocalFilePath)) {
    return {
      envFilePath: dotEnvLocalFilePath,
      envContent: readFileSync(dotEnvLocalFilePath, 'utf8'),
    };
  }

  if (existsSync(dotEnvFilePath)) {
    return {
      envFilePath: dotEnvFilePath,
      envContent: readFileSync(dotEnvFilePath, 'utf8'),
    };
  }

  return {
    envFilePath: undefined,
    envContent: '',
  };
}

/**
 * Gets an environment variable value and its source.
 * Checks in order: process.env, then cached .env file content.
 * @param varName - The environment variable name to look up
 * @param envFileCache - Cached env file content from readEnvFiles() to avoid repeated file reads
 */
export function getEnvVarWithSource(
  varName: string,
  envFileCache: EnvFileCache,
): EnvVarSource {
  // Check process.env first (highest priority)
  if (process.env[varName]) {
    return {
      value: process.env[varName]!,
      source: 'process.env',
    };
  }

  // Check .env files from cache
  if (envFileCache.envContent) {
    const regex = new RegExp(`^${varName}=(.*)$`, 'm');
    const match = envFileCache.envContent.match(regex);

    if (match && match[1]) {
      const source = envFileCache.envFilePath?.endsWith('.env.local')
        ? '.env.local'
        : '.env';
      return {
        value: match[1],
        source: source as '.env.local' | '.env',
      };
    }
  }

  return {
    value: '',
    source: 'not-found',
  };
}
