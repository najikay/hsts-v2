# RadioGroup + ImagePicker — the two components E6.10 is blocked on

**Built by:** design-system pass for [L]
**Date:** 2026-08-22
**Scope:** F2.1 / C-8 (exactly one correct answer, radio-select) and E6.6 (illustration upload)
**Status:** `./mvnw -B clean verify` green. Numbers in §5.
**Consumer:** Member A, E6.10 and E6.11. Nothing here is wired into a screen yet.

---

## 1. What was built

| File | What it is |
|---|---|
| `src/main/java/client/ui/components/logic/RadioGroupLogic.java` | FX-free. Where an arrow key lands: wrap, skip-disabled, terminate on an all-disabled group, mirror horizontally under RTL. Measured. |
| `src/main/java/client/ui/components/RadioGroup.java` | Thin `VBox` over `ToggleGroup`. Generic in the option id; `indexed(...)` gives the 1..4 the wire wants. Excluded by name. |
| `src/main/java/client/ui/components/logic/ImagePickerLogic.java` | FX-free. The `ImageAction` state machine, the size/extension/header rules, the refusal sentences, size formatting. Measured. |
| `src/main/java/client/ui/components/ImagePicker.java` | Thin `VBox`: empty / preview / removed frame, Choose, Remove, message row, `FileChooser`. Excluded by name. |
| `src/main/java/client/ui/components/Icons.java` | Four literals added: `IMAGE`, `IMAGE_OFF`, `UPLOAD`, `DELETE`. All verified present in the material2 pack. |
| `src/main/resources/css/hsts.css` | New documented block "E6.10 question editor components". |
| `src/main/java/client/ui/gallery/GalleryScreen.java` | New section "Question editor components (E6.10 · F2.1, C-8)". |
| `src/test/java/client/ui/components/logic/RadioGroupLogicTest.java` | 29 cases. |
| `src/test/java/client/ui/components/logic/ImagePickerLogicTest.java` | 58 cases, with the cancel paths in their own nested class. |
| `src/test/java/client/ui/QuestionEditorComponentsInteractionTest.java` | 15 TestFX cases with real keyboard and mouse input. |
| `src/test/java/client/ui/UiSmokeTest.java` | The gallery assertion now also requires both components and their contents. |

---

## 2. How E6.10 binds them

### 2.1 The answer key (C-8)

```java
RadioGroup<Integer> correct = RadioGroup.indexed("Correct answer", answerLabels).required();

// Loading an existing question. select(...) is SILENT: it does not fire setOnSelect,
// so filling the form in from a QuestionDetail does not mark it dirty.
correct.select(detail.correctAnswer());          // 1..4, the same numbering as the wire

// The teacher changing her mind. Only fired by real input.
correct.setOnSelect(index -> form.markDirty());

// Saving.
int correctAnswer = correct.selected();          // null when she has not chosen

// E6.11's validation, rendered exactly as a FormField error.
correct.showError("Choose which of the four answers is the correct one.");
correct.clearValidation();
correct.focusSelected();                         // send the keyboard back to the group
```

Ids are **one-based** because `QuestionDraft.correctAnswer()` and `QuestionEdit.correctAnswer()`
are. There is no zero-based index anywhere in the component's public surface, deliberately: the
one place an off-by-one could hide is the place it is most visible.

`selectedProperty()` is a `ReadOnlyObjectProperty<Integer>`, so a Save button can bind its
disabled state to `correct.selectedProperty().isNull()` rather than re-checking on every event.

For a group that is not four answers, use the general constructor with
`RadioGroup.Option<T>(id, label)` pairs and any id type you like.

### 2.2 The illustration (E6.6)

```java
ImagePicker picker = new ImagePicker("Illustration");

// EDIT path only: hand it the answer to QUESTION_IMAGE_GET, once, before she sees it.
picker.loadExisting(imageBytes);                 // null for a version with no picture

picker.setOnChange(action -> form.markDirty());

// Saving a NEW question (QUESTION_CREATE):
new QuestionDraft(courseCode, text, answers, correctAnswer, topic, difficulty,
        picker.logic().chosenBytes());           // null when she attached nothing

// Saving an EDIT (QUESTION_UPDATE):
new QuestionEdit(displayId5, baseVersionNo, text, answers, correctAnswer, topic, difficulty,
        picker.action(), picker.logic().chosenBytes());
```

Do not construct the pair by hand. `action()` and `chosenBytes()` are guaranteed consistent by
the component, and that guarantee is what §3 is about.

### 2.3 The `ImageAction` mapping table

Read this as: the state the picker is in, and therefore what `action()` and `chosenBytes()`
return. Every row is asserted in `ImagePickerLogicTest`.

