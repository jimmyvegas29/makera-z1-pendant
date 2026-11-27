package com.z1pendant.update

import com.z1pendant.BuildInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setValue
import platform.UIKit.UIApplication
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS updates: the check only. An iPhone app can't install another version of itself, and a
 * sideloaded one is signed with each user's own Apple ID, so "Update" opens the release page and
 * the new .ipa goes on through Sideloadly as before.
 */
class IosUpdater : Updater {
    override suspend fun fetchText(url: String): String = suspendCancellableCoroutine { cont ->
        val nsUrl = NSURL.URLWithString(url) ?: run { cont.resumeWithException(IllegalArgumentException("bad URL")); return@suspendCancellableCoroutine }
        val req = NSMutableURLRequest.requestWithURL(nsUrl)
        req.setValue("Z1-Pendant/${BuildInfo.VERSION}", forHTTPHeaderField = "User-Agent")
        req.setValue("application/vnd.github+json", forHTTPHeaderField = "Accept")
        req.timeoutInterval = 20.0
        val task = NSURLSession.sharedSession.dataTaskWithRequest(req) { data, response, error ->
            val code = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
            when {
                error != null -> cont.resumeWithException(Exception(error.localizedDescription))
                code != 200 -> cont.resumeWithException(Exception("GitHub answered HTTP $code"))
                else -> {
                    val text = data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() } ?: ""
                    cont.resume(text)
                }
            }
        }
        cont.invokeOnCancellation { task.cancel() }
        task.resume()
    }

    override fun openPage(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(nsUrl, options = emptyMap<Any?, Any>(), completionHandler = null)
    }
}
