package org.nirobo.browser

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** A small offline PDF viewer for PDF files opened from Android's file picker/downloads. */
class PdfReaderActivity : Activity() {
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pageIndex = 0
    private lateinit var image: ImageView
    private lateinit var pageLabel: TextView
    private lateinit var previous: Button
    private lateinit var next: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(14, 59, 44)
        buildReaderUi()
        openDocument()
    }

    private fun buildReaderUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 247, 246))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.rgb(14, 59, 44))
        }
        previous = Button(this).apply {
            text = "‹"
            contentDescription = "Previous PDF page"
            setOnClickListener { showPage(pageIndex - 1) }
        }
        pageLabel = TextView(this).apply {
            text = "PDF Reader"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        next = Button(this).apply {
            text = "›"
            contentDescription = "Next PDF page"
            setOnClickListener { showPage(pageIndex + 1) }
        }
        toolbar.addView(previous)
        toolbar.addView(pageLabel, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(next)
        image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(8, 8, 8, 8)
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        root.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun openDocument() {
        val uri = intent?.data
        if (uri == null) {
            showError("No PDF file was provided.")
            return
        }
        try {
            descriptor = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("The file could not be opened.")
            renderer = PdfRenderer(descriptor!!)
            if (renderer!!.pageCount == 0) throw IllegalStateException("This PDF has no pages.")
            showPage(0)
        } catch (e: Exception) {
            showError("Can't open this PDF. Make sure the file is available and is a valid PDF.")
        }
    }

    private fun showPage(index: Int) {
        val pdf = renderer ?: return
        if (index !in 0 until pdf.pageCount) return
        try {
            val page = pdf.openPage(index)
            val screenWidth = (resources.displayMetrics.widthPixels - 32).coerceAtLeast(1)
            val scale = (screenWidth.toFloat() / page.width).coerceAtMost(2f)
            val bitmap = Bitmap.createBitmap(
                (page.width * scale).toInt().coerceAtLeast(1),
                (page.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            image.setImageBitmap(bitmap)
            pageIndex = index
            pageLabel.text = "Page ${index + 1} of ${pdf.pageCount}"
            previous.isEnabled = index > 0
            next.isEnabled = index < pdf.pageCount - 1
        } catch (e: Exception) {
            showError("This PDF page could not be rendered.")
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        pageLabel.text = "PDF unavailable"
        previous.isEnabled = false
        next.isEnabled = false
    }

    override fun onDestroy() {
        image.setImageDrawable(null)
        renderer?.close()
        renderer = null
        descriptor?.close()
        descriptor = null
        super.onDestroy()
    }
}
