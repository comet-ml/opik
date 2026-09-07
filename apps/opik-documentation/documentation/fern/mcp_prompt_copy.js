// MCP server page: the "Install prompt" chip copies the installer prompt.
// Feedback is icon-only: the copy icon turns into a check for a moment.
(function () {
  var timers = new WeakMap();

  function mark(chip, state) {
    chip.classList.remove("is-copied", "is-failed");
    chip.classList.add(state);
    clearTimeout(timers.get(chip));
    timers.set(chip, setTimeout(function () {
      chip.classList.remove("is-copied", "is-failed");
    }, 2000));
  }

  // When the clipboard is unavailable or refused, show the prompt as selectable
  // text right under the chip row instead of a modal dialog.
  function showFallback(chip, text) {
    var row = chip.closest(".mcp-clients") || chip.parentNode;
    var pre = row.nextElementSibling;
    if (!pre || !pre.classList.contains("mcp-prompt-fallback")) {
      pre = document.createElement("pre");
      pre.className = "mcp-prompt-fallback";
      row.parentNode.insertBefore(pre, row.nextSibling);
    }
    pre.textContent = text;
    pre.hidden = false;
  }

  function hideFallback(chip) {
    var row = chip.closest(".mcp-clients") || chip.parentNode;
    var pre = row.nextElementSibling;
    if (pre && pre.classList.contains("mcp-prompt-fallback")) pre.hidden = true;
  }

  document.addEventListener("click", function (event) {
    var chip = event.target.closest && event.target.closest("[data-opik-copy]");
    if (!chip) return;
    event.preventDefault();
    var text = chip.getAttribute("data-opik-copy");
    var done = function () { mark(chip, "is-copied"); hideFallback(chip); };
    var fail = function () { mark(chip, "is-failed"); showFallback(chip, text); };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, fail);
    } else {
      fail();
    }
  });
})();
