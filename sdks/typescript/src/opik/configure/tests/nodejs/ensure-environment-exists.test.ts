import { describe, it, expect, vi, beforeEach } from 'vitest';
import axios from 'axios';
import { ensureEnvironmentExists } from '../../src/nodejs/ensure-environment';

vi.mock('axios');
vi.mock('../../src/utils/urls', () => ({
  buildOpikApiUrl: (host: string) => `${host}opik/api`,
}));

const ENVIRONMENTS_URL = 'http://localhost:5173/opik/api/v1/private/environments';

describe('ensureEnvironmentExists', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('does not POST when the environment already exists', async () => {
    vi.mocked(axios.get).mockResolvedValueOnce({
      data: { content: [{ name: 'staging' }, { name: 'production' }] },
    });

    await ensureEnvironmentExists('http://localhost:5173/', 'staging', 'api-key', 'my-workspace');

    expect(axios.get).toHaveBeenCalledOnce();
    expect(axios.post).not.toHaveBeenCalled();
  });

  it('POSTs to create the environment when it does not exist', async () => {
    vi.mocked(axios.get).mockResolvedValueOnce({
      data: { content: [{ name: 'production' }] },
    });
    vi.mocked(axios.post).mockResolvedValueOnce({});

    await ensureEnvironmentExists('http://localhost:5173/', 'staging', 'api-key', 'my-workspace');

    expect(axios.post).toHaveBeenCalledWith(
      ENVIRONMENTS_URL,
      { name: 'staging' },
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'api-key',
          'Comet-Workspace': 'my-workspace',
        }),
      }),
    );
  });

  it('POSTs when the content list is empty', async () => {
    vi.mocked(axios.get).mockResolvedValueOnce({ data: { content: [] } });
    vi.mocked(axios.post).mockResolvedValueOnce({});

    await ensureEnvironmentExists('http://localhost:5173/', 'new-env');

    expect(axios.post).toHaveBeenCalledOnce();
  });

  it('does not set Authorization or Comet-Workspace headers when credentials are omitted', async () => {
    vi.mocked(axios.get).mockResolvedValueOnce({ data: { content: [] } });
    vi.mocked(axios.post).mockResolvedValueOnce({});

    await ensureEnvironmentExists('http://localhost:5173/', 'new-env');

    const postHeaders = vi.mocked(axios.post).mock.calls[0][2]?.headers as Record<string, string>;
    expect(postHeaders['Authorization']).toBeUndefined();
    expect(postHeaders['Comet-Workspace']).toBeUndefined();
  });

  it('throws when the list request fails', async () => {
    vi.mocked(axios.get).mockRejectedValueOnce(new Error('network error'));

    await expect(
      ensureEnvironmentExists('http://localhost:5173/', 'staging'),
    ).rejects.toThrow("Failed to ensure environment 'staging' exists");
    expect(axios.post).not.toHaveBeenCalled();
  });

  it('throws when the create request fails', async () => {
    vi.mocked(axios.get).mockResolvedValueOnce({ data: { content: [] } });
    vi.mocked(axios.post).mockRejectedValueOnce(new Error('conflict'));

    await expect(
      ensureEnvironmentExists('http://localhost:5173/', 'staging'),
    ).rejects.toThrow("Failed to ensure environment 'staging' exists");
  });
});
