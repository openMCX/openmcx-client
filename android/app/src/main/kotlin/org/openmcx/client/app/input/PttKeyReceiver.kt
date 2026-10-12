//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.input

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.openmcx.client.app.Engine

/**
 * PTT from outside the app: the broadcasts rugged handsets send for their PTT key, and this
 * app's own actions (used by accessories and by the lab tests). A press requests the floor
 * in the active call; a release releases it.
 */
class PttKeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            in DOWN -> Engine.ptt(pressed = true, source = "broadcast ${intent.action}")
            in UP -> Engine.ptt(pressed = false, source = "broadcast ${intent.action}")
        }
    }

    companion object {
        val DOWN = setOf("android.intent.action.PTT.down", "com.sonim.intent.action.PTT_KEY_DOWN", "org.openmcx.client.action.PTT_DOWN")
        val UP = setOf("android.intent.action.PTT.up", "com.sonim.intent.action.PTT_KEY_UP", "org.openmcx.client.action.PTT_UP")
    }
}
