// Shared logic for the unaddressed-review-comments labels (OPIK-8251).
//
// Loaded by two callers via require():
//   - the review-comments-labeler job in labeler.yml (one PR, event-driven)
//   - unaddressed_comments_sweep.yml (many PRs, scheduled)
//
// It lives in one file so the bot allowlist and the "what counts as unaddressed"
// rule have a single definition; a divergence between the event path and the
// sweep would make the label flap as the two disagreed.

// Review bots whose comments should be attributed to the bot label. Add a new
// review bot here and both callers pick it up.
//
// baz-reviewer has no "[bot]" suffix, so the suffix heuristic below does not
// catch it — hence the explicit list. Keep both: the suffix covers GitHub Apps
// we never enumerate (dependabot[bot], copilot-bot[bot], ...) without needing a
// repo change each time one starts reviewing.
const REVIEW_BOTS = new Set(["baz-reviewer", "github-advanced-security"]);

const BOT_LABEL = "💬 unaddressed: bot";
const HUMAN_LABEL = "💬 unaddressed: human";

// Uniform charcoal chip, matching the size labels, so this metadata family stays
// recessive against semantic labels (bug, Frontend, ...) on a busy PR list.
const LABEL_COLOR = "2d3139";

const MANAGED_LABELS = [
  { name: BOT_LABEL, color: LABEL_COLOR, description: "Review comments from a bot reviewer that nobody has replied to or resolved" },
  { name: HUMAN_LABEL, color: LABEL_COLOR, description: "Review comments from a human reviewer that nobody has replied to or resolved" },
];

const isBot = (login) =>
  !!login && (REVIEW_BOTS.has(login) || login.endsWith("[bot]"));

// One query per PR. Comment authors are fetched inline rather than per-thread so
// a PR with N threads costs 1 request instead of N+1; PRs here reach 30 threads.
//
// A thread with more than 100 comments would have its tail truncated, which can
// only cause a false "unaddressed" (a reply past #100 goes unseen) — the safe
// direction to fail, and not a shape that occurs in practice.
const THREADS_QUERY = /* GraphQL */ `
  query ($owner: String!, $repo: String!, $number: Int!, $cursor: String) {
    repository(owner: $owner, name: $repo) {
      pullRequest(number: $number) {
        reviewThreads(first: 100, after: $cursor) {
          pageInfo {
            hasNextPage
            endCursor
          }
          nodes {
            isResolved
            isOutdated
            comments(first: 100) {
              nodes {
                author {
                  login
                }
              }
            }
          }
        }
      }
    }
  }
`;

async function fetchReviewThreads({ github, owner, repo, number }) {
  const threads = [];
  let cursor = null;
  for (;;) {
    const result = await github.graphql(THREADS_QUERY, { owner, repo, number, cursor });
    const page = result.repository.pullRequest.reviewThreads;
    threads.push(...page.nodes);
    if (!page.pageInfo.hasNextPage) return threads;
    cursor = page.pageInfo.endCursor;
  }
}

// A thread is unaddressed when nobody engaged with it and it was never resolved.
//
// Resolution alone is not a usable signal here: clicking "Resolve conversation"
// is an inconsistent habit, so keying on it flags PRs whose feedback was fully
// discussed alongside PRs whose feedback was ignored. Requiring a reply from
// someone other than the thread's opener separates those without asking anyone
// to change how they work.
//
// The "other than the opener" part carries real weight — review bots routinely
// follow up on their own comments, so a plain comment count treats a
// bot-talking-to-itself thread as engaged and silently clears the label on a
// thread nobody ever answered.
function classifyThreads(threads) {
  let bot = 0;
  let human = 0;

  for (const thread of threads) {
    if (thread.isResolved) continue;
    // The commented lines have since been rewritten, so the point is likely moot.
    if (thread.isOutdated) continue;

    const authors = thread.comments.nodes.map((c) => c.author?.login).filter(Boolean);
    const opener = authors[0];
    // A thread whose author is deleted or otherwise unreadable can't be attributed.
    if (!opener) continue;
    if (authors.some((login) => login !== opener)) continue;

    if (isBot(opener)) bot += 1;
    else human += 1;
  }

  return { bot, human };
}

function desiredLabels({ bot, human }) {
  const labels = [];
  if (bot > 0) labels.push(BOT_LABEL);
  if (human > 0) labels.push(HUMAN_LABEL);
  return labels;
}

// Create the managed labels on demand, and keep their color/description
// authoritative regardless of how they were first created, so the workflow
// needs no manual label setup in a fresh fork.
async function ensureLabels({ github, core, owner, repo }) {
  for (const label of MANAGED_LABELS) {
    try {
      const { data: existing } = await github.rest.issues.getLabel({ owner, repo, name: label.name });
      if (existing.color !== label.color || existing.description !== label.description) {
        await github.rest.issues.updateLabel({
          owner,
          repo,
          name: label.name,
          color: label.color,
          description: label.description,
        });
        core.info(`Updated label: ${label.name}`);
      }
    } catch (err) {
      if (err.status !== 404) throw err;
      await github.rest.issues.createLabel({
        owner,
        repo,
        name: label.name,
        color: label.color,
        description: label.description,
      });
      core.info(`Created label: ${label.name}`);
    }
  }
}

// Reconcile with an incremental delta over MANAGED_LABELS only: remove a managed
// label that shouldn't be there, add a wanted one that's missing, and never touch
// anything else. Unlike setLabels, this cannot clobber a path label, a size
// label, or a label someone applied by hand.
async function reconcileLabels({ github, core, owner, repo, number, wanted }) {
  const live = await github.paginate(github.rest.issues.listLabelsOnIssue, {
    owner,
    repo,
    issue_number: number,
    per_page: 100,
  });
  const current = live.map((l) => l.name);
  const managed = MANAGED_LABELS.map((l) => l.name);

  for (const stale of current.filter((name) => managed.includes(name) && !wanted.includes(name))) {
    try {
      await github.rest.issues.removeLabel({ owner, repo, issue_number: number, name: stale });
      core.info(`PR #${number}: removed ${stale}`);
    } catch (err) {
      // Someone may have removed it between the read and now; a 404 is benign.
      if (err.status !== 404) throw err;
    }
  }

  const missing = wanted.filter((name) => !current.includes(name));
  if (missing.length > 0) {
    await github.rest.issues.addLabels({ owner, repo, issue_number: number, labels: missing });
    core.info(`PR #${number}: added ${missing.join(", ")}`);
  }
}

// Full pass for a single PR. Returns the label set applied, for logging.
async function labelPullRequest({ github, core, owner, repo, number }) {
  const threads = await fetchReviewThreads({ github, owner, repo, number });
  const counts = classifyThreads(threads);
  const wanted = desiredLabels(counts);

  core.info(
    `PR #${number}: ${threads.length} threads, unaddressed bot=${counts.bot} human=${counts.human} -> [${wanted.join(", ") || "none"}]`
  );

  await reconcileLabels({ github, core, owner, repo, number, wanted });
  return wanted;
}

module.exports = {
  BOT_LABEL,
  HUMAN_LABEL,
  MANAGED_LABELS,
  REVIEW_BOTS,
  isBot,
  classifyThreads,
  desiredLabels,
  ensureLabels,
  fetchReviewThreads,
  labelPullRequest,
  reconcileLabels,
};
