const createButton = (href, text, icon = "") => {
  const link = document.createElement("a");
  link.href = href;
  link.style.cssText =
    "border: 0 solid #e5e7eb; box-sizing: inherit; outline-color: transparent; color: inherit; text-decoration: inherit; display: inline-flex; align-items: center; border-radius: .375rem; border-width: 1px; padding-left: .75rem; padding-right: .75rem; padding-bottom: .375rem; padding-top: .375rem; font-size: .875rem; line-height: 1.25rem; font-weight: 500;";
  link.target = "_blank";

  link.rel = "noreferrer";
  if (icon) link.innerHTML = icon;
  link.appendChild(document.createTextNode(text));
  return link;
};

const injectGitHubLink = () => {
  const path = window.location.pathname;
  
  // Define specific cookbook pages that should have Colab buttons
  const cookbookPages = [
    "quickstart_notebook",
    "evaluate_hallucination_metric", 
    "evaluate_moderation_metric",
    "dynamic_tracing_control",
  ];
  
  // Check if current page is one of the specified cookbook pages
  const isValidCookbookPage = cookbookPages.some(page => 
    path.includes(`/${page}`) || path.endsWith(`/${page}`)
  );
  
  if (!isValidCookbookPage) return;

  const header = document.querySelector(".fern-layout-guide header");
  if (!header) return;

  // Construct notebook path based on the page slug
  let notebookPath;
  if (path.includes("agent_optimization/quickstart_notebook")) {
    notebookPath = "/sdks/opik_optimizer/notebooks/OpikOptimizerIntro.ipynb";
  } else if (path.includes("quickstart_notebook")) {
    notebookPath = "/apps/opik-documentation/documentation/docs/cookbook/quickstart_notebook.ipynb";
  } else if (path.includes("evaluate_hallucination_metric")) {
    notebookPath = "/apps/opik-documentation/documentation/docs/cookbook/evaluate_hallucination_metric.ipynb";
  } else if (path.includes("evaluate_moderation_metric")) {
    notebookPath = "/apps/opik-documentation/documentation/docs/cookbook/evaluate_moderation_metric.ipynb";
  } else if (path.includes("dynamic_tracing_control")) {
    notebookPath = "/apps/opik-documentation/documentation/docs/cookbook/dynamic_tracing_cookbook.ipynb";
  } else {
    return; // No notebook available for this page
  }
  
  const githubUrl = "https://github.com/comet-ml/opik/blob/main" + notebookPath;
  const colabUrl = "https://colab.research.google.com/github/comet-ml/opik/blob/main" + notebookPath;

  const container = document.createElement("div");
  container.className = "fern-callout rounded-lg p-4 first:mt-0 callout-outlined-tip mt-24";
  container.setAttribute("data-intent", "tip");

  const content = document.createElement("div");
  content.className = "flex items-center justify-between";

  const leftSection = document.createElement("div");
  leftSection.className = "flex items-center gap-2";
  leftSection.innerHTML = `
    <div class="flex items-center w-4">
      <svg width="1.5em" height="1.5em" stroke-width="1.5" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" color="currentColor" class="size-icon-md text-intent-success">
        <path d="M8.58737 8.23597L11.1849 3.00376C11.5183 2.33208 12.4817 2.33208 12.8151 3.00376L15.4126 8.23597L21.2215 9.08017C21.9668 9.18848 22.2638 10.0994 21.7243 10.6219L17.5217 14.6918L18.5135 20.4414C18.6409 21.1798 17.8614 21.7428 17.1945 21.3941L12 18.678L6.80547 21.3941C6.1386 21.7428 5.35909 21.1798 5.48645 20.4414L6.47825 14.6918L2.27575 10.6219C1.73617 10.0994 2.03322 9.18848 2.77852 9.08017L8.58737 8.23597Z" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"></path>
      </svg>
    </div>
    <span class="text-sm font-medium text-intent-success">This is a Jupyter notebook!</span>
  `;

  const rightSection = document.createElement("div");
  rightSection.className = "flex items-center gap-2";

  const githubIcon =
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" fill="black" class="h-4 w-4 mr-1"><path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.012 8.012 0 0 0 16 8c0-4.42-3.58-8-8-8z"/></svg>';

  const colabIcon =
    '<svg xmlns:xlink="http://www.w3.org/1999/xlink" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" class="h-4 w-4 mr-1"><g><path d="M4.54,9.46,2.19,7.1a6.93,6.93,0,0,0,0,9.79l2.36-2.36A3.59,3.59,0,0,1,4.54,9.46Z" fill="#E8710A"/><path d="M2.19,7.1,4.54,9.46a3.59,3.59,0,0,1,5.08,0l1.71-2.93h0l-.1-.08h0A6.93,6.93,0,0,0,2.19,7.1Z" fill="#F9AB00"/><path d="M11.34,17.46h0L9.62,14.54a3.59,3.59,0,0,1-5.08,0L2.19,16.9a6.93,6.93,0,0,0,9,.65l.11-.09" fill="#F9AB00"/><path d="M12,7.1a6.93,6.93,0,0,0,0,9.79l2.36-2.36a3.59,3.59,0,1,1,5.08-5.08L21.81,7.1A6.93,6.93,0,0,0,12,7.1Z" fill="#F9AB00"/><path d="M21.81,7.1,19.46,9.46a3.59,3.59,0,0,1-5.08,5.08L12,16.9A6.93,6.93,0,0,0,21.81,7.1Z" fill="#E8710A"/></g></svg>';

  rightSection.appendChild(createButton(githubUrl, "GitHub", githubIcon));
  rightSection.appendChild(createButton(colabUrl, "Run in Google Colab", colabIcon));

  content.appendChild(leftSection);
  content.appendChild(rightSection);
  container.appendChild(content);
  header.insertAdjacentElement("afterend", container);
};

// Single function to handle both initial load and mutations
const setupBannerInjection = () => {
  const observer = new MutationObserver(() => {
    const header = document.querySelector(".fern-layout-guide header");
    if (header && !document.querySelector(".callout-outlined-tip")) {
      injectGitHubLink();
      console.log("test");
    }
  });

  if (document.body) {
    observer.observe(document.body, { childList: true, subtree: true });
    //   injectGitHubLink(); // Initial injection attempt
  }
};

document.readyState === "loading"
  ? document.addEventListener("DOMContentLoaded", setupBannerInjection)
  : setupBannerInjection();
