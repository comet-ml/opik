export interface ReleaseNoteEntry {
  /** Publication date of the changelog entry, `YYYY-MM-DD`. */
  date: string;
  /**
   * The Opik release that first contained this entry, or `null` for entries predating the
   * release index. Derived at build time — see `scripts/release-notes/generate-manifest.mjs`.
   */
  version: string | null;
  /**
   * When that release was published, or `null` while the entry is only attributed to the
   * upcoming version from `version.txt` and has not shipped yet.
   */
  released_at: string | null;
  url: string | null;
  /** Markdown body of the entry. */
  body: string;
}

export interface ReleaseNotesManifest {
  entries: ReleaseNoteEntry[];
}

export interface DeploymentVersion {
  version: string;
  first_seen_at: string;
}

export interface DeploymentVersionHistory {
  current_version: string;
  /** Versions this install has run, newest first. */
  versions: DeploymentVersion[];
}
