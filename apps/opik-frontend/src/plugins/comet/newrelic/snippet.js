export default function initSnippet(licenseKey, applicationID) {
  // Inject configuration

  const configScript = document.createElement("script");
  configScript.type = "text/javascript";
  configScript.text = `
    window.NREUM = window.NREUM || {};
    NREUM.info = {
      applicationID: "${applicationID}",
      licenseKey: "${licenseKey}",
      sa: 1,
    };
  `;
  document.head.appendChild(configScript);

  // Inject agent script
  const agentScript = document.createElement("script");
  agentScript.src =
    "https://js-agent.newrelic.com/nr-loader-spa-current.min.js";
  agentScript.async = true;
  document.head.appendChild(agentScript);
}
