// MCP server page: the "Install prompt" chip copies the installer prompt.
(function () {
  var timers = new WeakMap();

  function flash(chip, text) {
    var label = chip.querySelector("span");
    if (!label) return;
    if (!chip.dataset.opikLabel) chip.dataset.opikLabel = label.textContent;
    label.textContent = text;
    clearTimeout(timers.get(chip));
    timers.set(chip, setTimeout(function () {
      label.textContent = chip.dataset.opikLabel;
    }, 2500));
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

  document.addEventListener("click", function (event) {
    var chip = event.target.closest && event.target.closest("[data-opik-copy]");
    if (!chip) return;
    event.preventDefault();
    var text = chip.getAttribute("data-opik-copy");
    var done = function () { flash(chip, "Copied. Paste it into your agent"); };
    var fail = function () {
      flash(chip, "Copy blocked. The prompt is shown below");
      showFallback(chip, text);
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, fail);
    } else {
      fail();
    }
  });
})();
