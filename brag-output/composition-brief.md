# Composition brief: Switchboard (vertical 1080x1920, ~48s)

Source: /media/aiyu/550E85595197DBEC/development/Switchboard (README.md, docs/docs/*.md, frontend/tailwind.config.cjs).
Plan: ./brag-plan.md (storyboard is authoritative for text, order and timing).

## Show the thing
Use real screenshots copied to composition/assets/img (phone: android_*.jpg 1080x2400; desktop: desktop_*.png 1120x720; deck 2400x1080). Never redraw the UI.

## Layout system
Kicker (JetBrains Mono, green, uppercase) + headline (Inter 800, ~104px) + sub (Inter 500, 42px, ink-dim) in the top 40%. Device mockups below: desktop window + overlapping phone (type B) or a single large phone (type A). Keep critical text out of the bottom ~250px and top ~150px (social UI safe zones).

## Palette / type
#1a1b26 bg, #21222d panel, #2f3140 edge, #c8d1f0 ink, #8b93b8 dim, #7aa2f7 blue, #9ece6a green. Inter + JetBrains Mono from local woff2.

## Audio
Music assets/music/bed.mp3, low bed with fade-in and fade-out. Subtle audio-reactive glow from extracted RMS/bass. Scene cuts beat-locked to the bundled cue grid. SFX chosen after motion exists.

## Requirements
- Every text line settles and holds long enough to read.
- 1-3 strong beat locks marked `// beat-locked`.
- `npx hyperframes check` must pass before render.
