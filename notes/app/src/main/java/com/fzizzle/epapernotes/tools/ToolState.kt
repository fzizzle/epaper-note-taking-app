package com.fzizzle.epapernotes.tools

enum class Tool { PEN, HIGHLIGHTER, ERASER, LASSO, SNIP }

/** Which side button is held when the pen touches down (spec §6.3). */
enum class PenButton { NONE, LASSO_BUTTON, ERASER_END }

/** What Onyx's live line looks like while the pen moves. */
enum class LiveLine { PEN, HIGHLIGHTER, THIN_GREY }

/** ERASER and LASSO show a thin grey trail. SNIP never draws with fast-ink (see [ToolState.fastInkOn]); PEN stands in. */
fun liveLine(tool: Tool): LiveLine = when (tool) {
    Tool.HIGHLIGHTER -> LiveLine.HIGHLIGHTER
    Tool.ERASER, Tool.LASSO -> LiveLine.THIN_GREY
    Tool.PEN, Tool.SNIP -> LiveLine.PEN
}

/** The tool rules in one place. UI thread only. */
class ToolState {
    private var beforeSnip = Tool.PEN

    var chosen: Tool = Tool.PEN
        set(value) {
            // Snip is a one-shot: remember where to go back to. Choosing it twice keeps the first.
            if (value == Tool.SNIP && field != Tool.SNIP) beforeSnip = field
            field = value
        }

    var hasSelection = false
    var pasteArmed = false
    var clipboard: Clipboard? = null

    /** A button counts only for the stroke it was held at touch-down; [chosen] is untouched. */
    fun strokeTool(button: PenButton): Tool = when (button) {
        PenButton.LASSO_BUTTON -> Tool.LASSO
        PenButton.ERASER_END -> Tool.ERASER
        PenButton.NONE -> chosen
    }

    /** Fast-ink would paint over the selection box, the paste ghost and the snip rectangle. */
    fun fastInkOn(): Boolean = !hasSelection && !pasteArmed && chosen != Tool.SNIP

    /** Back to the tool before the snip. Only while snipping: a late call mustn't undo a newer choice. */
    fun snipDone() {
        if (chosen == Tool.SNIP) chosen = beforeSnip
    }

    val showPaste: Boolean get() = clipboard != null
}
