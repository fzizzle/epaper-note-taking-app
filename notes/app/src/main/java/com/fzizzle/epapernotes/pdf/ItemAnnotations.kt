package com.fzizzle.epapernotes.pdf

import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.ImageItem
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.Item
import com.fzizzle.epapernotes.ink.ItemIds
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeCodec
import com.fzizzle.epapernotes.ink.StrokeOutline
import com.fzizzle.epapernotes.ink.StrokeStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * How the app's items are kept as PDF annotations, for [PdfFile].
 *
 * A stroke is a standard `Ink` annotation carrying its pressure data under `/PNotesStroke`,
 * and the version of its drawing under `/PNotesDrawing`.
 * A pasted picture is a `Stamp` carrying `/PNotesImage` (`pi1 <quarterTurns>`). Both keep the
 * item's id in `/NM`, the standard annotation-name field. Plan-2 strokes have no `/NM`, so they
 * go by their object number, as `o<n>`.
 *
 * Both draw with an appearance the app writes itself, so every viewer shows the same thing.
 */
internal class ItemAnnotations(private val doc: PDFDocument) {

    /**
     * Each picture's PNG, by its image's object number. Re-encoding a snip takes a while, and
     * the eraser and lasso read every item on the page each time, so each picture is encoded
     * once. A picture's image object is never changed, only dropped with its annotation
     * ([forget]); a pasted or moved picture gets a new image object.
     */
    private val pngs = HashMap<Int, ByteArray>()

    /**
     * True for an annotation the app made and can edit: shaped like one of ours, with an id.
     * Anything else, including one of ours without a usable id (see [idOf]), counts as another
     * app's and is never touched.
     */
    fun isOurs(dict: PDFObject): Boolean = looksOurs(dict) && idOf(dict) != null

    private fun looksOurs(dict: PDFObject): Boolean = when (dict.get("Subtype").asName()) {
        "Ink" -> dict.get(StrokeCodec.KEY).isString
        "Stamp" -> dict.get(IMAGE_KEY).isString
        else -> false
    }

    /**
     * The item's id: its `/NM`, if that is a plain id the journal can carry ([ItemIds.isPlain]).
     * Otherwise (a plan-2 stroke, or a name another app wrote over ours) it goes by its object
     * number, `o<n>`. An annotation written straight into /Annots, not as an object of its own,
     * has no object number, and "o0" would name nothing: it gets no id, so it isn't ours to
     * edit and is left out of [PdfFile.items]. The app always writes its own as objects.
     */
    fun idOf(dict: PDFObject): String? {
        val name = dict.get("NM")
        if (name.isString && ItemIds.isPlain(name.asString())) return name.asString()
        val number = dict.asIndirect()
        return if (number > 0) "o$number" else null
    }

    /** The item an annotation holds, or null if it isn't one of ours or can't be read. */
    fun read(dict: PDFObject): Item? {
        if (!looksOurs(dict)) return null
        val id = idOf(dict) ?: return null
        return when (dict.get("Subtype").asName()) {
            "Ink" -> StrokeCodec.decode(dict.get(StrokeCodec.KEY).asString())?.let { InkItem(id, it) }
            else -> readImage(id, dict)
        }
    }

    /** Writes [item] as a new annotation at the end of the page, and returns that annotation's dictionary. */
    fun add(p: PDFPage, item: Item): PDFObject = when (item) {
        is InkItem -> addInk(p, item)
        is ImageItem -> addImage(p, item)
    }

    private fun addInk(p: PDFPage, item: InkItem): PDFObject {
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
        drawInk(annot.getObject(), stroke)
        return annot.getObject()
    }

    /**
     * Gives an ink annotation the pressure outline as its drawing, written straight into the
     * dictionary as for a picture. The outline, its box and /Rect are all in PDF space, so the
     * form needs no /Matrix.
     *
     * Before this, the app used MuPDF's setAppearance, which stored the page transform as the
     * form's /Matrix. Every viewer then drew the ink mirrored in its box, or turned a quarter
     * on a turned page (owner: "resized writing went upside down"). [isFaulty] finds those.
     *
     * It also notes which version of the drawing this is ([DRAWING_VERSION]), so a later
     * opening can tell a stroke drawn by older code and draw it again.
     */
    private fun drawInk(dict: PDFObject, stroke: Stroke) {
        val box = StrokeOutline.bounds(stroke)
        val contents = "/GS0 gs\n" + StrokeOutline.contentStream(stroke)
        val form = doc.newDictionary()
        form.put("Type", doc.newName("XObject"))
        form.put("Subtype", doc.newName("Form"))
        form.put("BBox", rectArray(box))
        form.put("Resources", opacityResources(StrokeStyle.of(stroke.kind).opacity))
        val ap = doc.newDictionary()
        ap.put("N", doc.addStream(buffer(contents), form))
        // Everything is made before the annotation is touched, so a failure above leaves it as it was.
        val rect = rectArray(box)
        dict.put("AP", ap)
        dict.put("Rect", rect)
        // MuPDF's own drawing padded /Rect with /RD; ours is the outline's box exactly.
        dict.delete("RD")
        dict.put(DRAWING_KEY, DRAWING_VERSION)
    }

