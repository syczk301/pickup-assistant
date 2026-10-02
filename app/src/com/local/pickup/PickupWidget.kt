package com.local.pickup

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import kotlin.math.ceil
import kotlin.math.floor

open class PickupWidget : AppWidgetProvider() {
    protected open val defaultSize: SizeF get() = SizeF(280f, 160f)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) refresh(context)
        else super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(context, manager.getAppWidgetOptions(id), defaultSize))
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        manager.updateAppWidget(id, views(context, options, defaultSize))
    }

    companion object {
        private val providers = arrayOf(
            PickupWidget::class.java, PickupSmallWidget::class.java,
            PickupStripWidget::class.java, PickupLargeWidget::class.java,
        )

        // Keep the original receiver so widgets already on the home screen survive updates.
        fun views(context: Context, options: Bundle, fallback: SizeF = SizeF(280f, 160f)): RemoteViews {
            val pending = Store.load(context).filter { it.completed == 0L }.sortedByDescending { it.created }
            if (Build.VERSION.SDK_INT >= 31) {
                @Suppress("DEPRECATION")
                val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                    ?.filter { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }
                    ?.distinct()?.take(16)
                if (!sizes.isNullOrEmpty()) {
                    return RemoteViews(sizes.associateWith { render(context, it, pending) })
                }
            }
            // Older launchers supply portrait width and landscape height as their minimums.
            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val widthKey = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
            val heightKey = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
            val width = options.getInt(widthKey, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, fallback.width.toInt()))
            val height = options.getInt(heightKey, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, fallback.height.toInt()))
            return render(context, SizeF(width.coerceAtLeast(1).toFloat(), height.coerceAtLeast(1).toFloat()), pending)
        }

        private fun render(context: Context, size: SizeF, pending: List<Store.Parcel>): RemoteViews {
            val result = when {
                size.height < 105f -> strip(context, size, pending)
                size.width < 240f && size.height < 240f -> small(context, size, pending)
                else -> list(context, size, pending)
            }
            result.setOnClickPendingIntent(R.id.widget_root, ReminderReceiver.open(context))
            return result
        }

        private fun small(context: Context, size: SizeF, pending: List<Store.Parcel>): RemoteViews {
            val result = RemoteViews(context.packageName, R.layout.widget_small)
            header(context, result, pending.size, size.width, true)
            result.setViewVisibility(R.id.widget_featured, if (pending.isEmpty()) View.GONE else View.VISIBLE)
            val showFooter = footerFits(context, size, pending)
            result.setViewVisibility(R.id.widget_footer, if (showFooter) View.VISIBLE else View.GONE)
            empty(context, result, pending.isEmpty(), size.height - 24f - headerHeight(context) - 6f, size.width - 28f)
            if (pending.isNotEmpty()) {
                val parcel = pending.first()
                val room = (size.height - 24f - headerHeight(context) - 6f - if (showFooter) footerHeight(context) + 6f else 0f).coerceAtLeast(1f)
                val showMeta = room >= textHeight(context, 16f, true) + textHeight(context, 11f) + 6f
                val codeSize = fitHeight(context, 24f, if (showMeta) room - textHeight(context, 11f) - 7f else room - 3f, true)
                result.setTextViewText(R.id.widget_code1, parcel.code)
                result.setTextViewTextSize(R.id.widget_code1, TypedValue.COMPLEX_UNIT_SP, fitWidth(context, parcel.code, codeSize, size.width - 28f))
                result.setTextViewText(R.id.widget_meta1, metadata(parcel))
                result.setViewVisibility(R.id.widget_meta1, if (showMeta) View.VISIBLE else View.GONE)
                val more = if (pending.size > 1) "另有 ${pending.size - 1} 件" else "查看包裹"
                result.setTextViewText(R.id.widget_more, more)
                result.setTextViewTextSize(R.id.widget_more, TypedValue.COMPLEX_UNIT_SP, fitWidth(context, more, 11f, size.width - 48f, false))
            }
            return result
        }

        private fun strip(context: Context, size: SizeF, pending: List<Store.Parcel>): RemoteViews {
            val result = RemoteViews(context.packageName, R.layout.widget_strip)
            result.setTextViewText(R.id.widget_title, pending.size.toString())
            val room = (size.height - 16f).coerceAtLeast(1f)
            val hasMeta = room >= textHeight(context, 16f, true) + textHeight(context, 10f) + 2f
            result.setViewVisibility(R.id.widget_meta1, if (hasMeta) View.VISIBLE else View.GONE)
            result.setViewVisibility(R.id.widget_label, if (hasMeta) View.VISIBLE else View.GONE)
            val code = pending.firstOrNull()?.code ?: "都取完啦"
            val codeSize = fitHeight(context, if (pending.isEmpty()) 16f else 22f, if (hasMeta) room - textHeight(context, 10f) - 2f else room, true, pending.isEmpty())
            result.setTextViewText(R.id.widget_code1, code)
            result.setTextViewTextSize(R.id.widget_code1, TypedValue.COMPLEX_UNIT_SP, fitWidth(context, code, codeSize, size.width - 106f))
            result.setTextViewText(R.id.widget_meta1, pending.firstOrNull()?.let(::metadata) ?: "轻松出门")
            val countSize = fitHeight(context, 24f, if (hasMeta) room - textHeight(context, 10f) - 2f else room, true)
            result.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, fitWidth(context, pending.size.toString(), countSize, 44f))
            return result
        }

        private fun list(context: Context, size: SizeF, pending: List<Store.Parcel>): RemoteViews {
            val result = RemoteViews(context.packageName, R.layout.widget)
            header(context, result, pending.size, size.width)
            val showFooter = footerFits(context, size, pending)
            result.setViewVisibility(R.id.widget_footer, if (showFooter) View.VISIBLE else View.GONE)
            val room = (size.height - 24f - headerHeight(context) - 6f - if (showFooter) footerHeight(context) + 6f else 0f).coerceAtLeast(1f)
            val columns = if (size.width >= 240f) 2 else 1
            // Reserve two extra dp for launcher rounding and Chinese fallback glyphs.
            val minimumRow = textHeight(context, 16f, true) + textHeight(context, 10f) + 7f
            val bandCount = floor((room + 1f) / (minimumRow + 1f)).toInt().coerceIn(1, 6)
            val shown = pending.take(bandCount * columns)
            val visibleBands = ceil(shown.size.toFloat() / columns).toInt().coerceAtLeast(1)
            val rowHeight = (room - visibleBands + 1f) / visibleBands
            val showMeta = rowHeight >= textHeight(context, 12f, true) + textHeight(context, 10f) + 7f
            val metaSize = if (rowHeight >= textHeight(context, 22f, true) + textHeight(context, 12f) + 5f) 12f else 10f
            val codeSize = fitHeight(context, 22f, rowHeight - 6f - if (showMeta) textHeight(context, metaSize) + 1f else 0f, true)
            val cellWidth = (size.width - 28f - if (columns == 2) 17f else 0f) / columns
            empty(context, result, pending.isEmpty(), size.height - 24f - headerHeight(context) - 6f, size.width - 28f)
            for (band in 0..5) {
                val visible = band * columns < shown.size
                result.setViewVisibility(resource(context, "widget_band${band + 1}"), if (visible) View.VISIBLE else View.GONE)
                result.setViewVisibility(resource(context, "widget_sep${band + 1}"), if (visible && band < visibleBands - 1) View.VISIBLE else View.GONE)
                result.setViewVisibility(resource(context, "widget_center${band + 1}"), if (columns == 2 && shown.getOrNull(band * columns + 1) != null) View.VISIBLE else View.GONE)
                for (column in 0..1) {
                    val slot = band * 2 + column + 1
                    val parcel = if (column < columns) shown.getOrNull(band * columns + column) else null
                    val rowId = resource(context, "widget_row$slot")
                    val codeId = resource(context, "widget_code$slot")
                    val metaId = resource(context, "widget_meta$slot")
                    result.setViewVisibility(rowId, when {
                        column >= columns -> View.GONE
                        parcel == null -> View.INVISIBLE
                        else -> View.VISIBLE
                    })
                    // Remove the inter-column padding when the layout becomes a single column.
                    result.setViewPadding(rowId, if (columns == 2 && column == 1) dp(context, 8f) else 0, dp(context, 2f), if (columns == 2 && column == 0) dp(context, 8f) else 0, dp(context, 2f))
                    if (parcel != null) {
                        result.setTextViewText(codeId, parcel.code)
                        result.setTextViewTextSize(codeId, TypedValue.COMPLEX_UNIT_SP, fitWidth(context, parcel.code, codeSize, cellWidth))
                        result.setTextViewText(metaId, metadata(parcel))
                        result.setTextViewTextSize(metaId, TypedValue.COMPLEX_UNIT_SP, metaSize)
                        result.setViewVisibility(metaId, if (showMeta) View.VISIBLE else View.GONE)
                    }
                }
            }
            val remaining = pending.size - shown.size
            result.setTextViewText(R.id.widget_more, if (remaining > 0) "还有 $remaining 件" else "${pending.size} 件待取")
            return result
        }

        private fun header(context: Context, result: RemoteViews, count: Int, width: Float, compact: Boolean = false) {
            result.setTextViewText(R.id.widget_title, "$count 件")
            result.setInt(R.id.widget_header, "setMinimumHeight", dp(context, headerHeight(context)))
            val paint = Paint().apply { textSize = sp(context, 12f) }
            val label = if (compact) "待取" else "待取包裹"
            val required = (paint.measureText(label) + paint.measureText("$count 件")) / context.resources.displayMetrics.density + 20f
            result.setViewVisibility(R.id.widget_label, if (required <= width - 28f) View.VISIBLE else View.INVISIBLE)
        }

        private fun empty(context: Context, result: RemoteViews, visible: Boolean, room: Float, width: Float) {
            result.setViewVisibility(R.id.widget_empty, if (visible) View.VISIBLE else View.GONE)
            if (!visible) return
            val showIcon = room >= 36f + textHeight(context, 12f, true, true)
            result.setViewVisibility(R.id.widget_empty_icon, if (showIcon) View.VISIBLE else View.GONE)
            val mainSize = fitWidth(context, "都取完啦", fitHeight(context, 16f, room - if (showIcon) 36f else 8f, true, true), width)
            val linkSize = fitWidth(context, "打开拾件簿 ›", 11f, width)
            val subtitleSize = fitWidth(context, "轻松出门", 12f, width)
            result.setTextViewTextSize(R.id.widget_empty_title, TypedValue.COMPLEX_UNIT_SP, mainSize)
            result.setTextViewTextSize(R.id.widget_empty_link, TypedValue.COMPLEX_UNIT_SP, linkSize)
            result.setTextViewTextSize(R.id.widget_empty_subtitle, TypedValue.COMPLEX_UNIT_SP, subtitleSize)
            val main = textHeight(context, mainSize, true, true)
            val link = textHeight(context, linkSize)
            val spacing = if (showIcon) 40f else 12f
            result.setViewVisibility(R.id.widget_empty_link, if (room >= spacing + main + link) View.VISIBLE else View.GONE)
            result.setViewVisibility(R.id.widget_empty_subtitle, if (room >= spacing + 2f + main + link + textHeight(context, subtitleSize)) View.VISIBLE else View.GONE)
        }

        private fun metadata(parcel: Store.Parcel): String = parcel.station.ifBlank { parcel.carrier.ifBlank { "待取包裹" } }
        private fun footerFits(context: Context, size: SizeF, pending: List<Store.Parcel>) = pending.isNotEmpty() &&
            size.height >= 24f + headerHeight(context) + footerHeight(context) + 12f + textHeight(context, 12f, true) + 6f
        private fun headerHeight(context: Context) = maxOf(24f, textHeight(context, 12f) + 4f)
        private fun footerHeight(context: Context) = textHeight(context, 11f)
        private fun dp(context: Context, size: Float) = ceil(size * context.resources.displayMetrics.density).toInt()
        private fun sp(context: Context, size: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, context.resources.displayMetrics)
        private fun resource(context: Context, name: String) = context.resources.getIdentifier(name, "id", context.packageName)

        private fun fitHeight(context: Context, preferred: Float, height: Float, bold: Boolean, chinese: Boolean = !bold): Float {
            var size = preferred
            while (size > 12f && textHeight(context, size, bold, chinese) > height) size -= .5f
            return size
        }

        private fun fitWidth(context: Context, text: String, preferred: Float, width: Float, bold: Boolean = true): Float {
            val paint = Paint().apply { typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
            var size = preferred
            while (size > 8f) {
                paint.textSize = sp(context, size)
                if (paint.measureText(text) <= (width - 2f).coerceAtLeast(1f) * context.resources.displayMetrics.density) break
                size -= .5f
            }
            return size
        }

        private fun textHeight(context: Context, size: Float, bold: Boolean = false, chinese: Boolean = !bold): Float {
            val paint = TextPaint().apply {
                textSize = sp(context, size)
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            val sample = if (chinese) "取件驿站" else "0123456789-"
            val builder = StaticLayout.Builder.obtain(sample, 0, sample.length, paint, 10000)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setMaxLines(1)
            if (Build.VERSION.SDK_INT >= 28) builder.setUseLineSpacingFromFallbacks(true)
            return builder.build().height / context.resources.displayMetrics.density
        }

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            for (provider in providers) {
                val ids = manager.getAppWidgetIds(ComponentName(context, provider))
                if (ids.isNotEmpty()) {
                    val size = when (provider) {
                        PickupSmallWidget::class.java -> SizeF(130f, 160f)
                        PickupStripWidget::class.java -> SizeF(280f, 56f)
                        PickupLargeWidget::class.java -> SizeF(280f, 340f)
                        else -> SizeF(280f, 160f)
                    }
                    for (id in ids) manager.updateAppWidget(id, views(context, manager.getAppWidgetOptions(id), size))
                }
            }
        }
    }
}

class PickupSmallWidget : PickupWidget() {
    override val defaultSize: SizeF get() = SizeF(130f, 160f)
}

class PickupStripWidget : PickupWidget() {
    override val defaultSize: SizeF get() = SizeF(280f, 56f)
}

class PickupLargeWidget : PickupWidget() {
    override val defaultSize: SizeF get() = SizeF(280f, 340f)
}
