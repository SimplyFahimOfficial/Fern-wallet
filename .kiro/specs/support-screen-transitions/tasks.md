# Tasks: Support My Work and Help & Support transitions

Done in PR #59.

- [x] 1. Find the root causes in `NavigationRoot`, `IvyMotion`, `RootActivity` and the two screens (R1, R2)
- [x] 2. Give Help & Support an opaque root background (R1, R3.1)
- [x] 3. Give Support My Work an opaque root background (R1, R3.1)
- [x] 4. Add `IvyMotion.recedeBackSpec()` and use it when back reveals a screen (R2.3, R3.2)
- [x] 5. Document the opaque-root rule on `Screen.isLegacy` (R3.1)
- [x] 6. CI green on PR #59: PR description check, build, detekt and lint, unit and screenshot tests (R3.4)

Open. These need a device or emulator.

- [ ] 7. Check each entry point in light and dark themes (R4.4, R4.5):
  - Home help card -> Help & Support -> back
  - Home support card -> Support My Work -> back
  - Home menu "Support My Work" card -> Support My Work -> back
  - Settings -> Help & Support -> back
  - Help & Support -> Support My Work -> back
- [ ] 8. Check toolbar back, system back, and slow, fast, cancelled and interrupted back gestures (R2, R4.2)
- [ ] 9. Check rapid open/close and repeated navigation for stale layers (R4.2)
- [ ] 10. Record the screen at 60 fps and step through frames: no see-through, ghosting, gray layer or snap at the end (R5)
- [ ] 11. Confirm the dashboard keeps its scroll position after Support My Work and Help & Support (R4.3)
- [ ] 12. Run debug and release builds (R5)
- [ ] 13. If a pop remains, change how the leaving screen's fade ends and re-check tasks 8 to 10

Optional.

- [ ] 14. Add a snapshot or lint check that every non-legacy screen has an opaque root
