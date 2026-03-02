---
name: slick-self-improve
description: After fixing any SLICK bug, documents the fix and updates prevention rules. Maps to the Escoffier Meta-Protocol. Use after resolving any error the user reports or that appears in build/test output.
---

# SLICK Self-Improve Protocol

Every fix must be documented so we never repeat the same mistake. This is the Escoffier Meta-Protocol in action.

## When to Use

- After fixing any bug the user reported
- After resolving build or test failures
- When you discover and fix an error during development
- After any debugging session that took more than one attempt

## Required Actions

### 1. Add to TROUBLESHOOTING_LOG

File: `docs/TROUBLESHOOTING_LOG.md`

Format:
```markdown
| Symptom | Root Cause | Fix | Derived Rule |
|---------|-----------|-----|--------------|
| ConvoyForegroundService killed after 10 min on Samsung | Samsung's Bixby energy saver overrides foreground service | Guide user to Settings > Battery > SLICK > Unrestricted | Always prompt OEM battery optimization bypass during onboarding |
```

### 2. Update Domain SKILL.md GOTCHAS

Open the relevant domain skill (e.g., `engine/mesh/SKILL.md`) and append:

```markdown
## GOTCHAS
[2026-03-01] BUG: Connection drops on Xiaomi devices after screen off
ROOT CAUSE: MIUI's "Autostart" is disabled by default; kills Nearby Connections
FIX: Add Autostart to OEM battery optimization bypass checklist in onboarding
PREVENTION: Test P2P mesh with screen off on Samsung, Xiaomi, Oppo during Phase 8
```

### 3. Update MEMORY.md for Patterns

File: `docs/brain/MEMORY.md`

Format:
```markdown
[2026-03-01] **SQLCipher**: Never retrieve Keystore key in Application.onCreate() -- Keystore not unlocked yet. Use lazy initialization.
```

### 4. Escalate to New Rule if Pattern Repeats

If the same type of issue occurs 3+ times:
- Create or update a `.cursor/rules/*.mdc` file
- Name it with the relevant concern (e.g., `oem-battery.mdc`)
- Add it to `.cursor/SKILLS_INDEX.md`

## Style Guide

Never fix without documenting. The next session should find the fix immediately in `TROUBLESHOOTING_LOG.md` -- not rediscover it from scratch.
