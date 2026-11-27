// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.mirror

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import app.nectarlink.android.R

/**
 * Explains why NectarLink needs "Display over other apps" to show a PC window, and takes the
 * user to the setting. Opened from the notification the phone shows when a window comes
 * and it can't be drawn. Coming back without the permission only says what that means.
 */
class OverlayPermissionActivity : Activity() {
    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (WindowOverlays.canDrawOverlays(this)) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_permission_title)
            .setMessage(R.string.overlay_permission_body)
            .setPositiveButton(R.string.overlay_permission_open) { _, _ ->
                asked = true
                runCatching { startActivity(OverlayPermission.settingsIntent(this)) }
            }
            .setNegativeButton(R.string.overlay_permission_not_now) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (!asked) return
        // Back from the setting.
        if (!WindowOverlays.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_denied, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
