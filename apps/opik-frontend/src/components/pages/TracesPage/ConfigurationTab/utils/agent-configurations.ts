export const isProdTag = (tag: string) => /^prod(uction)?$/i.test(tag);

export const sortTags = (tags: string[]) => [
  ...tags.filter(isProdTag),
  ...tags.filter((t) => !isProdTag(t)),
];
