# 空中画笔 Air Sketch — project notes for agents

PICO OS 6 spatial app (Spatial SDK 6.1.9, Kotlin/Compose + ECS). Package `io.embodit.airsketch`, v0.1.0.
Design package: `C:\Users\Admin\Downloads\spatial-app-factory\apps\air-sketch\design-package.json`
(archetype A · Shared Space · one Volumetric `DefaultWindowContainer` 0.56 × 0.62 × 0.56 m · `single_panel_in_volume`).
Skeleton ported from `C:\Users\Admin\Downloads\voxel-jigsaw` (same archetype: volume manifest, SpatialView +
attachment panels, separate tap / drag pointerInputs, main-thread frame loop, palette-texture batching).

**Rules:** all 2D UI is SpatialUI (`com.pico.spatial.ui.*`) inside `PicoTheme` (`ui/theme/SketchTheme.kt`); no
`androidx.compose.material*` / `MaterialTheme` (gate: `grep -rEf spatial-app-factory/bin/material-grep.txt`).
Do not change the container / window model. 3D runs on the ECS side; Compose sends discrete events only.

## Layout

| Path | What |
|---|---|
| `domain/` | Pure Kotlin (JVM-tested): `Model.kt` (Brush, Palette 12 colours, Sizes, StrokeData, Work JSON in mm), `StrokeBuilder.kt` (Zone = draw cylinder r 0.19 / y 0.11–0.58, 3 mm sampling, α 0.5 smoothing, boundary pause, Catmull-Rom 4 mm, taper), `StrokeMesher.kt` (6-sided tube / ribbon band, uv0 = palette column), `Samples.kt` (spring / tree / orbit + debug paths) |
| `data/WorkStore.kt` | `files/works/index.json` + `works/<id>.json`, prefs (language, slowSpin, onboardingDone, lastWorkId, nameSeq, lastTools) |
| `scene/SketchScene.kt` | Base + ticks, nib (sphere + depth-test-off ring + Ø6 cm collider), dot patch + collider, shadow + dash line, mirror plane, boundary glow, live stroke (body + tail), per-brush batches (≤ 60k verts), frame loop, gestures |
| `ui/SketchController.kt` | State machine S01–S17, undo/redo, saving (1 s debounce, immediately on focus loss), gallery, onboarding, panel layout |
| `ui/Panels.kt` | Toolbar panel (tools / palette / menu / confirm / gallery / settings / full / error / loading), pill, hint bubble |
| `ui/DebugCommands.kt` | QA broadcast (debug builds) |
| `tools/` | `dev.sh` (build/install/launch/cmd/log, pinned to emulator-5564 = AVD PICO_6_1_Factory3), `qa_tour.sh`, `snap.sh`, `make_icon.py`, `harness/` (PostMessage input, stick, `emu_button.ps1`) |

Frames: "volume" = origin on the volume floor centre, +Y up, +Z to the user (SpatialView origin is the volume
centre → root at (0, −0.31, 0)). "art" = volume frame turned by the base yaw; strokes are stored in art.

## Debug broadcast

```
adb -s emulator-5564 shell am broadcast -a io.embodit.airsketch.DEBUG -p io.embodit.airsketch --es cmd <name> [--es s '<args>']
bash tools/dev.sh cmd <name> "<args>"
```

`state S01..S17` (enters each design state, below) · `seed spring|tree|orbit` · `stroke helix|zigzag|circle|line [ink|neon|ribbon|taper] [s|m|l] [colour id] [half]`
(nib jumps to the path start, then is dragged along it through the same T1 pipeline; `half` holds at 50 % → S04; `release` ends it) ·
`tapPlane x y` (logs want/got/err) · `dragShadow dx dz` · `dragBase dx` · `holdBase dx` · `yaw deg` · `turn90` · `mirror on|off` ·
`undo` · `redo` · `fill` · `locale zh|en|follow` · `corruptSave` · `boundsCheck` (`BOUNDS outside=0`) · `nibInfo` · `resetOnboarding` ·
`tools b s c` · `palette` · `menu` · `gallery` · `card i` · `settings` · `display` · `keep` · `new` · `clear` · `delete` · `confirm` ·
`stick x,ms` (holds a thumbstick value) · `dump` · `resetAll`.

