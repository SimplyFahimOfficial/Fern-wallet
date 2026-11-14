# Requirements: Support My Work and Help & Support transitions

Opening and closing these two screens shows ghosting, overlapping UI, a gray layer and a pop at the
end. Fix the composition and synchronization, not the motion style.

## R1. Entering
1. While Support My Work or Help & Support opens, no element of the previous screen (search bar,
   quick-access icons, cards) is visible through it.
2. The incoming screen is an opaque surface for the whole transition.

## R2. Leaving
1. While either screen closes (toolbar back, system back, back gesture), its text, QR code and
   buttons never blend with the revealed screen as a ghost image.
2. No gray, clipped layer is visible.
3. The revealed screen finishes returning to full size when the leaving screen finishes leaving,
   with no snap at the end.

## R3. Scope and consistency
1. Both screens use the same fix, with no duplicated navigation code.
2. Existing slide distance, fade, durations (300 ms in, 200 ms out) and easing are kept.
3. Transitions are not replaced by plain fades and are not disabled.
4. No new dependency.

## R4. Behaviour to preserve
1. Directional movement and the layered "receding" screen behind.
2. Predictive back: scrubbing, release, cancel and interruption leave no stale layer.
3. Scroll position and screen state survive navigation.
4. Entry points keep working:
   - Home help card -> Help & Support
   - Home support card -> Support My Work
   - Home menu "Support My Work" card -> Support My Work
   - Settings -> Help & Support
   - Help & Support -> Support My Work
5. Light and dark themes both look correct.

## R5. Verification
Frame-by-frame review of every entry point, in light and dark themes, with toolbar back, system
back and slow, fast, cancelled and interrupted back gestures, plus rapid repeated navigation, on
debug and release builds.
