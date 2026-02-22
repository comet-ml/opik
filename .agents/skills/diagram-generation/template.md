# HTML Diagram Template

Use this as the base structure for generating diagrams. Adapt sections based on what the changes require.

## Base HTML Structure

```html
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>OPIK-{TICKET} – {Title}</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #0d1117; color: #c9d1d9; padding: 40px; }
  .container { max-width: 960px; margin: 0 auto; padding: 40px; }
  h1 { color: #58a6ff; font-size: 24px; margin-bottom: 6px; }
  .subtitle { color: #8b949e; font-size: 14px; margin-bottom: 36px; }
  .section { margin-bottom: 32px; }
  .section-label { font-size: 11px; font-weight: 700; letter-spacing: 1.5px; text-transform: uppercase; margin-bottom: 14px; display: flex; align-items: center; gap: 8px; }
  .section-label .dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
  .section-label.blue { color: #58a6ff; }
  .section-label.blue .dot { background: #58a6ff; }
  .section-label.red { color: #f85149; }
  .section-label.red .dot { background: #f85149; }
  .section-label.green { color: #3fb950; }
  .section-label.green .dot { background: #3fb950; }
  .section-label.purple { color: #bc8cff; }
  .section-label.purple .dot { background: #bc8cff; }

  .flow { display: flex; align-items: center; gap: 0; margin: 10px 0; flex-wrap: wrap; }
  .box { padding: 9px 14px; border-radius: 8px; font-size: 12px; white-space: nowrap; font-weight: 500; line-height: 1.4; }
  .arrow { padding: 0 5px; flex-shrink: 0; font-size: 18px; color: #30363d; }
  .arrow.green { color: #3fb950; }
  .arrow.red { color: #f85149; }

  .box.client { background: #1a1f2e; border: 1px solid #2d3548; color: #93c5fd; }
  .box.layer { background: #161b22; border: 1px solid #21262d; color: #c9d1d9; }
  .box.layer b { color: #58a6ff; }
  .box.converter { background: #122117; border: 1px solid #1e5631; color: #86efac; }
  .box.converter b { color: #3fb950; }
  .box.bad { background: #2d1111; border: 2px solid #f85149; color: #fca5a5; }
  .box.good { background: #0d2818; border: 2px solid #3fb950; color: #86efac; }
  .box.sql { background: #1b1728; border: 1px solid #3b2d5e; color: #c4b5fd; font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 11px; }

  .code { font-family: 'JetBrains Mono', 'Fira Code', monospace; background: #161b22; padding: 1px 5px; border-radius: 4px; font-size: 11px; color: #e6edf3; }
  .note { color: #6e7681; font-size: 11px; margin-top: 5px; margin-left: 4px; }
  .divider { border: none; border-top: 1px solid #21262d; margin: 24px 0; }

  .tag { display: inline-block; font-size: 10px; padding: 2px 8px; border-radius: 20px; font-weight: 600; margin-left: 6px; vertical-align: middle; }
  .tag.new { background: #0d419d; color: #79c0ff; }
  .tag.modified { background: #3d2e00; color: #d29922; }

  .banner { width: 100%; border-radius: 10px; padding: 16px 24px; margin-bottom: 16px; }
  .banner.problem { background: linear-gradient(135deg, #2d1215, #1c1012); border: 1px solid #f8514966; }
  .banner.solution { background: linear-gradient(135deg, #0c2d1a, #0d1117); border: 1px solid #3fb95066; }
  .banner h2 { font-size: 14px; margin-bottom: 6px; }
  .banner.problem h2 { color: #f85149; }
  .banner.solution h2 { color: #3fb950; }
  .banner p { font-size: 12.5px; color: #b1bac4; line-height: 1.6; }

  .guard { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; font-size: 13px; }
  .guard-icon { width: 20px; height: 20px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 11px; flex-shrink: 0; background: #064e3b; color: #6ee7b7; }

  .copy-btn {
    position: fixed; top: 16px; right: 16px; z-index: 100;
    background: #238636; color: #fff; border: none; border-radius: 8px;
    padding: 10px 20px; font-size: 13px; font-weight: 600; cursor: pointer;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
    transition: background 0.2s;
  }
  .copy-btn:hover { background: #2ea043; }
  .copy-btn.copied { background: #1a7f37; }
</style>
</head>
<body>

<button class="copy-btn" onclick="copyAsImage(this)">Copy as image</button>

<div class="container" id="diagram">

  <h1>OPIK-{TICKET} &mdash; {Title}</h1>
  <div class="subtitle">{One-line summary of what this change does}</div>

  <!-- ─── SECTION: Request / Data Flow ─── -->
  <!-- Use .flow rows with .box and .arrow elements -->

  <hr class="divider">

  <!-- ─── SECTION: Why This Approach ─── -->
  <!-- Use .banner.problem and .banner.solution side by side -->
  <!-- Or use before/after flow comparison -->

  <hr class="divider">

  <!-- ─── SECTION: Files Changed ─── -->
  <!-- Use grid layout with .box.layer for modified, .box.converter for new -->

  <hr class="divider">

  <!-- ─── SECTION: Key Design Decisions ─── -->
  <!-- Use .guard elements with numbered icons -->

</div>

<script src="https://cdnjs.cloudflare.com/ajax/libs/html2canvas/1.4.1/html2canvas.min.js"></script>
<script>
async function copyAsImage(btn) {
  btn.textContent = 'Rendering...';
  try {
    const el = document.getElementById('diagram');
    const canvas = await html2canvas(el, {
      backgroundColor: '#0d1117',
      scale: 2,
      useCORS: true,
    });
    const blob = await new Promise(r => canvas.toBlob(r, 'image/png'));
    await navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })]);
    btn.textContent = 'Copied!';
    btn.classList.add('copied');
    setTimeout(() => { btn.textContent = 'Copy as image'; btn.classList.remove('copied'); }, 2000);
  } catch (e) {
    console.error(e);
    btn.textContent = 'Failed — check console';
    setTimeout(() => { btn.textContent = 'Copy as image'; }, 3000);
  }
}
</script>

</body>
</html>
```

