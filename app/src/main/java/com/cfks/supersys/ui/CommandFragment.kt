package com.cfks.supersys.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.cfks.supersys.R
import com.cfks.supersys.databinding.FragmentCommandBinding
import net.steamcrafted.materialiconlib.MaterialDrawableBuilder
import java.io.File
import java.io.IOException

class CommandFragment : Fragment() {

    companion object {
        private const val SUPERSYS_DIR = "/storage/emulated/0/SuperSys"
        private const val CMD_FILE = "$SUPERSYS_DIR/cmd.txt"
        private const val RESULT_FILE = "$SUPERSYS_DIR/result.txt"
    }

    private var _binding: FragmentCommandBinding? = null
    private val binding get() = _binding!!

    private var fileObserver: FileObserver? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isExecuting = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCommandBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnGrantPerm.setOnClickListener {
            try {
                requestStoragePermission()
            } catch (e: Exception) {
                showErrorDialog("请求权限失败", e.toString())
            }
        }

        binding.btnExecute.setOnClickListener {
            try {
                executeCommand()
            } catch (e: Exception) {
                showErrorDialog("执行命令时发生异常", e.toString())
                resetExecutingState()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            updatePermissionStatus()
        } catch (e: Exception) {
            // Ignore permission check errors
        }
    }

