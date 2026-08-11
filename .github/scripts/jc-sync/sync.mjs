/**
 * JC label → Jira sync: the write path (OPIK-7833).
 *
 * Given one GitHub issue, ensure a Jira ticket exists for it, is linked back,
 * and is announced on the issue. Safe to run repeatedly: every step is
 * idempotent, so a re-label, a retry, or a resumed run converges rather than
 * duplicating.
 *
 * Order is deliberate — create, then link, then comment. If the process dies
 * between create and link, the next run still finds the ticket via tier 2 or 3
 * and backfills what's missing. The reverse order could strand a link with no
 * ticket.
 *
 * Usage (normally invoked by .github/workflows/jc_label_to_jira.yml):
 *   node .github/scripts/jc-sync/sync.mjs --issue=7760 [--dry-run]
 *
 * Env: GITHUB_TOKEN, GITHUB_REPOSITORY,
 *      JIRA_BASE_URL, JIRA_USER_EMAIL, JIRA_API_TOKEN
 */
import {
  buildDescription,
  COMMENT_MARKER,
  findExistingTicket,
  JIRA_PROJECT,
  remoteLinkGlobalId,
  remoteLinkJql,
  resolveIssueType,
  SYNCED_LABELS,
} from './resolve.mjs';

const args = process.argv.slice(2);
const argOf = (name, fallback) => {
  const hit = args.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.slice(name.length + 3) : fallback;
};
const DRY_RUN = args.includes('--dry-run');
const ISSUE_NUMBER = Number(argOf('issue', ''));

const [REPO_OWNER, REPO_NAME] = (
  process.env.GITHUB_REPOSITORY || 'comet-ml/opik'
).split('/');

const GH_TOKEN = process.env.GITHUB_TOKEN || process.env.GH_TOKEN;
const JIRA_BASE = (process.env.JIRA_BASE_URL || '').replace(/\/+$/, '');
const JIRA_EMAIL = process.env.JIRA_USER_EMAIL || '';
const JIRA_TOKEN = process.env.JIRA_API_TOKEN || '';

function fail(msg) {
  console.error(`::error::${msg}`);
  process.exit(1);
}

if (!Number.isInteger(ISSUE_NUMBER) || ISSUE_NUMBER <= 0) {
  fail('--issue=<number> is required');
}
if (!GH_TOKEN) fail('GITHUB_TOKEN is required');
if (!JIRA_BASE || !JIRA_EMAIL || !JIRA_TOKEN) {
  fail('JIRA_BASE_URL, JIRA_USER_EMAIL and JIRA_API_TOKEN are required');
}

const ghHeaders = {
  authorization: `Bearer ${GH_TOKEN}`,
  accept: 'application/vnd.github+json',
  'x-github-api-version': '2022-11-28',
  'user-agent': 'opik-jc-sync',
};
const jiraAuth = `Basic ${Buffer.from(`${JIRA_EMAIL}:${JIRA_TOKEN}`).toString('base64')}`;

async function request(url, opts = {}, attempt = 0) {
  const res = await fetch(url, opts);
  if ((res.status === 429 || res.status >= 500) && attempt < 4) {
    const retryAfter = Number(res.headers.get('retry-after')) || 0;
    await new Promise((r) =>
      setTimeout(r, retryAfter * 1000 || 2 ** attempt * 500),
    );
    return request(url, opts, attempt + 1);
  }
  return res;
}

async function gh(path, init = {}) {
  const res = await request(`https://api.github.com${path}`, {
    ...init,
    headers: { ...ghHeaders, ...(init.headers || {}) },
  });
  if (!res.ok) {
    throw new Error(`GitHub ${res.status} ${path}: ${await res.text()}`);
  }
  return res.status === 204 ? null : res.json();
}

/**
 * Pull a readable message out of a Jira error body.
 *
 * Jira returns `errorMessages` / `errors` as JSON, and localises some of them
 * to the account's language — a raw dump is close to useless in a log. Fall
 * back to the status line when there's nothing quotable.
 */
function jiraError(status, path, body) {
  let detail = '';
  try {
    const parsed = JSON.parse(body);
    const parts = [
      ...(parsed.errorMessages || []),
      ...Object.entries(parsed.errors || {}).map(([k, v]) => `${k}: ${v}`),
    ];
    detail = parts.join('; ');
  } catch {
    detail = body.slice(0, 200);
  }

  // The common failures deserve a hint rather than a bare status code.
  const hint =
    status === 401
      ? ' — check JC_JIRA_USER_EMAIL / JC_JIRA_API_TOKEN'
      : status === 403
        ? ' — the Jira account lacks permission on this project'
        : status === 404 && path.includes('/remotelink')
          ? ' — ticket not visible to this account (often a bad token rather than a missing ticket)'
          : '';

  return `Jira ${status} on ${path}${hint}${detail ? `: ${detail}` : ''}`;
}

