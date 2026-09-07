package com.watchmen.tracker

import android.os.Bundle
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class AppGuideActivity : AppCompatActivity() {

    private var forceAcknowledge = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1️⃣ Read intent flag FIRST
        forceAcknowledge = intent.getBooleanExtra("force_ack", false)

        // 2️⃣ Inflate layout
        setContentView(R.layout.activity_app_guide)

        // 3️⃣ Bind views (IDs match XML exactly)
        val pager = findViewById<ViewPager2>(R.id.guidePager)
        val btnAcknowledge = findViewById<Button>(R.id.btnGuideAcknowledge)
        val dots = findViewById<TabLayout>(R.id.guideDots)

        // 4️⃣ Setup adapter
        val adapter = AppGuideAdapter(this)
        pager.adapter = adapter

        // 5️⃣ Attach swipe dots
        TabLayoutMediator(dots, pager) { _, _ -> }.attach()

        if (forceAcknowledge) {
            // 🔒 Locked until final page
            btnAcknowledge.isEnabled = false
            btnAcknowledge.alpha = 0.5f
            btnAcknowledge.text = getString(R.string.guide_acknowledge)

            // 🚫 Block BACK button
            onBackPressedDispatcher.addCallback(
                this,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        // Intentionally blocked
                    }
                }
            )

            // 🔓 Unlock button only on last page
            pager.registerOnPageChangeCallback(
                object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        val isLastPage = position == adapter.itemCount - 1
                        btnAcknowledge.isEnabled = isLastPage
                        btnAcknowledge.alpha = if (isLastPage) 1f else 0.5f
                    }
                }
            )

        } else {
            // ℹ️ Non-forced mode (acts as Close)
            btnAcknowledge.text = getString(R.string.close)
            btnAcknowledge.isEnabled = true
            btnAcknowledge.alpha = 1f
        }

        // 6️⃣ Button action
        btnAcknowledge.setOnClickListener {
            if (forceAcknowledge) {
                getSharedPreferences("watchmen_prefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean("guide_acknowledged", true)
                    .apply()
            }
            finish()
        }
    }
}
