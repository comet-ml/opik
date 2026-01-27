# Create Changelog

**Command**: `cursor create-changelog`

## Overview

Create a changelog file in the same format as existing Opik changelogs (e.g., `2025-12-18.mdx`). This command analyzes git commits in a specified time period, groups them by themes, and generates a formatted MDX changelog file with links to GitHub and documentation.

This workflow will:

- Fetch commits from git in a specified date range
- Filter for OPIK-related commits (commits with OPIK ticket numbers or relevant keywords)
- **🛑 CHECKPOINT 1**: Present commits for review (included vs excluded) and wait for approval
- Group commits by dynamically generated themes based on semantic analysis (themes are created based on actual content, not predefined categories)
- **🛑 CHECKPOINT 2**: Present proposed themes for review and wait for approval
- Format commits in MDX format matching existing changelog style
- Include GitHub links to commits and PRs
- Generate the changelog file in the correct location with date-based naming
- **📸 Integrate images** from the changelog images folder (if any)
- **🚀 Start Fern server** for local preview and final verification

**⚡ Interactive Mode**: This command includes two approval checkpoints where you can review and adjust the commit list and theme groupings before the final changelog is generated. After generation, a local preview server starts so you can verify the rendered changelog in your browser.

---

## Inputs

- **Start date (optional)**: Start date for the changelog period (e.g., "2025-12-19"). If not provided, automatically detected from the latest existing changelog's ending release tag.
- **End date (optional)**: End date for the changelog period (e.g., "2026-01-07"). Defaults to today if not provided.
- **Changelog date (required)**: The date for the changelog file name (e.g., "2026-01-07" for file `2026-01-07.mdx`)
- **Release versions (optional)**: Comma-separated list of release versions to include at the bottom (e.g., "1.9.57,1.9.58,1.9.59")
- **Previous release tag (optional)**: Previous release tag for GitHub compare link (e.g., "1.9.56")

---

## Steps

### 1. Preflight & Date Validation

- **Update repository**: Pull latest changes from remote to ensure commit history is current
  ```bash
  git fetch origin main
  git pull origin main
  ```

- **Verify Opik project structure**: Confirm we're in the Opik repository
- **Validate date formats**: Ensure dates are in valid format (YYYY-MM-DD)
- **Check date range**: Ensure start date is before end date
- **Verify git repository**: Ensure we're in a git repository with commit history
- **Create images folder**: Create a folder for changelog images at the appropriate location
  ```bash
  mkdir -p apps/opik-documentation/documentation/fern/img/changelog/[YYYY-MM-DD]
  ```
  This folder is where the user can add screenshots/images for the changelog. Inform the user:
  ```
  📁 Created image folder: apps/opik-documentation/documentation/fern/img/changelog/[YYYY-MM-DD]/
  
  You can add images to this folder at any time during the changelog creation process.
  Supported formats: PNG, JPG, GIF, SVG
  ```

---
### 1b. Auto-Detect Starting Point from Previous Changelog

Automatically determine the starting point for the new changelog by analyzing the most recent existing changelog:

**Changelog Directory:**
```
apps/opik-documentation/documentation/fern/docs/changelog/
```

**Process:**

1. **List all changelog files**: Get all `.mdx` files in the changelog directory
2. **Sort by filename**: Files are named `YYYY-MM-DD.mdx`, so alphabetical sorting gives chronological order
3. **Get the latest file**: Select the most recent changelog file (last in sorted order)
4. **Extract the ending release tag**: Parse the GitHub compare link to get the ending tag
   - Look for pattern: `https://github.com/comet-ml/opik/compare/[start-tag]...[end-tag]`
   - Extract `[end-tag]` - this is where the new changelog should start
5. **Get the commit date**: Use `git log` to find the date of the ending tag's commit
   ```bash
   git log -1 --format="%ai" [end-tag]
   ```

