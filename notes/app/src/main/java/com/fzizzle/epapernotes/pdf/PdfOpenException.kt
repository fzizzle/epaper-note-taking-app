package com.fzizzle.epapernotes.pdf

/** A file that can't be opened as a PDF: damaged, password-protected, or not a PDF at all. */
class PdfOpenException(message: String, cause: Throwable? = null) : Exception(message, cause)
