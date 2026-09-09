// "Install tools" page action: a button in Fern's page-actions toolbar that opens
// a modal with one install command per coding agent (MCP server + skills).
// Fern renders the toolbar client-side and re-renders it on navigation, so the
// button is (re)inserted by a MutationObserver and everything is idempotent.
(function () {
  var DOCS_URL = "https://www.comet.com/docs/opik/mcp-server";
  var MCP_URL = "https://www.comet.com/opik/api/v1/mcp";

  var TABS = [
    {
      id: "auto",
      icon: '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 8V4H8"/><rect width="16" height="12" x="4" y="8" rx="2"/><path d="M2 14h2"/><path d="M20 14h2"/><path d="M15 13v2"/><path d="M9 13v2"/></svg>',
      label: "Auto",
      text: "Detects the coding agents on your machine, registers the Opik MCP server with them and installs the Opik skills. Needs uv, no SDK.",
      command: "uvx opik mcp configure"
    },
    {
      id: "claude-code",
      icon: '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="#D97757" aria-hidden="true"><path d="m4.7144 15.9555 4.7174-2.6471.079-.2307-.079-.1275h-.2307l-.7893-.0486-2.6956-.0729-2.3375-.0971-2.2646-.1214-.5707-.1215-.5343-.7042.0546-.3522.4797-.3218.686.0608 1.5179.1032 2.2767.1578 1.6514.0972 2.4468.255h.3886l.0546-.1579-.1336-.0971-.1032-.0972L6.973 9.8356l-2.55-1.6879-1.3356-.9714-.7225-.4918-.3643-.4614-.1578-1.0078.6557-.7225.8803.0607.2246.0607.8925.686 1.9064 1.4754 2.4893 1.8336.3643.3035.1457-.1032.0182-.0728-.164-.2733-1.3539-2.4467-1.445-2.4893-.6435-1.032-.17-.6194c-.0607-.255-.1032-.4674-.1032-.7285L6.287.1335 6.6997 0l.9957.1336.419.3642.6192 1.4147 1.0018 2.2282 1.5543 3.0296.4553.8985.2429.8318.091.255h.1579v-.1457l.1275-1.706.2368-2.0947.2307-2.6957.0789-.7589.3764-.9107.7468-.4918.5828.2793.4797.686-.0668.4433-.2853 1.8517-.5586 2.9021-.3643 1.9429h.2125l.2429-.2429.9835-1.3053 1.6514-2.0643.7286-.8196.85-.9046.5464-.4311h1.0321l.759 1.1293-.34 1.1657-1.0625 1.3478-.8804 1.1414-1.2628 1.7-.7893 1.36.0729.1093.1882-.0183 2.8535-.607 1.5421-.2794 1.8396-.3157.8318.3886.091.3946-.3278.8075-1.967.4857-2.3072.4614-3.4364.8136-.0425.0304.0486.0607 1.5482.1457.6618.0364h1.621l3.0175.2247.7892.522.4736.6376-.079.4857-1.2142.6193-1.6393-.3886-3.825-.9107-1.3113-.3279h-.1822v.1093l1.0929 1.0686 2.0035 1.8092 2.5075 2.3314.1275.5768-.3218.4554-.34-.0486-2.2039-1.6575-.85-.7468-1.9246-1.621h-.1275v.17l.4432.6496 2.3436 3.5214.1214 1.0807-.17.3521-.6071.2125-.6679-.1214-1.3721-1.9246L14.38 17.959l-1.1414-1.9428-.1397.079-.674 7.2552-.3156.3703-.7286.2793-.6071-.4614-.3218-.7468.3218-1.4753.3886-1.9246.3157-1.53.2853-1.9004.17-.6314-.0121-.0425-.1397.0182-1.4328 1.9672-2.1796 2.9446-1.7243 1.8456-.4128.164-.7164-.3704.0667-.6618.4008-.5889 2.386-3.0357 1.4389-1.882.929-1.0868-.0062-.1579h-.0546l-6.3385 4.1164-1.1293.1457-.4857-.4554.0608-.7467.2307-.2429 1.9064-1.3114Z"/></svg>',
      label: "Claude Code",
      text: "Registers the Opik MCP server with Claude Code and installs the skills. Sign-in opens in your browser on first use.",
      command: "uvx opik mcp configure --ai-client claude-code --skills"
    },
    {
      id: "codex",
      icon: '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="4 17 10 11 4 5"/><line x1="12" x2="20" y1="19" y2="19"/></svg>',
      label: "Codex",
      text: "Registers the Opik MCP server with Codex and installs the skills.",
      command: "uvx opik mcp configure --ai-client codex --skills"
    },
    {
      id: "cursor",
      icon: '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M11.503.131 1.891 5.678a.84.84 0 0 0-.42.726v11.188c0 .3.162.575.42.724l9.609 5.55a1 1 0 0 0 .998 0l9.61-5.55a.84.84 0 0 0 .42-.724V6.404a.84.84 0 0 0-.42-.726L12.497.131a1.01 1.01 0 0 0-.996 0M2.657 6.338h18.55c.263 0 .43.287.297.515L12.23 22.918c-.062.107-.229.064-.229-.06V12.335a.59.59 0 0 0-.295-.51l-9.11-5.257c-.109-.063-.064-.23.061-.23"/></svg>',
      label: "Cursor",
      text: "Registers the Opik MCP server with Cursor and installs the skills. MCP needs a Cursor Pro plan or higher.",
      command: "uvx opik mcp configure --ai-client cursor --skills"
    },
    {
      id: "vscode",
      icon: '<svg viewBox="0 0 100 100" fill="none" xmlns="http://www.w3.org/2000/svg" width="16" height="16" aria-hidden="true"> <mask id="opik-it-mask0" mask-type="alpha" maskUnits="userSpaceOnUse" x="0" y="0" width="100" height="100"> <path fill-rule="evenodd" clip-rule="evenodd" d="M70.9119 99.3171C72.4869 99.9307 74.2828 99.8914 75.8725 99.1264L96.4608 89.2197C98.6242 88.1787 100 85.9892 100 83.5872V16.4133C100 14.0113 98.6243 11.8218 96.4609 10.7808L75.8725 0.873756C73.7862 -0.130129 71.3446 0.11576 69.5135 1.44695C69.252 1.63711 69.0028 1.84943 68.769 2.08341L29.3551 38.0415L12.1872 25.0096C10.589 23.7965 8.35363 23.8959 6.86933 25.2461L1.36303 30.2549C-0.452552 31.9064 -0.454633 34.7627 1.35853 36.417L16.2471 50.0001L1.35853 63.5832C-0.454633 65.2374 -0.452552 68.0938 1.36303 69.7453L6.86933 74.7541C8.35363 76.1043 10.589 76.2037 12.1872 74.9905L29.3551 61.9587L68.769 97.9167C69.3925 98.5406 70.1246 99.0104 70.9119 99.3171ZM75.0152 27.2989L45.1091 50.0001L75.0152 72.7012V27.2989Z" fill="white"/> </mask> <g mask="url(#opik-it-mask0)"> <path d="M96.4614 10.7962L75.8569 0.875542C73.4719 -0.272773 70.6217 0.211611 68.75 2.08333L1.29858 63.5832C-0.515693 65.2373 -0.513607 68.0937 1.30308 69.7452L6.81272 74.754C8.29793 76.1042 10.5347 76.2036 12.1338 74.9905L93.3609 13.3699C96.086 11.3026 100 13.2462 100 16.6667V16.4275C100 14.0265 98.6246 11.8378 96.4614 10.7962Z" fill="#0065A9"/> <g filter="url(#opik-it-filter0_d)"> <path d="M96.4614 89.2038L75.8569 99.1245C73.4719 100.273 70.6217 99.7884 68.75 97.9167L1.29858 36.4169C-0.515693 34.7627 -0.513607 31.9063 1.30308 30.2548L6.81272 25.246C8.29793 23.8958 10.5347 23.7964 12.1338 25.0095L93.3609 86.6301C96.086 88.6974 100 86.7538 100 83.3334V83.5726C100 85.9735 98.6246 88.1622 96.4614 89.2038Z" fill="#007ACC"/> </g> <g filter="url(#opik-it-filter1_d)"> <path d="M75.8578 99.1263C73.4721 100.274 70.6219 99.7885 68.75 97.9166C71.0564 100.223 75 98.5895 75 95.3278V4.67213C75 1.41039 71.0564 -0.223106 68.75 2.08329C70.6219 0.211402 73.4721 -0.273666 75.8578 0.873633L96.4587 10.7807C98.6234 11.8217 100 14.0112 100 16.4132V83.5871C100 85.9891 98.6234 88.1786 96.4586 89.2196L75.8578 99.1263Z" fill="#1F9CF0"/> </g> <g style="mix-blend-mode:overlay" opacity="0.25"> <path fill-rule="evenodd" clip-rule="evenodd" d="M70.8511 99.3171C72.4261 99.9306 74.2221 99.8913 75.8117 99.1264L96.4 89.2197C98.5634 88.1787 99.9392 85.9892 99.9392 83.5871V16.4133C99.9392 14.0112 98.5635 11.8217 96.4001 10.7807L75.8117 0.873695C73.7255 -0.13019 71.2838 0.115699 69.4527 1.44688C69.1912 1.63705 68.942 1.84937 68.7082 2.08335L29.2943 38.0414L12.1264 25.0096C10.5283 23.7964 8.29285 23.8959 6.80855 25.246L1.30225 30.2548C-0.513334 31.9064 -0.515415 34.7627 1.29775 36.4169L16.1863 50L1.29775 63.5832C-0.515415 65.2374 -0.513334 68.0937 1.30225 69.7452L6.80855 74.754C8.29285 76.1042 10.5283 76.2036 12.1264 74.9905L29.2943 61.9586L68.7082 97.9167C69.3317 98.5405 70.0638 99.0104 70.8511 99.3171ZM74.9544 27.2989L45.0483 50L74.9544 72.7012V27.2989Z" fill="url(#opik-it-paint0_linear)"/> </g> </g> <defs> <filter id="opik-it-filter0_d" x="-8.39411" y="15.8291" width="116.727" height="92.2456" filterUnits="userSpaceOnUse" color-interpolation-filters="sRGB"> <feFlood flood-opacity="0" result="BackgroundImageFix"/> <feColorMatrix in="SourceAlpha" type="matrix" values="0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 127 0"/> <feOffset/> <feGaussianBlur stdDeviation="4.16667"/> <feColorMatrix type="matrix" values="0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0.25 0"/> <feBlend mode="overlay" in2="BackgroundImageFix" result="effect1_dropShadow"/> <feBlend mode="normal" in="SourceGraphic" in2="effect1_dropShadow" result="shape"/> </filter> <filter id="opik-it-filter1_d" x="60.4167" y="-8.07558" width="47.9167" height="116.151" filterUnits="userSpaceOnUse" color-interpolation-filters="sRGB"> <feFlood flood-opacity="0" result="BackgroundImageFix"/> <feColorMatrix in="SourceAlpha" type="matrix" values="0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 127 0"/> <feOffset/> <feGaussianBlur stdDeviation="4.16667"/> <feColorMatrix type="matrix" values="0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0.25 0"/> <feBlend mode="overlay" in2="BackgroundImageFix" result="effect1_dropShadow"/> <feBlend mode="normal" in="SourceGraphic" in2="effect1_dropShadow" result="shape"/> </filter> <linearGradient id="opik-it-paint0_linear" x1="49.9392" y1="0.257812" x2="49.9392" y2="99.7423" gradientUnits="userSpaceOnUse"> <stop stop-color="white"/> <stop offset="1" stop-color="white" stop-opacity="0"/> </linearGradient> </defs> </svg>',
      label: "VS Code",
      text: "Registers the Opik MCP server with VS Code Copilot and installs the skills.",
      command: "uvx opik mcp configure --ai-client vscode --skills"
    },
    {
      id: "skills",
      icon: '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z"/><path d="M20 3v4"/><path d="M22 5h-4"/></svg>',
      label: "Skills only",
      text: "Installs the Opik skill pack for every coding agent found on your machine, without the MCP server. Needs Node.",
      command: "npx skills add comet-ml/opik-skills -g -y"
    }
  ];

  var ICON_DOWNLOAD =
    '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" x2="12" y1="15" y2="3"/></svg>';
  var ICON_COPY =
    '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>';
  var ICON_CHECK =
    '<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 6 9 17l-5-5"/></svg>';
  var ICON_CLOSE =
    '<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>';

  var CSS =
    ".fern-page-actions>span[aria-hidden]{margin:0 2px!important}" +
    ".fern-page-actions>button,.fern-page-actions>a{padding-left:5px!important;padding-right:5px!important}" +
    ".opik-it-overlay{position:fixed;inset:0;z-index:1000;background:rgba(0,0,0,.45);display:flex;align-items:center;justify-content:center;padding:16px}" +
    ".opik-it-dialog{width:min(700px,100%);max-height:90vh;overflow:auto;background:var(--background,Canvas);color:inherit;border:1px solid var(--grayscale-a6,rgba(128,128,128,.35));border-radius:12px;padding:22px 24px 20px;box-shadow:0 20px 60px rgba(0,0,0,.25);font-size:15px;line-height:1.5}" +
    ".opik-it-head{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}" +
    ".opik-it-title{margin:0;font-size:20px;font-weight:600;line-height:1.2}" +
    ".opik-it-close{border:0;background:transparent;color:inherit;cursor:pointer;padding:4px;border-radius:6px;opacity:.7}" +
    ".opik-it-close:hover{opacity:1;background:var(--accent-a3,rgba(128,128,128,.15))}" +
    ".opik-it-lead{margin:10px 0 0;color:var(--grayscale-a11,inherit)}" +
    ".opik-it-lead a{color:var(--accent-a11,inherit)}" +
    ".opik-it-tabs{display:flex;flex-wrap:wrap;gap:2px;margin:18px 0 0;border-bottom:1px solid var(--grayscale-a6,rgba(128,128,128,.35))}" +
    ".opik-it-tab{border:0;background:transparent;color:var(--grayscale-a11,inherit);cursor:pointer;padding:8px 10px;font:inherit;font-size:14px;font-weight:500;border-bottom:2px solid transparent;margin-bottom:-1px}" +
    ".opik-it-tab{display:inline-flex;align-items:center;gap:6px}" +
    ".opik-it-tab svg{width:16px;height:16px;flex:none}" +
    ".opik-it-tab[aria-selected=true]{color:var(--accent-a11,inherit);border-bottom-color:var(--accent-a11,currentColor)}" +
    ".opik-it-text{margin:16px 0 10px;color:var(--grayscale-a11,inherit)}" +
    ".opik-it-cmd{display:flex;align-items:center;justify-content:space-between;gap:12px;border:1px solid var(--grayscale-a6,rgba(128,128,128,.35));border-radius:8px;padding:12px 14px;background:var(--grayscale-a2,rgba(128,128,128,.08))}" +
    ".opik-it-dialog code{border:0!important;box-shadow:none!important;background:transparent!important;padding:0!important;border-radius:0!important;color:inherit}" +
    ".opik-it-cmd code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:14px;white-space:pre-wrap;word-break:break-word}" +
    ".opik-it-alt{margin:18px 0 8px;font-size:13.5px;color:var(--grayscale-a11,inherit)}" +
    ".opik-it-cmd.opik-it-cmd-alt code{font-size:13px}" +
    ".opik-it-copy{flex:none;border:0;background:transparent;color:inherit;cursor:pointer;padding:6px;border-radius:6px;opacity:.75;display:inline-flex}" +
    ".opik-it-copy:hover{opacity:1;background:var(--accent-a3,rgba(128,128,128,.15))}" +
    ".opik-it-foot{margin:16px 0 0;font-size:13.5px;color:var(--grayscale-a11,inherit)}" +
    ".opik-it-foot code{font-size:12.5px}" +
    ".opik-it-foot a{color:var(--accent-a11,inherit)}";

  function injectStyles() {
    if (document.getElementById("opik-install-tools-css")) return;
    var style = document.createElement("style");
    style.id = "opik-install-tools-css";
    style.textContent = CSS;
    document.head.appendChild(style);
  }

  function copyText(text, button) {
    var done = function () {
      button.innerHTML = ICON_CHECK;
      setTimeout(function () { button.innerHTML = ICON_COPY; }, 2000);
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, function () {
        var range = document.createRange();
        range.selectNodeContents(button.previousElementSibling);
        var sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
      });
    }
  }

  function openModal() {
    if (document.querySelector(".opik-it-overlay")) return;
    injectStyles();

    var overlay = document.createElement("div");
    overlay.className = "opik-it-overlay";
    overlay.innerHTML =
      '<div class="opik-it-dialog" role="dialog" aria-modal="true" aria-labelledby="opik-it-title">' +
        '<div class="opik-it-head">' +
          '<h2 class="opik-it-title" id="opik-it-title">Install agent tools</h2>' +
          '<button class="opik-it-close" type="button" aria-label="Close">' + ICON_CLOSE + "</button>" +
        "</div>" +
        '<p class="opik-it-lead">Connect your coding agent to Opik: read traces and experiments, score outputs and fix code from chat. <a href="' + DOCS_URL + '">Learn more</a></p>' +
        '<div class="opik-it-tabs" role="tablist"></div>' +
        '<p class="opik-it-text"></p>' +
        '<div class="opik-it-cmd"><code></code><button class="opik-it-copy" type="button" aria-label="Copy command">' + ICON_COPY + "</button></div>" +
        '<p class="opik-it-alt">Another MCP client? Any client that speaks MCP can take the Opik Cloud server directly:</p>' +
        '<div class="opik-it-cmd opik-it-cmd-alt"><code>npx add-mcp ' + MCP_URL + ' --name opik-mcp</code><button class="opik-it-copy" type="button" aria-label="Copy command">' + ICON_COPY + "</button></div>" +
        '<p class="opik-it-foot">Self-hosted Opik: the commands in the tabs detect your deployment.</p>' +
      "</div>";

    var tablist = overlay.querySelector(".opik-it-tabs");
    var text = overlay.querySelector(".opik-it-text");
    var code = overlay.querySelector(".opik-it-cmd code");
    var copyButtons = overlay.querySelectorAll(".opik-it-copy");

    function select(id) {
      TABS.forEach(function (tab) {
        var el = tablist.querySelector('[data-tab="' + tab.id + '"]');
        el.setAttribute("aria-selected", tab.id === id ? "true" : "false");
        if (tab.id === id) {
          text.textContent = tab.text;
          code.textContent = tab.command;
        }
      });
    }

    TABS.forEach(function (tab) {
      var el = document.createElement("button");
      el.type = "button";
      el.className = "opik-it-tab";
      el.setAttribute("role", "tab");
      el.setAttribute("data-tab", tab.id);
      el.innerHTML = tab.icon + "<span>" + tab.label + "</span>";
      el.addEventListener("click", function () { select(tab.id); });
      tablist.appendChild(el);
    });
    select(TABS[0].id);

    function close() {
      overlay.remove();
      document.removeEventListener("keydown", onKey);
    }
    function onKey(e) { if (e.key === "Escape") close(); }

    overlay.addEventListener("click", function (e) { if (e.target === overlay) close(); });
    overlay.querySelector(".opik-it-close").addEventListener("click", close);
    copyButtons.forEach(function (btn) {
      btn.addEventListener("click", function () {
        copyText(btn.previousElementSibling.textContent, btn);
      });
    });
    document.addEventListener("keydown", onKey);

    document.body.appendChild(overlay);
    overlay.querySelector(".opik-it-tab").focus();
  }

  function insertButton(toolbar) {
    injectStyles();
    if (toolbar.querySelector("[data-opik-install-tools]")) return;
    var more = toolbar.querySelector('[aria-label="More actions"]');
    var sample = toolbar.querySelector("button, a");
    if (!sample) return;

    var button = document.createElement("button");
    button.type = "button";
    button.className = sample.className;
    button.setAttribute("data-opik-install-tools", "");
    button.title = "Install the Opik MCP server and skills for your coding agent";
    button.innerHTML = ICON_DOWNLOAD + "<span>Install tools</span>";
    button.addEventListener("click", openModal);

    var sep = document.createElement("span");
    sep.setAttribute("aria-hidden", "true");
    sep.className = "text-(color:--grayscale-a8)";
    sep.style.margin = "0 8px";
    sep.textContent = "|";

    if (more) {
      // Before the "More actions" trigger and its preceding separator.
      var prev = more.previousElementSibling;
      var anchor = prev && prev.getAttribute("aria-hidden") === "true" ? prev : more;
      toolbar.insertBefore(sep, anchor);
      toolbar.insertBefore(button, anchor);
    } else {
      toolbar.appendChild(sep);
      toolbar.appendChild(button);
    }
  }

  function scan() {
    document.querySelectorAll(".fern-page-actions").forEach(insertButton);
  }

  var observer = new MutationObserver(scan);
  function start() {
    scan();
    observer.observe(document.body, { childList: true, subtree: true });
  }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
