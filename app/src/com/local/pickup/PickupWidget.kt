package com.local.pickup

import android.appwidget.*
import android.content.*
import android.os.Bundle
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.widget.RemoteViews
import kotlin.math.floor
import kotlin.math.ceil

class PickupWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(context, manager.getAppWidgetOptions(id)))
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        manager.updateAppWidget(id, views(context, options))
    }

    companion object {
        // RemoteViews uses only platform widgets so launcher hosts can inflate it safely.
        fun views(context: Context, options: Bundle): RemoteViews {
            val pending = Store.load(context).filter { it.completed == 0L }.sortedByDescending { it.created }
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160)
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300)
            val fontScale = context.resources.configuration.fontScale
            val density = context.resources.displayMetrics.density
            val scaledDensity = context.resources.displayMetrics.scaledDensity
            val columns = if (width >= 240) 2 else 1
            val bandCount = floor((height - 20f - 34f * fontScale) / (34f * fontScale + 1f)).toInt().coerceIn(1, 6)
            val capacity = bandCount * columns
            val result = RemoteViews(context.packageName, R.layout.widget)
            result.setTextViewText(R.id.widget_title, "${pending.size} 件")
            result.setInt(R.id.widget_header, "setMinimumHeight", ceil(20f * scaledDensity).toInt())
            result.setInt(R.id.widget_footer, "setMinimumHeight", ceil(14f * scaledDensity).toInt())
            result.setViewVisibility(R.id.widget_empty, if (pending.isEmpty()) View.VISIBLE else View.GONE)
            val bands = intArrayOf(R.id.widget_band1, R.id.widget_band2, R.id.widget_band3, R.id.widget_band4, R.id.widget_band5, R.id.widget_band6)
            val centers = intArrayOf(R.id.widget_center1, R.id.widget_center2, R.id.widget_center3, R.id.widget_center4, R.id.widget_center5, R.id.widget_center6)
            val separators = intArrayOf(R.id.widget_sep1, R.id.widget_sep2, R.id.widget_sep3, R.id.widget_sep4, R.id.widget_sep5, R.id.widget_sep6)
            val cells = intArrayOf(R.id.widget_row1, R.id.widget_row2, R.id.widget_row3, R.id.widget_row4, R.id.widget_row5, R.id.widget_row6, R.id.widget_row7, R.id.widget_row8, R.id.widget_row9, R.id.widget_row10, R.id.widget_row11, R.id.widget_row12)
            val codes = intArrayOf(R.id.widget_code1, R.id.widget_code2, R.id.widget_code3, R.id.widget_code4, R.id.widget_code5, R.id.widget_code6, R.id.widget_code7, R.id.widget_code8, R.id.widget_code9, R.id.widget_code10, R.id.widget_code11, R.id.widget_code12)
            val meta = intArrayOf(R.id.widget_meta1, R.id.widget_meta2, R.id.widget_meta3, R.id.widget_meta4, R.id.widget_meta5, R.id.widget_meta6, R.id.widget_meta7, R.id.widget_meta8, R.id.widget_meta9, R.id.widget_meta10, R.id.widget_meta11, R.id.widget_meta12)
            val shown = pending.take(capacity)
            val visibleBands = ceil(shown.size.toFloat() / columns).toInt().coerceAtLeast(1)
            // Match the weighted native layout; fewer parcels get larger, evenly spaced rows.
            val headerHeight = maxOf(20f * fontScale, textHeight(context, 11f) + 4f)
            val footerHeight = maxOf(14f * fontScale, textHeight(context, 10f))
            val rowHeight = ((height - 8f - 2f - headerHeight - footerHeight - (visibleBands - 1)) / visibleBands).coerceAtLeast(1f)
            var codeSize = 24f
            var metaSize = 14f
            while (codeSize > 12f && textHeight(context, codeSize, true) + textHeight(context, metaSize) + 3f > rowHeight) {
                codeSize -= .5f
                metaSize = (codeSize * .6f).coerceIn(10f, 14f)
            }
            val cellWidth = (width - 24f - if (columns == 2) 1f else 0f) / columns - 12f
            bands.forEachIndexed { band, id ->
                val visible = band < bandCount && band * columns < shown.size
                result.setViewVisibility(id, if (visible) View.VISIBLE else View.GONE)
                
                result.setViewVisibility(separators[band], if (visible && band < visibleBands - 1) View.VISIBLE else View.GONE)
                result.setViewVisibility(centers[band], if (columns == 2 && shown.getOrNull(band * columns + 1) != null) View.VISIBLE else View.GONE)
                for (column in 0..1) {
                    val slot = band * 2 + column
                    val parcel = if (column < columns) shown.getOrNull(band * columns + column) else null
                    result.setViewVisibility(cells[slot], when {
                        column >= columns -> View.GONE
                        parcel == null -> View.INVISIBLE
                        else -> View.VISIBLE
                    })
                    if (parcel != null) {
                        result.setTextViewText(codes[slot], parcel.code)
                        val codePaint = Paint().apply { typeface = Typeface.DEFAULT_BOLD; textSize = codeSize * scaledDensity }
                        val fittedCodeSize = minOf(codeSize, codeSize * (cellWidth - 2f) * density / codePaint.measureText(parcel.code)).coerceAtLeast(12f)
                        result.setTextViewTextSize(codes[slot], android.util.TypedValue.COMPLEX_UNIT_SP, fittedCodeSize)
                        result.setTextViewTextSize(meta[slot], android.util.TypedValue.COMPLEX_UNIT_SP, metaSize)
                        result.setTextViewText(meta[slot], parcel.station.ifEmpty { parcel.carrier })
                    }
                }
            }
            val remaining = pending.size - shown.size
            result.setViewVisibility(R.id.widget_more, if (remaining > 0) View.VISIBLE else View.INVISIBLE)
            result.setTextViewText(R.id.widget_more, "还有 $remaining 件")
            result.setOnClickPendingIntent(R.id.widget_root, ReminderReceiver.open(context))
            return result
        }

        private fun textHeight(context: Context, size: Float, bold: Boolean = false): Float {
            // Chinese fallback fonts can be taller than the Latin Paint metrics.
            val paint = TextPaint().apply {
                textSize = size * context.resources.displayMetrics.scaledDensity
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            val sample = if (bold) "0123456789-" else "驿站取件"
            val builder = StaticLayout.Builder.obtain(sample, 0, sample.length, paint, 10000)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setMaxLines(1)
            if (Build.VERSION.SDK_INT >= 28) builder.setUseLineSpacingFromFallbacks(true)
            return builder.build().height / context.resources.displayMetrics.density
        }

        fun refresh(c: Context) {
            val manager = AppWidgetManager.getInstance(c)
            val ids = manager.getAppWidgetIds(ComponentName(c, PickupWidget::class.java))
            if (ids.isNotEmpty()) PickupWidget().onUpdate(c, manager, ids)
        }
    }
}
