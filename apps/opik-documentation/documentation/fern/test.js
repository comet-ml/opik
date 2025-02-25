const injectGitHubLink = () => {
  console.log("Calling injection function...");
  const path = window.location.pathname;
  if (!path.includes("docs/opik/cookbook/") || path.endsWith("docs/opik/cookbook/overview")) {
    return;
  }

  const heading = document.querySelector(".fern-page-heading");
  heading.style.marginBottom = "1rem";
  if (!heading) {
    console.log("Couldn't find header");
    return;
  }
  // Create main container
  const container = document.createElement("div");
  container.className = "rounded-lg p-4 first:mt-0 callout-outlined-tip mt-24";

  // Create flex container
  const flexContainer = document.createElement("div");
  flexContainer.className = "flex items-center justify-between";
  container.appendChild(flexContainer);

  // Create left section with icon and text
  const leftSection = document.createElement("div");
  leftSection.className = "flex items-center gap-2";
  flexContainer.appendChild(leftSection);

  // Create icon container
  const iconContainer = document.createElement("div");
  iconContainer.className = "flex items-center w-4";
  iconContainer.innerHTML = `<svg width="1.5em" height="1.5em" stroke-width="1.5" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" color="currentColor" class="size-icon-md text-intent-success"><path d="M8.58737 8.23597L11.1849 3.00376C11.5183 2.33208 12.4817 2.33208 12.8151 3.00376L15.4126 8.23597L21.2215 9.08017C21.9668 9.18848 22.2638 10.0994 21.7243 10.6219L17.5217 14.6918L18.5135 20.4414C18.6409 21.1798 17.8614 21.7428 17.1945 21.3941L12 18.678L6.80547 21.3941C6.1386 21.7428 5.35909 21.1798 5.48645 20.4414L6.47825 14.6918L2.27575 10.6219C1.73617 10.0994 2.03322 9.18848 2.77852 9.08017L8.58737 8.23597Z" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"></path></svg>`;
  leftSection.appendChild(iconContainer);

  // Add text
  const text = document.createElement("span");
  text.className = "text-sm font-medium text-intent-success";
  text.textContent = "This is a Jupyter notebook!";
  leftSection.appendChild(text);

  // Create right section for buttons
  const rightSection = document.createElement("div");
  rightSection.className = "flex items-center gap-2";
  flexContainer.appendChild(rightSection);

  // Get the notebook name from the URL
  const notebookPath =
    window.location.pathname.replace("/docs/opik/cookbook/", "/apps/opik-documentation/documentation/docs/cookbook/") +
    ".ipynb";

  // Create the full GitHub URL
  const githubUrl = "https://github.com/comet-ml/opik/blob/main" + notebookPath;

  // Add GitHub button
  const githubLink = document.createElement("a");
  githubLink.href = githubUrl;
  githubLink.className =
    "inline-flex items-center rounded-md border border-[#e5e7eb] bg-white px-3 py-1.5 text-sm font-medium text-[#16a34a] hover:bg-gray-50";
  githubLink.target = "_blank";
  githubLink.rel = "noreferrer";

  // Create GitHub icon
  const githubIcon = document.createElement("span");
  githubIcon.className = "mr-1";
  githubIcon.innerHTML = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" fill="currentColor" class="h-4 w-4">
          <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.012 8.012 0 0 0 16 8c0-4.42-3.58-8-8-8z"/>
      </svg>`;
  githubLink.appendChild(githubIcon);

  // Add GitHub text
  const githubText = document.createElement("span");
  githubText.textContent = "GitHub";
  githubLink.appendChild(githubText);

  rightSection.appendChild(githubLink);

  // Add Colab button
  const colabLink = document.createElement("a");
  colabLink.href = "https://colab.research.google.com/github/comet-ml/opik/blob/main" + notebookPath;
  colabLink.className =
    "inline-flex items-center gap-1.5 rounded-md border border-[#e5e7eb] bg-white px-3 py-1.5 text-sm font-medium text-[#16a34a] hover:bg-gray-50";
  colabLink.target = "_blank";
  colabLink.rel = "noreferrer";
  colabLink.innerHTML = `Run in Google Colab`;
  rightSection.appendChild(colabLink);

  heading.parentNode.insertBefore(container, heading.nextSibling);
};
