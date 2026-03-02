---
name: slick-leave-better
description: Ensures each change slightly improves the SLICK codebase. Use for every edit, refactor, or fix -- every touch should leave things cleaner than before.
---

# SLICK Leave-Better Protocol

Every touch should leave the codebase slightly better than before.

## When to Use

- Every edit
- Every refactor
- Every fix

## Habits

- **Clean up while changing**: Remove unused imports, fix nearby minor issues in touched files
- **Improve names**: If you touch code with unclear variable names, clarify them
- **Add KDoc**: If you modify a public function that lacks KDoc, add it before leaving
- **Extract over inline**: Prefer extracting a small helper over leaving complex inline logic
- **Check for constants**: If you see a magic number in a touched file, move it to `SlickConstants`
- **Check for duplicates**: Before leaving, check if a similar utility already exists in `util/`
- **Check Timber**: If you see a `Log.d` or `println` in a touched file, replace with Timber

## Scope Discipline

Stay within the changed area. If a larger cleanup is needed elsewhere, log it in `.cursor/TECH_DEBT.md` rather than scope-creeping the current task.

## Style Guide

Low-effort improvements in the same file are expected. Don't scope creep; stay within the changed area.
