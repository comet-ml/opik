import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const FILE_KEY = "DQkbgEBm59YiQUzoxxZ0ON";
const API_BASE = "https://api.figma.com/v1";
const NODES_PER_REQUEST = 100;

const outPath = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  "tokens.json",
);

const accessToken = process.env.FIGMA_ACCESS_TOKEN;
if (!accessToken) {
  console.error(
    "FIGMA_ACCESS_TOKEN is not set. Add it to apps/opik-frontend/.env (the token needs file_content:read and library_content:read scopes).",
  );
  process.exit(1);
}

async function figmaGet(endpoint) {
  const response = await fetch(`${API_BASE}${endpoint}`, {
    headers: { "X-Figma-Token": accessToken },
  });
  if (!response.ok) {
    const body = await response.text();
    const hint =
      response.status === 403
        ? " (403 usually means the token is missing the library_content:read scope — regenerate it with that scope enabled)"
        : "";
    throw new Error(
      `Figma API GET ${endpoint} failed: ${response.status} ${response.statusText}${hint}\n${body}`,
    );
  }
  return response.json();
}

const kebab = (segment) =>
  segment
    .replace(/([a-z0-9])([A-Z])/g, "$1-$2")
    .trim()
    .replace(/\s+/g, "-")
    .toLowerCase();

const tokenPath = (name) => name.split("/").map(kebab);

function setToken(tree, segments, token) {
  let node = tree;
  for (const segment of segments.slice(0, -1)) {
    node[segment] ??= {};
    node = node[segment];
  }
  node[segments.at(-1)] = token;
}

function sortKeysDeep(value) {
  if (Array.isArray(value)) return value.map(sortKeysDeep);
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((key) => [key, sortKeysDeep(value[key])]),
    );
  }
  return value;
}

function toHex({ r, g, b }, alpha = 1) {
  const channel = (fraction) =>
    Math.round(fraction * 255)
      .toString(16)
      .padStart(2, "0");
  const base = `#${channel(r)}${channel(g)}${channel(b)}`;
  return alpha < 1 ? `${base}${channel(alpha)}` : base;
}

function colorValue(document, styleName) {
  const fill = (document.fills ?? []).find(
    (paint) => paint.type === "SOLID" && paint.visible !== false,
  );
  if (!fill) {
    throw new Error(`Style "${styleName}" has no visible solid fill`);
  }
  return toHex(fill.color, fill.opacity ?? fill.color.a ?? 1);
}

function typographyValue(document, styleName) {
  const style = document.style;
  if (!style) {
    throw new Error(`Style "${styleName}" has no text style definition`);
  }
  return {
    fontFamily: style.fontFamily,
    fontWeight: style.fontWeight,
    fontSize: `${Math.round(style.fontSize)}px`,
    lineHeight: `${Math.round(style.lineHeightPx)}px`,
  };
}

function shadowValue(document, styleName) {
  const shadows = (document.effects ?? [])
    .filter(
      (effect) => effect.type === "DROP_SHADOW" && effect.visible !== false,
    )
    .map((effect) => ({
      color: toHex(effect.color, effect.color.a ?? 1),
      offsetX: `${effect.offset.x}px`,
      offsetY: `${effect.offset.y}px`,
      blur: `${effect.radius}px`,
      spread: `${effect.spread ?? 0}px`,
    }));
  if (shadows.length === 0) {
    throw new Error(`Style "${styleName}" has no visible drop shadow`);
  }
  return shadows.length === 1 ? shadows[0] : shadows;
}

const TYPE_BY_STYLE = {
  FILL: { $type: "color", value: colorValue },
  TEXT: { $type: "typography", value: typographyValue },
  EFFECT: { $type: "shadow", value: shadowValue },
};

try {
  const { meta } = await figmaGet(`/files/${FILE_KEY}/styles`);
  const styles = meta.styles
    .filter(
      (style) =>
        style.style_type in TYPE_BY_STYLE && !style.name.startsWith("[Figma]/"),
    )
    .sort((a, b) => a.name.localeCompare(b.name));

  const nodes = {};
  for (let i = 0; i < styles.length; i += NODES_PER_REQUEST) {
    const ids = styles
      .slice(i, i + NODES_PER_REQUEST)
      .map((style) => style.node_id)
      .join(",");
    const batch = await figmaGet(
      `/files/${FILE_KEY}/nodes?ids=${encodeURIComponent(ids)}`,
    );
    Object.assign(nodes, batch.nodes);
  }

  const tree = {};
  for (const style of styles) {
    const node = nodes[style.node_id];
    if (!node?.document) {
      throw new Error(
        `Node ${style.node_id} for style "${style.name}" not returned by the nodes endpoint`,
      );
    }
    const { $type, value } = TYPE_BY_STYLE[style.style_type];
    setToken(tree, tokenPath(style.name), {
      $type,
      $value: value(node.document, style.name),
      $extensions: { "com.figma": { styleKey: style.key } },
    });
  }

  fs.writeFileSync(outPath, JSON.stringify(sortKeysDeep(tree), null, 2) + "\n");
  console.log(`Wrote ${styles.length} tokens to ${outPath}`);
} catch (error) {
  console.error(error.message);
  process.exit(1);
}
