// MCP server page: the "Your coding agent" chip copies the installer prompt.
(function () {
  function flash(el, text) {
    var label = el.querySelector("span");
    if (!label) return;
    var original = label.textContent;
    label.textContent = text;
    setTimeout(function () { label.textContent = original; }, 2500);
  }
  document.addEventListener("click", function (event) {
    var chip = event.target.closest && event.target.closest("[data-opik-copy]");
    if (!chip) return;
    event.preventDefault();
    var text = chip.getAttribute("data-opik-copy");
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(
        function () { flash(chip, "Copied. Paste it into your agent"); },
        function () { flash(chip, "Copy failed. Select the prompt manually"); }
      );
    } else {
      window.prompt("Copy this prompt into your coding agent:", text);
    }
  });
})();