async function jira(path, init = {}) {
  const res = await request(`${JIRA_BASE}${path}`, {
    ...init,
    headers: {
      authorization: jiraAuth,
      accept: 'application/json',
      'content-type': 'application/json',
      ...(init.headers || {}),
    },
  });
  if (!res.ok) {
    throw new Error(jiraError(res.status, path, await res.text()));
  }
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

async function jiraSearch(jql, maxResults = 10) {
  const data = await jira('/rest/api/3/search/jql', {
    method: 'POST',
    body: JSON.stringify({ jql, maxResults, fields: ['key', 'labels'] }),
  });
  return data.issues || [];
}

/** Tier 1 — indexed exact lookup by remote-link globalId. */
async function findByRemoteLink(globalId) {
  const issues = await jiraSearch(remoteLinkJql(globalId), 2);
  return issues.length ? issues[0].key : null;
}

/** Tier 2 — text search on the GitHub URL, restricted to sync-created tickets. */
async function findByJql(jql) {
  const issues = await jiraSearch(jql);
  if (!issues.length) return null;
  const synced = issues.filter((i) =>
    (i.fields?.labels || []).includes('github-sync'),
  );
  if (synced.length > 1) {
    return { key: synced[0].key, duplicates: synced.map((i) => i.key) };
  }
  if (synced.length === 1) return synced[0].key;
  return { key: issues[0].key, related: issues.map((i) => i.key) };
}

/**
 * Identifies this automation's failure notice so a retry updates the existing
 * comment rather than posting another. Load-bearing — changing it strands old
 * notices on issues.
 */
const FAILURE_MARKER = '<!-- jc-sync:failure -->';

const summary = [];
const note = (line) => {
  console.log(line);
  summary.push(line);
};

/** Remove a stale failure notice once a later run succeeds. */
async function clearFailureNotice() {
  if (DRY_RUN) return;
  try {
    const existing = await gh(
      `/repos/${REPO_OWNER}/${REPO_NAME}/issues/${ISSUE_NUMBER}/comments?per_page=100`,
    );
    for (const c of existing.filter((c) => c.body?.includes(FAILURE_MARKER))) {
      await gh(`/repos/${REPO_OWNER}/${REPO_NAME}/issues/comments/${c.id}`, {
        method: 'DELETE',
      });
      note('Cleared a stale failure notice.');
    }
  } catch (err) {
    console.error(`::warning::Could not clear failure notice: ${err.message}`);
  }
}

async function writeSummary() {
  if (!process.env.GITHUB_STEP_SUMMARY) return;
  const { appendFile } = await import('node:fs/promises');
  const issueUrl = `${process.env.GITHUB_SERVER_URL || 'https://github.com'}/${REPO_OWNER}/${REPO_NAME}/issues/${ISSUE_NUMBER}`;
  const heading = DRY_RUN
    ? `### JC → Jira — [#${ISSUE_NUMBER}](${issueUrl}) (dry run)`
    : `### JC → Jira — [#${ISSUE_NUMBER}](${issueUrl})`;
  await appendFile(
    process.env.GITHUB_STEP_SUMMARY,
    `${heading}\n\n${summary.map((l) => `- ${l}`).join('\n')}\n\n`,
  );
}

async function main() {
  const repo = await gh(`/repos/${REPO_OWNER}/${REPO_NAME}`);
  const issue = await gh(
    `/repos/${REPO_OWNER}/${REPO_NAME}/issues/${ISSUE_NUMBER}`,
  );

  if (issue.pull_request) {
    note('Target is a pull request, not an issue — nothing to do.');
    return;
  }

  const globalId = remoteLinkGlobalId(repo.id, issue.number);
  const type = resolveIssueType({ labels: issue.labels, title: issue.title });
  note(
    `Resolved type **${type.type}** (${type.signal}${type.confident ? '' : ', low confidence'})`,
  );
  if (!type.confident) {
    console.log(
      `::warning::No label or title prefix on #${issue.number}; defaulted to ${type.type}`,
    );
  }

  const existing = await findExistingTicket(
    { repoId: repo.id, issueNumber: issue.number, issueUrl: issue.html_url },
    {
      findByRemoteLink,
      findByJql,
      listIssueComments: async () => {
        const comments = await gh(
          `/repos/${REPO_OWNER}/${REPO_NAME}/issues/${issue.number}/comments?per_page=100`,
        );
        return comments.map((c) => c.body);
      },
    },
  );

  if (existing?.duplicates) {
    console.log(
      `::warning::#${issue.number} already has multiple synced tickets: ${existing.duplicates.join(', ')}`,
    );
  }
  if (existing?.related) {
    note(`Tickets citing this issue (not sync-created): ${existing.related.join(', ')}`);
  }

  let key = existing?.key ?? null;

  if (key) {
    note(`Existing ticket **${key}** found via ${existing.via} — not creating.`);
  } else if (DRY_RUN) {
    note(`DRY RUN — would create a ${type.type} in ${JIRA_PROJECT}.`);
    await writeSummary();
    return;
  } else {
    const created = await jira('/rest/api/2/issue', {
      method: 'POST',
      body: JSON.stringify({
        fields: {
          project: { key: JIRA_PROJECT },
          issuetype: { name: type.type },
          summary: issue.title.slice(0, 255),
          description: buildDescription({
            body: issue.body,
            issueUrl: issue.html_url,
          }),
          labels: SYNCED_LABELS,
          priority: { name: 'Medium' },
        },
      }),
    });
    key = created.key;
    note(`Created **${key}** (${type.type}).`);
  }

  // Backfill the link even on an existing ticket: the ~50 tickets from the old
  // flow have none, and adding one upgrades them to the tier 1 fast path.
  // POSTing the same globalId twice returns the same link, so this is safe.
  if (DRY_RUN) {
    note(`DRY RUN — would ensure remote link \`${globalId}\` on ${key}.`);
  } else {
    await jira(`/rest/api/3/issue/${key}/remotelink`, {
      method: 'POST',
      body: JSON.stringify({
        globalId,
        object: {
          url: issue.html_url,
          title: `${REPO_OWNER}/${REPO_NAME}#${issue.number}`,
        },
      }),
    });
    note(`Remote link ensured (\`${globalId}\`).`);
  }

  // Comment back only if this exact ticket isn't already announced, so a
  // re-label doesn't add a second identical comment.
  const comments = await gh(
    `/repos/${REPO_OWNER}/${REPO_NAME}/issues/${issue.number}/comments?per_page=100`,
  );
  const announced = comments.some(
    (c) => c.body?.includes(COMMENT_MARKER) && c.body.includes(key),
  );

  if (announced) {
    note(`${key} already announced on the issue — no comment posted.`);
  } else if (DRY_RUN) {
    note(`DRY RUN — would comment ${key} on #${issue.number}.`);
  } else {
    const url = `${JIRA_BASE}/browse/${key}`;
    await gh(`/repos/${REPO_OWNER}/${REPO_NAME}/issues/${issue.number}/comments`, {
      method: 'POST',
      body: JSON.stringify({ body: `**${COMMENT_MARKER}** [${key}](${url})` }),
    });
    note(`Commented ${key} on #${issue.number}.`);
  }

  await clearFailureNotice();

  if (process.env.GITHUB_OUTPUT) {
    const { appendFile } = await import('node:fs/promises');
    await appendFile(process.env.GITHUB_OUTPUT, `ticket=${key}\n`);
  }
  await writeSummary();
}

/**
 * Tell the labeller the sync failed, on the issue itself.
 *
 * The old flow failed silently, so maintainers learned to toggle the label as a
 * retry — which is how issue #7000 ended up with two tickets. A visible failure
 * plus a documented re-run path removes the reason to toggle.
 *
 * Best-effort and idempotent: keyed on FAILURE_MARKER so a retry loop replaces
 * the previous notice instead of stacking notices, and any error posting it is
 * swallowed so it can't mask the original failure.
 */
async function reportFailure(message) {
  if (DRY_RUN || !Number.isInteger(ISSUE_NUMBER) || ISSUE_NUMBER <= 0) return;

  const runUrl =
    process.env.GITHUB_SERVER_URL && process.env.GITHUB_RUN_ID
      ? `${process.env.GITHUB_SERVER_URL}/${process.env.GITHUB_REPOSITORY}/actions/runs/${process.env.GITHUB_RUN_ID}`
      : null;

  const body = [
    FAILURE_MARKER,
    '',
    'Automatic Jira ticket creation failed for this issue.',
    '',
    `> ${message}`,
    '',
    runUrl ? `[View the failed run](${runUrl})` : null,
    '',
    'The `JC` label has been left in place. Re-run the **JC Label to Jira** ' +
      'workflow from the Actions tab once the cause is fixed — removing and ' +
      're-adding the label is not needed, and risks creating a duplicate.',
  ]
    .filter((l) => l !== null)
    .join('\n');

  try {
    const path = `/repos/${REPO_OWNER}/${REPO_NAME}/issues/${ISSUE_NUMBER}/comments`;
    const existing = await gh(`${path}?per_page=100`);
    const prior = existing.find((c) => c.body?.includes(FAILURE_MARKER));

    if (prior) {
      await gh(
        `/repos/${REPO_OWNER}/${REPO_NAME}/issues/comments/${prior.id}`,
        { method: 'PATCH', body: JSON.stringify({ body }) },
      );
      console.log('Updated the existing failure notice on the issue.');
    } else {
      await gh(path, { method: 'POST', body: JSON.stringify({ body }) });
      console.log('Posted a failure notice on the issue.');
    }
  } catch (err) {
    // Never let the notice hide the real error.
    console.error(`::warning::Could not post failure notice: ${err.message}`);
  }
}

main().catch(async (err) => {
  console.error(`::error::${err.message}`);
  summary.push(`**Failed:** ${err.message}`);
  await reportFailure(err.message);
  await writeSummary().catch(() => {});
  process.exit(1);
});
