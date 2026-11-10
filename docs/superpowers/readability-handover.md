# Readability plan: handover (2026-11-10)

Branch `review-readability`, not merged. [Plan](plans/2026-11-10-review-readability.md).

## Where it stands

| Task | State |
|---|---|
| 1. Tablet's own display settings | Done: they do very little ([trial](readability-trial.md)). |
| 2. Sharp zoom | Done, reviewed, owner-checked. |
| 3. Contrast | Done, reviewed, owner-checked. |
| 4. Cleaner page turns (REGAL) | Done earlier, owner-chosen. |
| 5. Trim margins | Done, reviewed (2 fix rounds), owner-checked. |
| 7. Page drawn on whole pixels | Done, reviewed (approved), tablet suite passes (85/85, 2026-11-10). Controller screenshot check passed. **Owner check still to do.** |
| 8. Dark text as black | Done, reviewed (no Critical/Important), laptop 326 and tablet 85/85 pass; a full page with Strongest takes 20 ms (limit 80). **Owner check (step 6) still to do.** |
| 6. Owner session | Not started: write `notes/TESTING-readability.md`, update the spec §6.2, then stop. |

## Next steps

1. The owner checks Tasks 7 and 8 on the tablet (build installed 2026-11-10).
2. Task 6, then a whole-branch review that triages the deferred minor findings below, then merge.

If a build fails on a file named "… 2.xml" (or any "* 2.*") under `notes/app/build/`, those are duplicates made by file sync: `rm -rf notes/app/build` and build again.

## Review record

The task-by-task review ledger, copied from the git-ignored workspace so it travels with the branch. The "minor (deferred)" lines are for the final whole-branch review to triage; "Ruling" lines are decisions made on the owner's behalf.

### SDD ledger — plan: docs/superpowers/plans/2026-11-10-review-readability.md

Spec: docs/superpowers/specs/2026-11-07-epaper-pdf-notes-design.md (reachable).
Scope of this run: Task 2 only (owner said "yes go ahead" to Task 2, 2026-11-10). Task 1 done by controller (commits 4c53f93, 758e1eb). Task 4 done earlier (14e1acc).
Branch: review-readability, from main 57041fd.

#### Pre-flight scan

| Rows | Produces / consumes | Found |
|---|---|---|
| Task 2 self | Tests (ViewportTest visiblePagePart*) vs Viewport.visiblePagePart() | Agree. File refs `WritingActivity.kt:256-266` stale: requestRender is now at ~300. |
| Task 2 vs PR #15 (ink drawn solid) | Task 2 consumes `PdfFile.renderRegion` (snips) for the sharp patch | CONFLICT: renderRegion runs `p.run(...)`, which smooths all ink; `render()` draws full-strength ink solid (drawSolid). A patch from renderRegion would show the thinning ink again when zoomed. |
| Task 2 vs Task 3 | Task 3 applies contrast to "the region render from Task 2" | Agree; Task 3 adds it later. |
| Task 2 vs Task 4 | Task 4 changed the after-zoom refresh (REGAL, `pen.cleanRefresh`, CLEAN_REFRESH_MS 450) that Task 2's "hold the clean refresh" step defers | Agree; Task 2 holds the existing call. |
| Task 2 vs Task 5 | Task 5's Viewport `fit` keeps pageToScreen in whole-page points so `visiblePagePart` keeps working; Task 5 caps renderWidth and relies on the patch | Agree. |
| Task 3 self, 5 self, 6 self | not run this time | — |

Ruling: the sharp patch is drawn with solid full-strength ink, as `render()` does, not with `renderRegion` as it stands — spec + owner check of 2026-11-10 (ink must not thin) outrank the plan's "Consumes renderRegion" — if wrong, zoomed ink looks harder-edged than snips, a one-line flag to flip. Snips keep today's smoothed rendering (plan: "The snip path is left exactly as it is"); the solid path is shared with render() (drawSolid), not copied.
Ruling: the implementer does Steps 1–4 and 6 and adds the Log.i of the region render time; Step 5 (tablet check) is done by the controller and the owner after review, since the owner is using the tablet — if wrong, nothing lost, the check just happens later.

