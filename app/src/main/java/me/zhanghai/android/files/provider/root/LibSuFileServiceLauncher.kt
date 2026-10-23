/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.topjohnwu.superuser.NoShellException
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.remote.IRemoteFileService
import me.zhanghai.android.files.provider.remote.RemoteFileServiceInterface
import me.zhanghai.android.files.provider.remote.RemoteFileSystemException
import me.zhanghai.android.files.util.createIntent
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object LibSuFileServiceLauncher {
    private const val TAG = "LibSuFileServiceLauncher"
    private val lock = Any()

    @Volatile
    private var detectedSuCommand: String? = null

    init {
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            CustomShellBuilder {
                getOrFindSuCommand() ?: "su"
            }
        )
    }

    private fun extractRawToFile(resId: Int, destFile: File) {
        try {
            if (destFile.exists() && destFile.length() > 0) {
                destFile.setExecutable(true, false)
                return
            }
            application.resources.openRawResource(resId).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.setExecutable(true, false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract raw resource $resId to ${destFile.path}", e)
        }
    }

    private fun ensureEmbeddedSuExtracted() {
        try {
            val filesDir = application.filesDir
            extractRawToFile(R.raw.tclsu, File(filesDir, "tclsu"))
            extractRawToFile(R.raw.su_v7a, File(filesDir, "su"))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract embedded su files", e)
        }
    }

    private fun testSuCommand(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(command, "-c", "id"))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            exitCode == 0 && output.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    fun getOrFindSuCommand(): String? {
        detectedSuCommand?.let { return it }

        // 1. Standard su
        if (testSuCommand("su")) {
            detectedSuCommand = "su"
            return "su"
        }

        // 2. System binaries including tclsu (automotive/TCL car platform variant)
        val systemCandidates = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/system/bin/tclsu",
            "/system/xbin/tclsu",
            "/sbin/su",
            "/vendor/bin/su",
            "tclsu"
        )
        for (candidate in systemCandidates) {
            if (!candidate.startsWith("/") || File(candidate).exists()) {
                if (testSuCommand(candidate)) {
                    Log.i(TAG, "Found working su candidate: $candidate")
                    detectedSuCommand = candidate
                    return candidate
                }
            }
        }

        // 3. Embedded su / tclsu binaries extracted from raw resources
        ensureEmbeddedSuExtracted()
        val embeddedCandidates = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) {
            listOf(File(application.filesDir, "tclsu"), File(application.filesDir, "su"))
        } else {
            listOf(File(application.filesDir, "su"), File(application.filesDir, "tclsu"))
        }
        for (embedded in embeddedCandidates) {
            if (embedded.exists() && testSuCommand(embedded.absolutePath)) {
                Log.i(TAG, "Found working embedded su: ${embedded.absolutePath}")
                detectedSuCommand = embedded.absolutePath
                return embedded.absolutePath
            }
        }

        return null
    }

    fun isSuAvailable(): Boolean = getOrFindSuCommand() != null

    @Throws(RemoteFileSystemException::class)
    fun launchService(): IRemoteFileService {
        synchronized(lock) {
            val suCmd = getOrFindSuCommand()
            if (suCmd == null) {
                throw RemoteFileSystemException("Root isn't available")
            }
            return try {
                runBlocking {
                    try {
                        withTimeout(RootFileService.TIMEOUT_MILLIS) {
                            // Proactively create the shell because RootService doesn't allow us to
                            // handle errors during shell creation.
                            suspendCancellableCoroutine<Unit> { continuation ->
                                Shell.EXECUTOR.submit {
                                    try {
                                        Shell.getShell()
                                        continuation.resume(Unit)
                                    } catch (t: Throwable) {
                                        Log.e(TAG, "Shell.getShell failed", t)
                                        continuation.resumeWithException(
                                            RemoteFileSystemException(t)
                                        )
                                    }
                                }
                            }
                            suspendCancellableCoroutine { continuation ->
                                val intent = LibSuFileService::class.createIntent()
                                val connection = object : ServiceConnection {
                                    override fun onServiceConnected(
                                        name: ComponentName,
                                        service: IBinder
                                    ) {
                                        val serviceInterface =
                                            IRemoteFileService.Stub.asInterface(service)
                                        continuation.resume(serviceInterface)
                                    }

                                    override fun onServiceDisconnected(name: ComponentName) {
                                        if (continuation.isActive) {
                                            continuation.resumeWithException(
                                                RemoteFileSystemException(
                                                    "libsu service disconnected"
                                                )
                                            )
                                        }
                                    }

                                    override fun onBindingDied(name: ComponentName) {
                                        if (continuation.isActive) {
                                            continuation.resumeWithException(
                                                RemoteFileSystemException("libsu binding died")
                                            )
                                        }
                                    }

                                    override fun onNullBinding(name: ComponentName) {
                                        if (continuation.isActive) {
                                            continuation.resumeWithException(
                                                RemoteFileSystemException("libsu binding is null")
                                            )
                                        }
                                    }
                                }
                                launch(Dispatchers.Main.immediate) {
                                    try {
                                        RootService.bind(intent, connection)
                                    } catch (t: Throwable) {
                                        Log.e(TAG, "RootService.bind threw exception on Main thread", t)
                                        if (continuation.isActive) {
                                            continuation.resumeWithException(RemoteFileSystemException(t))
                                        }
                                    }
                                    continuation.invokeOnCancellation {
                                        launch(Dispatchers.Main.immediate) {
                                            try {
                                                RootService.unbind(connection)
                                            } catch (t: Throwable) {
                                                Log.w(TAG, "RootService.unbind failed", t)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        throw RemoteFileSystemException(e)
                    }
                }
            } catch (e: InterruptedException) {
                throw RemoteFileSystemException(e)
            }
        }
    }

    private class CustomShellBuilder(private val commandProvider: () -> String) : Shell.Builder() {
        private var customFlags: Int = Shell.FLAG_REDIRECT_STDERR
        private var customTimeout: Long = TimeUnit.MILLISECONDS.toSeconds(RootFileService.TIMEOUT_MILLIS)

        override fun setFlags(flags: Int): Shell.Builder {
            // Strip FLAG_MOUNT_MASTER to prevent vehicle su failure (unknown option --mount-master)
            customFlags = flags and Shell.FLAG_MOUNT_MASTER.inv()
            return this
        }

        override fun setTimeout(timeout: Long): Shell.Builder {
            customTimeout = timeout
            return this
        }

        override fun build(): Shell {
            val cmd = commandProvider()
            return createFreshBuilder().build(cmd)
        }

        override fun build(vararg commands: String): Shell {
            return if (commands.isEmpty()) {
                build()
            } else {
                createFreshBuilder().build(*commands)
            }
        }

        override fun build(process: Process): Shell {
            return createFreshBuilder().build(process)
        }

        private fun createFreshBuilder(): Shell.Builder =
            Shell.Builder.create()
                .setInitializers(LibSuShellInitializer::class.java)
                .setFlags(customFlags)
                .setTimeout(customTimeout)
    }
}

private class LibSuShellInitializer : Shell.Initializer() {
    // Prevent normal shells from being created and set as the main shell.
    override fun onInit(context: Context, shell: Shell): Boolean = shell.isRoot
}

class LibSuFileService : RootService() {
    override fun onCreate() {
        super.onCreate()

        try {
            RootFileService.main(this)
        } catch (t: Throwable) {
            Log.e("LibSuFileService", "RootFileService.main failed in RootService", t)
        }
    }

    override fun onBind(intent: Intent): IBinder = RemoteFileServiceInterface()
}
