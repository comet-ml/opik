export const TEST_ITEMS = [
  { input: 'input0', output: 'output0' },
  { input: 'input1', output: 'output1' },
  { input: 'input2', output: 'output2' },
  { input: 'input3', output: 'output3' },
  { input: 'input4', output: 'output4' },
  { input: 'input5', output: 'output5' },
  { input: 'input6', output: 'output6' },
  { input: 'input7', output: 'output7' },
  { input: 'input8', output: 'output8' },
  { input: 'input9', output: 'output9' },
];

export const TEST_ITEMS_UPDATE = [
  { input: 'update-input0', output: 'update-output0' },
  { input: 'update-input1', output: 'update-output1' },
  { input: 'update-input2', output: 'update-output2' },
  { input: 'update-input3', output: 'update-output3' },
  { input: 'update-input4', output: 'update-output4' },
  { input: 'update-input5', output: 'update-output5' },
  { input: 'update-input6', output: 'update-output6' },
  { input: 'update-input7', output: 'update-output7' },
  { input: 'update-input8', output: 'update-output8' },
  { input: 'update-input9', output: 'update-output9' },
];

export function compareItemLists(
  expected: Array<Record<string, any>>,
  actual: Array<Record<string, any>>
): boolean {
  // Normalize function to handle case-insensitive keys and filter out ID/system fields
  const normalize = (item: Record<string, any>): Record<string, any> => {
    const normalized: Record<string, any> = {};
    for (const [key, val] of Object.entries(item)) {
      const lowerKey = key.toLowerCase();
      // Only include data fields (input/output), exclude IDs, tags, timestamps, etc.
      if (lowerKey === 'input' || lowerKey === 'output') {
        normalized[lowerKey] = typeof val === 'string' ? val.trim() : val;
      }
    }
    return normalized;
  };

  const normalizedExpected = expected.map(item => normalize(item));
  const normalizedActual = actual.map(item => normalize(item));

  if (normalizedExpected.length !== normalizedActual.length) {
    return false;
  }

  const setExpected = new Set(normalizedExpected.map(item => JSON.stringify(item)));
  const setActual = new Set(normalizedActual.map(item => JSON.stringify(item)));

  return setExpected.size === setActual.size &&
    [...setExpected].every(item => setActual.has(item));
}

export function getUpdatedItems(
  current: Array<Record<string, any>>,
  update: Array<Record<string, any>>
): Array<Record<string, any>> {
  return current.map((currentItem, index) => ({
    id: currentItem.id,
    input: update[index].input,
    output: update[index].output,
  }));
}
