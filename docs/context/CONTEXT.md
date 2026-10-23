# Context

How this app was made (spatial-app-factory, 2026-10-21 to 2026-10-23). The binding spec is
[design-package.json](design-package.json) (states S01 - S17, brushes, sizes, palette, panels, entities, copy).

| What | Where |
|---|---|
| PRD (private) | https://claude.ai/artifact/YNhVAPCUsTvm6yxXA1idDR |
| Interactive sample (gate B) | https://claude.ai/artifact/8A2yUfYUkH8N6Qiv6eBMgo |
| QA report #1 (pass, 6 known issues) | https://claude.ai/artifact/BE2VrqdukrVK1EgVGWDw1d |
| Developer notes, platform facts, debug broadcast, screenshot tour | [../../AGENTS.md](../../AGENTS.md) |

History: research pool round 1 (port of the "draw in the air" idea, Open Brush / A-Painter as references) ->
design (gate B passed) -> build v0.1.0 from the voxel-jigsaw skeleton (archetype A, Shared Space volume),
7 builds, 12 JVM tests, 6 deviations within tolerance -> QA #1 passed with no blocking finding.

Deviations recorded by the build station: icon colour lifted from #3D1F6B to #5F30A6 (same hue, to stand apart
from Memory Palace); samples generated in code instead of JSON assets; undo without the 0.2 s fade; drag amounts
not divided by the volume scale; dot brightness 12 %; thumbstick mapped to turning the base (the real stick does
not reach a Shared Space volume on the emulator).

Open for the user: wear the headset and check drawing feel, the real thumbstick and palette colours.
