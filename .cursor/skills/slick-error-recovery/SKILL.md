---
name: slick-error-recovery
description: When something breaks in SLICK, finds and applies known fixes first, then documents any new fix. Use when builds fail, tests fail, or the user reports runtime errors.
---

# SLICK Error Recovery Protocol

When something breaks, find known fixes first. If new, fix then document so we never repeat it.

## When to Use

- Build fails
- Tests fail
- User reports a runtime error
- Pre-commit checks reject

## Decision Tree

1. **Check known fixes first**
   - Read `docs/TROUBLESHOOTING_LOG.md` for similar symptoms
   - Check the domain `engine/*/SKILL.md` or `.cursor/skills/*/SKILL.md` GOTCHAS section
   - Check `.cursor/rules/` for a relevant pattern

2. **If match found** → Apply the documented fix immediately (don't rediscover it)

3. **If no match (new error)**:
   - Fix the issue with proper `Result<T>` error handling
   - Document in `docs/TROUBLESHOOTING_LOG.md`: Symptom | Root Cause | Fix | Derived Rule
   - Update the relevant domain SKILL.md GOTCHAS section
   - Update `docs/brain/MEMORY.md` with a brief lesson

## Common SLICK Error Categories

| Category | First Places to Check |
|---------|-----------------------|
| SQLCipher / Room | Android Keystore unlock timing, schema migration |
| Nearby Connections | OEM autostart permissions, NEARBY_WIFI_DEVICES grant |
| MapLibre | `lineMetrics` not set, lifecycle not wired, GL thread violations |
| Open-Meteo | BOM model parameter, `past_minutely_15` array indexing |
| AES-256-GCM | IV prepend/strip mismatch, key alias wrong |
| Foreground Service | OEM battery optimizer, `foregroundServiceType` declaration |
| Activity Recognition | 30-90s delay is expected -- not a bug for quick stops |

## Style Guide

Always check known fixes before inventing a new solution. Never fix without documenting. The goal is a self-healing codebase where each error makes the next session faster.
