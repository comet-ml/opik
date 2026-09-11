import { SyntaxNode, Tree } from "@lezer/common";

export type QuickFilterMode = "json" | "yaml";

export type QuickFilterTarget = {
  // Icon position (end of the value); value text spans [from, pos].
  pos: number;
  from: number;
  path: string;
  value: string;
};

// Scalar value node names per grammar. Null is excluded: the dictionary filter
// has no is-empty operator, so a null value would seed a dead filter.
// BlockLiteral (| / > multi-line scalars) is excluded too: its node text keeps
// the block indicator and indentation, which can't be normalized into a clean
// filter value.
const JSON_VALUE_SCALARS = new Set(["String", "Number", "True", "False"]);
const YAML_VALUE_SCALARS = new Set(["Literal", "QuotedLiteral"]);
// Unquoted YAML scalars that denote null (skipped, like JSON Null).
const YAML_NULL_TOKENS = new Set(["null", "Null", "NULL", "~"]);

const unquote = (raw: string): string => {
  const s = raw.trim();
  if (s.length >= 2) {
    if (s.startsWith('"') && s.endsWith('"')) {
      try {
        const parsed = JSON.parse(s);
        return typeof parsed === "string" ? parsed : s.slice(1, -1);
      } catch {
        return s.slice(1, -1);
      }
    }
    if (s.startsWith("'") && s.endsWith("'")) {
      return s.slice(1, -1).replace(/''/g, "'");
    }
  }
  return s;
};

// Known limitation (matches the filter autocomplete's getJSONPaths): keys that
// literally contain "." or "[]" produce a dotted path indistinguishable from a
// nested/indexed one, so such a key maps to the wrong filter target.
const buildPath = (parts: Array<{ key?: string; index?: number }>): string => {
  let out = "";
  for (const part of parts) {
    if (part.key !== undefined) {
      out += out ? `.${part.key}` : part.key;
    } else {
      out += `[${part.index}]`;
    }
  }
  return out;
};

// Lezer exposes punctuation tokens ("[", "]", "{", "}", ",", ":") as named
// children, so they must be skipped when computing an element's index.
const PUNCTUATION = new Set(["[", "]", "{", "}", ",", ":"]);

// Index of `child` among `parent`'s value children (optionally restricted to a
// node name), ignoring punctuation tokens.
const namedIndex = (
  parent: SyntaxNode,
  child: SyntaxNode,
  name?: string,
): number => {
  let idx = 0;
  for (let cursor = parent.firstChild; cursor; cursor = cursor.nextSibling) {
    if (PUNCTUATION.has(cursor.name)) continue;
    if (name && cursor.name !== name) continue;
    if (
      cursor.from === child.from &&
      cursor.to === child.to &&
      cursor.name === child.name
    ) {
      return idx;
    }
    idx++;
  }
  return -1;
};

const jsonPath = (node: SyntaxNode, doc: string): string | null => {
  const parts: Array<{ key?: string; index?: number }> = [];
  let cur: SyntaxNode | null = node;
  while (cur) {
    const parent: SyntaxNode | null = cur.parent;
    if (!parent) break;
    if (parent.name === "Property") {
      const nameNode = parent.getChild("PropertyName");
      if (!nameNode) return null;
      parts.unshift({ key: unquote(doc.slice(nameNode.from, nameNode.to)) });
    } else if (parent.name === "Array") {
      const idx = namedIndex(parent, cur);
      if (idx < 0) return null;
      parts.unshift({ index: idx });
    }
    cur = parent;
  }
  return parts.length ? buildPath(parts) : null;
};

const yamlPath = (node: SyntaxNode, doc: string): string | null => {
  const parts: Array<{ key?: string; index?: number }> = [];
  let cur: SyntaxNode | null = node;
  while (cur) {
    const parent: SyntaxNode | null = cur.parent;
    if (!parent) break;
    if (parent.name === "Key") return null; // scalar is a mapping key, not a value
    if (parent.name === "Pair") {
      const keyNode = parent.getChild("Key");
      if (!keyNode) return null;
      parts.unshift({ key: unquote(doc.slice(keyNode.from, keyNode.to)) });
    } else if (
      parent.name === "BlockSequence" ||
      parent.name === "FlowSequence"
    ) {
      const idx = namedIndex(parent, cur, "Item");
      if (idx < 0) return null;
      parts.unshift({ index: idx });
    }
    cur = parent;
  }
  return parts.length ? buildPath(parts) : null;
};

export const collectQuickFilterTargets = (
  tree: Tree,
  doc: string,
  mode: QuickFilterMode,
  from = 0,
  to = doc.length,
): QuickFilterTarget[] => {
  const scalars = mode === "json" ? JSON_VALUE_SCALARS : YAML_VALUE_SCALARS;
  const targets: QuickFilterTarget[] = [];
  tree.iterate({
    from,
    to,
    enter: (ref) => {
      if (!scalars.has(ref.name)) return;
      const node = ref.node;
      const raw = doc.slice(node.from, node.to);
      // Skip YAML null scalars (JSON Null is already excluded by the set).
      if (
        mode === "yaml" &&
        ref.name === "Literal" &&
        YAML_NULL_TOKENS.has(raw.trim())
      ) {
        return;
      }
      const path = mode === "json" ? jsonPath(node, doc) : yamlPath(node, doc);
      if (path === null) return;
      // Empty value → a "contains" filter on "" is a no-op, so offer nothing.
      const value = unquote(raw);
      if (value === "") return;
      targets.push({ pos: node.to, from: node.from, path, value });
    },
  });
  return targets;
};