## Section Recipes

### Flow Row (horizontal data flow)
```html
<div class="section">
  <div class="section-label blue"><span class="dot"></span> REQUEST FLOW</div>
  <div class="flow">
    <div class="box client">GET /endpoint</div>
    <div class="arrow green">→</div>
    <div class="box layer"><b>Resource</b><br>validation</div>
    <div class="arrow green">→</div>
    <div class="box layer"><b>Service</b><br>business logic</div>
    <div class="arrow green">→</div>
    <div class="box sql">WHERE clause</div>
  </div>
  <div class="note">Description of the flow</div>
</div>
```

### Problem / Solution Side-by-Side
```html
<div style="display: flex; gap: 16px; flex-wrap: wrap;">
  <div class="banner problem" style="flex: 1; min-width: 380px;">
    <h2>Problem</h2>
    <p>What was wrong...</p>
  </div>
  <div class="banner solution" style="flex: 1; min-width: 380px;">
    <h2>Solution</h2>
    <p>How we fixed it...</p>
  </div>
</div>
```

### Before / After Flow Comparison
```html
<div style="display: flex; gap: 24px; flex-wrap: wrap;">
  <div style="flex: 1; min-width: 380px;">
    <div style="color: #f85149; font-size: 12px; font-weight: 600; margin-bottom: 8px;">Before</div>
    <div class="flow">
      <div class="box client">Input</div>
      <div class="arrow red">→</div>
      <div class="box bad">Error</div>
    </div>
  </div>
  <div style="flex: 1; min-width: 380px;">
    <div style="color: #3fb950; font-size: 12px; font-weight: 600; margin-bottom: 8px;">After</div>
    <div class="flow">
      <div class="box client">Input</div>
      <div class="arrow green">→</div>
      <div class="box good">Success</div>
    </div>
  </div>
</div>
```

### Files Changed Grid
```html
<div class="section">
  <div class="section-label blue"><span class="dot"></span> FILES CHANGED</div>
  <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 8px; max-width: 700px;">
    <div class="box layer" style="font-size: 11px;"><b>ExistingFile</b> — what changed</div>
    <div class="box converter" style="font-size: 11px;"><b>NewFile</b> — new</div>
  </div>
</div>
```

### Design Decisions / Safety Guards
```html
<div class="section">
  <div class="section-label green"><span class="dot"></span> KEY DESIGN DECISIONS</div>
  <div class="guard"><div class="guard-icon">1</div> Decision or constraint explanation</div>
  <div class="guard"><div class="guard-icon">2</div> Another decision</div>
</div>
```
