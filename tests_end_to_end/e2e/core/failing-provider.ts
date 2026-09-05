/**
 * A provider destination that answers a PERMANENT 4xx, for specs about how the
 * online-scoring engine classifies a provider failure.
 *
 * The destination is a path the Opik deployment under test does not serve, so
 * the chat call the engine makes — `POST <base_url>/chat/completions` — is
 * answered `404` by the environment itself. No provider key, no third-party
 * network, no rate limit, and the same answer on every run: the whole point is
 * that the failure is terminal and reproducible, which is what makes "the
 * engine called the provider exactly once" an assertable fact.
 *
 * Why not the mock LLM gateway (`core/mock-auth.ts`): that service runs on the
 * test runner, so only a local OSS backend can reach it. These specs need a
 * destination that works wherever the suite points, including a remote
 * deployment, and the deployment's own API is reachable from the backend by
 * definition on every topology where the backend and the browser share an
 * address.
 */
import { loadEnvConfig } from '../config/env.config';
import { AuthConfigCheckError, checkProviderAuthConfig } from './provider-keys';

/**
 * Path segment under the deployment's own API that no resource is mounted at.
 * Deliberately not a real-looking route: a name that later becomes a real
 * endpoint would turn this from a 404 into whatever that endpoint answers.
 */
const UNSERVED_PATH = 'v1/private/e2e-permanent-4xx-destination';

const DESTINATION_ENV = 'OPIK_PERMANENT_4XX_URL';

/**
 * The URL to hand a provider key as its `base_url`.
 *
 * Overridable because the backend's view of the deployment is not always the
 * test process's view — the same split `MOCK_AUTH_URL_FOR_BACKEND` exists for.
 * On a docker-compose OSS install the suite's `http://localhost:5173` is the
 * container's own loopback, so point this at a host-reachable address there.
 */
export function permanentFailureBaseUrl(): string {
  const override = process.env[DESTINATION_ENV];
  if (override) return override;
  return `${loadEnvConfig().apiBaseUrl}/${UNSERVED_PATH}`;
}

/** Backend wording for "the destination was contacted and answered a status". */
const REACHED_WITH_STATUS = /token fetch failed with status '(\d{3})'/;

/**
 * Backend wordings for "the destination was never successfully contacted" — an
 * IOException on send, or a refusal by the SSRF destination guard. Same two the
 * mock-auth gate matches, and for the same reason: 400 is overloaded across
 * every way a server-side fetch can fail.
 */
const UNREACHABLE_PATTERNS = [/could not reach/i, /destination/i];

/** The transient 4xx the engine deliberately redelivers — not permanent failures. */
const TRANSIENT_CLIENT_ERRORS = [408, 425, 429];

interface DestinationProbe {
  /** The status the BACKEND saw, when it reached the destination at all. */
  status: number | null;
  /** Null when the destination is usable; otherwise why the spec must skip. */
  skipReason: string | null;
}

let destinationProbe: Promise<DestinationProbe> | undefined;

/**
 * Proves, server-side, that the backend really gets a permanent 4xx from
 * {@link permanentFailureBaseUrl} — before any rule, trace or browser exists.
 *
 * Without this the two failure modes are indistinguishable from the outside: a
 * destination the backend cannot reach produces no wire status at all, which
 * the engine classifies as retryable and reports with different wording. A spec
 * that seeded against an unreachable URL would fail on a message assertion and
 * read as a product regression.
 *
 * The probe goes through the auth-config test endpoint because it is the one
 * place the backend performs a caller-chosen fetch and reports the outcome. It
 * requests the base URL itself rather than `<base>/chat/completions`; both are
 * unserved paths on the same server, so a 404 for one is a 404 for the other.
 *
 * Cached: the answer is a property of the deployment, and every spec asks.
 */
function probeDestination(): Promise<DestinationProbe> {
  destinationProbe ??= (async () => {
    const url = permanentFailureBaseUrl();
    try {
      await checkProviderAuthConfig({
        token_url: url,
        send_as: 'basic',
        credentials: [
          { key: 'grant_type', value: 'client_credentials', secret: false },
          { key: 'client_id', value: 'e2e-permanent-4xx', secret: false },
          { key: 'client_secret', value: 'e2e-permanent-4xx', secret: true },
        ],
      });
      // A 2xx means the path IS served and answers successfully, so it cannot
      // stand in for a permanent failure.
      return {
        status: null,
        skipReason: `${url} answered successfully, so it cannot produce the permanent 4xx these specs need`,
      };
    } catch (err) {
      if (!(err instanceof AuthConfigCheckError)) throw err;

      if (err.status === 404) {
        return {
          status: null,
          skipReason:
            'deployment has no /auth-config/test endpoint, so the 4xx destination cannot be verified before use',
        };
      }

      if (err.status === 400) {
        const reached = REACHED_WITH_STATUS.exec(err.body);
        if (reached) {
          const status = Number(reached[1]);
          const isPermanent4xx =
            status >= 400 && status < 500 && !TRANSIENT_CLIENT_ERRORS.includes(status);
          return {
            status,
            skipReason: isPermanent4xx
              ? null
              : `${url} answers ${status}, which is not a permanent 4xx`,
          };
        }
        if (UNREACHABLE_PATTERNS.some((re) => re.test(err.body))) {
          return {
            status: null,
            skipReason:
              `the Opik backend cannot reach ${url}: ${err.body} — set ${DESTINATION_ENV} to an ` +
              'address the backend can reach that answers a permanent 4xx (any unserved path on ' +
              'the deployment itself will do).',
          };
        }
      }

      throw err;
    }
  })();
  return destinationProbe;
}

/** Null when the destination is usable, otherwise the reason to skip. */
export async function permanentFailureSkipReason(): Promise<string | null> {
  return (await probeDestination()).skipReason;
}

/**
 * The permanent 4xx status the backend observed at the destination — the status
 * a rule's failure must therefore report.
 *
 * Read from the probe rather than hard-coded, so a deployment reached through an
 * override that answers 403 rather than 404 asserts against what it really
 * sends. Throws rather than defaulting: callers reach here only after
 * {@link permanentFailureSkipReason} returned null, so an absent status is a
 * broken gate, not a case to paper over.
 */
export async function permanentFailureStatus(): Promise<number> {
  const { status, skipReason } = await probeDestination();
  if (status === null) {
    throw new Error(
      `permanentFailureStatus called on an unusable destination: ${skipReason ?? 'unknown'}`,
    );
  }
  return status;
}
