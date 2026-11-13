# Owner check: files changed elsewhere

For the conflict copies built on 2026-11-13 (spec §7.4). Use a copy of a PDF in a scratch folder, never a real workbook. The file has to change while Jot stays in front, as a sync app would do it, so change it from the laptop over USB. (Leaving Jot for a file manager saves your writing first, so that way there's never a conflict.)

## C1. Changed while you're writing

1. Open the scratch PDF in Jot and leave it on screen.
2. From the laptop, copy a different PDF over it, under the same name.
3. Write a word in Jot and wait about ten seconds.
   - **Expected:** a message says the file was changed elsewhere and that your writing continues in "<name> (my edits DATE)". The strip shows that name.
4. Write another word, then go back to the library.
   - **Expected:** two files. The original is the laptop's version, without your writing. The "(my edits …)" copy is the old version with both words.

## C2. Changed while nothing is unsaved

1. Open the scratch PDF and write nothing.
2. Switch to a file manager, copy a different PDF over it under the same name, and come back to Jot.
   - **Expected:** "This file was changed elsewhere. This is the new version." The new file's pages show. No copy is made.

## C3. The "Not saved" mark

This one is hard to set off on purpose (it needs a save that fails twice, or a full tablet). If the strip ever says **Not saved · tap** where the file's name is, tap it.
   - **Expected:** a short explanation, and the name comes back by itself once saving works again.

## Questions

- Is the copy's name right, and should Jot stay in the copy (as the design says) or go back to the changed file?
- In C2, would you rather be asked before the new version replaces what's on screen?
