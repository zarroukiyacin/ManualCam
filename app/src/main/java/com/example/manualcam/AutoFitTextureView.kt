package com.example.manualcam

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView

/** TextureView qui conserve le ratio de l'aperçu caméra (pas d'image déformée). */
class AutoFitTextureView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : TextureView(context, attrs) {

    private var ratioW = 0
    private var ratioH = 0

    fun setAspectRatio(w: Int, h: Int) {
        ratioW = w
        ratioH = h
        requestLayout()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        if (ratioW == 0 || ratioH == 0) {
            setMeasuredDimension(w, h)
        } else if (w * ratioH < h * ratioW) {
            setMeasuredDimension(w, w * ratioH / ratioW)
        } else {
            setMeasuredDimension(h * ratioW / ratioH, h)
        }
    }
}
