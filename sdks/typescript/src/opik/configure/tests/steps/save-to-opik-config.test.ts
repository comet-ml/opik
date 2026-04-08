import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import * as fs from 'fs';
import {
  parseOpikSection,
  serializeOpikSection,
  saveToOpikConfigStep,
} from '../../src/steps';

// ESM requires module-level mocking for native node modules.
vi.mock('fs', async () => {
  const actual = await vi.importActual<typeof import('fs')>('fs');
  return {
    ...actual,
    existsSync: vi.fn(),
    readFileSync: vi.fn(),
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

// ---------------------------------------------------------------------------
// parseOpikSection
// ---------------------------------------------------------------------------

describe('parseOpikSection', () => {
  it('parses all keys from an [opik] section', () => {
    const content = `
[opik]
api_key = my-api-key
url_override = https://example.com/api
workspace = my-workspace
project_name = my-project
`.trim();

    expect(parseOpikSection(content)).toEqual({
      api_key: 'my-api-key',
      url_override: 'https://example.com/api',
      workspace: 'my-workspace',
      project_name: 'my-project',
    });
  });

  it('strips surrounding quotes from values', () => {
    const content = `[opik]\napi_key = "quoted-key"\nproject_name = 'single-quoted'`;
    const result = parseOpikSection(content);
    expect(result.api_key).toBe('quoted-key');
    expect(result.project_name).toBe('single-quoted');
  });

  it('returns empty object when [opik] section is absent', () => {
    expect(parseOpikSection('')).toEqual({});
    expect(parseOpikSection('[other]\nfoo = bar')).toEqual({});
  });

  it('stops reading at the next section header', () => {
    const content = `[opik]\nproject_name = proj\n[other]\napi_key = should-not-appear`;
    const result = parseOpikSection(content);
    expect(result.project_name).toBe('proj');
    expect(result.api_key).toBeUndefined();
  });

  it('ignores comment lines', () => {
    const content = `[opik]\n# this is a comment\n; another comment\nproject_name = proj`;
    expect(parseOpikSection(content).project_name).toBe('proj');
  });

  it('handles keys without values gracefully (no = sign)', () => {
    const content = `[opik]\nbadline\nproject_name = proj`;
    expect(parseOpikSection(content).project_name).toBe('proj');
  });
});

// ---------------------------------------------------------------------------
// serializeOpikSection
// ---------------------------------------------------------------------------

describe('serializeOpikSection', () => {
  it('creates a new [opik] block when file is empty', () => {
    const result = serializeOpikSection(
      { project_name: 'proj', url_override: 'http://localhost/api' },
      '',
    );
    expect(result).toContain('[opik]');
    expect(result).toContain('project_name = proj');
    expect(result).toContain('url_override = http://localhost/api');
  });

  it('replaces an existing [opik] block in-place', () => {
    const existing = `[opik]\nproject_name = old\nurl_override = http://old/api\n`;
    const result = serializeOpikSection(
      { project_name: 'new', url_override: 'http://new/api' },
      existing,
    );
    expect(result).toContain('project_name = new');
    expect(result).toContain('url_override = http://new/api');
    expect(result).not.toContain('project_name = old');
  });

  it('preserves other sections when replacing [opik]', () => {
    const existing = `[other]\nfoo = bar\n\n[opik]\nproject_name = old\n`;
    const result = serializeOpikSection({ project_name: 'new' }, existing);
    expect(result).toContain('[other]');
    expect(result).toContain('foo = bar');
    expect(result).toContain('project_name = new');
    expect(result).not.toContain('project_name = old');
  });

  it('appends [opik] when other sections exist but no [opik]', () => {
    const existing = `[other]\nfoo = bar\n`;
    const result = serializeOpikSection({ project_name: 'proj' }, existing);
    expect(result).toContain('[other]');
    expect(result).toContain('[opik]');
    expect(result).toContain('project_name = proj');
  });

  it('omits keys whose value is empty or undefined', () => {
    const result = serializeOpikSection(
      { project_name: 'proj', api_key: '' },
      '',
    );
    expect(result).not.toContain('api_key');
  });
});

// ---------------------------------------------------------------------------
// saveToOpikConfigStep
// ---------------------------------------------------------------------------

describe('saveToOpikConfigStep', () => {
  beforeEach(() => {
    vi.mocked(fs.existsSync).mockReturnValue(false);
    vi.mocked(fs.readFileSync).mockReturnValue('');
    vi.mocked(fs.promises.writeFile).mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('creates the config file when it does not exist', async () => {
    await saveToOpikConfigStep({
      projectName: 'my-project',
      urlOverride: 'http://localhost/api',
    });

    expect(fs.promises.writeFile).toHaveBeenCalledOnce();
    const written = vi.mocked(fs.promises.writeFile).mock.calls[0][1] as string;
    expect(written).toContain('[opik]');
    expect(written).toContain('project_name = my-project');
    expect(written).toContain('url_override = http://localhost/api');
    expect(written).not.toContain('api_key');
    expect(written).not.toContain('workspace');
  });

  it('includes api_key and workspace for cloud deployments', async () => {
    await saveToOpikConfigStep({
      projectName: 'cloud-proj',
      urlOverride: 'https://www.comet.com/opik/api',
      apiKey: 'secret-key',
      workspace: 'my-workspace',
    });

    expect(fs.promises.writeFile).toHaveBeenCalledOnce();
    const written = vi.mocked(fs.promises.writeFile).mock.calls[0][1] as string;
    expect(written).toContain('api_key = secret-key');
    expect(written).toContain('workspace = my-workspace');
  });

  it('merges with an existing config file', async () => {
    const existingContent = `[opik]\napi_key = old-key\nproject_name = old-project\n`;
    vi.mocked(fs.existsSync).mockReturnValue(true);
    vi.mocked(fs.readFileSync).mockReturnValue(existingContent);

    await saveToOpikConfigStep({
      projectName: 'new-project',
      urlOverride: 'https://www.comet.com/opik/api',
      apiKey: 'new-key',
    });

    expect(fs.promises.writeFile).toHaveBeenCalledOnce();
    const written = vi.mocked(fs.promises.writeFile).mock.calls[0][1] as string;
    expect(written).toContain('project_name = new-project');
    expect(written).toContain('api_key = new-key');
    expect(written).not.toContain('old-project');
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
