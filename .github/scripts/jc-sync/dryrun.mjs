/**
 * JC → Jira sync: dry-run validator (OPIK-7833).
 *
 * Replays the resolver over every GitHub issue that already carries the `JC`
 * label and reports what the sync *would* do. Writes nothing, to either side.
 *
 * The point is to prove the dedupe resolver against the real backlog before any
 * create path exists: every one of these issues has already been synced, so a
 * correct resolver must find an existing ticket for all of them. Anything
 * reported as WOULD-CREATE is either a genuine miss by the old flow or a bug in
 * the resolver — both worth knowing before going live.
 *
 * Usage:
 *   GITHUB_TOKEN=...            (or gh auth)
 *   JIRA_BASE_URL=https://comet-ml.atlassian.net
 *   JIRA_USER_EMAIL=...
 *   JIRA_API_TOKEN=...
 *   node .github/scripts/jc-sync/dryrun.mjs [--limit=N] [--json=out.json]
 *
 * Jira credentials are optional: without them tiers 1 and 2 are skipped and the
 * run degrades to comment-only matching, which still exercises tier 3.
 */
import {
  findExistingTicket,
  remoteLinkJql,
  resolveIssueType,
} from './resolve.mjs';

const REPO_OWNER = process.env.JC_REPO_OWNER || 'comet-ml';
const REPO_NAME = process.env.JC_REPO_NAME || 'opik';
const TRIGGER_LABEL = process.env.JC_LABEL || 'JC';

const args = process.argv.slice(2);
const argOf = (name, fallback) => {
  const hit = args.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.slice(name.length + 3) : fallback;
};
const LIMIT = Number(argOf('limit', '0')) || 0;
const JSON_OUT = argOf('json', '');

const GH_TOKEN = process.env.GITHUB_TOKEN || process.env.GH_TOKEN;
const JIRA_BASE = (process.env.JIRA_BASE_URL || '').replace(/\/+$/, '');
const JIRA_EMAIL = process.env.JIRA_USER_EMAIL || '';
const JIRA_TOKEN = process.env.JIRA_API_TOKEN || '';
const JIRA_READY = Boolean(JIRA_BASE && JIRA_EMAIL && JIRA_TOKEN);

if (!GH_TOKEN) {
  console.error('GITHUB_TOKEN (or GH_TOKEN) is required.');
  process.exit(2);
}

const ghHeaders = {
  authorization: `Bearer ${GH_TOKEN}`,
  accept: 'application/vnd.github+json',
  'x-github-api-version': '2022-11-28',
  'user-agent': 'opik-jc-sync-dryrun',
};

const jiraHeaders = JIRA_READY
  ? {
      authorization: `Basic ${Buffer.from(`${JIRA_EMAIL}:${JIRA_TOKEN}`).toString('base64')}`,
      accept: 'application/json',
      'content-type': 'application/json',
    }
  : null;

/** Fetch with a bounded retry on 429/5xx, so a long replay doesn't die midway. */
async function request(url, opts = {}, attempt = 0) {
  const res = await fetch(url, opts);
  if ((res.status === 429 || res.status >= 500) && attempt < 4) {
    const retryAfter = Number(res.headers.get('retry-after')) || 0;
    const wait = retryAfter * 1000 || 2 ** attempt * 500;
    await new Promise((r) => setTimeout(r, wait));
    return request(url, opts, attempt + 1);
  }
  return res;
}

async function gh(path) {
  const res = await request(`https://api.github.com${path}`, {
    headers: ghHeaders,
  });
  if (!res.ok) {
    throw new Error(`GitHub ${res.status} on ${path}: ${await res.text()}`);
  }
  return res.json();
}

async function jira(path, init = {}) {
  const res = await request(`${JIRA_BASE}${path}`, {
    ...init,
    headers: { ...jiraHeaders, ...(init.headers || {}) },
  });
  if (!res.ok) {
    throw new Error(`Jira ${res.status} on ${path}: ${await res.text()}`);
  }
  return res.json();
}

async function jiraSearch(jql, maxResults = 10) {
  const data = await jira('/rest/api/3/search/jql', {
    method: 'POST',
    body: JSON.stringify({ jql, maxResults, fields: ['key', 'labels'] }),
  });
  return data.issues || [];
}

/**
 * Tier 1 — exact remote-link lookup.
 *
 * `issuesWithRemoteLinksByGlobalId()` is an indexed JQL function, so this is a
 * true exact match rather than tier 2's text search. Historical tickets carry
 * no remote links, so during a replay of the backlog this correctly returns
 * null and falls through.
 */
async function findByRemoteLink(globalId) {
  if (!JIRA_READY) return null;
  const issues = await jiraSearch(remoteLinkJql(globalId), 2);
  return issues.length ? issues[0].key : null;
}

/**
 * Tier 2 — JQL over the GitHub URL in the description.
 *
 * A GitHub URL can legitimately appear in more than one ticket: engineers cite
 * the issue in follow-up and sibling tickets. Only tickets carrying
 * `github-sync` were created by this automation, so:
 *
 *   - prefer a `github-sync` ticket as the match, and
 *   - only report `duplicates` when *several* of them exist, which is the
 *     genuine double-create this sync must prevent.
 *
 * Without that filter the replay reported 33 "duplicates", of which only 6 were
 * real — the other 27 were hand-written tickets that merely referenced the URL.
 */
