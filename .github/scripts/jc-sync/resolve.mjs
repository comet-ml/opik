/**
 * JC label → Jira sync: pure resolution logic.
 *
 * This module holds the two decisions the sync has to make before it writes
 * anything:
 *
 *   1. resolveIssueType()  — is this a Bug or a Task?
 *   2. findExistingTicket() — has this GitHub issue already been synced?
 *
 * Everything here is dependency-injected (see the `deps` params) so the logic
 * can be exercised against real data with no writes and no Actions runtime.
 * See OPIK-7833.
 */

/** Jira project the tickets land in. */
export const JIRA_PROJECT = 'OPIK';

/** Labels every synced ticket carries. */
export const SYNCED_LABELS = ['JC', 'Opik_Ops', 'github-sync'];

/** The label that triggers a sync. */
export const TRIGGER_LABEL = 'JC';

/**
 * Marker used in the "ticket created" comment posted back on the GitHub issue.
 * Also parsed as the third-tier dedupe fallback, so the shape is load-bearing:
 * changing it orphans the history.
 */
export const COMMENT_MARKER = 'Jira Ticket Created:';

/**
 * Deterministic Jira remote-link id for a GitHub issue.
 *
 * Keyed on the numeric repo id rather than `owner/name` so a repo rename or
 * transfer doesn't silently break dedupe and start duplicating tickets.
 */
export function remoteLinkGlobalId(repoId, issueNumber) {
  if (!Number.isInteger(repoId) || repoId <= 0) {
    throw new TypeError(`repoId must be a positive integer, got ${repoId}`);
  }
  if (!Number.isInteger(issueNumber) || issueNumber <= 0) {
    throw new TypeError(
      `issueNumber must be a positive integer, got ${issueNumber}`,
    );
  }
  return `github-issue-${repoId}-${issueNumber}`;
}

/**
 * Decide the Jira issue type for a GitHub issue.
 *
 * Precedence, highest first:
 *   1. GitHub labels — the strongest signal, because a human applied them.
 *   2. Title prefix — `[Bug]` / `[FR]`, the convention in the issue templates.
 *   3. Default to Task, flagged as low confidence.
 *
 * The old n8n flow used only the title prefix and silently defaulted to Bug,
 * which mis-typed 5 of the first 50 synced tickets — OPIK-6872 ("Able to view
 * the full agent loop run in a single trace") is a feature request filed as a
 * Bug. Labels first fixes that class of error.
 *
 * @returns {{type: 'Bug'|'Task', signal: string, confident: boolean}}
 */
export function resolveIssueType({ labels = [], title = '' } = {}) {
  const lower = new Set(
    labels
      .map((l) => (typeof l === 'string' ? l : l?.name))
      .filter(Boolean)
      .map((n) => n.toLowerCase()),
  );

  // Feature-ish labels win over bug-ish ones: an issue carrying both is a
  // feature request that someone also triaged as broken behaviour, and filing
  // it as a Task keeps it off the bug count.
  if (lower.has('feature_request') || lower.has('enhancement')) {
    return { type: 'Task', signal: 'github-label', confident: true };
  }
  if (lower.has('bug')) {
    return { type: 'Bug', signal: 'github-label', confident: true };
  }

  const t = title.trimStart();
  if (/^\[FR\]/i.test(t)) {
    return { type: 'Task', signal: 'title-prefix', confident: true };
  }
  if (/^\[Bug\]/i.test(t)) {
    return { type: 'Bug', signal: 'title-prefix', confident: true };
  }

  // No signal. Task is the safer default — a mislabelled Task is untidy, a
  // mislabelled Bug pollutes bug metrics and on-call triage.
  return { type: 'Task', signal: 'default', confident: false };
}

/**
 * Extract every `OPIK-1234` key from a blob of text, in order, deduped.
 *
 * Scoped to the target project on purpose. A generic `[A-Z]+-\d+` also matches
 * keys from other projects that happen to be linked in the same comment — the
 * replay found `YT-51`/`YT-44`/`YT-39` (a different Jira project) being picked
 * up as if they were the synced ticket.
 */
export function parseTicketKeys(text, project = JIRA_PROJECT) {
  if (!text) return [];
  const re = new RegExp(`\\b(${project}-\\d+)\\b`, 'g');
  const seen = new Set();
  for (const m of String(text).matchAll(re)) seen.add(m[1]);
  return [...seen];
}

/** First matching ticket key in `text`, or null. */
export function parseTicketKey(text, project = JIRA_PROJECT) {
  return parseTicketKeys(text, project)[0] ?? null;
}

/**
 * Find an already-synced Jira ticket for a GitHub issue.
 *
 * Three tiers, cheapest and most reliable first. Any hit means "do not create".
 *
 *   1. Remote link by globalId — exact, indexed, and what this sync writes
 *      going forward. The fast path for everything created from now on.
 *   2. JQL on the GitHub URL in the description — catches the ~50 tickets the
 *      old n8n flow created, which have no remote link. Text search, so it can
 *      lag right after a create; never the primary key.
 *   3. The `Jira Ticket Created:` comment on the GitHub issue itself — catches
 *      tickets whose description was later edited, and any case where Jira
 *      search is unavailable.
 *
 * Tier 3 matters more than it looks. On GitHub issue #7000 the label was
 * toggled three times in eight minutes and produced two tickets (OPIK-6834 and
 * OPIK-6835) — only the second was commented back, so the first was orphaned.
 * Reading the comments is how an interrupted run recovers on retry.
 *
 * @param {object} args
 * @param {number} args.repoId
 * @param {number} args.issueNumber
 * @param {string} args.issueUrl        - html_url of the GitHub issue
 * @param {object} deps
 * @param {(globalId: string) => Promise<string|null>} deps.findByRemoteLink
 * @param {(jql: string) => Promise<string|null>}      deps.findByJql
 * @param {() => Promise<string[]>}                    deps.listIssueComments
 * @returns {Promise<{key: string, via: string}|null>}
 */
