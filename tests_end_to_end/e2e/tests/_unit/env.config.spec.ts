import { test, expect } from '@playwright/test';
import { loadEnvConfig } from '../../config/env.config';

test.describe('env.config', () => {
  test('oss deployment has working defaults', () => {
    const env = loadEnvConfig({
      OPIK_DEPLOYMENT: 'oss',
    });
    expect(env.deployment).toBe('oss');
    expect(env.baseUrl).toBe('http://localhost:5173');
    expect(env.apiBaseUrl).toBe('http://localhost:5173/api');
    expect(env.workspace).toBe('default');
    expect(env.features.ollie).toBe(false);
    expect(env.features.opikConnect).toBe(false);
  });

  test('OPIK_BASE_URL with trailing /api is normalized so apiBaseUrl is not duplicated', () => {
    const env = loadEnvConfig({
      OPIK_DEPLOYMENT: 'cloud',
      OPIK_BASE_URL: 'https://staging.dev.comet.com/opik/api',
      OPIK_TEST_USER_EMAIL: 'test@example.com',
      OPIK_TEST_USER_PASSWORD: 'secret',
      OPIK_TEST_USER_NAME: 'testuser',
    });
    expect(env.baseUrl).toBe('https://staging.dev.comet.com/opik');
    expect(env.apiBaseUrl).toBe('https://staging.dev.comet.com/opik/api');
  });

  test('OPIK_BASE_URL trailing slash is stripped', () => {
    const env = loadEnvConfig({
      OPIK_DEPLOYMENT: 'cloud',
      OPIK_BASE_URL: 'https://staging.dev.comet.com/opik/',
      OPIK_TEST_USER_EMAIL: 'test@example.com',
      OPIK_TEST_USER_PASSWORD: 'secret',
      OPIK_TEST_USER_NAME: 'testuser',
    });
    expect(env.baseUrl).toBe('https://staging.dev.comet.com/opik');
    expect(env.apiBaseUrl).toBe('https://staging.dev.comet.com/opik/api');
  });

  test('cloud deployment requires email+password', () => {
    expect(() => loadEnvConfig({ OPIK_DEPLOYMENT: 'cloud' })).toThrow(/email.*password/i);
  });

  test('cloud deployment with credentials loads with ollie enabled', () => {
    const env = loadEnvConfig({
      OPIK_DEPLOYMENT: 'cloud',
      OPIK_BASE_URL: 'https://staging.cometml.com',
      OPIK_TEST_USER_EMAIL: 'test@example.com',
      OPIK_TEST_USER_PASSWORD: 'secret',
      OPIK_TEST_USER_NAME: 'testuser',
    });
    expect(env.deployment).toBe('cloud');
    expect(env.features.ollie).toBe(true);
    expect(env.features.opikConnect).toBe(true);
    expect(env.workspace).toBe('testuser');
  });

  test('feature flag overrides work', () => {
    const env = loadEnvConfig({
      OPIK_DEPLOYMENT: 'oss',
      OLLIE_ENABLED: 'true',
    });
    expect(env.features.ollie).toBe(true);
  });

  test('OLLIE_ENABLED=false overrides cloud default', () => {
    const env = loadEnvConfig({
      OPIK_DEPLOYMENT: 'cloud',
      OPIK_BASE_URL: 'https://staging.cometml.com',
      OPIK_TEST_USER_EMAIL: 'test@example.com',
      OPIK_TEST_USER_PASSWORD: 'secret',
      OPIK_TEST_USER_NAME: 'testuser',
      OLLIE_ENABLED: 'false',
    });
    expect(env.features.ollie).toBe(false);
  });

  test('runId is stamped and is a valid timestamp string', () => {
    const env = loadEnvConfig({ OPIK_DEPLOYMENT: 'oss' });
    expect(env.runId).toMatch(/^\d{8}-\d{6}-\d{3}$/);
    expect(env.cujPrefix).toBe(`cuj-${env.runId}`);
  });

  test('llmJudges derives from ANTHROPIC_API_KEY + SKIP_LLM_JUDGES', () => {
    const withKey = loadEnvConfig({ OPIK_DEPLOYMENT: 'oss', ANTHROPIC_API_KEY: 'sk-ant-...' });
    expect(withKey.features.llmJudges).toBe(true);

    const withSkip = loadEnvConfig({
      OPIK_DEPLOYMENT: 'oss',
      ANTHROPIC_API_KEY: 'sk-ant-...',
      SKIP_LLM_JUDGES: 'true',
    });
    expect(withSkip.features.llmJudges).toBe(false);
  });

  test('self-hosted requires OPIK_BASE_URL', () => {
    expect(() => loadEnvConfig({ OPIK_DEPLOYMENT: 'self-hosted' })).toThrow(/OPIK_BASE_URL/);
  });

  test('invalid deployment throws', () => {
    expect(() => loadEnvConfig({ OPIK_DEPLOYMENT: 'bogus' as unknown as string })).toThrow(/Invalid OPIK_DEPLOYMENT/);
  });
});
