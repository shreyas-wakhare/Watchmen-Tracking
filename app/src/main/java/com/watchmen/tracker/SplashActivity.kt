package com.watchmen.tracker

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AnticipateOvershootInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.lifecycleScope
import com.watchmen.tracker.BuildConfig
import com.watchmen.tracker.auth.AuthManager
import com.watchmen.tracker.auth.LoginActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

class SplashActivity : AppCompatActivity() {

    // Define constants for professional tuning
    private val FADE_IN_DURATION = 800L
    private val FADE_DELAY_TITLE = 300L
    private val FADE_DELAY_TAGLINE = 500L
    private val SPLASH_DISPLAY_TIME = 2200L // Total time before transition
    private val PROGRESS_BAR_FADE_DURATION = 400L

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before setContentView() for Shared Element Transition to work smoothly
        window.requestFeature(android.view.Window.FEATURE_ACTIVITY_TRANSITIONS)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        AuthManager.init(this)
        BackendEndpointManager.init(this)

        // 1. Initialize Views (using updated IDs from the enhanced XML)
        val logo = findViewById<ImageView>(R.id.iv_app_logo)
        val title = findViewById<TextView>(R.id.tv_app_title)
        val progressBar = findViewById<ProgressBar>(R.id.pb_loading_indicator)
        val versionText = findViewById<TextView>(R.id.tv_app_version)

        // Setup version text (optional runtime detail)
        // BuildConfig.VERSION_NAME is now resolved via the import
        versionText.text = "v${BuildConfig.VERSION_NAME} | \u00A9 ${Calendar.getInstance().get(Calendar.YEAR)}"

        // 2. Logo Animation (Move + Fade-in)
        val logoInitialTranslationY = 50f
        logo.translationY = logoInitialTranslationY
        logo.alpha = 0f

        logo.animate()
            .alpha(1f) // Fade in
            .translationY(0f) // Move up to final position
            .setDuration(FADE_IN_DURATION)
            .setInterpolator(AnticipateOvershootInterpolator(1.2f)) // Added overshoot for creative bounce effect
            .start()

        // 3. Title/Tagline Animation (Staggered Fade-in)
        title.animate()
            .alpha(1f)
            .setStartDelay(FADE_DELAY_TITLE)
            .setDuration(FADE_IN_DURATION)
            .setInterpolator(DecelerateInterpolator())
            .start()



        // 4. Progress Bar Animation (Appearing after text)
        progressBar.animate()
            .alpha(1f)
            .setStartDelay(FADE_DELAY_TAGLINE + 200) // Delay after tagline appears
            .setDuration(PROGRESS_BAR_FADE_DURATION)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // 5. Delay and Transition based on Auth State
        lifecycleScope.launch {
            delay(SPLASH_DISPLAY_TIME)

            val targetActivity = if (AuthManager.isLoggedIn(this@SplashActivity)) {
                MainActivity::class.java
            } else {
                LoginActivity::class.java
            }

            val intent = Intent(this@SplashActivity, targetActivity)

            // Shared Element Transition setup
            val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                this@SplashActivity,
                logo as View,
                getString(R.string.logo_transition_name)
            )

            startActivity(intent, options.toBundle())
            finish()
        }
    }
}