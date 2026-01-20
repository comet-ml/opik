/**
 * Parses entity references in text and replaces them with human-readable names.
 *
 * Entity references follow the format: {type:id}
 * For example: {span:01978716-1435-7749-b575-ff4982e17264}
 *
 * @param text - The text containing entity references
 * @param entityMap - A map of entity IDs to their display names
 * @returns The text with entity references replaced by names, or IDs if not found
 *
 * @example
 * const entityMap = new Map([['span-123', 'answer']]);
 * parseEntityReferences('Fetching {span:span-123}', entityMap);
 * // Returns: 'Fetching answer'
 *
 * @example
 * // Fallback to ID when entity not found
 * parseEntityReferences('Fetching {span:unknown-id}', new Map());
 * // Returns: 'Fetching unknown-id'
 *
 * @example
 * // Unknown entity types are kept as-is
 * parseEntityReferences('Fetching {unknown:id}', new Map());
 * // Returns: 'Fetching {unknown:id}'
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
