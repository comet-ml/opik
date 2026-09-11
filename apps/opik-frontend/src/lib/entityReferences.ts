/**
 * Parses entity references in text and replaces them with human-readable names.
 *
 * Entity references follow the format: {type:id}
 * For example: {span:01978716-1435-7749-b575-ff4982e17264}
 *
 * This utility is generic and will attempt to replace any entity reference
 * found in the entityMap, regardless of type. If the entity ID is not found
 * in the map, the original reference is kept as-is.
 *
 * @param text - The text containing entity references
 * @param entityMap - A map of entity IDs to their display names
 * @returns The text with entity references replaced by names from the map
 *
 * @example
 * const entityMap = new Map([['span-123', 'answer']]);
 * parseEntityReferences('Fetching {span:span-123}', entityMap);
 * // Returns: 'Fetching answer'
 *
 * @example
 * // Fallback to original reference when entity not found
 * parseEntityReferences('Fetching {span:unknown-id}', new Map());
 * // Returns: 'Fetching {span:unknown-id}'
 *
 * @example
 * // Works for any entity type if ID is in the map
 * const entityMap = new Map([['trace-1', 'my_trace']]);
 * parseEntityReferences('Analyzing {trace:trace-1}', entityMap);
 * // Returns: 'Analyzing my_trace'
 */
export function parseEntityReferences(
  text: string,
  entityMap: Map<string, string>,
): string {
  const entityReferencePattern = /\{(\w+):([^}]+)\}/g;

  return text.replace(entityReferencePattern, (match, type, id) => {
    const entityName = entityMap.get(id);
    return entityName ?? match;
  });
}