Logcat tag `AirSketch`; every state change logs `STATE <id>[+S07][+S09] …` (S07 mirror and S09 redo-available are marker states).

## Screenshot list (QA)

`bash tools/qa_tour.sh [OUT] [states…]` runs all 17 and writes `<S>.png` (volume crop), `<S>-full.png`, `tour.txt`, `tour.png`.
Set `CROP=x0,y0,x1,y1` (2560-px frame) when the window opened somewhere else; the volume lands in a different place per cold start.

| State | How | Notes |
|---|---|---|
| S01 | `state S01` | loading strip held 6 s (debug only) |
| S02 | `state S02` | bubble over the nib; dots lit; first sentence |
| S03 | `state S03` | empty canvas, undo / redo greyed |
| S04 | `state S04` | helix half drawn, bar dimmed |
| S05 | `state S05` | spring sample (read-only until the first stroke) |
| S06 | `state S06` | palette grown upwards |
| S07 | `state S07` | mirror on, base at 30°, zigzag + mirrored copy → `S05+S07` |
| S08 | `state S08` | base drag held at 45°, ticks lit coral (`release` ends it) |
| S09 | `state S09` | spring + line + undo → `S05+S09`, nib back on the spring end |
| S10–S15 | `state S1x` | menu / confirm clear / gallery / confirm delete / display / settings |
| S16 | `state S16` | fill to 16 000 points (≈ 10 s on the emulator) |
| S17 | `state S17` | corrupt the current work, reboot → can't open (the file is left untouched) |

Onboarding steps (S02 sentence 2–4): draw a stroke → tap the dots (`tapPlane`) → turn (`turn90`); `resetOnboarding` restarts it.

## Emulator facts (2026-10-23)

- **Real input works** (`tools/harness/emu.sh`, coordinates in the 2160-px framebuffer = 2560-px screenshot × 0.84375):
  pinch-drag on the nib draws (DRAG kind=DRAW), tap on the dots moves the nib, drag on the shadow, drag on the base
  top turns it, clicks on toolbar buttons. The base front is hidden behind the toolbar from a standing camera.
- `SpatialTapValue.position` converted with `convertPosition(…, Local, localSpatialCoordinateSpace)` is the true hit
  point on the dot patch (hit z = plane z within 1 mm) → nib lands where tapped.
- `MeshResource.createWithMeshModel` / `replaceWithMeshModel` block ~1 s each on the idle emulator (they wait for a
  render frame at ~1 fps), independent of size; building the data is 10–40 ms. `emu.sh fast on` helps for ~10 s.
- `ControllerTrackingProvider.supportState` = `DEVICE_NOT_SUPPORTED` in this Shared Space volume on the emulator
  and on the headset at launch; the real stick (`harness/stick.sh`) does not reach the app. The stick → base turn is
  verified through `stick 0.8,2500`; real-stick feel is device-only.
- `pico-cli capture screenshot` often returns half frames; `tools/snap.sh` uses the emulator console instead.
- Launcher: `harness/emu_button.ps1 -Name home_button` twice (`adb input keyevent HOME` does nothing).
- Emulator restart if it is gone: `pico-cli emulator start --avd PICO_6_1_Factory3 --adb-device emulator-5564 -y`.

## Build

```
export JAVA_HOME='C:\Users\Admin\.pico\primer-cli\jdk\jdk-21.0.12.1+1' PICO_HOME='C:\Users\Admin\AppData\Local\PICO\sdk'
./gradlew.bat testDebugUnitTest assembleDebug --no-daemon     # 12 JVM tests
python -I tools/make_icon.py <out512.png>                     # 3D icon layers + 512 export
```
