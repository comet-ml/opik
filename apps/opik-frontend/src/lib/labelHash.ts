import md5 from "md5";

// Stable across users and reloads: the automatic colour of a label everywhere in Opik keys off this.
export const labelHash = (label: string) => parseInt(md5(label).slice(-8), 16);
