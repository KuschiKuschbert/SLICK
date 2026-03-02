---
name: slick-craft
description: Ensures SLICK changes feel finished and consistent. Use when adding or changing anything users see or touch -- screens, buttons, errors, copy.
---

# SLICK Craft Protocol

Changes must feel finished. No half-done states, no placeholder copy.

## When to Use

- Adding or changing UI screens
- Anything users see or touch
- New composables, buttons, dialogs, overlays

## Checklist

- **Empty states**: Any list or feed screen must have proper empty-state UI when there's no data
- **Loading states**: Use appropriate skeleton or progress indicators during async operations
- **Error states**: Clear, actionable error messages in SLICK tactical voice (not "Something went wrong")
- **No placeholder copy**: No "TODO", "lorem ipsum", or "placeholder" in user-facing strings
- **24h time throughout**: Every timestamp visible to the user uses HH:mm format
- **Metric units throughout**: Every measurement uses km/h, °C, mm, km
- **Consistent patterns**: Similar screens use similar layouts and interaction patterns

## SLICK Voice Checklist

Before finishing any UI copy:
- Does it use tactical terms? (Node, Vector, Telemetry)
- Does it avoid prohibited words? (Safe, Clear, Guaranteed)
- Does it report observations, not certainties? ("Observed rain 18 min ago" not "Road is wet")
- Is it terse enough to read at a glance? Zone 1 copy must be readable in 0.5 seconds.

## Tactical HUD Finish Check

For In-Flight HUD screens specifically:
- All numbers use Monospace font
- Background is `#000000` (not `#121212` or `#1A1A1A`)
- Tap targets in Zone 3 are at least 60dp tall and 50% screen width
- No text below 14sp anywhere on the HUD

## Style Guide

Details matter. If it ships, it should feel intentional. SLICK is tactical equipment -- it must behave like it.
