import { ReleaseNoteEntry } from "@/types/release-notes";

/**
 * The version a non-released build reports (`OPIK_VERSION` defaults to `latest` in config.yml).
 * Such a build tracks `main`, so it has everything — it is not a point on the version line.
 */
export const DEVELOPMENT_VERSION = "latest";

const SEMVER = /^(\d+)\.(\d+)\.(\d+)$/;

export const isDevelopmentVersion = (version?: string | null): boolean =>
  !version || version === DEVELOPMENT_VERSION;

const parseVersion = (version?: string | null): number[] | null => {
  const match = version ? SEMVER.exec(version) : null;
  return match ? [Number(match[1]), Number(match[2]), Number(match[3])] : null;
};

/**
 * Orders two Opik versions, or returns `null` when either is not a plain `x.y.z` — callers must
 * decide what an uncomparable version means rather than silently treating it as older or newer.
 */
export const compareVersions = (
  left?: string | null,
  right?: string | null,
): number | null => {
  const a = parseVersion(left);
  const b = parseVersion(right);
  if (!a || !b) return null;

  for (let i = 0; i < 3; i += 1) {
    if (a[i] !== b[i]) return a[i] - b[i];
  }
  return 0;
};

/**
 * The notes a deployment on `currentVersion` is entitled to see: everything up to and including
 * its own release.
 *
 * Entries from later releases are withheld on purpose — showing a self-hosted install features it
 * does not have is the failure mode this whole surface exists to fix. A development build sees
 * everything, since it tracks `main`.
 */
export const entriesForVersion = (
  entries: ReleaseNoteEntry[],
  currentVersion?: string | null,
): ReleaseNoteEntry[] => {
  if (isDevelopmentVersion(currentVersion)) return entries;

  return entries.filter((entry) => {
    const comparison = compareVersions(entry.version, currentVersion);
    return comparison !== null && comparison <= 0;
  });
};

/**
 * The notes an upgrade brought: entries released after `fromExclusive` and no later than
 * `toInclusive`. With no `fromExclusive` (the first version an install ever ran) this is
 * everything up to `toInclusive`.
 */
export const entriesInRange = (
  entries: ReleaseNoteEntry[],
  fromExclusive: string | null,
  toInclusive: string,
): ReleaseNoteEntry[] =>
  entriesForVersion(entries, toInclusive).filter((entry) => {
    if (!fromExclusive) return true;
    const comparison = compareVersions(entry.version, fromExclusive);
    return comparison !== null && comparison > 0;
  });
