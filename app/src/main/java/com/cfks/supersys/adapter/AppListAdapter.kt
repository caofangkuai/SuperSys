package com.cfks.supersys.adapter

import android.content.SharedPreferences
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cfks.supersys.databinding.ItemAppBinding
import com.cfks.supersys.model.AppInfo

class AppListAdapter(
    private val prefs: SharedPreferences
) : ListAdapter<AppInfo, AppListAdapter.AppViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(o: AppInfo, n: AppInfo) = o.packageName == n.packageName
            override fun areContentsTheSame(o: AppInfo, n: AppInfo) = o == n
        }
    }

    inner class AppViewHolder(val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = getItem(position)
        with(holder.binding) {
            ivAppIcon.setImageDrawable(app.icon)
            tvAppName.text = app.label
            tvAppPackage.text = app.packageName

            val granted = prefs.getBoolean(app.packageName, false)
            switchGrant.setOnCheckedChangeListener(null)
            switchGrant.isChecked = granted
            switchGrant.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(app.packageName, isChecked).apply()
            }

            ivAppIcon.alpha = if (app.isEnabled) 1.0f else 0.4f
        }
    }
}
