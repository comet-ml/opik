# Component Specifications

> **Figma Source**: [Custom Views - Components](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-13327&m=dev)

---

## Inline Components

### Text

> **Figma**: [Text Component](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-14918)

**Type**: Inline

**Variants**: Body, Bold accented, Caption

**Features**:
- Truncation support
- Monospaced option (for prompts)

**Props**:
| Prop | Type | Required | Description |
|------|------|----------|-------------|
| value | string | Yes | Text content |
| variant | "body" \| "bold" \| "caption" | No | Text style variant |
| truncate | boolean | No | Enable truncation |
| monospace | boolean | No | Use monospace font |

---

### Number

> **Figma**: [Number Component](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15116)

**Type**: Inline (Stackable)

**Features**:
- Multiple size variants
- Can be stacked horizontally

**Props**:
| Prop | Type | Required | Description |
|------|------|----------|-------------|
| value | number | Yes | Numeric value |
| label | string | No | Optional label |
| size | "xs" \| "sm" \| "md" \| "lg" \| "xl" | No | Display size |
| format | "decimal" \| "percent" \| "currency" | No | Number format |

---

### Label

> **Figma**: [Label Component](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15121)

**Type**: Inline

**Description**: Small text descriptor

**Rules**:
- Non-interactive
- Never the only child of a container

**Props**:
| Prop | Type | Required | Description |
|------|------|----------|-------------|
| text | string | Yes | Label text |

---

### Tag / Status Chip

> **Figma**: [Tags/Chips Component](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15706)

**Type**: Inline

**Use for**: State, severity, classification

**Examples**: `error`, `retrieval`, `cached`, `streamed`, `tool-call`

**Props**:
| Prop | Type | Required | Description |
|------|------|----------|-------------|
| label | string | Yes | Chip text |
| variant | "default" \| "error" \| "warning" \| "success" \| "info" | No | Color variant |

---

### Boolean (Bool)

> **Figma**: [Boolean Component](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=240-17129)

**Type**: Inline

**Description**: Default, compact value renderer for boolean values.

**Props**:
| Prop | Type | Required | Description |
|------|------|----------|-------------|
| value | boolean \| null | Yes | Boolean value |
| style | "check" \| "text" | No | Display style (default: "check") |

**Use for**: Key-value metadata (cached, streamed, is_error, success)

---

### BoolChip

> **Figma**: [BoolChip Component](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=240-17129)

**Type**: Inline

**Description**: A boolean rendered as a status chip (good when it's semantically a state).

**Rendering**: Same visual language as Tag/Chip

**Props**:
| Prop | Type | Required | Description |
|------|------|----------|-------------|
| value | boolean | Yes | Boolean value |
| trueLabel | string | No | Label when true |
| falseLabel | string | No | Label when false |

---

### LinkButton (Trace/Span Link)

> **Figma**: [LinkButton Component](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15707)

**Type**: Inline

**Description**: First-class links to traces and spans (not generic links).

**Rules**:
- Only valid when a trace/span ID exists
- Should not be used inside Input/Output blocks

**Props**:
| Prop | Type | Required | Description |
|------|------|----------|-------------|
| type | "trace" \| "span" | Yes | Link type |
| id | string | Yes | Trace or span ID |
| label | string | No | Display label |

---

### Link (Generic)

> **Figma**: [Link Component](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15133)

**Type**: Inline

**Description**: Generic hyperlink for external URLs.

**Props**:
| Prop | Type | Required | Description |
|------|------|----------|-------------|
| url | string | Yes | Target URL |
| text | string | No | Display text |
| label | string | No | Optional label above |

---

## Block Components

### Header

> **Figma**: [Header Component](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15121)

**Type**: Block

**Description**: Section or view title

**Rules**:
- Cannot be nested inside Level 2 container

**Props**:
| Prop | Type | Required | Description |
|------|------|----------|-------------|
| text | string | Yes | Header text |
| level | 1 \| 2 \| 3 | No | Heading level |

---

### Text Block (Input/Output)

> **Figma**: [Text Block Component](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-16919)

**Type**: Block

**Description**: Primary display for input and output content in trace view.

**Rules**:
- Always use for input and output in trace view
- Can be used for other data as well
- If nested in Level 2 component, may not need a label

**Props**:
| Prop | Type | Required | Description |
|------|------|----------|-------------|
| content | string | Yes | Text content |
| label | string | No | Optional label |
| role | "input" \| "output" \| "default" | No | Semantic role |

---

### Code Block

> **Figma**: [Code Block Component](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15130)

**Type**: Block (NOT Stackable)

**Features**:
- Language hint
- Line wrapping vs scroll
- Copy action

**Props**:
| Prop | Type | Required | Description |
|------|------|----------|-------------|
| content | string | Yes | Code content |
| language | string | No | Language for syntax highlighting |
| label | string | No | Optional label |
| wrap | boolean | No | Enable line wrapping |
| showLineNumbers | boolean | No | Show line numbers |
| showCopy | boolean | No | Show copy button |

---

### Divider

> **Figma**: [Divider Component](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-16768)

**Type**: Block

**Description**: Lightweight separation when no container is needed.

**Rules**:
- Cannot appear inside Level 2
- Cannot separate Input and Output

**Props**:
| Prop | Type | Required | Description |
|------|------|----------|-------------|
| (none) | - | - | No props needed |

---

### Image

> **Figma**: [Image Component](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15694)

**Type**: Block

**Features**:
- Preview with label
- Actions (expand, download)
- Tag display for metadata

**Props**:
| Prop | Type | Required | Description |
|------|------|----------|-------------|
| src | string | Yes | Image URL or base64 |
| alt | string | No | Alt text |
| label | string | No | Display label |
| tag | string | No | Metadata tag (e.g., dimensions) |

---

### Video

> **Figma**: [Video Component](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15694)

**Type**: Block

**Features**:
- Preview with play button
- Tag display for metadata

**Props**:
| Prop | Type | Required | Description |
|------|------|----------|-------------|
| src | string | Yes | Video URL |
| label | string | No | Display label |
| tag | string | No | Metadata tag (e.g., duration) |
| controls | boolean | No | Show controls |

---

### Audio

> **Figma**: [Audio Component](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-15695)

**Type**: Block

**Features**:
- Waveform preview
- Play/pause controls
- Tag display

**Props**:
| Prop | Type | Required | Description |
|------|------|----------|-------------|
| src | string | Yes | Audio URL |
| label | string | No | Display label |
| tag | string | No | Metadata tag (e.g., duration) |
| controls | boolean | No | Show controls |

---

### PDF / File

> **Figma**: [File Component](https://www.figma.com/design/cqul0LOdNZkFPmneOUnus2/Custom-views?node-id=239-10067)

**Type**: Block

**Props**:
| Prop | Type | Required | Description |
|------|------|----------|-------------|
| url | string | Yes | File URL |
| filename | string | No | Display filename |
| label | string | No | Optional label |
| type | "pdf" \| "generic" | No | File type hint |