async function findByJql(jql) {
  if (!JIRA_READY) return null;

  // Ask Jira for the synced tickets directly. Filtering a capped page
  // client-side would let a synced ticket fall off the end of a heavily-cited
  // issue and read as "none synced" — which downstream means "create".
  const synced = await jiraSearch(`${jql} AND labels = "github-sync"`, 50);
  if (synced.length > 1) {
    return { key: synced[0].key, duplicates: synced.map((i) => i.key) };
  }
  if (synced.length === 1) return synced[0].key;

  // No sync-created ticket. Something references this issue, but the sync never
  // made one — report the reference so it can be triaged, not as a duplicate.
  const any = await jiraSearch(jql, 20);
  if (!any.length) return null;
  return { key: any[0].key, related: any.map((i) => i.key) };
}

async function main() {
  console.log(
    `Replaying label "${TRIGGER_LABEL}" on ${REPO_OWNER}/${REPO_NAME}` +
      (JIRA_READY ? ' (Jira: on)' : ' (Jira: OFF — tier 3 only)'),
  );

  const repo = await gh(`/repos/${REPO_OWNER}/${REPO_NAME}`);
  const repoId = repo.id;
  console.log(`repo id ${repoId}\n`);

  // Collect labeled issues (issues only — the label is never used on PRs, but
  // filter defensively since the search endpoint returns both).
  const issues = [];
  for (let page = 1; ; page += 1) {
    const batch = await gh(
      `/repos/${REPO_OWNER}/${REPO_NAME}/issues` +
        `?labels=${encodeURIComponent(TRIGGER_LABEL)}` +
        `&state=all&per_page=100&page=${page}`,
    );
    if (!batch.length) break;
    issues.push(...batch.filter((i) => !i.pull_request));
    if (batch.length < 100) break;
    if (LIMIT && issues.length >= LIMIT) break;
  }

  const scope = LIMIT ? issues.slice(0, LIMIT) : issues;
  console.log(`${scope.length} labeled issues to replay\n`);

  const rows = [];
  const tally = {
    matched: 0,
    wouldCreate: 0,
    relatedOnly: 0,
    duplicates: 0,
    unconfidentType: 0,
    byVia: {},
    byType: {},
    bySignal: {},
  };

  for (const issue of scope) {
    const type = resolveIssueType({
      labels: issue.labels,
      title: issue.title,
    });

    let existing = null;
    let error = null;
    try {
      existing = await findExistingTicket(
        {
          repoId,
          issueNumber: issue.number,
          issueUrl: issue.html_url,
        },
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
    } catch (err) {
      error = err.message;
    }

    const key = existing?.key ?? null;
    const dupes = existing?.duplicates ?? null;
    const related = existing?.related ?? null;

    tally.byType[type.type] = (tally.byType[type.type] || 0) + 1;
    tally.bySignal[type.signal] = (tally.bySignal[type.signal] || 0) + 1;
    if (!type.confident) tally.unconfidentType += 1;

    // `key` is null when only citing tickets were found, so the live sync would
    // still create. Count that as would-create and flag it for a human.
    if (key) {
      tally.matched += 1;
      tally.byVia[existing.via] = (tally.byVia[existing.via] || 0) + 1;
    } else if (!error) {
      tally.wouldCreate += 1;
      if (related) tally.relatedOnly += 1;
    }
    if (dupes) tally.duplicates += 1;

    rows.push({
      number: issue.number,
      state: issue.state,
      title: issue.title,
      labels: issue.labels.map((l) => l.name),
      resolvedType: type.type,
      typeSignal: type.signal,
      typeConfident: type.confident,
      existingKey: key,
      matchedVia: existing?.via ?? null,
      duplicates: dupes,
      related,
      error,
    });

    const verdict = error
      ? `ERROR ${error.slice(0, 60)}`
      : key
        ? `match ${key} (${existing.via})`
        : 'WOULD-CREATE';
    const warn = [
      type.confident ? '' : ' [type:unconfident]',
      dupes ? ` [DUPES ${dupes.join(',')}]` : '',
      related ? ` [related ${related.join(',')}]` : '',
    ].join('');
    console.log(
      `#${String(issue.number).padEnd(5)} ${type.type.padEnd(4)} ${verdict}${warn}`,
    );
  }

  console.log('\n--- summary ---');
  console.log(`replayed          ${rows.length}`);
  console.log(`matched existing  ${tally.matched}`);
  console.log(`would create      ${tally.wouldCreate}`);
  console.log(`  ...of which referenced by a non-synced ticket: ${tally.relatedOnly}`);
  console.log(`duplicate pairs   ${tally.duplicates}`);
  console.log(`unconfident type  ${tally.unconfidentType}`);
  console.log(`matched via       ${JSON.stringify(tally.byVia)}`);
  console.log(`type distribution ${JSON.stringify(tally.byType)}`);
  console.log(`type signal       ${JSON.stringify(tally.bySignal)}`);

  if (JSON_OUT) {
    const { writeFile } = await import('node:fs/promises');
    await writeFile(JSON_OUT, JSON.stringify({ tally, rows }, null, 2));
    console.log(`\nwrote ${JSON_OUT}`);
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
