# Diagram Style Guide

## Theme

- Background: `#0d1117` (GitHub dark)
- Text: `#c9d1d9` (primary), `#8b949e` (subtitle), `#6e7681` (notes)
- Font: `-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif`
- Code font: `'JetBrains Mono', 'Fira Code', monospace`
- Container: `max-width: 960px; margin: 0 auto; padding: 40px`

## Semantic Colors

| Role | Color | Use for |
|------|-------|---------|
| **Blue** (API/info) | `#58a6ff` | Titles, API endpoints, request flow, highlights |
| **Green** (new/good) | `#3fb950` | New files, success states, solutions, converters |
| **Red** (error/bad) | `#f85149` | Errors, problems, breaking changes, deadlocks |
| **Purple** (special) | `#bc8cff` | SQL, mappers, enums, special logic |
| **Yellow** (highlight) | `#fbbf24` | Key fields, important values |
| **Orange** (modified) | `#d29922` | Modified files, DAO changes |

## Section Labels

```html
<div class="section-label blue"><span class="dot"></span> SECTION TITLE</div>
```

- Font: 11px, 700 weight, 1.5px letter-spacing, uppercase
- Colored dot (8px circle) before text
- Colors: `.blue`, `.red`, `.green`, `.purple`

## Flow Rows

```html
<div class="flow">
  <div class="box client">Source</div>
  <div class="arrow arr-r green">→</div>
  <div class="box layer"><b>Component</b><br>detail</div>
</div>
```

- `display: flex; align-items: center`
- Boxes: `padding: 9px 14px; border-radius: 8px; font-size: 12px`
- Use `→` entity or SVG arrows between boxes

## Box Themes

| Class | Background | Border | Text | Use |
|-------|-----------|--------|------|-----|
| `.client` | `#1a1f2e` | `#2d3548` | `#93c5fd` | Client/request origin |
| `.layer` | `#161b22` | `#21262d` | `#c9d1d9` | Generic layer/component |
| `.converter` | `#122117` | `#1e5631` | `#86efac` | Converters, new logic |
| `.bad` | `#2d1111` | `#f85149` 2px | `#fca5a5` | Error states, problems |
| `.good` | `#0d2818` | `#3fb950` 2px | `#86efac` | Success states, solutions |
| `.sql` | `#1b1728` | `#3b2d5e` | `#c4b5fd` | SQL/database queries |

## Architecture Trees

- Vertical lines: `width: 1px; height: 14px; background: #30363d`
- Horizontal branches: `height: 1px; background: #30363d`
- Base class: dashed border (`1px dashed #475569`)
- Implementations: solid border, colored by type
- NEW badge: `background: #3fb950; color: #0d1117; font-size: 9px; border-radius: 4px`

## Problem / Solution Banners

```html
<div class="banner problem">
  <h2>Problem</h2>
  <p>Description...</p>
</div>
<div class="banner solution">
  <h2>Solution</h2>
  <p>Description...</p>
</div>
```

- Problem: `background: linear-gradient(135deg, #2d1215, #1c1012); border: 1px solid #f8514966`
- Solution: `background: linear-gradient(135deg, #0c2d1a, #0d1117); border: 1px solid #3fb95066`

## Files Changed Grid

```html
<div style="display: grid; grid-template-columns: 1fr 1fr; gap: 8px; max-width: 700px;">
  <div class="box layer" style="font-size: 11px;"><b>FileName</b> — change summary</div>
  <div class="box converter" style="font-size: 11px;"><b>NewFile</b> — new</div>
</div>
```

- 2-column grid for files
- Use `.layer` for modified, `.converter` for new files

## Dividers

```html
<hr class="divider">
```
`border: none; border-top: 1px solid #21262d; margin: 24px 0`

## Notes

```html
<div class="note">Explanation with <span class="code">inline code</span></div>
```
`color: #6e7681; font-size: 11px`

## Tags

```html
<span class="tag new">new</span>
<span class="tag modified">modified</span>
```

- New: `background: #0d419d; color: #79c0ff`
- Modified: `background: #3d2e00; color: #d29922`

## Safety Guards / Numbered Lists

```html
<div class="guard">
  <div class="guard-icon guard-green">1</div>
  Guard description
</div>
```

- Icon: 20px circle, numbered
- Green: `background: #064e3b; color: #6ee7b7`
