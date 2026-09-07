package com.watchmen.tracker

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import android.widget.ImageView

class AppGuideAdapter(
    private val context: Context
) : RecyclerView.Adapter<AppGuideAdapter.GuideViewHolder>() {

    data class GuidePage(
        val imageRes: Int,
        val titleRes: Int,
        val bodyRes: Int
    )

    private val pages = listOf(
        GuidePage(R.drawable.dwex_guide_intro, R.string.guide_intro_title, R.string.guide_intro_body),
        GuidePage(R.drawable.dwex_guide_integrity, R.string.guide_integrity_title, R.string.guide_integrity_body),
        GuidePage(R.drawable.dwex_guide_hourly, R.string.guide_hourly_title, R.string.guide_hourly_body),
        GuidePage(R.drawable.dwex_guide_missed, R.string.guide_missed_checkin_title, R.string.guide_missed_checkin_body),
        GuidePage(R.drawable.dwex_guide_incident, R.string.guide_incident_title, R.string.guide_incident_body),
        GuidePage(R.drawable.dwex_guide_panic, R.string.guide_panic_title, R.string.guide_panic_body),
        GuidePage(R.drawable.dwex_guide_device, R.string.guide_device_title, R.string.guide_device_body),
        GuidePage(R.drawable.dwex_guide_announce, R.string.guide_announcements_title, R.string.guide_announcements_body)
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GuideViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_guide_page, parent, false)
        return GuideViewHolder(view)
    }

    override fun onBindViewHolder(holder: GuideViewHolder, position: Int) {
        val page = pages[position]
        holder.image.setImageResource(page.imageRes)
        holder.title.text = context.getString(page.titleRes)
        holder.body.text = context.getString(page.bodyRes)
    }

    override fun getItemCount(): Int = pages.size

    class GuideViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.guidePageImage)
        val title: TextView = view.findViewById(R.id.guidePageTitle)
        val body: TextView = view.findViewById(R.id.guidePageBody)
    }
}