#### Tasks
Task 2: implementer DONE_WITH_CONCERNS (e680db9); review Approved, spec ✅, 0 Critical/Important.
Task 2: minor (deferred): region renders don't coalesce — quick undos while zoomed queue one region render each (PageView.kt:318–321, 412–419).
Task 2: minor (deferred): a failed region render leaves sharpPatchComing set; needs regionFailed(token) (WritingActivity.kt:333–336).
Task 2: minor (deferred, plan-mandated): pen unavailable up to 450+300 ms after a zoom; a stroke begun then is lost (pre-existing at 450 ms). Owner to judge in Step 5.
Task 2: minor (deferred): heldCleanRefresh body duplicated (WritingActivity.kt:222–223, 425–426).
Task 2: minor (deferred): patch ink version captured in the activity, not PageView (WritingActivity.kt:~322).
Task 2: ⚠️ to resolve: connectedDebugAndroidTest not run (new regionCanDrawInkSolidInItsPlace; render() now always via drawWithSolidInk).
Task 2: ⚠️ resolved: connectedDebugAndroidTest 85/85 pass incl. regionCanDrawInkSolidInItsPlace and the existing render tests (2026-11-10 18:4x). Build installed, storage permission restored. Awaiting owner Step 5.
Task 2: complete (commits 758e1eb..9ada3fa, review clean; owner Step 5: works well)

#### Pre-flight scan, Task 3 (owner said "yes do it", 2026-11-10)

| Rows | Produces / consumes | Found |
|---|---|---|
| Task 3 self | Tests (PageLookTest 6 cases, PageLookDeviceTest) vs PageLook/ReadingPrefs interfaces | Agree. |
| Task 3 vs Task 2 | Task 3 applies contrast to "the region render from Task 2" (now `requestRegionRender`, renderRegion(smoothInk=false)); `dropAllRenderings` must forget "every page picture and the sharp patch" | Agree; dropAllRenderings must re-ask for the patch too when zoomed. |
| Task 3 vs Task 5 | Task 5 consumes `ReadingPrefs.trimMargins` (created here) | Agree; Task 3 creates the field (unused until Task 5). |
| Task 3 vs snips | "The snip path is left exactly as it is" vs renderRegion now shared with the patch | Agree: contrast is applied in WritingActivity's two render callbacks, never inside PdfFile, so the snip call (default smoothInk) never sees it. |
| Task 3 vs page menu | Plan: ⋯ "grows from two entries to four" | ⋯ now has a third, TEMPORARY "Toolbar: … island" entry. Ruling: add Contrast after the two rotate entries and leave the temporary entry as it is — not this task's to remove — costs nothing if wrong. |

Ruling: the implementer does Steps 1–4 and 7; Step 5's tablet test is written and compiled, and the controller runs it with the tablet suite; Step 6 is the owner's check — as for Task 2.
Task 3: implementer DONE_WITH_CONCERNS (2cd0c9e); review Approved, spec ✅, 0 Critical/Important.
Task 3: minor (deferred): dropAllRenderings frees pictures before replacements exist; a tool tap or leaving within ~300 ms draws the grey placeholder (PageView.kt:332-338).
Task 3: minor (deferred): PageLookTest doesn't pin exact curve values (e.g. STRONGER[128]==91, STRONGEST[128]==56).
Task 3: minor (deferred): highlighter colour hand-copied in PageLookTest.kt:58; name StrokeStyle.kt:23.
Task 3: minor (deferred): ReadingPrefs.kt:14 runCatching broader than needed.
Task 3: minor (deferred): re-ask lines repeated from pageChanged (PageView.kt:336-337 vs 321-322).
Task 3: minor (deferred): zoomed contrast change can take two panel updates (as pageChanged does).
Task 3: Step 5 run by controller: 22 ms at STRONGEST; tablet suite 86/86 pass. Build installed (after clearing app/build of 518 '* 2' sync duplicates). Awaiting owner Step 6.
Task 3: complete (commits 9ada3fa..3e68b74, review clean; owner Step 6: that works)

#### Pre-flight scan, Task 5 (continuing the approved plan, 2026-11-10)

