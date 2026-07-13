import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import StyleDictionary from "style-dictionary";

const nameMap = JSON.parse(
  fs.readFileSync(
    path.join(path.dirname(fileURLToPath(import.meta.url)), "name-map.json"),
    "utf8",
  ),
);

const HEADER = `/**
 * AUTO-GENERATED — DO NOT EDIT.
 * Source: Figma "Opik Design System" styles, exported to design-tokens/tokens.json (DTCG).
 * Rebuild with: npm run tokens:build
 *
 * Imported by src/index.tsx (before main.scss). Tokens mapped in
 * design-tokens/name-map.json are emitted under their code variable name and
 * replace the light-mode :root definitions that used to live in src/main.scss;
 * the .dark overrides stay in main.scss and win the cascade by source order.
 */`;

function hexToHslTriple(hex) {
  const [r, g, b] = [1, 3, 5].map(
    (offset) => parseInt(hex.slice(offset, offset + 2), 16) / 255,
  );
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l = (max + min) / 2;
  const d = max - min;
  let h = 0;
  let s = 0;
  if (d !== 0) {
    s = d / (1 - Math.abs(2 * l - 1));
    if (max === r) h = 60 * (((((g - b) / d) % 6) + 6) % 6);
    else if (max === g) h = 60 * ((b - r) / d + 2);
    else h = 60 * ((r - g) / d + 4);
  }
  return `${Math.round(h)} ${Math.round(s * 100)}% ${Math.round(l * 100)}%`;
}

StyleDictionary.registerFormat({
  name: "css/variables-with-notice",
  format: async ({ dictionary }) => {
    const lines = dictionary.allTokens.map((token) => {
      const mapped = nameMap[token.path.join(".")];
      const name = mapped ? mapped.cssVar.replace(/^--/, "") : token.name;
      const raw = token.$value ?? token.value;
      const value = mapped?.format === "hsl-triple" ? hexToHslTriple(raw) : raw;
      return `  --${name}: ${String(value).replace(/'/g, '"')};`;
    });
    return `${HEADER}\n\n:root {\n${lines.join("\n")}\n}\n`;
  },
});

export default {
  source: ["design-tokens/tokens.json"],
  platforms: {
    css: {
      transformGroup: "css",
      transforms: [
        "name/kebab",
        "typography/css/shorthand",
        "shadow/css/shorthand",
      ],
      buildPath: "src/styles/",
      files: [
        {
          destination: "tokens.generated.css",
          format: "css/variables-with-notice",
        },
      ],
    },
  },
};
