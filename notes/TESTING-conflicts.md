# Owner check: files changed elsewhere

For the conflict copies built on 2026-11-13 (spec §7.4). Use a copy of a PDF in a scratch folder, never a real workbook. The file has to change while Jot stays in front, as a sync app would do it, so change it from the laptop over USB. (Leaving Jot for a file manager saves your writing first, so that way there's never a conflict.)

## C1. Changed while you're writing

1. Open the scratch PDF in Jot and leave it on screen.
2. From the laptop, copy a different PDF over it, under the same name.
3. Write a word in Jot and wait about ten seconds.
   - **Expected:** a message says the file was changed elsewhere and that your writing continues in "<name> (my edits DATE)". The strip shows that name.
4. Write another word, then go back to the library.
   - **Expected:** two files. The original is the laptop's version, without your writing. The "(my edits …)" copy is the old version with both words.
   - **Expected:** only one "(my edits …)" copy, however long you kept writing.

## C2. Changed while nothing is unsaved

1. Open the scratch PDF and write nothing.
2. Switch to a file manager, copy a different PDF over it under the same name, and come back to Jot.
   - **Expected:** "This file was changed elsewhere. This is the new version." The new file's pages show. No copy is made.

## C3. The "Not saved" mark

This one is hard to set off on purpose (it needs a save that fails twice, or a full tablet). If the strip ever says **Not saved · tap** where the file's name is, tap it.
   - **Expected:** a short explanation, and the name comes back by itself once saving works again.

## C4. Writing the moment you come back

1. Open the scratch PDF in Jot, write nothing, then switch to a file manager.
2. From the laptop, copy a different PDF over the scratch PDF, under the same name.
3. Come back to Jot and write a word at once, before any message shows.
   - **Expected:** your writing continues in a "(my edits …)" copy. The word is in that copy.
4. Go back to the library.
   - **Expected:** the original is the laptop's version, without your word. The copy is the old version, with it.

## C5. Waiting before you write

1. Do steps 1 and 2 of C4.
2. Come back to Jot and wait for "This file was changed elsewhere. This is the new version." Then write a word.
   - **Expected:** the word writes normally, and no copy is made. The word is in the file, on the new version's pages.

## C6. Lifting the pen as the new version lands

1. Do steps 1 and 2 of C4, but come back to Jot and start writing a word right away. Try to lift the pen at the moment the new version arrives. This may take a few tries.
   - **Expected:** the word is in a "(my edits …)" copy. It is never on some other page of the new version.
2. After the message about the new version, write another word.
   - **Expected:** the pen writes normally.

## Questions

- Is the copy's name right, and should Jot stay in the copy (as the design says) or go back to the changed file?
- In C2, would you rather be asked before the new version replaces what's on screen?
