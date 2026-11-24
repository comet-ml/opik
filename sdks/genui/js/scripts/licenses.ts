import {
  existsSync,
  readdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { basename, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

interface Metafile {
  outputs: Record<string, { inputs: Record<string, unknown> }>;
}

const root = fileURLToPath(new URL("..", import.meta.url));
const libraries = join(root, "dist", "libraries");
const metafilePath = join(libraries, "metafile-iife.json");
const metafile = JSON.parse(readFileSync(metafilePath, "utf8")) as Metafile;
const MARKER = "node_modules/";

const packageDir = (input: string): string | null => {
  const at = input.lastIndexOf(MARKER);
  if (at < 0) return null;
  const parts = input.slice(at + MARKER.length).split("/");
  const name = parts[0].startsWith("@") ? `${parts[0]}/${parts[1]}` : parts[0];
  return join(root, input.slice(0, at + MARKER.length), name);
};

interface Manifest {
  name: string;
  version: string;
  license?: string;
  dependencies?: Record<string, string>;
}

const manifestOf = (dir: string) =>
  JSON.parse(readFileSync(join(dir, "package.json"), "utf8")) as Manifest;

const resolveDependency = (from: string, name: string): string | null => {
  for (let dir = from; dir.startsWith(dirname(root)); dir = dirname(dir)) {
    const candidate = join(dir, "node_modules", name);
    if (existsSync(join(candidate, "package.json"))) return candidate;
  }
  return null;
};

const withDependencies = (bundled: string[]): string[] => {
  const seen = new Set(bundled);
  for (const dir of seen)
    for (const name of Object.keys(manifestOf(dir).dependencies ?? {})) {
      const dependency = resolveDependency(dir, name);
      if (dependency) seen.add(dependency);
    }
  return [...seen];
};

const licenseText = (dir: string): string => {
  const file = readdirSync(dir, { withFileTypes: true }).find(
    (entry) => entry.isFile() && /^(licen[sc]e|copying)/i.test(entry.name),
  );
  if (!file) throw new Error(`${dir} has no license file`);
  return readFileSync(join(dir, file.name), "utf8").trim();
};

for (const [output, { inputs }] of Object.entries(metafile.outputs)) {
  if (!output.endsWith(".js")) continue;
  const bundled = Object.keys(inputs)
    .map(packageDir)
    .filter((dir): dir is string => dir !== null);
  const sections = withDependencies([...new Set(bundled)])
    .map((dir) => ({ ...manifestOf(dir), text: licenseText(dir) }))
    .sort(
      (a, b) =>
        a.name.localeCompare(b.name) || a.version.localeCompare(b.version),
    )
    .map(
      ({ name, version, license, text }) =>
        `${name}@${version} (${license ?? "see below"})\n\n${text}\n`,
    );
  if (sections.length === 0) throw new Error(`${output} bundles no packages`);
  const target = join(
    dirname(join(root, output)),
    `${basename(output, ".js")}.licenses.txt`,
  );
  writeFileSync(target, sections.join(`\n${"-".repeat(72)}\n\n`));
  console.log(
    `wrote ${target.slice(root.length)} (${sections.length} packages)`,
  );
}

rmSync(metafilePath);
