import initSnippet from "./snippet";

export const initReo = (apiKey?: string) => {
  if (apiKey) {
    initSnippet(apiKey);
  }
};
