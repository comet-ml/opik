#!/usr/bin/env node
/**
 * Generates `public/release-notes.json` — the in-product release notes manifest.
 *
 * Why the manifest is generated and committed rather than fetched at runtime:
 *
 *  - The frontend Docker build context is `apps/opik-frontend` only, so the changelog
 *    sources under `apps/opik-documentation` are NOT reachable at image-build time.
 *  - A self-hosted or STSaaS install may have no egress, so the product cannot fetch
 *    notes from comet.com. Shipping the manifest inside the image is what makes the
 *    panel work air-gapped.
 *
 * It lives in `public/`, so it is served as a static same-origin asset and fetched on
 * demand when the panel opens — it never enters the JS bundle or the app-boot path.
 *
 * Entry -> version attribution is DERIVED, never hand-maintained: a changelog entry
 * dated D first shipped in the earliest release published on or after D. The release
 * dates come from `release-index.json` (refreshed by `fetch-release-index.mjs`). An
 * entry newer than every published release is attributed to the version `main` is
 * about to cut, read from `version.txt`: the release workflow builds the frontend
 * image from the tag *before* the GitHub release exists, so without this the release
 * that ships an entry could never show it. An entry may override either with an
 * explicit `version:` in its frontmatter.
 *
 * Usage:
 *   node scripts/release-notes/generate-manifest.mjs           # write the manifest
 *   node scripts/release-notes/generate-manifest.mjs --check   # fail if it is stale (CI)
 */
import { readFileSync, readdirSync, writeFileSync, existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTEND_ROOT = resolve(HERE, "../..");
const REPO_ROOT = resolve(FRONTEND_ROOT, "../..");
const CHANGELOG_DIR = resolve(
  REPO_ROOT,
  "apps/opik-documentation/documentation/fern/docs-v2/changelog",
);
const VERSION_FILE = resolve(REPO_ROOT, "version.txt");
const RELEASE_INDEX = resolve(HERE, "release-index.json");
const OUTPUT = resolve(FRONTEND_ROOT, "public/release-notes.json");

/** Where the dated entries are published; `canonical-url` frontmatter overrides it. */
const DOCS_BASE_URL = "https://www.comet.com/docs/opik";

const ENTRY_FILE = /^(\d{4}-\d{2}-\d{2})\.mdx$/;
const SEMVER = /^\d+\.\d+\.\d+$/;

/**
 * Splits `---\nkey: value\n---\nbody` into its frontmatter map and body. Hand-rolled
 * rather than pulling in a YAML dependency: changelog frontmatter is flat `key: value`
 * pairs only, and this script must stay dependency-free so it can run before `npm ci`.
 */
export const parseFrontmatter = (source) => {
  const normalized = source.replace(/\r\n/g, "\n");
  if (!normalized.startsWith("---\n")) {
    return { frontmatter: {}, body: normalized.trim() };
  }

  const end = normalized.indexOf("\n---", 3);
  if (end === -1) {
    return { frontmatter: {}, body: normalized.trim() };
  }

  const frontmatter = {};
  for (const line of normalized.slice(4, end).split("\n")) {
    const separator = line.indexOf(":");
    if (separator === -1) continue;
    frontmatter[line.slice(0, separator).trim()] = line
      .slice(separator + 1)
      .trim()
      .replace(/^["']|["']$/g, "");
  }

  return {
    frontmatter,
    body: normalized.slice(normalized.indexOf("\n", end + 1) + 1).trim(),
  };
};

/**
 * Turns a docs-site MDX body into markdown the product can render on its own.
 *
 *  - `<Frame>` blocks, bare `<img>` / `<video>` tags and markdown images are dropped. Their
 *    sources are `/img/...` paths that exist only in the docs-site build, and an air-gapped
 *    install has no egress to fetch them from anywhere; a broken image box is worse than no
 *    image, and an image that does load would put comet.com in the render path.
 *  - Site-relative links (`](/tracing/...)`) would resolve against the product's own origin,
 *    where they 404, so they are pointed at the docs site. A link is something the reader
 *    chooses to follow, so it does not put comet.com in the render path.
 */
export const prepareBody = (body) =>
  body
    .replace(/<Frame\b[^>]*>[\s\S]*?<\/Frame>/g, "")
    .replace(/<(?:img|video)\b[^>]*?\/?>(?:\s*<\/(?:img|video)>)?/g, "")
    .replace(/!\[[^\]]*\]\([^)]*\)/g, "")
    .replace(/\]\((\/[^)\s]*)\)/g, (_, path) => `](${DOCS_BASE_URL}${path})`)
    .replace(/\n{3,}/g, "\n\n")
    .trim();

