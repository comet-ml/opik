export type EntityReference = {
  type: "span" | "unknown";
  id: string;
  name: string;
};

export type ParsedTextPart =
  | { type: "text"; content: string }
  | { type: "entity"; entity: EntityReference };

/**
 * Parses entity references in text and returns structured parts for rendering.
 *
 * Entity references follow the format: {type:id}
 * For example: {span:01978716-1435-7749-b575-ff4982e17264}
 *
 * @param text - The text containing entity references
 * @param entityMap - A map of entity IDs to their display names
 * @returns Array of text and entity parts for rendering
 *
 * @example
 * const entityMap = new Map([['span-123', 'answer']]);
 * parseEntityReferencesToParts('Fetching {span:span-123}', entityMap);
 * // Returns: [
 * //   { type: 'text', content: 'Fetching ' },
 * //   { type: 'entity', entity: { type: 'span', id: 'span-123', name: 'answer' } }
 * // ]
 */
export function parseEntityReferencesToParts(
  text: string,
  entityMap: Map<string, string>,
): ParsedTextPart[] {
  const parts: ParsedTextPart[] = [];
  const entityReferencePattern = /\{(\w+):([^}]+)\}/g;

  let lastIndex = 0;
  let match;

  while ((match = entityReferencePattern.exec(text)) !== null) {
    const [fullMatch, type, id] = match;
    const matchStart = match.index;

    // Add text before the match
    if (matchStart > lastIndex) {
      parts.push({
        type: "text",
        content: text.substring(lastIndex, matchStart),
      });
    }

    // Add entity reference
    if (type === "span") {
      const entityName = entityMap.get(id);
      parts.push({
        type: "entity",
        entity: {
          type: "span",
          id,
          name: entityName || id,
        },
      });
    } else {
      // Unknown entity type - treat as plain text
      parts.push({
        type: "text",
        content: fullMatch,
      });
    }

    lastIndex = matchStart + fullMatch.length;
  }

  // Add remaining text after last match
  if (lastIndex < text.length) {
    parts.push({
      type: "text",
      content: text.substring(lastIndex),
    });
  }

  return parts;
}

/**
 * Parses entity references in text and replaces them with human-readable names.
 * This is a simpler version that returns plain text (kept for backward compatibility).
 *
 * @param text - The text containing entity references
 * @param entityMap - A map of entity IDs to their display names
 * @returns The text with entity references replaced by names, or IDs if not found
 */
export function parseEntityReferences(
  text: string,
  entityMap: Map<string, string>,
): string {
  const entityReferencePattern = /\{(\w+):([^}]+)\}/g;

  return text.replace(entityReferencePattern, (match, type, id) => {
    if (type === "span") {
      const entityName = entityMap.get(id);
      return entityName || id;
    }
    return match;
  });
}
