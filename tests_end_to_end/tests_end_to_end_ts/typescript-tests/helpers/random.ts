export function getRandomString(length: number): string {
  const chars = 'abcdefghijklmnopqrstuvwxyz';
  return Array.from({ length }, () =>
    chars[Math.floor(Math.random() * chars.length)]
  ).join('');
}

export function generateProjectName(): string {
  return `project_${getRandomString(5)}`;
}

export function generateDatasetName(): string {
  return `dataset_${getRandomString(5)}`;
}
