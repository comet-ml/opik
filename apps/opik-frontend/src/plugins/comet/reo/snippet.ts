export default function initReoSnippet(apiKey: string) {
  const script = document.createElement("script");
  script.src = `https://static.reo.dev/${apiKey}/reo.js`;
  script.async = true;
  script.defer = true;
  document.head.appendChild(script);
}
