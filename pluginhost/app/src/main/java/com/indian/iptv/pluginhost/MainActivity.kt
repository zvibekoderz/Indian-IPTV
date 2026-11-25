package com.indian.iptv.pluginhost

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.plugins.BasePlugin
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/** Proof of concept: load a real .cs3 CloudStream plugin and list its MainAPIs. */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 36, 48, 36)
            gravity = Gravity.START
        }
        status = TextView(this).apply {
            text = "Ready. Press Load plugin."
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 32)
        }
        val load = Button(this).apply {
            text = "Load LivXow plugin"
            setOnClickListener { loadPlugin() }
        }
        root.addView(status)
        root.addView(load)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun loadPlugin() {
        status.text = "Downloading .cs3…"
        Thread {
            try {
                val pluginDir = File(filesDir, "plugins").apply { mkdirs() }
                val file = File(pluginDir, "LivXowProvider.cs3")
                download(PLUGIN_URL, file)

                val unpacked = File(pluginDir, "livxow").apply { mkdirs() }
                unzip(file, unpacked)
                File(unpacked, "oat").deleteRecursively()
                val manifest = JSONObject(File(unpacked, "manifest.json").readText())
                val className = manifest.getString("pluginClassName")

                status.post { status.text = "Loading $className…" }

                val optimized = File(codeCacheDir, "dex").apply { mkdirs() }
                optimized.listFiles()?.forEach { it.deleteRecursively() }
                val loader = dalvik.system.DexClassLoader(
                    File(unpacked, "classes.dex").absolutePath,
                    optimized.absolutePath,
                    null,
                    classLoader,
                )
                val plugin = loader.loadClass(className).getDeclaredConstructor().newInstance() as BasePlugin
                plugin.load()

                val providers = APIHolder.allProviders
                    .joinToString("\n") { p: com.lagradost.cloudstream3.MainAPI ->
                        "${p.name} — ${p.mainUrl} (${p.javaClass.name})"
                    }
                status.post {
                    status.text = "Providers (${APIHolder.allProviders.size}):\n$providers"
                }
            } catch (e: Throwable) {
                status.post { status.text = "FAILED: ${e.javaClass.simpleName}: ${e.message}" }
            }
        }.start()
    }

    private fun download(url: String, target: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
        connection.inputStream.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        connection.disconnect()
    }

    private fun unzip(zip: File, target: File) {
        ZipFile(zip).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val out = File(target, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    companion object {
        const val PLUGIN_URL =
            "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/builds/LivXowProvider.cs3"
    }
}
