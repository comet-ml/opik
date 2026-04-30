import axios from 'axios';
import { buildOpikApiUrl } from '../utils/urls';

export async function ensureEnvironmentExists(
  host: string,
  environmentName: string,
  apiKey?: string,
  workspace?: string,
): Promise<void> {
  const baseApiUrl = `${buildOpikApiUrl(host)}/v1/private/environments`;
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (apiKey) headers['Authorization'] = apiKey;
  if (workspace) headers['Comet-Workspace'] = workspace;

  try {
    const listResponse = await axios.get<{ content?: Array<{ name?: string }> }>(
      baseApiUrl,
      { headers, timeout: 5000 },
    );
    const names = (listResponse.data?.content ?? []).map((e) => e.name);
    if (!names.includes(environmentName)) {
      await axios.post(baseApiUrl, { name: environmentName }, { headers, timeout: 5000 });
    }
  } catch (e) {
    throw new Error(`Failed to ensure environment '${environmentName}' exists: ${e instanceof Error ? e.message : String(e)}`);
  }
}
