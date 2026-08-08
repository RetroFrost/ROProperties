package com.frameflow.app

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FrameflowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        installCrashRecorder()
    }

    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = File(filesDir, "frameflow-crashes").apply { mkdirs() }
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val stack = StringWriter().also { writer -> throwable.printStackTrace(PrintWriter(writer)) }.toString()
                File(dir, "crash-$stamp.txt").writeText(
                    buildString {
                        appendLine("Frameflow crash")
                        appendLine("time=${System.currentTimeMillis()}")
                        appendLine("thread=${thread.name}")
                        appendLine("android=${android.os.Build.VERSION.SDK_INT}")
                        appendLine("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                        appendLine("version=${BuildConfig.VERSION_NAME}")
                        appendLine()
                        append(stack.take(512_000))
                    }
                )
            }
            if (previous != null) previous.uncaughtException(thread, throwable)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}
