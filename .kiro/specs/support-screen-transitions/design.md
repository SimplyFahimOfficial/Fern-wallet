# Design: Support My Work and Help & Support transitions

Requirements: `requirements.md`. Findings come from reading the code; nothing was run on a device.

## How navigation animates today

`NavigationRoot` (`shared/ui/navigation/.../NavigationRoot.kt`) draws every screen in one
`AnimatedContent` driven by a `SeekableTransitionState`, so a normal back and a predictive back
share one path. `IvyMotion.screenTransform` (`shared/ui/core/.../IvyMotion.kt`) defines it:

| Direction | Screen in front | Screen behind |
|---|---|---|
| Forward | slides in 8% and fades in, 300 ms, emphasized decelerate | stays drawn (alpha 1), host scales it to 94% and dims it 32% over 300 ms |
| Back | slides out 8% and fades out, 200 ms, emphasized accelerate, kept on top (`targetContentZIndex = -1`) | host returns it to full size and removes the dim |

`RootActivity` puts a theme-coloured `ThemedBackdrop` behind everything and wraps a screen in a
`Surface` only when `screen.isLegacy`.

## Root causes (confirmed in code)

1. **Help & Support and Support My Work have no background.**
   - Both are non-legacy, so they get no `Surface`, and their root `Column` in `HelpSupportScreen.kt`
     and `SupportScreen.kt` drew none.
   - The other non-legacy screens are opaque: Scaffold-based ones, `CardScreen` and `AppearanceScreen`
     (`.background(UI.colors.pure)`).
   - A transparent screen fading and sliding over the dashboard shows the dashboard through it
     (search bar, quick-access icons) and ghosts text, QR and buttons on exit.
   - The gray clipped layer is the dashboard itself, scaled to 94% and dimmed, seen through the screen.
2. **Back timing mismatch.** The revealed screen returned to full size over 300 ms while the leaving
   screen was gone after 200 ms, so the back layer was still settling with nothing above it.

## Changes

1. Add `.background(UI.colors.pure)` to the root of both screens (`UI.colors.pure` is the palette's
   Background, the colour `ThemedBackdrop` uses). No navigation code is duplicated; the screens only
   follow the convention already used by `CardScreen` and `AppearanceScreen`.
2. `IvyMotion.recedeBackSpec()` (decelerating, `Duration.Exit`). `NavigationRoot` uses it for the
   recede animation when the screen is being revealed by back (`targetState == Visible`), and keeps
   `recedeSpec()` (300 ms) when it recedes. Forward motion is unchanged.
3. `Screen.isLegacy` docs state that new screens need an opaque root.

## Checked and left alone

- `PredictiveBack`: `seekTo` while the gesture runs, `navigation.back()` on commit, and
  `animateTo(currentState)` on cancel. No conflict with the changes above.
- Slide, fade, easing and the shared-container code are unchanged. The leaving screen's fade over the
  receded screen is the intended reveal and is kept now that the screen is opaque.

## Open risks

- If a pop remains after these changes, the full-layer fade-out of the leaving screen is the next
  place to look. This is unconfirmed.
- The dashboard leaves composition after a forward navigation, so its scroll position depends on how
  `MainScreen` stores it. This was not checked.
- A future non-legacy screen without its own background repeats the bug. A lint or snapshot test
  would guard against it.
