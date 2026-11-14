# Sime 0.16.0 — changes based on SimeApp-0.16.0-fixed.zip

Only the requested keyboard behavior and layout were changed. No APK was built.

1. T9 idle `1` key now toggles the common-symbol candidate strip. Tapping `1` again closes it. The candidate-bar `×` dismissal is no longer used for this strip; the `×` remains available for prediction strips.
2. Removed `。` from the T9 `1`-key symbol list and added `“` and `”` as separate candidates.
3. QWERTY shift is latched: uppercase remains active across consecutive letter inputs until shift is tapped again.
4. Removed the QWERTY bottom-row `符号` key and the `中/EN` key beside Enter. Their combined width is distributed evenly across the remaining four side keys (`123`, comma, period, Enter); the space bar retains its width.
5. The T9 comma key already used `KeyDef.normal`, exactly the same appearance as the T9 digit keys (`t9digit` also uses `KeyDef.normal`). Its background therefore already matched the digit-key background in the supplied source; no visual-style change was necessary.

Files changed:
- `Android/app/src/main/java/com/shiyu/sime/ime/InputKernel.java`
- `Android/app/src/main/java/com/shiyu/sime/ime/InputView.java`
- `Android/app/src/main/java/com/shiyu/sime/ime/keyboard/T9KeyboardView.java`
- `Android/app/src/main/java/com/shiyu/sime/ime/keyboard/QwertyKeyboardView.java`
- `Android/app/src/main/java/com/shiyu/sime/ime/keyboard/layouts/QwertyLayout.java`
- `Android/app/src/main/java/com/shiyu/sime/ime/keyboard/layouts/T9Layout.java` (comment only, documents existing matching key appearance)

Build and Pixel 3 runtime verification have not been performed.