| Rows | Produces / consumes | Found |
|---|---|---|
| Task 5 self | ContentBoundsTest 7 cases + ViewportTest vs ContentBounds.of/trim, Viewport(fit) | Agree. |
| Task 5 vs Task 2 | Viewport `fit` keeps pageToScreen in whole-page points, so `visiblePagePart` and the patch keep working; Task 5 says renderWidth "stays the whole page's width at the fitted scale … cap it at 1.5× the view's width" — Task 2 already made renderWidth `sizes[page].w * pxPerPt / zoom` (fitted, 1×) | Agree: with fit, pxPerPt/zoom is the trimmed fitted scale, so the formula gives the whole page at that scale; add the 1.5×-view-width cap. |
| Task 5 vs Task 3 | Consumes `ReadingPrefs.trimMargins` (exists, unused) | Agree. Measuring renders (`PdfFile.render(page, 200)`) go straight to ContentBounds and must not get the contrast step (they aren't shown). |
| Task 5 vs pen limit (PR #14/bug fixes Task 7) | `penLimit()` = `viewport.pageBoundsPx()`; with trim the page is bigger than the view | Ruling: penLimit must stay the page's on-screen part, clipped to the view — the pen still can't write beside the page and Onyx gets a rect inside the screen — costs a one-line clip if Onyx already clips. |
| Task 5 vs grey band | PageView shades beside the page (BESIDE_PAGE) | Agree; unchanged, it shades whatever part of the view the page doesn't cover. |
| Task 5 vs page menu | "Trim margins" with a tick in ⋯ (decision 5) | Ruling: after "Contrast", before the TEMPORARY island entry. |

Ruling: the implementer does Steps 1–4 and 6; Step 5 (tablet check) is the owner's, after the controller runs the tablet suite and installs.
Task 5: implementer DONE_WITH_CONCERNS (ce9dd86): 313 laptop pass; concerns: smoothed measuring render, per-page renderWidth, blur at zoom 1 on heavy trim, tiny printed areas, up to 3 refreshes arriving at an unmeasured page.
Task 5: review Needs fixes (ce9dd86): spec ✅; Important: (1) wasted refit+update for untrimmed pages (had null ≠ whole box), (2) forward turn costs 2 updates (neighbour pictures made at the untrimmed width), (3) redundant showWithoutFastInk in refit path → up to 3 updates on arrival/open, (4) no limit on blowing up a tiny printed area.
Ruling: finding 4 enters the fix loop although the brief is silent — a page with only a page number blown up ~13× is unusable and common on scans; spec §4 (a hard-to-use screen is a failure) outranks the brief's silence — the cap is the fitted scale at most 2× the whole-page fit, the box grown symmetrically around the content before clipping — if wrong, a tiny-content page shows less enlarged, which the owner can still zoom.
Task 5: minor (deferred, plan-mandated): heavy trims blurry at zoom 1 (1.5× cap; patch only above zoom 1.05) (PageView.kt:496, 463).
Task 5: minor (deferred): fitPagesAgain's second publishPenMapping and comment misleading (WritingActivity.kt:592–595).
Task 5: minor (deferred): WritingActivity +150 lines; measuring bookkeeping could be a ui/PrintedAreas class (WritingActivity.kt:536–560, 586–668).
Task 5: minor (deferred): speck rule per axis lets specks widen the box (ContentBounds.kt:94–113).
Task 5: minor (deferred): no laptop test for the refit decisions (WritingActivity.kt:652–668).
Task 5: fix round 1/5 (4 addressed, 1 new open — refit changes the pen mapping before the panel shows the new layout, or costs an extra update when Onyx has the panel back (WritingActivity.kt:677–682); commits ce9dd86..f771136)
Task 5: minor (deferred): jump to an unmeasured, uncached page costs 3 updates (turn, old width, new width), not 2 as reported (PageView.kt:441–442; WritingActivity.kt:682).
Task 5: minor (deferred): comment "both widths at the cap" can't happen on a first refit (WritingActivity.kt:680).
Task 5: minor (deferred): no test for sliding the enlarged box off the top/left edge (ContentBoundsTest).
Task 5: fix round 2/5 (3 addressed, 0 open; commits f771136..435c3d9)
Task 5: minor (deferred): after a jump to an unmeasured page the page can show grey for ~3 renders while the refit picture is held; writing still lands right (old mapping matches the grey rect). Later: give up the refit on stylus/finger down.
Task 5: minor (deferred): showPicture skips patchCovers (PageView.kt:517-520) — zoom during a pending refit can cost an extra update.
Task 5: minor (deferred): off-width picture arriving after a completed refit still calls onPageShown (PageView.kt:512-513); pre-existing pageRendered behaviour underlies it.
Task 5: tablet suite 86/86 pass; build installed, storage permission restored. Awaiting owner Step 5.
Task 5: complete (commits 3e68b74..5bb2749, 2 fix rounds, review clean; owner Step 5: works fine)

#### Tasks 7 and 8 (added to the plan at the owner's request, "yes do all of that", 2026-11-10; plan commit 0abec69)

| Rows | Produces / consumes | Found |
|---|---|---|
| Task 7 self | ViewportTest 3 cases vs rounded origin in pageToScreen/screenToPage | Agree. |
| Task 7 vs Task 2 | Patch drawn at `Math.round(at)` from pageToScreen | Agree; with a whole-pixel origin the patch and page share the same origin rounding. |
| Task 7 vs Task 5 | Viewport fit box; clamp works on box edges then converts back | Agree; rounding is applied only on output (pageToScreen/screenToPage/pageBoundsPx), clamp keeps floats. |
| Task 7 vs pen mapping | publishPenMapping uses pageToScreen().inverse() | Agree; mapping follows the rounded origin, i.e. where the picture is drawn. |
| Task 8 self | PageLookTest 9 cases vs NEARLY_GREY 40, DARK 96, luma 299/587/114 | Agree; checked by hand: (43,47,64)→v 47→0; (149,151,160)→v 151→88 neutral; highlighter (255,247,176) chroma 79 kept. |
| Task 8 vs Task 3 | Same single pass as contrast; Normal+off is a no-op | Agree. |
| Task 8 vs Task 5 | Measuring render must not get either step | Agree (it bypasses the callbacks). |
Task 7: implementer DONE (74d1c57), 317 laptop pass.
Task 7: review Approved, spec ✅, 0 Critical/Important.
Task 7: minor (deferred): zoomKeepsFocusPointStill 0.01 pt tolerance holds only by its integer inputs; loosen to 0.5 px/pxPerPt (ViewportTest.kt:63–70).
Task 7: minor (deferred): aSlowMoveIsNotLost RED was float noise; add a line saying it's a guard (ViewportTest.kt:254–260).
Task 7: minor (deferred): zoomed and pushed to the right/bottom edge, rounding leaves a sub-pixel grey last column/row (Viewport.kt:192); cosmetic, pen limit keeps ink off.
Task 7: minor (deferred): 1 px check lets a stale anyFor picture 1 px wider draw unscaled; tighten width check to < 1f (PageView.kt:637).
Task 7: complete pending tablet check (commits 0abec69..74d1c57, review clean)
Task 7: step 5 controller check (2026-11-10): PWT test copy page 1 at normal size (Strongest, trim on), screenshot vs a poppler 1:1 render with the Strongest curve: mean difference 1.53 placed on a whole pixel, 2.03 / 1.91 for half-pixel blends up / down. The rest is poppler vs MuPDF. Owner part still to do.
Task 8: WIP commit 8178ab4 finished: device test (f054c72); first tablet run 80 ms, failing the < 80 ms limit; the pixel step now reuses the last pixel's answer for runs of one colour and builds its grey table once per picture: 20 ms. Review: matches the plan, 0 Critical/Important.
Task 8: minor (deferred): pale tints (chroma ≤ 40, e.g. formula anti-aliased edges (200, 230, 215), pastel boxes (225, 235, 250)) turn neutral grey; per the spec, but watch for it in step 6 (PageLook.kt, NEARLY_GREY ignores lightness).
Task 8: minor (deferred): the early return in applyTo (off + Normal) has no test; needs Android.
Task 8: minor (deferred): squash 8178ab4 ("work in progress") at merge; plan's message "notes: dark text can be shown as black".
Task 8: on by default (owner, 2026-11-10): ReadingPrefs default true; turned off from ⋯ as before.
Task 8: complete pending owner step 6.