    private fun updatePermissionStatus() {
        val ctx = requireContext()
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (granted) {
            binding.tvPermStatus.text = "已授权"
            binding.tvPermStatus.setTextColor(resources.getColor(R.color.status_success, null))
            binding.ivPermIcon.setIcon(MaterialDrawableBuilder.IconValue.CHECK_CIRCLE)
            binding.ivPermIcon.setColor(resources.getColor(R.color.status_success, null))
            binding.btnGrantPerm.visibility = View.GONE
            binding.btnExecute.isEnabled = true
        } else {
            binding.tvPermStatus.text = "未授权 - 需要所有文件访问权限"
            binding.tvPermStatus.setTextColor(resources.getColor(R.color.status_error, null))
            binding.ivPermIcon.setIcon(MaterialDrawableBuilder.IconValue.ALERT_CIRCLE)
            binding.ivPermIcon.setColor(resources.getColor(R.color.status_error, null))
            binding.btnGrantPerm.visibility = View.VISIBLE
            binding.btnExecute.isEnabled = false
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e2: Exception) {
                    showErrorDialog("无法打开权限设置页面", e2.toString())
                }
            }
        } else {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ), 100
            )
        }
    }

    private fun executeCommand() {
        if (isExecuting) {
            Toast.makeText(requireContext(), "正在执行中，请等待...", Toast.LENGTH_SHORT).show()
            return
        }

        val command = binding.etCommand.text?.toString()?.trim()
        if (command.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "请输入命令", Toast.LENGTH_SHORT).show()
            return
        }

        isExecuting = true
        binding.btnExecute.isEnabled = false
        binding.btnExecute.text = "执行中..."

        // Step 1: Create directory
        try {
            val dir = File(SUPERSYS_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                showErrorDialog("创建目录失败", "无法创建目录: $SUPERSYS_DIR\n可能缺少存储权限")
                resetExecutingState()
                return
            }
        } catch (e: SecurityException) {
            showErrorDialog("创建目录被拒绝", e.toString())
            resetExecutingState()
            return
        } catch (e: Exception) {
            showErrorDialog("创建目录异常", e.toString())
            resetExecutingState()
            return
        }

        // Step 2: Write command file
        try {
            val cmdFile = File(CMD_FILE)
            cmdFile.writeText(command)
            binding.tvResult.text = "命令已写入，正在等待执行结果..."
        } catch (e: SecurityException) {
            showErrorDialog("写入命令文件被拒绝", e.toString())
            resetExecutingState()
            return
        } catch (e: IOException) {
            showErrorDialog("写入命令文件IO错误", e.toString())
            resetExecutingState()
            return
        } catch (e: Exception) {
            showErrorDialog("写入命令文件异常", e.toString())
            resetExecutingState()
            return
        }

        // Step 3: Start FileObserver on result.txt
        try {
            startResultObserver()
        } catch (e: Exception) {
            showErrorDialog("启动文件监听失败", e.toString())
            resetExecutingState()
            return
        }

        // Step 4: Launch ChatActivity to trigger the Lua script
        try {
            val intent = Intent().apply {
                component = android.content.ComponentName(
                    "com.zuoyebang.iot.pad.zpvoiceassistant",
                    "com.zuoyebang.iot.pad.zpvoiceassistant.llm.ui.presentation.activity.ChatActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showErrorDialog("ChatActivity未找到", "目标应用未安装或ChatActivity不存在\n${e.message}")
            stopResultObserver()
            resetExecutingState()
        } catch (e: SecurityException) {
            showErrorDialog("启动ChatActivity被拒绝", e.toString())
            stopResultObserver()
            resetExecutingState()
        } catch (e: Exception) {
            showErrorDialog("启动ChatActivity异常", e.toString())
            stopResultObserver()
            resetExecutingState()
        }
    }

    private fun startResultObserver() {
        stopResultObserver()

        val resultFile = File(RESULT_FILE)

        // Delete old result file if exists
        if (resultFile.exists()) {
            resultFile.delete()
        }

        val mask = FileObserver.MODIFY or FileObserver.CLOSE_WRITE or FileObserver.CREATE

        fileObserver = object : FileObserver(resultFile, mask) {
            override fun onEvent(event: Int, path: String?) {
                try {
                    stopWatching()
                    fileObserver = null

                    mainHandler.postDelayed({
                        readAndDisplayResult()
                    }, 300)
                } catch (e: Exception) {
                    mainHandler.post {
                        if (_binding != null && isAdded) {
                            binding.tvResult.text = "文件监听回调异常: ${e.message}"
                            resetExecutingState()
                        }
                    }
                }
            }
        }
        fileObserver?.startWatching()

        // Timeout: if no result in 30 seconds, show timeout message
        mainHandler.postDelayed({
            if (fileObserver != null && isExecuting) {
                stopResultObserver()
                if (_binding != null && isAdded) {
                    binding.tvResult.text = "等待超时：30秒内未检测到结果文件变化\n可能目标应用未执行命令或Lua脚本未安装"
                    resetExecutingState()
                }
            }
        }, 30000)
    }

    private fun stopResultObserver() {
        try {
            fileObserver?.stopWatching()
        } catch (e: Exception) {
            // Ignore
        }
        fileObserver = null
    }

    private fun readAndDisplayResult() {
        try {
            val resultFile = File(RESULT_FILE)
            if (resultFile.exists()) {
                val content = resultFile.readText()
                if (_binding != null && isAdded) {
                    binding.tvResult.text = content
                    Toast.makeText(
                        requireContext(),
                        "执行完成，请手动返回",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                if (_binding != null && isAdded) {
                    binding.tvResult.text = "结果文件未生成\n可能Lua脚本未安装或命令执行失败"
                }
            }
        } catch (e: SecurityException) {
            if (_binding != null && isAdded) {
                binding.tvResult.text = "读取结果被拒绝: ${e.message}"
            }
        } catch (e: IOException) {
            if (_binding != null && isAdded) {
                binding.tvResult.text = "读取结果IO错误: ${e.message}"
            }
        } catch (e: Exception) {
            if (_binding != null && isAdded) {
                binding.tvResult.text = "读取结果异常: ${e.message}"
            }
        } finally {
            resetExecutingState()
        }
    }

    private fun resetExecutingState() {
        isExecuting = false
        if (_binding != null) {
            binding.btnExecute.isEnabled = true
            binding.btnExecute.text = "执行"
        }
    }

    private fun showErrorDialog(title: String, message: String) {
        if (!isAdded || _binding == null) return
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopResultObserver()
        mainHandler.removeCallbacksAndMessages(null)
        _binding = null
    }
}