| The teacher did | `action()` | `chosenBytes()` | The server writes version n+1 with |
|---|---|---|---|
| Nothing (question had a picture) | `KEEP` | `null` | the same picture, copied forward |
| Nothing (question had none) | `KEEP` | `null` | no picture |
| Chose a valid file | `REPLACE` | the bytes | the new picture |
| Chose a file, then chose another | `REPLACE` | the second file | the second picture |
| **Opened the chooser and cancelled** | **unchanged** | **unchanged** | **whatever it would have anyway** |
| Chose a file that was refused | **unchanged** | **unchanged** | whatever it would have anyway |
| Pressed Remove (question had a picture) | `REMOVE` | `null` | no picture |
| Pressed Remove (question had none) | `KEEP` | `null` | no picture |
| Chose a file, then pressed Remove (had a picture) | `REMOVE` | `null` | no picture |
| Chose a file, then pressed Remove (had none) | `KEEP` | `null` | no picture |
| Pressed Remove, then chose a file | `REPLACE` | the bytes | the new picture |

Two rows carry the design and are worth reading twice:

- **Cancel is `unchanged`, never `REMOVE` and never a silent clear.** This is the defect that was
  caught and fixed server-side in the write PR (`BankMessages.IMAGE_REPLACE_WITHOUT_FILE`). The
  server refusing it is right; the client should never produce it. See §3.
- **Remove on a question that never had a picture stays `KEEP`.** `REMOVE` there would ask the
  server to strip an illustration that does not exist, which writes a version whose only content
  is a claim about a change that did not happen.

### 2.4 Refusals

`chooseBytes(...)` and the Choose button both return / render an `ImagePickerLogic.Outcome`.
The picker shows the sentence itself, in the same message row `FormField` uses, so E6.10 does
**not** need to handle rejection. The four sentences, all constants on `ImagePickerLogic`:

| Constant | When |
|---|---|
| `TOO_LARGE` | over `QuestionImage.MAX_BYTES` (2 MB). Checked on the directory entry before the file is read. |
| `WRONG_EXTENSION` | not `.png` / `.jpg` / `.jpeg`, for a file picked around the chooser's filter |
| `WRONG_CONTENT` | the name says image, the leading bytes do not (the renamed-HEIC case) |
| `UNREADABLE` | the disk read failed after a successful pick |

A rejection never throws and never changes the picker's state. Order is size, then name, then
contents, which is the server's order and for the server's reason: a 40MB photo fails every rule
and only the size is one she can act on.

---

## 3. Decisions

**The client makes the cancel defect unrepresentable rather than refusing it.** Three properties,
not three conventions:

1. `choose(...)` is the only route into `REPLACE` and takes it only after bytes have passed every
   check. It accepts `null` and defines it as "nothing happened", so the sloppy call site
   (`logic.choose(readOrNull(file), name)`) is safe too.
2. `remove()` is the only route into `REMOVE`, and it is reachable only from the Remove button.
3. Therefore no cancel, no rejected file and no read error can reach `REMOVE`, and `REPLACE` can
   never be reported without bytes. `Cancelling.rejectionsNeverRemove` asserts the second of those
   across every bad-file shape from every starting state.

**Both components carry `hsts-field` in addition to their own class.** That is what makes an error
on a radio group look identical to an error on a text field: the label, the message row and the
`.invalid` treatment are section 6 of `hsts.css`, reused rather than reimplemented. `FormField`
itself cannot wrap either component because its constructor takes a `Control` and both are
containers; the compatibility is at the CSS and `ValidationState` level instead, which is where
it actually matters.

**Arrow keys select, not just move.** This is the platform convention for radio groups
specifically, and it is a deliberate reading of the brief's "arrows move, space selects": the
option the keyboard is on *is* the chosen one, so arrowing to answer 3 has chosen answer 3.
Space stays wired through `RadioButton`'s own binding, so the habit that expects to press it is
not wrong either. Both are asserted with real key presses.

**The arrow handler is an event FILTER, not a handler.** JavaFX installs directional traversal on
the focused `RadioButton` itself, so a handler on the container would run after focus had already
left the group and landed in the topic field. The filter sees the key on the way down. This is
the single subtlest line in either component.

**RTL is a rule, not a coat of paint.** `Left` means "towards the next option" when the group
renders right-to-left, which is what a Hebrew question bank does. The mirroring lives in
`RadioGroupLogic.step` and is unit tested, and there is a TestFX case that flips the group's node
orientation and presses a real Left arrow. Everything else mirrors for free because both
components are boxes and alignments rather than coordinates.

**The `FileChooser` is not the seam.** A native dialog cannot open headless, so the dialog is six
lines that turn a `File` into a call to `chooseBytes(bytes, name)`, and everything worth asserting
hangs off the second of those. The gallery drives the same seam, which is why a reviewer can read
the refusal sentences there without going to find a 3MB photo first.

