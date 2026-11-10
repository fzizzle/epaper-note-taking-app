# The tablet's own display settings: trial

Task 1 of the [readability plan](plans/2026-11-10-review-readability.md). If one of the tablet's settings makes slides easy enough to read, with no side effects, the app's own contrast setting (Task 3) is dropped.

## What the tablet applies now (2026-11-10)

From `adb shell dumpsys oec_service`. Unlike earlier on 2026-11-10, when there was no entry, the app now has one (`EACAppConfig:com.fzizzle.epapernotes`), most likely created when the owner set its refresh mode to Smooth.

| Setting | Value | Meaning, as far as the names tell |
|---|---|---|
| `globalActivityConfig.enable` | false | The per-app display overrides are switched off: the values below are stored but probably not applied, apart from the refresh mode. |
| `refreshConfig.refreshModeAlias` | smooth | The owner's choice (Boox refresh setting). |
| `refreshConfig.supportRegal` | false | The system's own REGAL use for this app is off. The app asks for REGAL itself (readability Task 4), which works. |
| `displayConfig.contrast` | 30 | Contrast step, at its default. |
| `displayConfig.enhance` | true | "Dark colour enhancement", stored as on. |
| `displayConfig.bwMode` | 0 | Colour, not black and white. |
| `paintConfig.textBold` | false | "Text bolding" off. |
| `paintConfig.imgGamma` | 60 | Picture gamma, at its default. |
| `paintConfig.fillEAC`, `imgEAC`, `iconEAC` | false | Background bleaching and similar off. |
| `colorMode` | vivid | |

No per-screen entries (`activityConfigMap` is empty).

## The owner's trial

To do: on one slide deck that is hard to read, long-press the app's icon → Optimize → Display, and try in turn "dark colour enhancement", "text bolding" and "background bleaching". For each:

- Is the page easier to read?
- Does the pen still feel the same?
- Is the highlighter still yellow?
- Does a pasted snip look like the page beside it?

## Verdict

To come.
