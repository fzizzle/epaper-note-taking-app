package com.fzizzle.epapernotes.pdf

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Rect
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.ImageItem
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.Item
import com.fzizzle.epapernotes.ink.StrokeCodec
import com.fzizzle.epapernotes.ink.StrokeOutline
import com.fzizzle.epapernotes.ink.StrokeStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * How the app's items are kept as PDF annotations, for [PdfFile].
 *
 * A stroke is a standard `Ink` annotation carrying its pressure data under `/PNotesStroke`.
 * A pasted picture is a `Stamp` carrying `/PNotesImage` (`pi1 <quarterTurns>`). Both keep the
 * item's id in `/NM`, the standard annotation-name field. Plan-2 strokes have no `/NM`, so they
 * go by their object number, as `o<n>`.
 *
 * Both draw with an appearance the app writes itself, so every viewer shows the same thing.
 */
internal class ItemAnnotations(private val doc: PDFDocument) {

    fun isOurs(dict: PDFObject): Boolean = when (dict.get("Subtype").asName()) {
        "Ink" -> dict.get(StrokeCodec.KEY).isString
        "Stamp" -> dict.get(IMAGE_KEY).isString
        else -> false
    }

    /** The item's id. Only meaningful for one of ours. */
    fun idOf(dict: PDFObject): String {
        val name = dict.get("NM")
        return if (name.isString && name.asString().isNotEmpty()) name.asString() else "o${dict.asIndirect()}"
    }

    /** The item an annotation holds, or null if it isn't one of ours or can't be read. */
    fun read(dict: PDFObject): Item? {
        if (!isOurs(dict)) return null
        val id = idOf(dict)
        return when (dict.get("Subtype").asName()) {
            "Ink" -> StrokeCodec.decode(dict.get(StrokeCodec.KEY).asString())?.let { InkItem(id, it) }
            else -> readImage(id, dict)
        }
    }

    fun add(p: PDFPage, item: Item) = when (item) {
        is InkItem -> addInk(p, item)
        is ImageItem -> addImage(p, item)
    }

    private fun addInk(p: PDFPage, item: InkItem) {
        val stroke = item.stroke
        val style = StrokeStyle.of(stroke.kind)
        val annot = p.createAnnotation(PDFAnnotation.TYPE_INK)

        // The ink list and colour are for viewers that ignore appearances and redraw ink
        // themselves. MuPDF's ink-list API works in its page space, not PDF space.
        val toPageSpace = p.transform
        annot.setInkList(arrayOf(stroke.points.map { Point(it.x, it.y).transform(toPageSpace) }.toTypedArray()))
        annot.setColor(style.rgb)
        annot.setOpacity(style.opacity)
        annot.setBorderWidth(style.maxWidth * stroke.widthScale)
        annot.getObject().put("NM", item.id)
        annot.getObject().put(StrokeCodec.KEY, StrokeCodec.encode(stroke))
        // Let MuPDF finish its own drawing now, or it would redraw over ours on the page update.
        annot.update()

        // Replace MuPDF's fixed-width drawing with the pressure outline. The outline is in
        // PDF space, and setAppearance expects page space, so the page transform maps it there.
        val box = StrokeOutline.bounds(stroke)
        val contents = "/GS0 gs\n" + StrokeOutline.contentStream(stroke)
        annot.setAppearance(toPageSpace, rect(box), opacityResources(style.opacity), buffer(contents))
    }

    private fun addImage(p: PDFPage, item: ImageItem) {
        val annot = p.createAnnotation(PDFAnnotation.TYPE_STAMP)
        // Let MuPDF draw its default stamp now, so it doesn't redraw it over ours later.
        annot.update()
        val dict = annot.getObject()
        dict.put("NM", item.id)
        dict.put(IMAGE_KEY, "$IMAGE_VERSION ${item.quarterTurns}")

        val picture = Image(item.png)
        val image = try { doc.addImage(picture) } finally { picture.destroy() }
        val xobjects = doc.newDictionary()
        xobjects.put(IMAGE_NAME, image)
        val res = doc.newDictionary()
        res.put("XObject", xobjects)
        val m = uprightMatrix(item.box, item.quarterTurns)
        val numbers = m.joinToString(" ") { String.format(Locale.ROOT, "%.3f", it) }
        val contents = "q $numbers cm /$IMAGE_NAME Do Q"

        // Written straight into the dictionary: MuPDF's setAppearance leaves a stamp's /Rect
        // at its default spot. Both are in PDF space, so the form needs no /Matrix.
        val form = doc.newDictionary()
        form.put("Type", doc.newName("XObject"))
        form.put("Subtype", doc.newName("Form"))
        form.put("BBox", rectArray(item.box))
        form.put("Resources", res)
        val ap = doc.newDictionary()
        ap.put("N", doc.addStream(buffer(contents), form))
        dict.put("AP", ap)
        dict.put("Rect", rectArray(item.box))
    }