**Example:**
```bash
# List changelog files and get the latest
ls -1 apps/opik-documentation/documentation/fern/docs/changelog/*.mdx | sort | tail -1
# Result: apps/opik-documentation/documentation/fern/docs/changelog/2025-12-18.mdx

# Extract the compare link from the latest changelog
grep -o 'github.com/comet-ml/opik/compare/[^)]*' apps/opik-documentation/documentation/fern/docs/changelog/2025-12-18.mdx
# Result: github.com/comet-ml/opik/compare/1.9.40...1.9.56

# The ending tag is 1.9.56, so new changelog starts AFTER 1.9.56
# Get the commit associated with tag 1.9.56
git log -1 --format="%H %ai" 1.9.56
```

**Auto-Detection Logic:**
- Read the latest changelog file from the changelog directory
- Extract the ending tag from the GitHub compare link (the tag after `...`)
- Use this tag as the starting point: `git log [previous-end-tag]..HEAD`
- The `previous_end_tag` becomes the `previous-release-tag` input for the new GitHub compare link

**Important Notes:**
- The new changelog should include commits AFTER the previous changelog's ending tag
- Use `git log [previous-tag]..HEAD` to get all commits since the previous changelog
- If no previous changelog exists, fall back to manual start date input

---

### 2. Fetch Commits from Git

Fetch all commits since the previous changelog. Use tag-based range (preferred) or date range:

```bash
# Preferred: Use tag range from auto-detected previous changelog
git log [previous-end-tag]..HEAD --pretty=format:"%H|%ai|%an|%s" --first-parent main

# Alternative: Use date range if manually specified
git log --since="[start-date]" --until="[end-date]" --pretty=format:"%H|%ai|%an|%s" --first-parent main
```

**Filter for OPIK-related commits:**
- Commits with "OPIK-" in the message (ticket numbers)
- Commits with "[OPIK" in the message
- Commits with "OPIK" keyword (case-insensitive)
- Exclude merge commits unless they have OPIK references
- Exclude automated commits (dependabot, github-actions) unless they have OPIK references
- **Exclude version bumps**: Exclude commits with "Bump" in the message (dependency updates)

**Data to extract for each commit:**
- Commit hash (short: 7 chars, full: 40 chars)
- Author name
- Commit date
- Commit message (subject line)
- **Jira ticket number** (if present in message, e.g., `OPIK-1234` or `[OPIK-1234]`) → used to generate Jira link
- **PR number** (if present in message, e.g., `(#4644)`) → used to generate GitHub PR link
- Component tags: `[FE]`, `[BE]`, `[SDK]`, `[DOCS]`, `[NA]`

---
### 2b. 🛑 CHECKPOINT 1: Review & Approve Commits

**STOP HERE and present the commit analysis to the user for approval.**

After fetching all commits, display two separate lists for user review:

#### **Summary:**
```
📋 Changelog Commit Analysis
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Previous changelog: [filename]
  
🏷️  Starting tag (from previous changelog): [previous-end-tag] (e.g., 1.9.56)
🏷️  Ending tag (latest release):            [current-end-tag] (e.g., 1.9.78)

📅 Date range: [start-date] → [end-date]

📊 Commits summary:
   Total commits found: [N]
   ✅ To be INCLUDED: [X] commits
   ❌ To be EXCLUDED: [Y] commits
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

**How to get the tags:**
```bash
# Starting tag: Extract from previous changelog's compare link
grep -o 'github.com/comet-ml/opik/compare/[^)]*' [previous-changelog] | sed 's/.*\.\.\.//g'

