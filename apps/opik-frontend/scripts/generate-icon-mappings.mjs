import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const FILE_KEY = "DQkbgEBm59YiQUzoxxZ0ON";
const FILE_URL = `https://www.figma.com/design/${FILE_KEY}/Opik-Design-System`;
const APP_ROOT = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const OUTPUT_PATH = path.join(APP_ROOT, "src/ui/icons.figma.tsx");

const token = process.env.FIGMA_ACCESS_TOKEN;
if (!token) {
  console.error(
    "FIGMA_ACCESS_TOKEN is not set. Add it to apps/opik-frontend/.env.",
  );
  process.exit(1);
}

const response = await fetch(
  `https://api.figma.com/v1/files/${FILE_KEY}/components`,
  {
    headers: { "X-Figma-Token": token },
  },
);

if (!response.ok) {
  const body = await response.text();
  console.error(
    `GET /v1/files/${FILE_KEY}/components failed: ${response.status} ${response.statusText}`,
  );
  console.error(body);
  if (response.status === 403) {
    console.error(
      "The FIGMA_ACCESS_TOKEN is missing the library_content:read scope required by this endpoint.",
    );
  }
  process.exit(1);
}

const { meta } = await response.json();

const toPascalCase = (kebab) =>
  kebab
    .split("-")
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join("");

const seen = new Set();
const matched = [];
const unmatched = [];

const lucide = await import("lucide-react");

for (const component of meta.components) {
  if (!/^icon-/.test(component.name) || seen.has(component.name)) {
    continue;
  }
  seen.add(component.name);
  const exportName = toPascalCase(component.name.replace(/^icon-/, ""));
  const entry = {
    figmaName: component.name,
    exportName,
    nodeId: component.node_id.replace(":", "-"),
  };
  if (exportName in lucide) {
    matched.push(entry);
  } else {
    unmatched.push(entry);
  }
}

matched.sort((a, b) => a.exportName.localeCompare(b.exportName));
unmatched.sort((a, b) => a.figmaName.localeCompare(b.figmaName));

const importNames = [...new Set(matched.map((entry) => entry.exportName))];
const connects = matched.map(
  (entry) => `figma.connect(
  ${entry.exportName},
  "${FILE_URL}?node-id=${entry.nodeId}",
  {
    example: () => <${entry.exportName} />,
  },
);`,
);

const output = `import figma from "@figma/code-connect";
import {
${importNames.map((name) => `  ${name},`).join("\n")}
} from "lucide-react";

${connects.join("\n\n")}
`;

fs.writeFileSync(OUTPUT_PATH, output);

console.log(`Matched ${matched.length} icons -> ${OUTPUT_PATH}`);
console.log(`Unmatched ${unmatched.length} icons (no lucide-react export):`);
for (const entry of unmatched) {
  console.log(`  ${entry.figmaName} (${entry.exportName}, ${entry.nodeId})`);
}
