.PHONY: cursor codex claude clean-agents help hooks hooks-remove precommit precommit-all

AI_DIR := .agents
CURSOR_DIR := .cursor
CLAUDE_DIR := .claude

define link_agent_config
	@if [ ! -d "$(AI_DIR)" ]; then \
		echo "Error: $(AI_DIR)/ does not exist. Run the migration first."; \
		exit 1; \
	fi
	@if [ -e "$(1)" ] && [ ! -L "$(1)" ]; then \
		echo "Error: $(1) exists and is not a symlink."; \
		echo "Remove it manually if you want to use .agents/ as source."; \
		exit 1; \
	fi
	@if [ -L "$(1)" ]; then \
		echo "$(1) symlink already exists"; \
	else \
		echo "Creating symlink $(1) -> $(AI_DIR)..."; \
		ln -s "$(AI_DIR)" "$(1)"; \
	fi
endef

define clean_synced_files
	@if [ -d "$(AI_DIR)/$(1)" ]; then \
		find "$(AI_DIR)/$(1)" $(2) 2>/dev/null | while read src; do \
			rel=$${src#$(AI_DIR)/$(1)/}; \
			dest_rel=$$rel; \
			$(3) \
			dest="$(CLAUDE_DIR)/$(4)/$$dest_rel"; \
			[ -f "$$dest" ] && rm -f "$$dest" && echo "  Removed $$dest" || true; \
		done; \
	fi
endef

help:
	@echo "AI Editor Configuration Sync"
	@echo ""
	@echo "  make cursor        - Ensure .cursor symlink points to .agents/"
	@echo "  make codex         - Ensure .codex symlink + generate Codex AGENTS.override.md from .agents/rules/*.mdc"
	@echo "  make claude        - Sync .agents/ to .claude/ + generate .mcp.json (preserves local files)"
	@echo "  make clean-agents  - Remove synced files from .claude/ and local Codex artifacts"
	@echo ""
	@echo "Git Hooks (pre-commit framework — install once per clone)"
	@echo ""
	@echo "  make hooks         - Install the pre-commit framework hook"
	@echo "  make hooks-remove  - Uninstall the pre-commit framework hook"
	@echo ""
	@echo "Lint Checks (root .pre-commit-config.yaml is the single source of truth)"
	@echo "  make precommit       - Run hooks on changed files (vs origin/main)"
	@echo "  make precommit-all   - Run all hooks on the whole repo (full audit)"
	@echo ""

# Sync to Cursor (symlink .cursor -> .agents)
cursor:
	$(call link_agent_config,$(CURSOR_DIR))
	@echo "Cursor ready!"

codex:
	$(call link_agent_config,.codex)
	@./scripts/sync-codex.sh "$(AI_DIR)" "AGENTS.md" "AGENTS.override.md"
	@echo "Codex ready! Generated AGENTS.override.md and .agents/generated/codex/rules/*.md"

# Sync to Claude (preserve nested structure, convert frontmatter)
# Converts: .mdc -> .md, Cursor frontmatter -> Claude frontmatter
# Cursor: globs/alwaysApply -> Claude: paths
# Preserves local customizations (files in .claude/ not in .agents/)
claude:
	@if [ ! -d "$(AI_DIR)/rules" ]; then \
		echo "Error: $(AI_DIR)/rules/ does not exist."; \
		exit 1; \
	fi
	@echo "Syncing $(AI_DIR)/rules/ to $(CLAUDE_DIR)/rules/ (preserving local files)..."
	@mkdir -p $(CLAUDE_DIR)/rules
	@find $(AI_DIR)/rules -name "*.mdc" | while read src; do \
		rel=$${src#$(AI_DIR)/rules/}; \
		dest="$(CLAUDE_DIR)/rules/$${rel%.mdc}.md"; \
		mkdir -p "$$(dirname "$$dest")"; \
		./scripts/convert-frontmatter.sh cursor-to-claude "$$src" "$$dest"; \
		echo "  $$rel -> $${rel%.mdc}.md"; \
	done
	@# Sync commands (preserve local files)
	@if [ -d "$(AI_DIR)/commands" ]; then \
		echo "Syncing $(AI_DIR)/commands/ to $(CLAUDE_DIR)/commands/ (preserving local files)..."; \
		mkdir -p $(CLAUDE_DIR)/commands; \
		find $(AI_DIR)/commands -name "*.md" | while read src; do \
			rel=$${src#$(AI_DIR)/commands/}; \
			dest="$(CLAUDE_DIR)/commands/$$rel"; \
			mkdir -p "$$(dirname "$$dest")"; \
			cp "$$src" "$$dest"; \
			echo "  $$rel"; \
		done; \
	fi
	@# Sync skills (preserve local files)
	@if [ -d "$(AI_DIR)/skills" ]; then \
		echo "Syncing $(AI_DIR)/skills/ to $(CLAUDE_DIR)/skills/ (preserving local files)..."; \
		mkdir -p $(CLAUDE_DIR)/skills; \
		cp -r $(AI_DIR)/skills/* $(CLAUDE_DIR)/skills/; \
		echo "  skills synced"; \
	fi
	@# Sync agents (preserve local files)
	@if [ -d "$(AI_DIR)/agents" ]; then \
		echo "Syncing $(AI_DIR)/agents/ to $(CLAUDE_DIR)/agents/ (preserving local files)..."; \
		mkdir -p $(CLAUDE_DIR)/agents; \
		cp -r $(AI_DIR)/agents/* $(CLAUDE_DIR)/agents/; \
		echo "  agents synced"; \
	fi
	@# Convert MCP config to Claude CLI format (.mcp.json at repo root)
	@if [ -f "$(AI_DIR)/mcp.json" ]; then \
		echo "Converting MCP config to Claude CLI format..."; \
		./scripts/convert-mcp.sh "$(AI_DIR)/mcp.json" ".mcp.json"; \
	fi
	@echo "Claude ready! Files in $(CLAUDE_DIR)/ and .mcp.json"

# Clean generated files (preserves .agents/ and local customizations in .claude/)
# Only deletes files that have a corresponding source in .agents/
clean-agents:
	@echo "Removing generated files (preserving local customizations)..."
	@[ -L "$(CURSOR_DIR)" ] && rm -f $(CURSOR_DIR) && echo "Removed $(CURSOR_DIR) symlink" || true
	@[ -L ".codex" ] && rm -f .codex && echo "Removed .codex symlink" || true
	@[ -f "AGENTS.override.md" ] && rm -f AGENTS.override.md && echo "Removed AGENTS.override.md" || true
	@[ -d "$(AI_DIR)/generated/codex" ] && rm -rf "$(AI_DIR)/generated/codex" && echo "Removed $(AI_DIR)/generated/codex" || true
	@# Clean synced files from Claude surfaces using shared deletion helper.
	$(call clean_synced_files,rules,-name "*.mdc",dest_rel=$${dest_rel%.mdc}.md;,rules)
	$(call clean_synced_files,commands,-name "*.md",:;,commands)
	$(call clean_synced_files,skills,-type f,:;,skills)
	$(call clean_synced_files,agents,-name "*.md",:;,agents)
	@# Clean up empty directories
	@find $(CLAUDE_DIR) -type d -empty -delete 2>/dev/null || true
	@[ -f ".mcp.json" ] && rm -f .mcp.json && echo "Removed .mcp.json" || true
	@echo "Done! Local customizations preserved."

# Install the pre-commit framework hook (writes .git/hooks/pre-commit).
hooks:
	@command -v pre-commit >/dev/null 2>&1 || { \
		echo "Error: pre-commit not found. Install it: pip install pre-commit (or brew install pre-commit)."; \
		exit 1; \
	}
	@# pre-commit refuses to install while core.hooksPath is set (it would write a
	@# hook git then ignores). Detect it and tell the user exactly how to clear it,
	@# rather than silently mutating their git config.
	@hp=$$(git config --get core.hooksPath || true); \
	if [ -n "$$hp" ]; then \
		echo "Error: core.hooksPath is set to '$$hp', which makes pre-commit refuse to install"; \
		echo "       (a hook written to .git/hooks would be ignored by git)."; \
		echo "       Clear it, then re-run 'make hooks':"; \
		echo "         git config --unset core.hooksPath        # local (this repo)"; \
		echo "         git config --global --unset core.hooksPath  # if it was set globally"; \
		exit 1; \
	fi
	@# -f: install only pre-commit's hook, never chain a pre-existing one in
	@# "migration mode" (a stale chained hook breaks commits). See OPIK-7235.
	@pre-commit install -f
	@# Clear any legacy hook a prior non-force install left behind.
	@rm -f "$$(git rev-parse --git-path hooks)/pre-commit.legacy"
	@echo "pre-commit hook installed."

# Uninstall the pre-commit framework hook.
hooks-remove:
	@command -v pre-commit >/dev/null 2>&1 || { \
		echo "pre-commit not found; nothing to uninstall."; \
		exit 0; \
	}
	@pre-commit uninstall
	@echo "pre-commit hook removed."

# Run all hooks on files changed vs origin/main (the same hooks a commit runs,
# but over the branch diff). Mirrors how CI lints a PR.
precommit:
	@command -v pre-commit >/dev/null 2>&1 || { \
		echo "Error: pre-commit not found. Install it: pip install pre-commit (or brew install pre-commit)."; \
		exit 1; \
	}
	@git fetch -q origin main 2>/dev/null || true
	@pre-commit run --from-ref origin/main --to-ref HEAD --show-diff-on-failure --verbose

# Full-repo audit: run every hook against every file. Slow; surfaces pre-existing
# debt. Not a routine gate (commits and CI are changed-files-only).
precommit-all:
	@command -v pre-commit >/dev/null 2>&1 || { \
		echo "Error: pre-commit not found. Install it: pip install pre-commit (or brew install pre-commit)."; \
		exit 1; \
	}
	@pre-commit run --all-files --show-diff-on-failure --verbose
