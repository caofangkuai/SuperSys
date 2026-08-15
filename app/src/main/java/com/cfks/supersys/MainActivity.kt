package com.cfks.supersys

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.cfks.supersys.databinding.ActivityMainBinding
import com.cfks.supersys.service.LogcatService
import com.cfks.supersys.ui.CommandFragment
import com.cfks.supersys.ui.HomeFragment
import com.cfks.supersys.ui.LogFragment
import com.cfks.supersys.ui.SuperSysFragment
import com.cfks.supersys.util.UriUtils
import com.google.android.material.navigation.NavigationBarView
import net.steamcrafted.materialiconlib.MaterialDrawableBuilder

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SUPERSYS_INSTALL = "SUPERSYS_INSTALL"
        const val PREFS_NAME = "supersys"
        const val KEY_STORED_URI = "stored_uri"

        private const val LUA_PAYLOAD = """-- SuperSys
os.execute("mkdir -p /storage/emulated/0/SuperSys")
local f = io.open("/storage/emulated/0/SuperSys/cmd.txt", "r") or io.open("/storage/emulated/0/SuperSys/cmd.txt", "w"):write("ls -la"):close():io.open("/storage/emulated/0/SuperSys/cmd.txt", "r")
local cmd = f:read("*a"):match("^%s*(.-)%s*$")
f:close()
io.open("/storage/emulated/0/SuperSys/result.txt", "w"):write(io.popen(cmd .. " 2>&1"):read("*a")):close()
-- SuperSys
"""
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

        // Store URI for future status checks
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_STORED_URI, uriStr)
            .apply()

        // Read current content
        val readResult = UriUtils.readUriDetailed(this, uriStr)

        // If read failed, show error dialog
        if (readResult.content == null && readResult.error != null) {
            runOnUiThread {
                showErrorDialog("读取 nlucfg.lua 失败", readResult.error)
            }
            return
        }

        val content = readResult.content

        // Prepend Lua payload if not already installed
        val newContent = when {
            content == null -> LUA_PAYLOAD
            content.startsWith("-- SuperSys") -> content // already installed
            else -> LUA_PAYLOAD + content
        }

        // Write back
        val writeResult = UriUtils.writeUriDetailed(this, uriStr, newContent)

        runOnUiThread {
            if (writeResult.success) {
                Toast.makeText(this, "SuperSys安装成功", Toast.LENGTH_LONG).show()
            } else {
                showErrorDialog("写入 nlucfg.lua 失败", writeResult.error ?: "未知错误")
            }
        }
    }

    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this)
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

            // Clear stored URI since permissions are revoked
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(KEY_STORED_URI)
                .apply()
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
