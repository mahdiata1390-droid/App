package com.codleander.gamebooster

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class HistoryChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var values: List<Long> = emptyList()
    fun setData(v: List<Long>) { values=v; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=4f; paint.color=0xFF19B5FF.toInt()
        if(values.size<2) return
        val max=(values.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
        val path=Path()
        values.forEachIndexed { i,n -> val x=width.toFloat()*i/(values.size-1); val y=height-16f-(height-32f)*(n.toFloat()/max); if(i==0) path.moveTo(x,y) else path.lineTo(x,y) }
        canvas.drawPath(path,paint)
        paint.style=Paint.Style.FILL
        values.forEachIndexed { i,n -> val x=width.toFloat()*i/(values.size-1); val y=height-16f-(height-32f)*(n.toFloat()/max); canvas.drawCircle(x,y,5f,paint) }
    }
}