    /**
     * Numbers go in with push(Float). Never push(PDFObject): MuPDF 1.28.5's Java binding drops a
     * reference it never took, so the pushed object is freed while the array still uses it (see
     * MuPdfStressTest). PDFDocument.addObject has the same fault.
     */
    private fun rectArray(b: Box): PDFObject = doc.newArray().apply {
        listOf(b.x0, b.y0, b.x1, b.y1).forEach { push(it) }
    }

    /** The picture back as a PNG, re-encoded by MuPDF from the image its appearance draws. */
    private fun readImage(id: String, dict: PDFObject): ImageItem? {
        val parts = dict.get(IMAGE_KEY).asString().trim().split(' ')
        if (parts.size != 2 || parts[0] != IMAGE_VERSION) return null
        val turns = parts[1].toIntOrNull()?.takeIf { it in 0..3 } ?: return null
        val r = dict.get("Rect")
        if (!r.isArray || r.size() != 4) return null
        val n = (0 until 4).map { r.get(it).asFloat() }
        val box = Box(min(n[0], n[2]), min(n[1], n[3]), max(n[0], n[2]), max(n[1], n[3]))
        val png = try {
            val obj = dict.get("AP").get("N").get("Resources").get("XObject").get(IMAGE_NAME)
            if (obj.isNull) return null
            val pixmap = doc.loadImage(obj).toPixmap()
            try { pixmap.asPNG().asByteArray() } finally { pixmap.destroy() }
        } catch (e: RuntimeException) {
            return null
        }
        return ImageItem(id, box, turns, png)
    }

    /**
     * Maps the image's unit square onto [box], turned counter-clockwise by [quarterTurns]
     * quarter turns, so it looks upright once the page is turned clockwise by as much (Ruling R1).
     * An image's unit square has its bottom-left corner at 0,0 and its top edge at y = 1.
     * Returned as the six numbers of a PDF `cm` operator.
     */
    private fun uprightMatrix(box: Box, quarterTurns: Int): FloatArray {
        val w = box.x1 - box.x0
        val h = box.y1 - box.y0
        return when (quarterTurns) {
            1 -> floatArrayOf(0f, h, -w, 0f, box.x1, box.y0) // its right points up, its top points left
            2 -> floatArrayOf(-w, 0f, 0f, -h, box.x1, box.y1) // upside down
            3 -> floatArrayOf(0f, -h, w, 0f, box.x0, box.y1) // its right points down, its top points right
            else -> floatArrayOf(w, 0f, 0f, h, box.x0, box.y0)
        }
    }

    /** An ExtGState that sets fill opacity, so the highlighter stays see-through in any viewer. */
    private fun opacityResources(opacity: Float): PDFObject {
        val gs = doc.newDictionary()
        gs.put("ca", opacity)
        val ext = doc.newDictionary()
        ext.put("GS0", gs)
        val res = doc.newDictionary()
        res.put("ExtGState", ext)
        return res
    }

    private fun rect(b: Box) = Rect(b.x0, b.y0, b.x1, b.y1)

    private fun buffer(text: String) = Buffer().apply { writeBytes(text.toByteArray(Charsets.US_ASCII)) }

    companion object {
        /** The private key a picture's data is kept under, in its annotation's dictionary. */
        const val IMAGE_KEY = "PNotesImage"
        private const val IMAGE_VERSION = "pi1"
        private const val IMAGE_NAME = "Img"
    }
}
