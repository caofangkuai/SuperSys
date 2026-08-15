package com.cfks.supersys.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cfks.supersys.R
import com.cfks.supersys.adapter.LogAdapter
import com.cfks.supersys.databinding.FragmentLogBinding
import com.cfks.supersys.model.LogEntry
import com.cfks.supersys.service.LogcatService
import net.steamcrafted.materialiconlib.MaterialDrawableBuilder

class LogFragment : Fragment(), LogcatService.LogListener {

    private var _binding: FragmentLogBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: LogAdapter
    private var logcatService: LogcatService? = null
    private var isBound = false
    private var autoScroll = true

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LogcatService.LocalBinder
            logcatService = binder.service
            // addListener replays buffered logs automatically
            logcatService?.addListener(this@LogFragment)
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            logcatService = null
            isBound = false
        }
    }

    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            saveLogToFile(uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set icons using Material Icon library
        val ctx = requireContext()
        binding.btnSave.icon = MaterialDrawableBuilder.with(ctx)
            .setIcon(MaterialDrawableBuilder.IconValue.CONTENT_SAVE)
            .setSizeDp(20)
            .build()
        binding.btnClear.icon = MaterialDrawableBuilder.with(ctx)
            .setIcon(MaterialDrawableBuilder.IconValue.CLOSE)
            .setColor(resources.getColor(R.color.status_error, null))
            .setSizeDp(20)
            .build()
        binding.fabScrollDown.setImageDrawable(
            MaterialDrawableBuilder.with(ctx)
                .setIcon(MaterialDrawableBuilder.IconValue.CHEVRON_DOWN)
                .setColor(resources.getColor(R.color.white, null))
                .setSizeDp(24)
                .build()
        )

        adapter = LogAdapter()
        val layoutManager = LinearLayoutManager(requireContext())
        binding.rvLog.layoutManager = layoutManager
        binding.rvLog.adapter = adapter

        binding.rvLog.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val total = layoutManager.itemCount
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val isAtBottom = total > 0 && lastVisible >= total - 2

                if (isAtBottom) {
                    autoScroll = true
                    binding.fabScrollDown.visibility = View.GONE
                } else {
                    autoScroll = false
                    binding.fabScrollDown.visibility = View.VISIBLE
                }
            }
        })

        binding.fabScrollDown.setOnClickListener {
            autoScroll = true
            val count = adapter.itemCount
            if (count > 0) {
                binding.rvLog.smoothScrollToPosition(count - 1)
            }
            binding.fabScrollDown.visibility = View.GONE
        }

        binding.btnClear.setOnClickListener {
            adapter.clear()
            Toast.makeText(requireContext(), getString(R.string.log_cleared), Toast.LENGTH_SHORT).show()
        }

        binding.btnSave.setOnClickListener {
            if (adapter.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.log_empty), Toast.LENGTH_SHORT).show()
            } else {
                val fileName = "supersys_log_${System.currentTimeMillis()}.txt"
                saveLauncher.launch(fileName)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Only bind to the already-running service; do NOT start it here
        val intent = Intent(requireContext(), LogcatService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onPause() {
        super.onPause()
        if (isBound) {
            logcatService?.removeListener(this)
            requireContext().unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onLog(entry: LogEntry) {
        if (_binding == null || !isAdded) return
        requireActivity().runOnUiThread {
            if (_binding == null) return@runOnUiThread
            adapter.addEntry(entry)
            if (autoScroll) {
                binding.rvLog.scrollToPosition(adapter.itemCount - 1)
            }
        }
    }

    override fun onError(message: String) {
        if (_binding == null || !isAdded) return
        requireActivity().runOnUiThread {
            if (!isAdded) return@runOnUiThread
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    private fun saveLogToFile(uri: Uri) {
        try {
            val content = adapter.getAllLogs()
            requireContext().contentResolver.openOutputStream(uri)?.use { output ->
                output.write(content.toByteArray())
            }
            Toast.makeText(requireContext(), getString(R.string.log_saved), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.log_save_failed), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
