import { describe, expect, it } from "vitest";
import {
  compareVersions,
  entriesForVersion,
  entriesInRange,
  isDevelopmentVersion,
} from "./releaseNotes";
import { ReleaseNoteEntry } from "@/types/release-notes";

const entry = (date: string, version: string | null): ReleaseNoteEntry => ({
  date,
  version,
  released_at: version ? `${date}T00:00:00Z` : null,
  url: null,
  body: `notes for ${version ?? "unversioned"}`,
});

const ENTRIES = [
  entry("2026-07-06", "2.2.10"),
  entry("2026-06-23", "2.1.16"),
  entry("2026-06-16", "2.1.9"),
  entry("2026-06-09", "1.9.30"),
  entry("2024-01-01", null),
];

describe("compareVersions", () => {
  it("orders by major, then minor, then patch", () => {
    expect(compareVersions("2.1.9", "2.1.16")).toBeLessThan(0);
    expect(compareVersions("2.2.0", "2.1.99")).toBeGreaterThan(0);
    expect(compareVersions("10.0.0", "9.9.9")).toBeGreaterThan(0);
    expect(compareVersions("2.1.9", "2.1.9")).toBe(0);
  });

  it("compares numerically, not lexically", () => {
    // The bug this guards: "2.1.9" > "2.1.16" as strings, which would hide a release's notes.
    expect(compareVersions("2.1.9", "2.1.16")).toBeLessThan(0);
  });

  it("returns null when either side is not a plain x.y.z", () => {
    expect(compareVersions("latest", "2.1.0")).toBeNull();
    expect(compareVersions("2.1.0", null)).toBeNull();
    expect(compareVersions("2.1.0-rc1", "2.1.0")).toBeNull();
  });
});

describe("isDevelopmentVersion", () => {
  it("treats the config.yml default and a missing version as development builds", () => {
    expect(isDevelopmentVersion("latest")).toBe(true);
    expect(isDevelopmentVersion(undefined)).toBe(true);
    expect(isDevelopmentVersion("2.1.16")).toBe(false);
  });
});

describe("entriesForVersion", () => {
  it("includes the deployed version's own notes", () => {
    expect(
      entriesForVersion(ENTRIES, "2.1.16").map((e) => e.version),
    ).toContain("2.1.16");
  });

  it("withholds notes from releases newer than the deployed version", () => {
    expect(
      entriesForVersion(ENTRIES, "2.1.16").map((e) => e.version),
    ).not.toContain("2.2.10");
  });

  it("drops entries with no version rather than guessing where they belong", () => {
    expect(
      entriesForVersion(ENTRIES, "2.2.10").map((e) => e.version),
    ).not.toContain(null);
  });

  it("shows everything on a development build, which tracks main", () => {
    expect(entriesForVersion(ENTRIES, "latest")).toHaveLength(ENTRIES.length);
  });
});

describe("entriesInRange", () => {
  it("returns what an upgrade brought, excluding the version upgraded from", () => {
    const versions = entriesInRange(ENTRIES, "1.9.30", "2.1.16").map(
      (e) => e.version,
    );

    expect(versions).toEqual(["2.1.16", "2.1.9"]);
  });

  it("returns everything up to the target for an install's first version", () => {
    expect(
      entriesInRange(ENTRIES, null, "2.1.9").map((e) => e.version),
    ).toEqual(["2.1.9", "1.9.30"]);
  });

  it("returns nothing when a release carried no user-facing changes", () => {
    expect(entriesInRange(ENTRIES, "2.1.16", "2.2.9")).toEqual([]);
  });
});
