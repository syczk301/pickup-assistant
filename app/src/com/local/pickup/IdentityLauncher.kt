package com.local.pickup

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object IdentityLauncher {
    private data class Target(val name: String, val packageName: String, val links: List<String>)
    // Routes found in the supplied APK; account/station-specific Pinduoduo parameters are omitted.
    private val targets = listOf(
        Target("淘宝", "com.taobao.taobao", listOf("taobao://m.taobao.com/tbopen/index.html?h5Url=https://pages-fast.m.taobao.com/wow/z/uniapp/1011717/last-mile-fe/end-collect-platform/identity-code")),
        Target("菜鸟", "com.cainiao.wireless", listOf("guoguo://go/quick_pick_qrcode", "guoguo://go/station_code", "guoguo://go/pickup_page_native")),
        Target("拼多多", "com.xunmeng.pinduoduo", listOf("https://yangkeduo.com/mdkd/dp/naruto/mdkd300?campaign=mdkd&template_id=mdkd300")),
    )

    fun open(activity: Activity, index: Int) {
        val target = targets.getOrNull(index) ?: return
        for (link in target.links) {
            if (launch(activity, Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(target.packageName))) return
        }
        if (index == 1 && launch(activity, Intent("com.cainiao.wireless.ACTION_IDENTITY_CODE").setPackage(target.packageName))) return
        val home = activity.packageManager.getLaunchIntentForPackage(target.packageName)
        if (home != null && launch(activity, home)) {
            Toast.makeText(activity, "已打开${target.name}，请进入取件身份码页面", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(activity, "无法打开${target.name}，请确认应用已安装且未被系统限制", Toast.LENGTH_LONG).show()
        }
    }

    private fun launch(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent); true
    } catch (_: ActivityNotFoundException) { false }
      catch (_: SecurityException) { false }
}