    /**
     * True for one of the app's strokes whose drawing should be made again. Either it was
     * drawn before [DRAWING_VERSION] 2 (no version noted, or a lower one), with the small
     * holes at its ends and corners; or it has a /Matrix other than "as is" (see [drawInk]).
     * Only one with a usable id ([isOurs]): anything else is never touched.
     */
    fun isFaulty(dict: PDFObject): Boolean {
        if (!isOurs(dict) || dict.get("Subtype").asName() != "Ink") return false
        val version = dict.get(DRAWING_KEY)
        if (!version.isNumber || version.asInteger() < DRAWING_VERSION) return true
        val m = dict.get("AP").get("N").get("Matrix")
        if (!m.isArray) return false
        if (m.size() != 6) return true
        return (0 until 6).any { m.get(it).asFloat() != IDENTITY[it] }
    }

    /** Draws a faulty stroke ([isFaulty]) again from its recorded points, in place. False if its points can't be read. */
    fun redraw(dict: PDFObject): Boolean {
        val stroke = StrokeCodec.decode(dict.get(StrokeCodec.KEY).asString()) ?: return false
        drawInk(dict, stroke)
        return true
    }

    private fun addImage(p: PDFPage, item: ImageItem): PDFObject {
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
        return dict
    }

    /**
     * Numbers go in with push(Float). Never push(PDFObject): MuPDF 1.28.5's Java binding drops a
     * reference it never took, so the pushed object is freed while the array still uses it (see
     * MuPdfStressTest). PDFDocument.addObject has the same fault.
     */
    private fun rectArray(b: Box): PDFObject = doc.newArray().apply {
        listOf(b.x0, b.y0, b.x1, b.y1).forEach { push(it) }
    }

    /** [dict]'s annotation is about to be deleted: let go of its picture's cached PNG. */
    fun forget(dict: PDFObject) {
        if (dict.get("Subtype").asName() != "Stamp") return
        val number = try { imageObject(dict).asIndirect() } catch (e: RuntimeException) { return }
        pngs.remove(number)
    }

    private fun imageObject(dict: PDFObject): PDFObject =
        dict.get("AP").get("N").get("Resources").get("XObject").get(IMAGE_NAME)

    /** The picture back as a PNG, re-encoded by MuPDF from the image its appearance draws (once: see [pngs]). */
    private fun readImage(id: String, dict: PDFObject): ImageItem? {
        val parts = dict.get(IMAGE_KEY).asString().trim().split(' ')
        if (parts.size != 2 || parts[0] != IMAGE_VERSION) return null
        val turns = parts[1].toIntOrNull()?.takeIf { it in 0..3 } ?: return null
        val r = dict.get("Rect")
        if (!r.isArray || r.size() != 4) return null
        val n = (0 until 4).map { r.get(it).asFloat() }
        val box = Box(min(n[0], n[2]), min(n[1], n[3]), max(n[0], n[2]), max(n[1], n[3]))
        val png = try {
            val obj = imageObject(dict)
            if (obj.isNull) return null
            // Images are always objects of their own; 0 would mean one written inline, not cached.
            val number = obj.asIndirect()
            pngs[number] ?: encode(obj).also { if (number > 0) pngs[number] = it }
        } catch (e: RuntimeException) {
            return null
        }
        return ImageItem(id, box, turns, png)
    }

    private fun encode(image: PDFObject): ByteArray {
        val loaded = doc.loadImage(image)
        try {
            val pixmap = loaded.toPixmap()
            return try { pixmap.asPNG().asByteArray() } finally { pixmap.destroy() }
        } finally {
            loaded.destroy()
        }
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

    private fun buffer(text: String) = Buffer().apply { writeBytes(text.toByteArray(Charsets.US_ASCII)) }

    companion object {
        /** The private key a picture's data is kept under, in its annotation's dictionary. */
        const val IMAGE_KEY = "PNotesImage"
        private const val IMAGE_VERSION = "pi1"
        private const val IMAGE_NAME = "Img"

        /** The private key a stroke's drawing version is kept under, next to its stroke data. */
        const val DRAWING_KEY = "PNotesDrawing"

        /**
         * Which version of the stroke drawing [drawInk] writes. Raise it when the drawing
         * changes in a way old strokes should pick up: [isFaulty] then finds every stroke
         * drawn before, and it's drawn again when its file is next opened.
         * 2: the round ends and corners go the same way round as the body (no holes).
         */
        const val DRAWING_VERSION = 2

        private val IDENTITY = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
    }
}
