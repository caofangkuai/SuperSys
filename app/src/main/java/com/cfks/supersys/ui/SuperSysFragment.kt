package com.cfks.supersys.ui

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.cfks.supersys.R
import com.cfks.supersys.adapter.AppListAdapter
import com.cfks.supersys.databinding.FragmentSupersysBinding
import com.cfks.supersys.model.AppInfo

class SuperSysFragment : Fragment() {

    private var _binding: FragmentSupersysBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: AppListAdapter
    private var showSystemApps = false
    private var allApps: List<AppInfo> = emptyList()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSupersysBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("supersys_apps", android.content.Context.MODE_PRIVATE)
        adapter = AppListAdapter(prefs)

        binding.rvAppList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAppList.adapter = adapter

        binding.switchShowSystem.setOnCheckedChangeListener { _, isChecked ->
            showSystemApps = isChecked
            refreshList()
        }

        loadAppsAsync()
    }

    private fun loadAppsAsync() {
        binding.layoutLoading.visibility = View.VISIBLE
        binding.rvAppList.visibility = View.GONE

        Thread {
            val pm = requireContext().packageManager
            val packages = pm.getInstalledApplications(0)

            val apps = packages.map { appInfo ->
                AppInfo(
                    packageName = appInfo.packageName,
                    label = pm.getApplicationLabel(appInfo).toString(),
                    icon = pm.getApplicationIcon(appInfo),
                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    isEnabled = appInfo.enabled
                )
            }.sortedBy { it.label.lowercase() }

            mainHandler.post {
                if (_binding == null) return@post
                allApps = apps
                binding.layoutLoading.visibility = View.GONE
                binding.rvAppList.visibility = View.VISIBLE
                refreshList()
            }
        }.apply {
            name = "AppListLoader"
            isDaemon = true
            start()
        }
    }

    private fun refreshList() {
        val filtered = if (showSystemApps) {
            allApps
        } else {
            allApps.filter { !it.isSystemApp }
        }
        adapter.submitList(filtered)
        binding.tvAppCount.text = getString(R.string.supersys_app_count, filtered.size)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
