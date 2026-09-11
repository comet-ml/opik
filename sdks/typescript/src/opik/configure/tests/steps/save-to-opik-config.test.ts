import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import * as fs from 'fs';
import ini from 'ini';
import { saveToOpikConfigStep } from '../../src/steps';

// ESM requires module-level mocking for native node modules.
vi.mock('fs', async () => {
  const actual = await vi.importActual<typeof import('fs')>('fs');
  return {
    ...actual,
    existsSync: vi.fn(),
    readFileSync: vi.fn(),
    mkdirSync: vi.fn(),
    promises: {
      ...actual.promises,
      writeFile: vi.fn(),
    },
  };
});

// Silence clack output during tests.
vi.mock('../../src/utils/clack', () => ({
  default: { log: { success: vi.fn(), warning: vi.fn() } },
}));

describe('saveToOpikConfigStep', () => {
  let originalConfigPath: string | undefined;

  beforeEach(() => {
    originalConfigPath = process.env.OPIK_CONFIG_PATH;
    delete process.env.OPIK_CONFIG_PATH;
    vi.mocked(fs.existsSync).mockReturnValue(false);
    vi.mocked(fs.readFileSync).mockReturnValue('');
    vi.mocked(fs.promises.writeFile).mockResolvedValue(undefined);
  });

  afterEach(() => {
    if (originalConfigPath !== undefined) {
      process.env.OPIK_CONFIG_PATH = originalConfigPath;
    } else {
      delete process.env.OPIK_CONFIG_PATH;
    }
    vi.clearAllMocks();
  });

  function getWrittenParsed(): Record<string, Record<string, string>> {
    const raw = vi.mocked(fs.promises.writeFile).mock.calls[0][1] as string;
    return ini.parse(raw) as Record<string, Record<string, string>>;
  }

  it('creates the config file when it does not exist', async () => {
    await saveToOpikConfigStep({
      projectName: 'my-project',
      urlOverride: 'http://localhost/api',
    });

    expect(fs.promises.writeFile).toHaveBeenCalledOnce();
    const written = getWrittenParsed();
    expect(written.opik.project_name).toBe('my-project');
    expect(written.opik.url_override).toBe('http://localhost/api');
    expect(written.opik.api_key).toBeUndefined();
    expect(written.opik.workspace).toBeUndefined();
  });

  it('includes api_key and workspace for cloud deployments', async () => {
    await saveToOpikConfigStep({
      projectName: 'cloud-proj',
      urlOverride: 'https://www.comet.com/opik/api',
      apiKey: 'secret-key',
      workspace: 'my-workspace',
    });

    expect(fs.promises.writeFile).toHaveBeenCalledOnce();
    const written = getWrittenParsed();
    expect(written.opik.api_key).toBe('secret-key');
    expect(written.opik.workspace).toBe('my-workspace');
  });

  it('merges with an existing config file', async () => {
    const existingContent = ini.stringify({
      opik: { api_key: 'old-key', project_name: 'old-project' },
    });
    vi.mocked(fs.existsSync).mockReturnValue(true);
    vi.mocked(fs.readFileSync).mockReturnValue(existingContent);

    await saveToOpikConfigStep({
      projectName: 'new-project',
      urlOverride: 'https://www.comet.com/opik/api',
      apiKey: 'new-key',
    });

    expect(fs.promises.writeFile).toHaveBeenCalledOnce();
    const written = getWrittenParsed();
    expect(written.opik.project_name).toBe('new-project');
    expect(written.opik.api_key).toBe('new-key');
  });

  it('preserves other sections from an existing config file', async () => {
    const existingContent = ini.stringify({
      other: { foo: 'bar' },
      opik: { project_name: 'old-project' },
    });
    vi.mocked(fs.existsSync).mockReturnValue(true);
    vi.mocked(fs.readFileSync).mockReturnValue(existingContent);

    await saveToOpikConfigStep({
      projectName: 'new-project',
      urlOverride: 'http://localhost/api',
    });

    const written = getWrittenParsed();
    expect(written.other.foo).toBe('bar');
    expect(written.opik.project_name).toBe('new-project');
  });

  it('writes to OPIK_CONFIG_PATH when the env var points to a path with an existing parent directory', async () => {
    const customPath = '/custom/path/.opik.config';
    process.env.OPIK_CONFIG_PATH = customPath;
    vi.mocked(fs.existsSync).mockReturnValue(true); // parent dir exists

    await saveToOpikConfigStep({
      projectName: 'proj',
      urlOverride: 'http://localhost/api',
    });

    expect(fs.promises.writeFile).toHaveBeenCalledOnce();
    expect(vi.mocked(fs.promises.writeFile).mock.calls[0][0]).toBe(customPath);
  });

  it('creates parent directory and writes to OPIK_CONFIG_PATH when parent dir is missing', async () => {
    const customPath = '/nonexistent/dir/.opik.config';
    process.env.OPIK_CONFIG_PATH = customPath;
    vi.mocked(fs.existsSync).mockReturnValue(false); // parent dir missing
    vi.mocked(fs.mkdirSync).mockReturnValue(undefined);

    await saveToOpikConfigStep({
      projectName: 'proj',
      urlOverride: 'http://localhost/api',
    });

    expect(fs.mkdirSync).toHaveBeenCalledWith('/nonexistent/dir', { recursive: true });
    expect(fs.promises.writeFile).toHaveBeenCalledOnce();
    expect(vi.mocked(fs.promises.writeFile).mock.calls[0][0]).toBe(customPath);
  });

  it('falls back to default path when OPIK_CONFIG_PATH parent dir cannot be created', async () => {
    process.env.OPIK_CONFIG_PATH = '/nonexistent/dir/.opik.config';
    vi.mocked(fs.existsSync).mockReturnValue(false);
    vi.mocked(fs.mkdirSync).mockImplementation(() => { throw new Error('EACCES'); });

    await saveToOpikConfigStep({
      projectName: 'proj',
      urlOverride: 'http://localhost/api',
    });

    expect(fs.promises.writeFile).toHaveBeenCalledOnce();
    const writtenPath = vi.mocked(fs.promises.writeFile).mock.calls[0][0] as string;
    expect(writtenPath).not.toBe('/nonexistent/dir/.opik.config');
    expect(writtenPath).toMatch(/\.opik\.config$/);
  });

  it('does not throw when writeFile fails — logs a warning instead', async () => {
    vi.mocked(fs.promises.writeFile).mockRejectedValue(new Error('EACCES'));

    await expect(
      saveToOpikConfigStep({
        projectName: 'proj',
        urlOverride: 'http://localhost/api',
      }),
    ).resolves.not.toThrow();
  });
});