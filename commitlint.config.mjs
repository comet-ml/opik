// Single source of truth for Opik's commit message and PR title format.
// Runs locally as the commit-msg pre-commit hook and in CI from the PR Linter
// workflow (PR title + every commit in the PR). See CONTRIBUTING.md.
//
// The rules encode the convention the PR linter enforced before commitlint, and
// nothing stricter: anything that passed then passes now. Tightening (an error-level
// required type, header length) is a follow-up, not a side effect of this file.

const TICKET_PREFIX = /^\[(OPIK-\d+|DND-\d+|DEV-\d+|CUST-\d+|issue-\d+|NA)\].+$/;
const CONVENTIONAL = /^\w+(\([^)]*\))?!?: .+$/;
const REVISION = /^Revision \d+: .+$/;

// Set by the PR Linter when it lints the PR title. A PR title becomes the squash
// commit on main, so it must carry the ticket prefix; branch commits may also use
// the follow-up forms.
const isPrTitle = process.env.OPIK_COMMITLINT_PR_TITLE === "1";

const FORMS = isPrTitle
  ? "  [<TICKET>] [COMPONENT] <type>: <description>"
  : [
      "  [<TICKET>] [COMPONENT] <type>: <description>   (first commit, becomes the PR title)",
      "  <type>(<scope>): <description>                 (follow-up commits)",
      "  Revision <N>: <description>                    (last resort)",
    ].join("\n");

const HELP = [
  `expected one of:\n${FORMS}`,
  "<TICKET> is OPIK-1234, DND-1234, DEV-1234, CUST-1234, issue-1234 (GitHub issue) or NA.",
  "Example: [OPIK-1234] [BE] feat: add create trace endpoint",
].join("\n");

export default {
  helpUrl: "https://github.com/comet-ml/opik/blob/main/CONTRIBUTING.md#commit-and-pr-conventions",
  parserPreset: {
    parserOpts: {
      // Skip any leading [TICKET] / [COMPONENT] tags, then read an optional
      // `type(scope)!:` so the type rules below can see it in either form.
      headerPattern: /^(?:\[[^\]]*\]\s*)*(?:(\w+)(?:\(([^)]*)\))?!?:\s+)?(.*)$/,
      headerCorrespondence: ["type", "scope", "subject"],
    },
  },
  plugins: [
    {
      rules: {
        "opik-header-format": ({ header }) => {
          const forms = isPrTitle ? [TICKET_PREFIX] : [TICKET_PREFIX, CONVENTIONAL, REVISION];
          return [forms.some((re) => re.test(header ?? "")), HELP];
        },
      },
    },
  ],
  rules: {
    "opik-header-format": [2, "always"],
    // Warnings only: plenty of merged titles have no type ("[NA] [BE] Update model
    // prices file"). Promote to errors once the convention is enforced end to end.
    "type-empty": [1, "never"],
    "type-enum": [
      1,
      "always",
      ["build", "chore", "ci", "docs", "feat", "fix", "perf", "refactor", "revert", "style", "test"],
    ],
  },
  // Bot-generated commits that can land inside a human PR or on main. Bot-authored
  // PRs (Dependabot, Renovate) are skipped by the PR Linter before commitlint runs.
  // Merge, revert, fixup! and squash! commits are covered by commitlint's default ignores.
  ignores: [
    (message) => /^Update versions to \S+ and bump base version to \S+/.test(message),
    (message) => message.startsWith("Optimised images with calibre/image-actions"),
    (message) => /^Bump \S+ from \S+ to \S+/.test(message),
  ],
};