/**
 * The release that first contained the notes dated `entryDate`: the earliest release
 * published at or after the start of that day (UTC).
 *
 * An entry newer than every published release is attributed to `upcomingVersion` (what
 * `version.txt` holds on `main`: the next version the release workflow will cut) with no
 * `released_at`, since it has not shipped yet. Without an upcoming version it gets `null`,
 * which the UI renders as "Unversioned" rather than guessing.
 *
 * `releases` must be sorted ascending by `released_at`.
 */
export const resolveVersionForDate = (releases, entryDate, upcomingVersion) => {
  const dayStart = `${entryDate}T00:00:00Z`;
  const published = releases.find((release) => release.released_at >= dayStart);
  if (published) return published;

  return upcomingVersion
    ? { version: upcomingVersion, released_at: null }
    : null;
};

const readUpcomingVersion = () => {
  if (!existsSync(VERSION_FILE)) return null;
  const version = readFileSync(VERSION_FILE, "utf8").trim();
  // `latest` or a non-release string would poison the attribution of every new entry.
  return SEMVER.test(version) ? version : null;
};

const readEntries = () => {
  if (!existsSync(CHANGELOG_DIR)) {
    console.error(
      `Changelog directory not found: ${CHANGELOG_DIR}\n` +
        "This script must run from a full repo checkout, not from the frontend Docker build context.",
    );
    process.exit(1);
  }

  const releases = JSON.parse(readFileSync(RELEASE_INDEX, "utf8"));
  const upcomingVersion = readUpcomingVersion();

  return readdirSync(CHANGELOG_DIR)
    .map((file) => ({ file, match: ENTRY_FILE.exec(file) }))
    .filter(({ match }) => match !== null)
    .map(({ file, match }) => {
      const date = match[1];
      const { frontmatter, body } = parseFrontmatter(
        readFileSync(join(CHANGELOG_DIR, file), "utf8"),
      );

      // An explicit `version:` in frontmatter wins over the derived attribution, so an
      // entry can be pinned when the date heuristic lands it a release early or late.
      const derived = resolveVersionForDate(releases, date, upcomingVersion);
      const pinned = frontmatter.version
        ? (releases.find((release) => release.version === frontmatter.version) ??
          (frontmatter.version === upcomingVersion
            ? { version: upcomingVersion, released_at: null }
            : null))
        : null;

      if (frontmatter.version && !pinned) {
        console.error(
          `${file}: frontmatter pins version '${frontmatter.version}', which is neither in release-index.json nor version.txt.`,
        );
        process.exit(1);
      }

      const release = pinned ?? derived;

      return {
        date,
        version: release?.version ?? null,
        released_at: release?.released_at ?? null,
        url: frontmatter["canonical-url"] ?? `${DOCS_BASE_URL}/changelog/${date}`,
        body: prepareBody(body),
      };
    })
    .sort((a, b) => b.date.localeCompare(a.date));
};

const entries = readEntries();
const manifest = `${JSON.stringify({ entries }, null, 2)}\n`;

if (process.argv.includes("--check")) {
  const current = existsSync(OUTPUT) ? readFileSync(OUTPUT, "utf8") : "";
  if (current !== manifest) {
    console.error(
      "public/release-notes.json is out of date.\n" +
        "Run `npm run release-notes` in apps/opik-frontend and commit the result.",
    );
    process.exit(1);
  }
  console.log(`public/release-notes.json is up to date (${entries.length} entries).`);
} else {
  writeFileSync(OUTPUT, manifest);
  const published = entries.filter((entry) => entry.released_at !== null).length;
  const pending = entries.filter(
    (entry) => entry.version !== null && entry.released_at === null,
  ).length;
  console.log(
    `Wrote ${entries.length} entries to ${OUTPUT} (${published} attributed to a published release, ` +
      `${pending} to the upcoming ${entries.find((e) => e.released_at === null && e.version)?.version ?? "version"}, ` +
      `${entries.length - published - pending} unversioned).`,
  );
}
