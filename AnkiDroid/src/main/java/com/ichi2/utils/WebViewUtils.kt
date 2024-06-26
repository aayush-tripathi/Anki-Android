/*
 *  Copyright (c) 2024 David Allison <davidallisongithub@gmail.com>
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.utils

import android.content.pm.PackageInfo
import android.content.pm.PackageManager

/**
 * Returns a [PackageInfo] from the current system WebView, or `null` if unavailable
 */
fun getAndroidSystemWebViewPackageInfo(packageManager: PackageManager): PackageInfo? {
    fun getPackage(packageName: String): PackageInfo? {
        return try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    // The WebView is called com.android.webview by default.
    // Partner devices which ship with Google applications ship the Google-specific version
    // of the WebView called com.google.android.webview.
    // https://issues.chromium.org/issues/40419837#comment10

    return getPackage("com.google.android.webview")
        ?: getPackage("com.android.webview") // com.android.webview is used on API 24
}

/**
 * Checks if the older Android Webview (com.android.webview) is installed.
 *
 * This method attempts to get package information for the package name "com.android.webview".
 * This package name refers to the earlier system WebView, which may be present on some devices.
 * Modern devices often require the "com.google.android.webview" package to provide WebView capabilities.
 * @param packageManager The PackageManager instance to use for querying installed packages.
 * @return A PackageInfo object containing information about the older WebView package if found,
 *           otherwise null.
 */
fun checkOlderWebView(packageManager: PackageManager): PackageInfo? {
    return try {
        packageManager.getPackageInfo("com.android.webview", 0)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
