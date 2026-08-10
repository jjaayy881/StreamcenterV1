package com.streamcenter.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!StreamApp.hasConfig(application)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        StreamApp.startBackendIfNeeded(application)

        if (StreamApp.needsTelegramLogin(application)) {
            startActivity(Intent(this, TelegramLoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        viewPager = findViewById(R.id.view_pager)
        val tabLayout: TabLayout = findViewById(R.id.tab_layout)

        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 4
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> TelegramFragment()
                1 -> LiveTvFragment()
                2 -> MediathekFragment()
                else -> StalkerFragment()
            }
        }

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Telegram"
                1 -> "LiveTV"
                2 -> "Mediathek"
                else -> "Stalker"
            }
        }.attach()

        // D-Pad: macht die Tab-Leiste selbst fokussierbar, damit man mit
        // hoch/runter zwischen Tab-Leiste und Liste wechseln kann
        tabLayout.isFocusable = true
        tabLayout.isFocusableInTouchMode = true
    }
}