export async function findExistingTicket(
  { repoId, issueNumber, issueUrl },
  { findByRemoteLink, findByJql, listIssueComments },
) {
  const globalId = remoteLinkGlobalId(repoId, issueNumber);

  const byLink = await findByRemoteLink(globalId);
  if (byLink) return { key: byLink, via: 'remote-link' };

  // Match on the path only. The stored URL may carry a trailing slash, an
  // anchor, or http/https — and `~` is a word-ish match, so a bare number
  // would match unrelated tickets.
  const needle = issueUrl.replace(/^https?:\/\//, '').replace(/\/+$/, '');
  const jql = `project = ${JIRA_PROJECT} AND description ~ ${JSON.stringify(`"${needle}"`)}`;
  // Callers may return a bare key or {key, duplicates}; normalise both.
  const byJql = await findByJql(jql);
  if (byJql) {
    const key = typeof byJql === 'string' ? byJql : byJql.key;
    const dupes = typeof byJql === 'string' ? null : byJql.duplicates;
    if (key) {
      return {
        key,
        via: 'jql-description',
        ...(dupes && dupes.length > 1 ? { duplicates: dupes } : {}),
      };
    }
  }

  // Collect across *all* marker comments rather than returning on the first.
  // Issue #4348 carries four (YT-51/52/53/54) — stopping early would report a
  // clean single match and hide exactly the duplication this sync must surface.
  const comments = await listIssueComments();
  const keys = [];
  for (const body of comments) {
    if (!body?.includes(COMMENT_MARKER)) continue;
    for (const key of parseTicketKeys(body)) {
      if (!keys.includes(key)) keys.push(key);
    }
  }
  if (keys.length) {
    return {
      key: keys[0],
      via: 'github-comment',
      ...(keys.length > 1 ? { duplicates: keys } : {}),
    };
  }

  return null;
}

/**
 * Convert GitHub-flavoured Markdown to Jira wiki markup.
 *
 * Deliberately partial. The Jira description is a convenience copy — the GitHub
 * issue stays the source of truth, which is why the backlink matters more than
 * perfect fidelity. Anything not handled here passes through as readable plain
 * text rather than breaking.
 *
 * Targets the v2 REST API, which takes wiki markup as a plain string. The v3
 * API would require building an ADF document tree, i.e. vendoring a Markdown→ADF
 * converter — a real dependency for a workflow whose whole point is being easy
 * to maintain.
 */
export function markdownToWiki(md) {
  if (!md) return '';

  // Pull fenced blocks out first so their contents can't be touched by the
  // inline rules below, then restore them at the end.
  const fences = [];
  let out = String(md).replace(
    /```([A-Za-z0-9_+-]*)\r?\n([\s\S]*?)```/g,
    (_, lang, code) => {
      const token = ` FENCE${fences.length} `;
      const header = lang ? `{code:${lang}}` : '{code}';
      fences.push(`${header}\n${code.replace(/\s+$/, '')}\n{code}`);
      return token;
    },
  );

  out = out
    // Headings: #### and deeper collapse to h4. — Jira has no h5/h6 in practice.
    .replace(/^#{4,}\s+(.+)$/gm, 'h4. $1')
    .replace(/^###\s+(.+)$/gm, 'h3. $1')
    .replace(/^##\s+(.+)$/gm, 'h2. $1')
    .replace(/^#\s+(.+)$/gm, 'h1. $1')
    // Task list items -> bullets carrying their state, before generic bullets.
    .replace(/^(\s*)[-*]\s+\[[xX]\]\s+(.+)$/gm, '$1* (/) $2')
    .replace(/^(\s*)[-*]\s+\[\s\]\s+(.+)$/gm, '$1* (x) $2')
    // Unordered list markers -> *, preserving indent depth.
    .replace(/^(\s*)[-*]\s+(.+)$/gm, '$1* $2')
    // Bold/italic. Bold first: ** would otherwise be eaten as two italics.
    .replace(/\*\*([^*\n]+)\*\*/g, '*$1*')
    .replace(/(^|[\s(])_([^_\n]+)_(?=[\s.,;:!?)]|$)/g, '$1_$2_')
    // Inline code.
    .replace(/`([^`\n]+)`/g, '{{$1}}')
    // [text](url) -> [text|url]
    .replace(/\[([^\]\n]+)\]\((https?:\/\/[^)\s]+)\)/g, '[$1|$2]');

  return out.replace(/ FENCE(\d+) /g, (_, i) => fences[Number(i)]);
}

/**
 * Build the Jira description for a synced issue.
 *
 * The trailing `GitHub: <url>` line is load-bearing: it is what the tier-2 JQL
 * fallback matches on. Keep it last and keep it bare.
 */
export function buildDescription({ body, issueUrl }) {
  const converted = markdownToWiki(body).trim();
  const parts = ['*Synced from GitHub issue body*', ''];
  parts.push(converted.length ? converted : '_No description provided._');
  parts.push('', `GitHub: ${issueUrl}`);
  return parts.join('\n');
}
