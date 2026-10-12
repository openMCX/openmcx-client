//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.identity

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import org.openmcx.client.client.BrowserLauncher

/** Opens the IdMS login in a Custom Tab (RFC 8252 §8.12: an external user-agent, not a WebView). */
fun customTabBrowser(context: Context) = BrowserLauncher { url ->
    CustomTabsIntent.Builder().build().apply { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        .launchUrl(context, Uri.parse(url))
}
