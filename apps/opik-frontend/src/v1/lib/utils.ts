import {
  buildDocsUrl as buildDocsUrlBase,
  buildDocsMarkdownUrl as buildDocsMarkdownUrlBase,
} from "@/lib/utils";

export const buildDocsUrl = (path: string = "", hash: string = "") =>
  buildDocsUrlBase(`/v1${path}`, hash);

export const buildDocsMarkdownUrl = (path: string = "") =>
  buildDocsMarkdownUrlBase(`/v1${path}`);