# Ending tag: Get the latest release tag
git tag --sort=-creatordate | grep -E "^[0-9]+\.[0-9]+\.[0-9]+$" | head -1
```

#### **✅ COMMITS TO BE INCLUDED** (will appear in changelog):

Display as a numbered table with clickable Jira and GitHub PR links for easy review:
```
| #  | Date       | Author      | Commit Message (cleaned)                    | Jira | PR |
|----|------------|-------------|---------------------------------------------|------|-----|
| 1  | 2026-01-10 | John Doe    | Add dataset versioning UI                   | [OPIK-1234](https://comet-ml.atlassian.net/browse/OPIK-1234) | [#4644](https://github.com/comet-ml/opik/pull/4644) |
| 2  | 2026-01-11 | Jane Smith  | Implement experiment API                    | [OPIK-1235](https://comet-ml.atlassian.net/browse/OPIK-1235) | [#4645](https://github.com/comet-ml/opik/pull/4645) |
| 3  | 2026-01-12 | Bob Wilson  | Add Python client method                    | [OPIK-1236](https://comet-ml.atlassian.net/browse/OPIK-1236) | [#4646](https://github.com/comet-ml/opik/pull/4646) |
| 4  | 2026-01-13 | Alice Green | Add new feature without ticket              | —    | [#4647](https://github.com/comet-ml/opik/pull/4647) |
| ...| ...        | ...         | ...                                         | ...  | ... |
```

**Link Generation:**
- **Jira links**: Extract `OPIK-XXXX` from commit message → `https://comet-ml.atlassian.net/browse/OPIK-XXXX`
- **GitHub PR links**: Extract `(#XXXX)` from commit message → `https://github.com/comet-ml/opik/pull/XXXX`
- Use `—` (em dash) if no Jira ticket is present in the commit message
- Clean the commit message by removing the ticket number and PR number for display

#### **❌ COMMITS TO BE EXCLUDED** (will NOT appear in changelog):

Display with reason for exclusion:
```
| #  | Date       | Author      | Commit Message                              | Reason          |
|----|------------|-------------|---------------------------------------------|-----------------|
| 1  | 2026-01-10 | Dependabot  | Bump axios from 1.6.0 to 1.6.1              | Version bump    |
| 2  | 2026-01-11 | John Doe    | [OPIK-1237] [FE] Fix null pointer in UI     | Bug fix         |
| 3  | 2026-01-12 | CI Bot      | Merge pull request #4567                    | Merge commit    |
| 4  | 2026-01-13 | Jane Smith  | [OPIK-1238] [BE] Resolve timeout issue      | Bug fix         |
| ...| ...        | ...         | ...                                         | ...             |
```

#### **User Prompt:**

Ask the user:
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🔍 REVIEW REQUIRED

Please review the commits above.

Options:
  • Type "proceed" or "yes" to continue with these commits
  • Type "exclude [numbers]" to exclude specific commits (e.g., "exclude 2, 5, 7")
  • Type "include [numbers]" to include specific excluded commits (e.g., "include 3")
  • Type "abort" to cancel the changelog generation

Your choice: 
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

#### **Handle User Response:**

- **"proceed" / "yes"**: Continue to Step 3 (Theme Categorization)
- **"exclude [numbers]"**: Remove specified commits from the included list, show updated summary, ask again
- **"include [numbers]"**: Move specified commits from excluded to included list, show updated summary, ask again
- **"abort"**: Stop the changelog generation process

**DO NOT proceed to Step 3 until the user explicitly approves the commit list.**

---

### 3. Categorize Commits by Theme (Dynamic)

Group commits into themes **dynamically based on semantic analysis** of the commit messages. Do NOT use predefined/hard-coded categories.

#### **Dynamic Theme Generation Process:**

1. **Analyze all commits semantically**: Read through all filtered commits and understand what each one is about
2. **Identify natural groupings**: Look for commits that relate to the same feature area, component, or type of change
3. **Create meaningful theme names**: Generate theme names that accurately describe the grouped commits
4. **Select appropriate emojis**: Choose emojis that match the theme's meaning

#### **Theme Generation Guidelines:**

- **Themes should emerge from the content**: Don't force commits into predefined buckets
- **Be specific**: Instead of generic "Features", use specific names like "Dataset Versioning", "Prompt Playground Enhancements", "OpenTelemetry Integration"
- **Group by user value**: Think about what the user gains, not internal code structure
- **Aim for 3-7 themes**: Too few loses detail, too many becomes fragmented
- **Each theme should have 2+ related items**: Single-item themes should be merged or reconsidered

#### **Example Theme Names (for inspiration, NOT to be used as fixed categories):**

- "Prompt Playground Enhancements" (not "UI/UX Improvements")
- "Dataset Versioning & Management" (not "Features")
- "LangChain Integration Updates" (not "Integrations")
- "Experiment Comparison Tools" (not "Features & Enhancements")
- "Cost Tracking & Usage Analytics" (not "Performance")
- "Python SDK Improvements" (not "SDK Updates")

#### **Exclusion Rules (still apply):**

- **Exclude bug fixes**: Do not include commits with "Fix", "Resolve", "Correct", "Repair", "Bug" in the message
- **Exclude version bumps**: Do not include commits with "Bump" in the message (dependency/version updates)
- **Exclude maintenance**: Routine maintenance, dependency updates, CI fixes

#### **Semantic Analysis Approach:**

1. Extract key nouns and verbs from each commit message
2. Identify the feature area or component being changed
3. Understand the user-facing impact of the change
4. Group commits that share the same feature area or user benefit
5. Name the theme based on what users will care about

---
### 3b. 🛑 CHECKPOINT 2: Review & Approve Themes

**STOP HERE and present the proposed themes to the user for approval.**

After analyzing commits and generating themes, display the proposed structure:

#### **Proposed Theme Structure:**

```
📊 Proposed Changelog Themes
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Total commits to include: [N]
Themes identified: [M]
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

🎯 THEME 1: [Emoji] [Theme Name] ([X] commits)
   ├── [Commit 1 message - cleaned]
   ├── [Commit 2 message - cleaned]
   └── [Commit 3 message - cleaned]

🎯 THEME 2: [Emoji] [Theme Name] ([Y] commits)
   ├── [Commit 4 message - cleaned]
   └── [Commit 5 message - cleaned]

🎯 THEME 3: [Emoji] [Theme Name] ([Z] commits)
   ├── [Commit 6 message - cleaned]
   ├── [Commit 7 message - cleaned]
   └── [Commit 8 message - cleaned]

[... more themes ...]

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

#### **User Prompt:**

Ask the user:
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🔍 REVIEW REQUIRED

Please review the proposed themes above.

Options:
  • Type "proceed" or "yes" to generate the changelog with these themes
  • Type "rename [theme#] [new name]" to rename a theme (e.g., "rename 1 Dataset Management")
  • Type "merge [theme#] [theme#]" to merge two themes (e.g., "merge 2 3")
  • Type "split [theme#]" to split a theme into multiple (will prompt for details)
  • Type "move [commit] to [theme#]" to move a commit between themes
  • Type "reorder [theme#] [new position]" to change theme order (e.g., "reorder 3 1")
  • Type "regenerate" to re-analyze and generate new theme suggestions
  • Type "abort" to cancel the changelog generation

Your choice: 
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

#### **Handle User Response:**

- **"proceed" / "yes"**: Continue to Step 4 (Format Commits for Changelog)
- **"rename [theme#] [new name]"**: Update theme name, show updated structure, ask again
- **"merge [theme#] [theme#]"**: Combine two themes, prompt for new name, show updated structure, ask again
- **"split [theme#]"**: Ask user how to split, create new themes, show updated structure, ask again
- **"move [commit] to [theme#]"**: Move commit to different theme, show updated structure, ask again
- **"reorder [theme#] [new position]"**: Change theme order, show updated structure, ask again
- **"regenerate"**: Re-run theme analysis with different approach, show new suggestions, ask again
- **"abort"**: Stop the changelog generation process

**DO NOT proceed to Step 4 until the user explicitly approves the theme structure.**

---

### 4. Format Commits for Changelog

For each theme category, format commits as:

**Format for Features (Conversational Style):**
```markdown
## 🚀 [Feature Name]

We've [action verb] [feature area] to [benefit/value proposition].

**What's new:** (or "What's improved:")

- **[Feature 1]** - [Conversational description explaining what it does and why it matters]
- **[Feature 2]** - [Conversational description with context]

[Optional closing sentence that ties features together or explains the overall impact]

👉 [Documentation link if available]
```

**Format for Other Categories (Conversational Style):**
```markdown
## [Emoji] [Category Name]

We've made [category] [better/more powerful/easier] with [key improvements].

**What's improved:**

- **[Improvement 1]** - [Conversational description]
- **[Improvement 2]** - [Conversational description]

[Optional summary sentence]
```

**Writing Guidelines:**
- **Use conversational language**: Write as if explaining to a colleague, not listing features
- **Start with impact**: Begin each section with what users get, not what was changed
- **Explain benefits**: Focus on "why this matters" not just "what was added"
- **Group related items**: Use narrative text to connect related features
- **Use active voice**: "We've added" not "Added"
- **Add context**: Explain how features work together or improve workflows

**Commit Message Processing:**
- **Remove component tags**: Strip `[FE]`, `[BE]`, `[SDK]`, `[DOCS]`, `[NA]` from display
- **Remove ticket numbers**: Strip `OPIK-XXXX` or `[OPIK-XXXX]` from display
- **Remove PR numbers**: Strip `(#XXXX)` from display
- Format commit message: Clean message without tags, tickets, or PR numbers
- **Do NOT create GitHub/Jira links**: Do not include links to commits, PRs, or Jira tickets
- **Check PR descriptions for documentation links**: If PR description contains external-facing documentation links (e.g., `https://www.comet.com/docs/opik/...`), include them in the changelog entry

---

### 5. Generate MDX File

Create the changelog file at:
`apps/opik-documentation/documentation/fern/docs/changelog/[changelog-date].mdx`

**File Structure:**
```markdown
Here are the most relevant improvements we've made since the last release:

## 📊 [Theme 1]

[Content for theme 1]

## 🐛 [Theme 2]

[Content for theme 2]

[... more themes ...]

---

And much more! 👉 [See full commit log on GitHub](https://github.com/comet-ml/opik/compare/[previous-tag]...[current-tag])

_Releases_: `[version1]`, `[version2]`, `[version3]`
```

**GitHub Compare Link:**
- If `previous-release-tag` provided: `https://github.com/comet-ml/opik/compare/[previous-tag]...HEAD`
- Otherwise: Find the last release tag before start date and use that

**Release Versions:**
- If `release-versions` provided: Use those
- Otherwise: Extract from git tags in the date range

---

### 5b. Integrate Images from Changelog Folder

After generating the initial MDX file, check if the user has added images to the changelog images folder:

**Image Folder Location:**
```
apps/opik-documentation/documentation/fern/img/changelog/[YYYY-MM-DD]/
```

**Process:**

1. **Check for images**: List all files in the changelog image folder
   ```bash
   ls -1 apps/opik-documentation/documentation/fern/img/changelog/[YYYY-MM-DD]/
   ```

2. **If images exist**, display them to the user:
   ```
   📸 Found [N] images in the changelog folder:
   
   1. AWS_Bedrock.png
   2. Multi-project-rule-support.png
   3. OpikAI-model-selector.png
   4. Playground-metric-results.png
   
   Would you like to add these images to the changelog?
   ```

3. **Ask user for image placement**: For each image, ask which theme/section it belongs to:
   ```
   For each image, specify where it should be placed:
   
   Image: AWS_Bedrock.png
   Options:
     • Type theme number and item number (e.g., "1.3" for Theme 1, Item 3)
     • Type "skip" to not include this image
   
   Placement: 
   ```

4. **Insert images into MDX**: Use the `<Frame>` component format:
   ```markdown
   - **Feature Name** - Feature description
   
   <Frame>
     <img src="/img/changelog/[YYYY-MM-DD]/[image-name].png" alt="[Descriptive alt text]" />
   </Frame>
   ```

**Image Format Guidelines:**
- Use `<Frame>` wrapper for consistent styling
- Include descriptive `alt` text for accessibility
- Place image immediately after the related bullet point
- Image path format: `/img/changelog/[YYYY-MM-DD]/[filename]`

**Example:**
```markdown
- **Native AWS Bedrock Integration** - Bedrock is now available as a native provider in the Playground

<Frame>
  <img src="/img/changelog/2026-01-13/AWS_Bedrock.png" alt="AWS Bedrock integration in the Playground provider selection" />
</Frame>

- **Next Feature** - Description continues...
```

**If no images found:**
- Continue to the next step without prompting
- Inform user they can add images later and manually update the MDX file

---

### 6. Group Related Commits

Within each theme, group related commits:

- **Same ticket number**: Group commits with same OPIK-XXXX together
- **Same feature area**: Group commits about same feature (e.g., "dataset", "experiment", "dashboard")
- **Same component**: Group by component tag (`[FE]`, `[BE]`, `[SDK]`)

**Grouping Format (Conversational):**
```markdown
## 📊 Dataset Versioning & Management

We've enhanced dataset versioning capabilities to give you better control over your data lifecycle and experiment tracking.

**What's improved:**

- **Dataset version selection** - Easily select and manage dataset versions directly in the UI, with support for linking versions to experiments
- **Experiment-dataset linking** - Dataset versions can now be linked to experiments, providing clear traceability from data to results
- **Improved filtering** - Better filtering support for versioned dataset items makes it easier to find what you need

These changes make it simpler to track which data was used in which experiments, improving reproducibility and data governance.
```

---

### 7. Add Documentation Links

For each feature/theme, check PR descriptions for external-facing documentation links:

- **Check PR descriptions**: For each PR, fetch the PR description and look for documentation links
- **Look for external documentation links**: Search for patterns like:
  - `https://www.comet.com/docs/opik/...`
  - `https://docs.comet.com/opik/...`
  - Links to documentation pages (not internal GitHub/Jira links)
- **Add documentation links**: If found, add `👉 [Documentation](link)` after the feature description
- **Do NOT create links**: Only use links that are explicitly mentioned in PR descriptions

**PR Description Parsing:**
- Fetch PR description using GitHub API or git show
- Search for markdown links: `[text](url)`
- Filter for external documentation URLs (not GitHub, Jira, or internal links)
- Extract and include in changelog entry

---

### 8. Quality Checks

Before finalizing the changelog:

- **Verify all commits are included**: Check commit count matches
- **Verify links work**: Ensure GitHub links are valid
- **Check formatting**: Ensure MDX syntax is correct
- **Verify date format**: Ensure file name matches date format
- **Check for duplicates**: Ensure no commit appears twice

---
### 9. 🚀 Start Local Preview Server

After generating the changelog file, start the Fern documentation server so you can preview the changes locally.

**Start the Fern Dev Server:**

```bash
cd apps/opik-documentation/documentation
npm install  # Only needed first time or if dependencies changed
npm run dev
```

This will:
1. Start the Fern documentation server
2. Open a local preview at `http://localhost:3000` (or similar port)
3. Allow you to navigate to the changelog section and verify the new entry

**What to verify:**
- Navigate to the Changelog section in the documentation
- Find your new changelog entry (by date)
- Verify:
  - ✅ All themes are displayed correctly
  - ✅ Formatting looks correct (headers, bullet points, emojis)
  - ✅ Documentation links work (if any)
  - ✅ GitHub compare link at the bottom works
  - ✅ Release versions are listed correctly
  - ✅ No broken MDX syntax or rendering issues

**User Prompt:**
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🌐 LOCAL PREVIEW SERVER STARTED

The Fern documentation server is now running.

📍 Open your browser to: http://localhost:3000
📂 Navigate to: Changelog → [Your changelog date]

Please review the changelog in the browser.

Options:
  • Type "done" when you've finished reviewing and are satisfied
  • Type "edit" if you need to make manual adjustments to the MDX file
  • Type "regenerate" to go back and regenerate the changelog

Your choice: 
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

**Handle User Response:**
- **"done"**: Complete the workflow, keep the server running for further review
- **"edit"**: Open the MDX file for manual editing, then refresh browser to see changes
- **"regenerate"**: Stop server, go back to checkpoint 2 to adjust themes

---

## Output

**Files/Folders Created:**
- `apps/opik-documentation/documentation/fern/docs/changelog/[YYYY-MM-DD].mdx` - The changelog MDX file
- `apps/opik-documentation/documentation/fern/img/changelog/[YYYY-MM-DD]/` - Folder for changelog images

**File Contents:**
- Formatted MDX changelog with:
  - **Conversational tone**: Each section starts with engaging intro sentences explaining what users get
  - **Narrative structure**: Features grouped with explanatory text, not just bullet lists
  - **Benefit-focused**: Explains "why this matters" not just "what was added"
  - Dynamically generated themed sections with contextual emojis
  - Grouped commits by feature area with narrative context
  - Clean commit messages (no component tags, ticket numbers, or PR links)
  - External documentation links from PR descriptions (if available)
  - GitHub compare link at bottom
  - Release versions list
  - **No bug fixes section**
  - **No component tags** (FE, BE, SDK, etc.)
  - **No Jira/GitHub issue links**
  - **No version bumps**

---

## Example Usage

```bash
# Create changelog for December 19, 2025 to January 7, 2026
cursor create-changelog \
  --start-date "2025-12-19" \
  --end-date "2026-01-07" \
  --changelog-date "2026-01-07" \
  --release-versions "1.9.57,1.9.58,1.9.59,1.9.60" \
  --previous-release-tag "1.9.56"
```

---

## Implementation Notes

### Git Commands

```bash
# Get commits in date range
git log --since="2025-12-19" --until="2026-01-07" \
  --pretty=format:"%H|%ai|%an|%s" \
  --first-parent main

# Filter for OPIK commits
git log --since="2025-12-19" --until="2026-01-07" \
  --grep="OPIK" --grep="\[OPIK" \
  --pretty=format:"%H|%ai|%an|%s" \
  --first-parent main

# Get release tags in range
git tag --sort=-creatordate | grep -E "^[0-9]+\.[0-9]+\.[0-9]+$"
```

### Commit Message Parsing

**Remove ticket number:**
- Pattern: `OPIK-[0-9]+` or `\[OPIK-[0-9]+\]`
- Example: `[OPIK-3614]` → remove from display

**Remove PR number:**
- Pattern: `\(#[0-9]+\)`
- Example: `(#4644)` → remove from display

**Remove component tags:**
- Pattern: `\[(FE|BE|SDK|DOCS|NA)\]`
- Example: `[FE]` → remove from display

**Extract documentation links from PR:**
- Fetch PR description using GitHub API
- Search for markdown links: `\[.*?\]\(https?://.*?\)`
- Filter for external documentation URLs (comet.com/docs, docs.comet.com)
- Extract URL and link text

### Theme Detection (Dynamic)

Analyze commits semantically to create dynamic themes:

```python
def generate_themes(commits):
    """
    Dynamically generate themes based on commit content.
    Do NOT use predefined categories.
    """
    
    # Step 1: Filter out excluded commits
    filtered_commits = []
    for commit in commits:
        message_lower = commit.message.lower()
        
        # Exclude bug fixes
        if any(kw in message_lower for kw in ["fix", "resolve", "correct", "repair", "bug"]):
            continue
        
        # Exclude version bumps
        if "bump" in message_lower:
            continue
            
        filtered_commits.append(commit)
    
    # Step 2: Analyze commits semantically
    # - Extract key terms (nouns, verbs, feature areas)
    # - Identify the component or feature being changed
    # - Understand the user-facing value
    
    # Step 3: Group by semantic similarity
    # - Commits about the same feature area go together
    # - Commits with similar user impact go together
    # - Use NLP/semantic analysis, not keyword matching
    
    # Step 4: Generate theme names
    # - Name should reflect the user value, not internal structure
    # - Be specific: "Prompt Playground" not "UI Features"
    # - Include relevant emoji based on theme meaning
    
    # Step 5: Return dynamically generated themes
    # Each theme has:
    # - name: Specific, descriptive name
    # - emoji: Contextually appropriate emoji
    # - commits: List of related commits
    # - summary: Brief description of what changed
    
    return themes
```

**Key Principles:**
- Themes are generated fresh for each changelog
- No predefined category list exists
- Theme names should be meaningful to users
- Groupings should feel natural, not forced

---


---

## Error Handling

- **Invalid date format**: Prompt user to use YYYY-MM-DD format
- **No commits found**: Inform user and suggest checking date range
- **Git errors**: Check if in git repository, if branch exists
- **File already exists**: Ask user if they want to overwrite
- **Invalid release tag**: Warn user but continue without compare link
- **PR description fetch fails**: Continue without documentation links (don't fail the process)
- **No documentation links found**: Continue normally (documentation links are optional)

---

## Related Commands

- `cursor create-prd` - Create PRD for features
- `cursor create-prototype` - Create prototype from PRD
- `cursor weekly-product-updates` - Weekly product updates

---

**End of Command Definition**

