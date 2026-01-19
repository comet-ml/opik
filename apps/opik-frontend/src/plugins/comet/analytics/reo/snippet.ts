export default function initReoSnippet(clientId: string) {
  const reoScript = document.createElement("script");
  reoScript.innerHTML = `
    !function(){var e,t,n;e="${clientId}",t=function(){Reo.init({clientID:"${clientId}"})},(n=document.createElement("script")).src="https://static.reo.dev/"+e+"/reo.js",n.defer=!0,n.onload=t,document.head.appendChild(n)}();
  `;
  document.head.appendChild(reoScript);
}
