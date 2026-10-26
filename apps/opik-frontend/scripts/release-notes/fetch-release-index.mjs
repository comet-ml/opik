#!/usr/bin/env node
/**
 * Refreshes `release-index.json` — the version -> release-date map used to attribute
 * changelog entries to the release that first contained them.
 *
 * This is the ONLY step that needs network access, and it is deliberately separate from
 * manifest generation: `generate-manifest.mjs` must stay offline and deterministic so it
 * can run in any build, including air-gapped ones.
 *
 * Usage: node scripts/release-notes/fetch-release-index.mjs
 * Requires the `gh` CLI, authenticated against comet-ml/opik.
 */
import { execFileSync } from "node:child_process";
import { writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const OUTPUT = resolve(HERE, "release-index.json");
const SEMVER = /^\d+\.\d+\.\d+$/;

const raw = execFileSync(
  "gh",
  [
    "release",
    "list",
    "--repo",
    "comet-ml/opik",
    "--limit",
    "1000",
    "--json",
    "tagName,publishedAt",
  ],
  { encoding: "utf8", maxBuffer: 32 * 1024 * 1024 },
);

const releases = JSON.parse(raw)
  // Drafts, SDK tags (`opik-openai-v1.0.2`) and ad-hoc build tags (`2.1.9-7244-merge-2398`)
  // are not deployable Opik versions and must not become release-note anchors.
  .filter((release) => SEMVER.test(release.tagName) && release.publishedAt > "0001")
  .map((release) => ({ version: release.tagName, released_at: release.publishedAt }))
  .sort((a, b) => a.released_at.localeCompare(b.released_at));

if (releases.length === 0) {
  console.error("No semver releases returned by `gh release list` — refusing to write an empty index.");
  process.exit(1);
}

writeFileSync(OUTPUT, `${JSON.stringify(releases, null, 2)}\n`);

console.log(
  `Wrote ${releases.length} releases to ${OUTPUT} (${releases[0].version} .. ${releases[releases.length - 1].version})`,
);
