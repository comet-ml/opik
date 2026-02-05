import { useQuery } from "@tanstack/react-query";
import axios from "axios";

const CONFIG_SERVICE_URL =
  import.meta.env.VITE_CONFIG_SERVICE_URL || "http://localhost:5050";

export type DiffLineType = "addition" | "deletion" | "context";

export type DiffLine = {
  type: DiffLineType;
  content: string;
  old_line: number | null;
  new_line: number | null;
};

export type VersionChange = {
  type: "added" | "removed" | "modified";
  prompt_name: string;
  diff: DiffLine[] | null;
  content?: unknown;
};

export type VersionDiff = {
  version_number: number;
  previous_version: number | null;
  changes: VersionChange[];
  summary: {
    added: number;
    removed: number;
    modified: number;
  };
};

type UseVersionDiffParams = {
  blueprintId: string;
  versionNumber: number;
  enabled?: boolean;
};

const getVersionDiff = async (
  blueprintId: string,
  versionNumber: number,
): Promise<VersionDiff> => {
  const { data } = await axios.get(
    `${CONFIG_SERVICE_URL}/v1/blueprints/${blueprintId}/versions/${versionNumber}/diff`,
  );
  return data;
};

export default function useVersionDiff({
  blueprintId,
  versionNumber,
  enabled = true,
}: UseVersionDiffParams) {
  return useQuery({
    queryKey: ["version-diff", blueprintId, versionNumber],
    queryFn: () => getVersionDiff(blueprintId, versionNumber),
    enabled: enabled && !!blueprintId && versionNumber > 0,
    staleTime: Infinity,
  });
}
