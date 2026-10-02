package com.local.pickup

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import java.util.Locale

/** Offline brand artwork. Sources and trademarks are listed in carrier-logos.json. */
object CarrierLogos {
    private data class Logo(val resource: Int, val widthDp: Int)

    private val logos = mapOf(
        "菜鸟驿站" to Logo(R.drawable.carrier_cainiao, 60),
        "顺丰速运" to Logo(R.drawable.carrier_sf, 30),
        "中通快递" to Logo(R.drawable.carrier_zto, 30),
        "圆通速递" to Logo(R.drawable.carrier_yto, 44),
        "申通快递" to Logo(R.drawable.carrier_sto, 30),
        "韵达快递" to Logo(R.drawable.carrier_yunda, 60),
        "极兔速递" to Logo(R.drawable.carrier_jt, 44),
        "京东物流" to Logo(R.drawable.carrier_jd, 30),
        "邮政EMS" to Logo(R.drawable.carrier_ems, 60),
        "德邦快递" to Logo(R.drawable.carrier_deppon, 60),
        "丰巢" to Logo(R.drawable.carrier_fcbox, 60),
    )
    private val aliases = mapOf(
        "菜鸟" to "菜鸟驿站", "顺丰" to "顺丰速运", "顺丰快递" to "顺丰速运",
        "中通" to "中通快递", "圆通" to "圆通速递", "圆通快递" to "圆通速递",
        "申通" to "申通快递", "韵达" to "韵达快递",
        "极兔" to "极兔速递", "极兔快递" to "极兔速递",
        "京东" to "京东物流", "京东快递" to "京东物流",
        "邮政" to "邮政EMS", "中国邮政" to "邮政EMS", "EMS" to "邮政EMS",
        "邮政EMS" to "邮政EMS", "德邦" to "德邦快递", "丰巢快递柜" to "丰巢",
    )

    private fun logo(carrier: String): Logo? {
        val name = carrier.trim()
        return logos[name] ?: aliases[name.uppercase(Locale.ROOT)]?.let(logos::get)
    }

    fun resource(carrier: String): Int = logo(carrier)?.resource ?: R.drawable.ic_tag

    fun widthDp(carrier: String): Int = logo(carrier)?.widthDp ?: 30

    fun view(context: Context, carrier: String): ImageView {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
        return ImageView(context).apply {
            setImageResource(resource(carrier))
            scaleType = ImageView.ScaleType.FIT_CENTER
            // Brand artwork has its own colors, including black lettering.
            imageTintList = null
            clearColorFilter()
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(5).toFloat()
            }
            setPadding(dp(2), dp(2), dp(2), dp(2))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
}
