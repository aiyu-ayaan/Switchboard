---
description: Apply the Switchboard development skill, architectural rules, and strict commit guidelines.
---

# /switchboard-dev

Load and enforce the **Switchboard Development Skill** (`switchboard-dev`).

## Instructions for AI Assistant

1. **Activate Skill**: Read and follow all instructions in [`.claude/skills/switchboard-dev/SKILL.md`](../skills/switchboard-dev/SKILL.md) and [`.agents/skills/switchboard-dev/SKILL.md`](../../.agents/skills/switchboard-dev/SKILL.md).
2. **Read Project Documentation**:
   - Monorepo Architecture: [`development/devdocs/architecture.md`](../../development/devdocs/architecture.md)
   - Security & E2EE Pairing: [`development/devdocs/security-pairing.md`](../../development/devdocs/security-pairing.md)
   - Development Phase Roadmap: [`development/devdocs/TODO.md`](../../development/devdocs/TODO.md)
   - Dual-Commit Workflow: [`development/devdocs/submodule-workflow.md`](../../development/devdocs/submodule-workflow.md)
   - Commit & Submodule Rules: [`development/Commit.md`](../../development/Commit.md)
   - Release Pipeline Triggers: [`development/Release-Commit.md`](../../development/Release-Commit.md)
3. **Strict Enforcement**:
   - **NEVER Push to Remote**: Commits must remain strictly **LOCAL**.
   - **No AI Attribution**: Use repository identity (`aiyu-ayaan`).
   - **Conventional Commit Format**: `<type>(<scope>): <short summary>`.
   - **Submodule Dual-Commit**: Verify branch `main` in `development/`, commit in submodule first, then commit root pointer on branch `master`.
   - **Documentation Integrity**: Keep `@devdocs` (`development/devdocs/`) and `docs/docs/` updated.
