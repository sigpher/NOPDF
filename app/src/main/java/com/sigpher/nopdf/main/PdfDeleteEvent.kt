package com.sigpher.nopdf.main

/**
 * @author Aaron aaronzzxup@gmail.com
 */
class PdfDeleteEvent(
        val deleted: List<String> = listOf(),
        val dir: String,
        val isEmpty: Boolean
)