package com.cfks.supersys.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cfks.supersys.R
import com.cfks.supersys.databinding.ItemLogBinding
import com.cfks.supersys.model.LogEntry
import java.util.Collections

class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    companion object {
        private const val MAX_ENTRIES = 2000
    }

    private val entries: MutableList<LogEntry> = Collections.synchronizedList(mutableListOf())

    inner class LogViewHolder(val binding: ItemLogBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemLogBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val entry = entries[position]
        val tv = holder.binding.tvLogLine
        tv.text = entry.rawLine

        val colorRes = when (entry.level) {
            LogEntry.LEVEL_VERBOSE -> R.color.log_verbose
            LogEntry.LEVEL_DEBUG -> R.color.log_debug
            LogEntry.LEVEL_INFO -> R.color.log_info
            LogEntry.LEVEL_WARN -> R.color.log_warn
            LogEntry.LEVEL_ERROR -> R.color.log_error
            LogEntry.LEVEL_FATAL -> R.color.log_fatal
            else -> R.color.log_default
        }
        tv.setTextColor(tv.context.getColor(colorRes))
    }

    override fun getItemCount(): Int = entries.size

    fun addEntry(entry: LogEntry) {
        synchronized(entries) {
            entries.add(entry)
            if (entries.size > MAX_ENTRIES) {
                val removeCount = entries.size - MAX_ENTRIES
                repeat(removeCount) { entries.removeAt(0) }
                notifyItemRangeRemoved(0, removeCount)
                notifyItemInserted(entries.size - 1)
            } else {
                notifyItemInserted(entries.size - 1)
            }
        }
    }

    fun setEntries(list: List<LogEntry>) {
        synchronized(entries) {
            val oldSize = entries.size
            entries.clear()
            entries.addAll(list)
            notifyItemRangeRemoved(0, oldSize)
            notifyItemRangeInserted(0, entries.size)
        }
    }

    fun clear() {
        synchronized(entries) {
            val size = entries.size
            entries.clear()
            notifyItemRangeRemoved(0, size)
        }
    }

    fun getAllLogs(): String {
        synchronized(entries) {
            return buildString {
                for (e in entries) {
                    appendLine(e.rawLine)
                }
            }
        }
    }

    fun isEmpty(): Boolean = entries.isEmpty()
}
