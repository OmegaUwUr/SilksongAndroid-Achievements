# Personalized touch controls — revision 30 candidate

This branch adds a configurable touch gamepad to Silksong. It is a development
candidate; the published revision-28 APK does not contain it. The candidate also
includes the revision-29 porting changes from PR #5.

## Enable and personalize

1. Install the candidate APK. A patch change requires on-device conversion and
   native compilation again; existing game files and complete tool downloads are
   reused where the porting cache permits.
2. Open **Settings → Gameplay → Personalized touch controls** in the launcher.
   Enable it and launch the game. This switch defaults off and is read at startup.
3. On the title screen, or after pausing the game, tap **Edit controls** at the top.
4. Select a button by touching it or using **Previous/Next**. Drag it to move it.
   Use **Smaller/Larger**, **Hide/Show**, and **Send** to adjust that button. Opacity
   applies to the whole layout. **Center** retrieves a selected control that has
   been moved underneath the editor toolbar.
5. **Right hand** and **Left hand** replace the working layout with a preset.
   **Save** persists changes; **Cancel** restores your last saved layout. Resuming
   play or changing scenes while editing also cancels unsaved changes.

Pause can be moved or resized but stays visible and bound to Menu. Lift your
fingers after entering/leaving the editor or returning from another app before
pressing controls again. Disable the launcher switch and relaunch to remove the
overlay; your saved layout is retained.

## Default bindings

The labels describe the intended actions with the game's default controller
layout. They are not action-level hooks and do not automatically follow in-game
remaps. If you change controller bindings in Silksong, adjust **Send** to match.
The overlay uses Unity's new Input System; the game's alternate legacy input
backend is not supported by this candidate.

| Touch control | Controller input |
| --- | --- |
| Move | Eight-direction left stick and D-pad, with center deadzone |
| Jump | A / South |
| Attack | X / West |
| Sprint | RT |
| Bind | B / East |
| Skill / Tool | RB |
| Clawline | LT |
| Needolin | Y / North |
| Quick map | LB |
| Inventory (PACK) | View / Select |
| Pause | Menu / Start (fixed) |

Hold movement and multiple actions together. Up/down directions also permit
directional attacks. Holding a button stays held; lifting or sliding off an
action releases it. Sliding back does not press it again until a new touch. The
movement finger can move beyond the circle while remaining captured. Unlocked
abilities and menu actions remain subject to the game's normal rules.

Layouts are local Unity `PlayerPrefs` JSON under `Silksong.TouchLayout.v1`, not
part of game saves or Steam Cloud. Position is relative to the screen's safe area;
size is relative to its shorter side. Clearing app data or uninstalling removes
the layout. The editor's text is currently English, like the launcher.

## Implementation and validation

- Launcher `SettingsStore` exports `touch_controls` in `game-settings.txt`.
- `TouchControlsOverlay.Bootstrap` is listed in `entrypoints.json` and creates the
  controller only when enabled. It updates a virtual `Gamepad` before the game's
  normal Update using `InputState.Change`, sending only changed states.
- `TouchInputModel` owns fingers and computes simultaneous input without Unity
  dependencies. Losing a pointer releases it. App focus/pause changes, scenes,
  resizing and editing neutralize input and require fingers to be lifted.
- `TouchControlsView` uses cached procedural sprites and Unity UI on display 0.
  It adds neither an EventSystem nor a GraphicRaycaster. Layout changes rebuild
  positions/text; ordinary frames only update pressed colors.
- The existing `DsTouch` filter excludes secondary-display and overlay-owned
  touches from menu mouse/touch input. Inventory gestures skip owned touches too.
  A captured release remains filtered for that frame even outside its button.
- Saved JSON is length-limited, matched by known IDs and clamped. Bad or unknown
  versions fall back to defaults. Pause visibility and Menu binding are enforced.

The host harness compiles the actual model, overlay, view and `DsTouch` with C# 9,
Roslyn 4.12 and .NET 9.0.22. All 24 checks passed, covering input combinations,
deadzone/aim, multiple holders, release/cancellation, safe areas, corrupt layouts,
save/cancel/reload, editor restrictions, mouse filtering, secondary-display
isolation and physical-controller coexistence. Unity APIs and persistence are
stubbed, so this does not validate game compatibility, rendering, IL2CPP or a
particular Android device. The `InputState.Change` and `GamepadState` APIs were
checked against the Input System 1.14.2 source used by the port.

Run the reproducible host checks:

```sh
python3 tools/silksong-patches/tests/touch/run_touch_checks.py \
  --dotnet /path/to/dotnet --roslyn /path/to/Roslyn/bincore/csc.dll
```

`TOUCH_DOTNET` and `TOUCH_ROSLYN` can supply the same paths. The harness downloads
nothing, writes compilation output to a temporary directory and removes it after
the run. An installed SDK's compiler or an extracted Roslyn compiler both work
with a compatible .NET runtime. Test sources are not staged into the APK.

## Device acceptance before merging or releasing

Run **Build APK** manually on `feat/custom-touch-controls`. The workflow packages
patch source; successful APK packaging alone does not compile it against the
game. Use `make check` with the owned depot/Android player assemblies, or complete
the on-device port, to validate the real patch assembly.

| Check | Expected result |
| --- | --- |
| Feature off, then enabled and relaunched | Existing play works while off; overlay appears only when enabled |
| Title/pause menus | Move, confirm/back and Pause work; touching a control does not click menu items underneath |
| Movement with Jump and Attack | Simultaneous holds work; diagonal and up/down attacks work without stuck movement |
| Bind, Sprint, Skill/Tool, Clawline, Needolin, Map, Inventory | Each matches the actual default controller action; held actions release immediately |
| Edit each control | Drag/resize/hide/remap work, including hidden-button selection; Pause remains accessible |
| Save/Cancel and relaunch | Save survives restart; Cancel/resuming the game discards unsaved edits |
| Left-hand preset and display changes | Controls stay inside safe bounds at 16:9, wide and cutout resolutions; toolbar text is legible |
| Home, screen lock, notifications and scene transitions while holding input | No stuck buttons or actions until fingers are lifted and pressed again |
| Physical controller connect/disconnect and mixed use | No idle controller switching or lost physical input; touch resumes after fresh presses |
| Supported second-screen device | Secondary map/inventory touches never move or attack; switching the panel off retains the main touch fence |
| Sustained play with overlay on/off | Compare frame pacing, CPU use and memory on the same scene and render settings |

No full candidate APK build, owned-depot compile, real-device run, or performance
measurement has been completed in this workspace. In particular, Input System
device pairing, controller prompts, game pause behavior and multi-display touch
identity need the checks above before promoting this candidate.
