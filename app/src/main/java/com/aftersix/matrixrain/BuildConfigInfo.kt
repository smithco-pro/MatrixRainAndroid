package com.aftersix.matrixrain

import android.content.Context

object BuildConfigInfo {
    fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    fun versionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
}
