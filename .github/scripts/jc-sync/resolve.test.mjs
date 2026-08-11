/**
 * Tests for the JC → Jira resolution logic (OPIK-7833).
 *
 * Run: node --test .github/scripts/jc-sync/
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  buildDescription,
  findExistingTicket,
  markdownToWiki,
  parseTicketKey,
  parseTicketKeys,
  remoteLinkGlobalId,
  resolveIssueType,
} from './resolve.mjs';

test('remoteLinkGlobalId is deterministic and repo-id keyed', () => {
  assert.equal(remoteLinkGlobalId(12345, 7760), 'github-issue-12345-7760');
  assert.equal(
    remoteLinkGlobalId(12345, 7760),
    remoteLinkGlobalId(12345, 7760),
  );
  // Different issues must not collide.
  assert.notEqual(
    remoteLinkGlobalId(12345, 776),
    remoteLinkGlobalId(12345, 7760),
  );
});

test('remoteLinkGlobalId rejects bad input rather than making a junk key', () => {
  assert.throws(() => remoteLinkGlobalId(0, 1), TypeError);
  assert.throws(() => remoteLinkGlobalId(1, 0), TypeError);
  assert.throws(() => remoteLinkGlobalId('12345', 1), TypeError);
  assert.throws(() => remoteLinkGlobalId(1, 1.5), TypeError);
});

test('labels take precedence over title prefix', () => {
  // The real regression: issue #7000 carried `enhancement` and was titled
  // "[FR]: ...". Both agree here, but the label is what we trust.
  assert.deepEqual(
    resolveIssueType({
      labels: ['enhancement'],
      title: '[FR]: Support OPIK_TRACK_DISABLE in Typescript SDK',
    }),
    { type: 'Task', signal: 'github-label', confident: true },
  );

  // Label disagrees with prefix -> label wins.
  assert.equal(
    resolveIssueType({ labels: ['enhancement'], title: '[Bug]: something' })
      .type,
    'Task',
  );
  assert.equal(
    resolveIssueType({ labels: ['Bug'], title: '[FR]: something' }).type,
    'Bug',
  );
});

test('feature labels outrank bug labels when both are present', () => {
  assert.equal(
    resolveIssueType({ labels: ['Bug', 'enhancement'], title: 'x' }).type,
    'Task',
  );
});

test('label matching is case insensitive and accepts label objects', () => {
  assert.equal(
    resolveIssueType({ labels: [{ name: 'Feature_Request' }], title: 'x' })
      .type,
    'Task',
  );
  assert.equal(
    resolveIssueType({ labels: ['BUG'], title: 'x' }).type,
    'Bug',
  );
});

test('title prefix is used when no label signal exists', () => {
  assert.deepEqual(
    resolveIssueType({ labels: ['JC'], title: '[Bug]: broken thing' }),
    { type: 'Bug', signal: 'title-prefix', confident: true },
  );
  assert.deepEqual(resolveIssueType({ labels: [], title: '[FR] add thing' }), {
    type: 'Task',
    signal: 'title-prefix',
    confident: true,
  });
});

test('no signal defaults to Task and is flagged unconfident', () => {
  // OPIK-6872: a feature request the old flow silently filed as a Bug.
  const r = resolveIssueType({
    labels: ['JC'],
    title: 'Able to view the full agent loop run in a single trace',
  });
  assert.deepEqual(r, { type: 'Task', signal: 'default', confident: false });
});

test('resolveIssueType tolerates missing input', () => {
  assert.equal(resolveIssueType().type, 'Task');
  assert.equal(resolveIssueType({}).confident, false);
});

test('parseTicketKey pulls the ticket out of a comment body', () => {
  assert.equal(
    parseTicketKey(
      '**Jira Ticket Created:** [OPIK-6835](https://comet-ml.atlassian.net/browse/OPIK-6835)',
    ),
    'OPIK-6835',
  );
  assert.equal(parseTicketKey('no key here'), null);
  assert.equal(parseTicketKey(null), null);
});

test('parseTicketKey ignores keys from other Jira projects', () => {
  // Regression: the replay matched YT-51/YT-44/YT-39 (a different project) as
  // if they were the synced ticket.
  assert.equal(
    parseTicketKey(
      '**Jira Ticket Created:** [YT-51](https://comet-ml.atlassian.net/browse/YT-51)',
    ),
    null,
  );
  assert.equal(
    parseTicketKey('linked YT-51 but created OPIK-1234'),
    'OPIK-1234',
  );
});

test('parseTicketKeys returns every distinct key in order', () => {
  assert.deepEqual(
    parseTicketKeys('OPIK-51 then OPIK-52 then OPIK-51 again'),
    ['OPIK-51', 'OPIK-52'],
  );
  assert.deepEqual(parseTicketKeys(''), []);
});

// --- dedupe ---------------------------------------------------------------

const ISSUE = {
  repoId: 12345,
  issueNumber: 7760,
  issueUrl: 'https://github.com/comet-ml/opik/issues/7760',
};

test('tier 1: remote link short-circuits before any other lookup', async () => {
  let jqlCalls = 0;
  let commentCalls = 0;
  const hit = await findExistingTicket(ISSUE, {
    findByRemoteLink: async (id) =>
      id === 'github-issue-12345-7760' ? 'OPIK-7825' : null,
    findByJql: async () => {
      jqlCalls += 1;
      return null;
    },
    listIssueComments: async () => {
      commentCalls += 1;
      return [];
    },
  });
  assert.deepEqual(hit, { key: 'OPIK-7825', via: 'remote-link' });
  assert.equal(jqlCalls, 0, 'must not fall through after a remote-link hit');
  assert.equal(commentCalls, 0);
});

test('tier 2: falls back to JQL for legacy tickets with no remote link', async () => {
  let seenJql = '';
  const hit = await findExistingTicket(ISSUE, {
    findByRemoteLink: async () => null,
    findByJql: async (jql) => {
      seenJql = jql;
      return 'OPIK-7825';
    },
    listIssueComments: async () => [],
  });
  assert.deepEqual(hit, { key: 'OPIK-7825', via: 'jql-description' });
  // Must search on the URL path, protocol-stripped and quoted for phrase match.
  assert.match(seenJql, /project = OPIK/);
  assert.match(seenJql, /github\.com\/comet-ml\/opik\/issues\/7760/);
  assert.ok(!seenJql.includes('https://'), 'protocol should be stripped');
});

test('tier 3: recovers an orphaned ticket from the GitHub comment', async () => {
  // The OPIK-6834 case: created, never linked, never found by search.
  const hit = await findExistingTicket(ISSUE, {
    findByRemoteLink: async () => null,
    findByJql: async () => null,
    listIssueComments: async () => [
      'Hi, I am Scout. Let me look into this.',
      '**Jira Ticket Created:** [OPIK-6834](https://comet-ml.atlassian.net/browse/OPIK-6834)',
    ],
  });
  assert.deepEqual(hit, { key: 'OPIK-6834', via: 'github-comment' });
});

test('tier 3 surfaces multiple tickets instead of hiding them', async () => {
  // Regression: GitHub issue #4348 carries four marker comments
  // (YT-51/52/53/54 in reality). Returning on the first match reported a clean
  // single hit and concealed the duplication.
  const hit = await findExistingTicket(ISSUE, {
    findByRemoteLink: async () => null,
    findByJql: async () => null,
    listIssueComments: async () => [
      '**Jira Ticket Created:** [OPIK-51](https://x/browse/OPIK-51)',
      '**Jira Ticket Created:** [OPIK-52](https://x/browse/OPIK-52)',
      '**Jira Ticket Created:** [OPIK-53](https://x/browse/OPIK-53)',
    ],
  });
  assert.equal(hit.key, 'OPIK-51');
  assert.equal(hit.via, 'github-comment');
  assert.deepEqual(hit.duplicates, ['OPIK-51', 'OPIK-52', 'OPIK-53']);
});

test('tier 2 duplicate reports are normalised onto the result', async () => {
  const hit = await findExistingTicket(ISSUE, {
    findByRemoteLink: async () => null,
    findByJql: async () => ({
      key: 'OPIK-6834',
      duplicates: ['OPIK-6834', 'OPIK-6835'],
    }),
    listIssueComments: async () => [],
  });
  assert.equal(hit.key, 'OPIK-6834');
  assert.equal(hit.via, 'jql-description');
  assert.deepEqual(hit.duplicates, ['OPIK-6834', 'OPIK-6835']);
});

test('tier 2 related-only hits are not treated as a match', async () => {
  // Engineers cite the GitHub URL in follow-up tickets. Those reference the
  // issue but were never created by the sync, so they must not read as a
  // duplicate — 27 of 33 reported "duplicates" in the first replay were these.
  const hit = await findExistingTicket(ISSUE, {
    findByRemoteLink: async () => null,
    findByJql: async () => ({ key: 'OPIK-4121', related: ['OPIK-4121'] }),
    listIssueComments: async () => [],
  });
  assert.equal(hit.key, null, 'a citing ticket is not a synced ticket');
  assert.deepEqual(hit.related, ['OPIK-4121']);
  assert.ok(!('duplicates' in hit));
});

test('a related-only tier 2 hit still falls through to tier 3', async () => {
  // Regression: returning early on `related` skipped the comment check, so
  // issues with both a citing ticket and a real marker comment (#5551) were
  // reported as WOULD-CREATE despite having been synced.
  let commentsRead = false;
  const hit = await findExistingTicket(ISSUE, {
    findByRemoteLink: async () => null,
    findByJql: async () => ({ key: 'OPIK-6426', related: ['OPIK-6426'] }),
    listIssueComments: async () => {
      commentsRead = true;
      return [
        '**Jira Ticket Created:** [OPIK-5551](https://x/browse/OPIK-5551)',
      ];
    },
  });
  assert.ok(commentsRead, 'tier 3 must still run');
  assert.equal(hit.key, 'OPIK-5551');
  assert.equal(hit.via, 'github-comment');
  // The citing ticket is still reported alongside the real match.
  assert.deepEqual(hit.related, ['OPIK-6426']);
});

test('a single-key result carries no duplicates field', async () => {
  const hit = await findExistingTicket(ISSUE, {
    findByRemoteLink: async () => null,
    findByJql: async () => 'OPIK-7825',
    listIssueComments: async () => [],
  });
  assert.equal(hit.key, 'OPIK-7825');
  assert.ok(!('duplicates' in hit));
});

test('a comment with the marker but no parseable key does not count as a hit', async () => {
  const hit = await findExistingTicket(ISSUE, {
    findByRemoteLink: async () => null,
    findByJql: async () => null,
    listIssueComments: async () => ['Jira Ticket Created: (pending)'],
  });
  assert.equal(hit, null);
});

test('no match anywhere returns null so the caller creates', async () => {
  const hit = await findExistingTicket(ISSUE, {
    findByRemoteLink: async () => null,
    findByJql: async () => null,
    listIssueComments: async () => ['unrelated chatter'],
  });
  assert.equal(hit, null);
});

// --- markdown -------------------------------------------------------------

test('fenced code becomes a Jira code macro with its language', () => {
  const wiki = markdownToWiki('before\n```python\nx = 1\n```\nafter');
  assert.match(wiki, /\{code:python\}\nx = 1\n\{code\}/);
  assert.match(wiki, /before/);
  assert.match(wiki, /after/);
});

test('inline rules do not corrupt fenced code contents', () => {
  // Markdown-ish syntax inside a code block must survive verbatim.
  const wiki = markdownToWiki('```\n- [x] **not bold** `nested`\n```');
  assert.match(wiki, /- \[x\] \*\*not bold\*\* `nested`/);
});

test('headings, emphasis, inline code and links convert', () => {
  assert.match(markdownToWiki('### Describe the problem'), /^h3\. Describe/m);
  assert.match(markdownToWiki('##### deep'), /^h4\. deep/m);
  assert.equal(markdownToWiki('**bold**'), '*bold*');
  assert.equal(markdownToWiki('`code`'), '{{code}}');
  assert.equal(
    markdownToWiki('[text](https://example.com)'),
    '[text|https://example.com]',
  );
});

test('task list checkboxes keep their state', () => {
  const wiki = markdownToWiki('- [x] Opik UI\n- [ ] Opik Server');
  assert.match(wiki, /\* \(\/\) Opik UI/);
  assert.match(wiki, /\* \(x\) Opik Server/);
});

test('nested list indentation is preserved', () => {
  const wiki = markdownToWiki('- top\n  - nested');
  assert.match(wiki, /^\* top$/m);
  assert.match(wiki, /^ {2}\* nested$/m);
});

test('buildDescription ends with a bare GitHub URL for the JQL fallback', () => {
  const d = buildDescription({
    body: '### Problem\n\nIt broke.',
    issueUrl: 'https://github.com/comet-ml/opik/issues/7760',
  });
  assert.match(d, /^\*Synced from GitHub issue body\*/);
  assert.match(d, /h3\. Problem/);
  assert.ok(
    d.trimEnd().endsWith('GitHub: https://github.com/comet-ml/opik/issues/7760'),
    'the URL line must be last and unadorned',
  );
});

test('buildDescription handles an empty issue body', () => {
  const d = buildDescription({
    body: '',
    issueUrl: 'https://github.com/comet-ml/opik/issues/1',
  });
  assert.match(d, /_No description provided\._/);
  assert.match(d, /GitHub: https/);
});
