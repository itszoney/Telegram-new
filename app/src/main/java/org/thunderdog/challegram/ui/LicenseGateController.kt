/*
 * Full-screen license-key entry screen.
 *
 * Shown when [LicenseKeyManager.isLicenseValid] returns false after
 * TDLib authorization is complete. The user cannot dismiss this screen
 * until a valid license key is entered.
 *
 * Launched from LicenseGateLauncher (called in BaseApplication) so it
 * overlays whatever the current screen is without altering existing nav.
 */
package org.thunderdog.challegram.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.thunderdog.challegram.R
import org.thunderdog.challegram.security.LicenseKeyManager

/**
 * Transparent-background Activity that blocks access until a valid
 * license key has been activated.
 *
 * Add to AndroidManifest.xml (already done in the security diff):
 * <activity
 *   android:name=".ui.LicenseGateController"
 *   android:theme="@style/Theme.AppCompat.Light.NoActionBar"
 *   android:excludeFromRecents="true" />
 */
class LicenseGateController : Activity() {

  private lateinit var keyInput:    EditText
  private lateinit var activateBtn: Button
  private lateinit var progressBar: ProgressBar
  private lateinit var statusText:  TextView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // If license became valid between the launch intent and this onCreate
    // (e.g., multiple intents queued), close immediately.
    if (LicenseKeyManager.isLicenseValid()) {
      finish()
      return
    }

    buildUI()
  }

  /** Disallow back-press — user must enter a valid key. */
  @Deprecated("Deprecated in Java")
  override fun onBackPressed() { /* no-op */ }

  // ── UI construction ────────────────────────────────────────────────────────

  private fun buildUI() {
    val dp = resources.displayMetrics.density

    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity     = Gravity.CENTER
      setPadding((32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt())
      setBackgroundColor(0xFF1A1A2E.toInt())
    }

    val title = TextView(this).apply {
      text      = "Activation Required"
      textSize  = 22f
      gravity   = Gravity.CENTER
      setTextColor(0xFFFFFFFF.toInt())
    }

    val subtitle = TextView(this).apply {
      text      = "Enter your license key to continue."
      textSize  = 14f
      gravity   = Gravity.CENTER
      setTextColor(0xFFCCCCCC.toInt())
      setPadding(0, (8 * dp).toInt(), 0, (24 * dp).toInt())
    }

    keyInput = EditText(this).apply {
      hint        = "XXXX-XXXX-XXXX-XXXX"
      textSize    = 16f
      gravity     = Gravity.CENTER
      inputType   = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
      setTextColor(0xFFFFFFFF.toInt())
      setHintTextColor(0xFF888888.toInt())
      setBackgroundColor(0xFF2A2A4A.toInt())
      setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
    }

    statusText = TextView(this).apply {
      text      = ""
      textSize  = 13f
      gravity   = Gravity.CENTER
      setTextColor(0xFFFF6B6B.toInt())
      setPadding(0, (8 * dp).toInt(), 0, 0)
    }

    progressBar = ProgressBar(this).apply {
      visibility = View.GONE
    }

    activateBtn = Button(this).apply {
      text = "Activate"
      setOnClickListener { onActivateClicked() }
    }

    val margin = LinearLayout.LayoutParams(
      LinearLayout.LayoutParams.MATCH_PARENT,
      LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, (8 * dp).toInt(), 0, 0) }

    root.addView(title)
    root.addView(subtitle)
    root.addView(keyInput,     margin)
    root.addView(statusText,   margin)
    root.addView(progressBar,  margin)
    root.addView(activateBtn,  margin)

    setContentView(root)
  }

  // ── Activation logic ───────────────────────────────────────────────────────

  private fun onActivateClicked() {
    val key = keyInput.text.toString().trim()
    if (key.length < 8) {
      statusText.text = "Please enter a valid license key."
      return
    }

    setLoading(true)

    LicenseKeyManager.activateKey(key, object : LicenseKeyManager.LicenseCallback {
      override fun onLicenseResult(valid: Boolean, message: String) {
        runOnUiThread {
          setLoading(false)
          if (valid) {
            Toast.makeText(this@LicenseGateController, "License activated!", Toast.LENGTH_SHORT).show()
            finish()  // remove gate; underlying Telegram X UI is already ready
          } else {
            statusText.text = message.ifBlank { "Invalid or expired license key." }
          }
        }
      }
    })
  }

  private fun setLoading(loading: Boolean) {
    progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    activateBtn.isEnabled  = !loading
    keyInput.isEnabled     = !loading
    statusText.text        = if (loading) "Verifying…" else ""
  }

  companion object {
    /** Launch the gate over the current task stack. */
    fun launch(from: android.content.Context) {
      val intent = Intent(from, LicenseGateController::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
      }
      from.startActivity(intent)
    }
  }
}