**Removed is not empty.** "Illustration removed" with a danger tint is a different sentence from
"No illustration", and the difference is that the first one is a pending destructive change she
should see before she saves. Three states in the frame, distinguished by icon and sentence first;
the tint only confirms.

**The 2 MB ceiling is imported, never restated.** `QuestionImage.MAX_BYTES` is the source of
truth and `ImagePickerLogic` reads it. The magic-byte tables are duplicated from
`server.features.bank.QuestionImages` because the Presentation tier does not import the Logic
tier; the server stays the authority and a client that skipped its local check would still be
refused. This copy exists to turn a round trip into an instant sentence.

**No logic class was manufactured for the selection itself.** `ToggleGroup` already guarantees
at-most-one, which is the invariant C-8 is about, and wrapping that in a hand-written rule would
be inventing work. `RadioGroupLogic` covers only the thing `ToggleGroup` genuinely does not know:
where an arrow key lands. Its four functions each have an edge case that bites (the wrap, the
disabled run, the cold start, the all-disabled group that spins a naive loop forever).

---

## 4. What the lead double-checks

In order of how expensive they would be to get wrong:

1. **The cancel path, at the E6.10 call site.** The component cannot produce the defect, but a
   screen still can: anything that reads `picker.logic()` and builds its own `QuestionEdit` from
   a nullable byte array has re-opened it. The rule for review is that E6.10 passes
   `picker.action()` and `picker.chosenBytes()` together and never assembles the pair by hand.
2. **`loadExisting` runs before the picker is visible.** `QUESTION_IMAGE_GET` is a separate verb,
   so an editor that opens before the bytes arrive shows "No illustration" about a question that
   has one, and a teacher who then presses Save gets `KEEP`-of-nothing rather than her diagram.
   That is correct behaviour for the component and a real trap for the screen: E6.10 must have
   the bytes in hand, or must not show the picker until it does. **This is the one thing in the
   pass a screen can still get wrong.**
3. **The arrow-key event filter.** If anyone changes `addEventFilter` to `addEventHandler` in
   `RadioGroup`, arrow keys will appear to still work in casual use and will leak focus out of
   the group at the ends. `arrowKeysSelect` catches it, and its final assertion (focus is still
   inside the group after a wrap) is the one that would fail.
4. **The check order in `ImagePickerLogic.check`.** Size, then name, then contents, matching
   `QuestionImages`. `sizeBeatsType` pins it. Reordering makes a 40MB document report the wrong
   problem.
5. **`RadioGroup.select` stays silent.** If it ever fires `setOnSelect`, every editor opened on an
   existing question is dirty before the teacher touches it, and the unsaved-changes prompt fires
   on Cancel every time. `selectIsSilent` pins it.
6. **The two coverage exclusions are per class and do not reach `logic/`.**
   `client/ui/components/ImagePicker*` and `client/ui/components/RadioGroup*` leave
   `client/ui/components/logic/ImagePickerLogic` and `.../RadioGroupLogic` measured, which is the
   whole point of the split. Never a wildcard segment.
7. **The gallery's demo PNG is inline base64, 122 bytes.** It is a genuine PNG because the
   picker's own sniff would refuse a fake, which is a nice property: the gallery cannot
   demonstrate the component with something the component would reject.
8. **E6.11 is not in this pass.** The duplicate-answer rule, the field-level mapping of server
   `VALIDATION` answers and the live-while-typing behaviour are Member A's. What is provided is
   the rendering surface (`showError` / `apply(ValidationState)` / `clearValidation`) on both
   components, phrased identically to `FormField` so the three can share one validation pass.

---

## 5. Verify

`./mvnw -B clean verify` with `HSTS_REQUIRE_MYSQL=true HSTS_TEST_SCHEMA=hsts_components`, final
run on the final tree:

```
Tests run: 4261, Failures: 0, Errors: 0, Skipped: 0
All coverage checks have been met.
BUILD SUCCESS
```

| | Before this pass | After |
|---|---|---|
| Tests | 4159 | **4261** (+102) |
| Instruction coverage | not re-measured on the base tree | **98.38%** (55 234 / 56 141) |
| JaCoCo BUNDLE gate (≥90% instruction) | met | **met** |

The 102 are 29 in `RadioGroupLogicTest`, 58 in `ImagePickerLogicTest` and 15 in
`QuestionEditorComponentsInteractionTest`. `UiSmokeTest` gained four assertions rather than a
test method.

Two coverage exclusions added, explicit and per class, listed alphabetically among the twelve E4
components already there:

- `client/ui/components/ImagePicker*`
- `client/ui/components/RadioGroup*`

Both new logic classes are instrumented and stay so. `RadioGroupLogic` and `ImagePickerLogic`
are the two classes a defect in this pass would hide in, and neither is excluded.
