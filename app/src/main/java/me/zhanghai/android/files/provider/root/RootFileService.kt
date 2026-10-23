/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import android.annotation.SuppressLint
import android.content.Context
import android.os.Process
import android.util.Log
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.compat.UserHandleCompat
import me.zhanghai.android.files.provider.FileSystemProviders
import me.zhanghai.android.files.provider.remote.RemoteFileService
import me.zhanghai.android.files.provider.remote.RemoteInterface
import me.zhanghai.android.files.util.lazyReflectedMethod
import java.io.File

// We are expanding our root file service to shell UID, but let's keep the original name since it's
// a bit awkward to express root-or-shell-UID in one or two words.
val isRunningAsRoot =
    when (UserHandleCompat.getAppId(Process.myUid())) {
        Process.ROOT_UID, Process.SHELL_UID -> true
        else -> false
    }

@SuppressLint("StaticFieldLeak")
lateinit var rootContext: Context private set

object RootFileService : RemoteFileService(
    RemoteInterface {
        if (ShizukuFileServiceLauncher.isAvailable()) {
            ShizukuFileServiceLauncher.launchService()
        } else {
            LibSuFileServiceLauncher.launchService()
        }
    }
) {
    const val TIMEOUT_MILLIS = 15 * 1000L

    private val LOG_TAG = RootFileService::class.java.simpleName

    // Not actually restricted because there's no restriction when running as root.
    //@RestrictedHiddenApi
    private val activityThreadCurrentActivityThreadMethod by lazyReflectedMethod(
        "android.app.ActivityThread", "currentActivityThread"
    )
    //@RestrictedHiddenApi
    private val activityThreadGetSystemContextMethod by lazyReflectedMethod(
        "android.app.ActivityThread", "getSystemContext"
    )

    fun main(context: Context? = null) {
        Log.i(LOG_TAG, "Creating package context")
        rootContext = try {
            context ?: createPackageContext(BuildConfig.APPLICATION_ID)
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "Failed to create package context via reflection", t)
            context ?: throw t
        }
        preloadNativeLibraries(rootContext)
        Log.i(LOG_TAG, "Installing file system providers")
        FileSystemProviders.install()
        FileSystemProviders.overflowWatchEvents = true
    }

    private fun preloadNativeLibraries(context: Context) {
        try {
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val syscallSo = File(nativeDir, "libsyscall.so")
            if (syscallSo.exists()) {
                System.load(syscallSo.absolutePath)
            }
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "Failed to preload libsyscall.so: ${t.message}")
        }
    }

    private fun createPackageContext(packageName: String): Context {
        val activityThread = activityThreadCurrentActivityThreadMethod.invoke(null)
            ?: throw IllegalStateException("ActivityThread.currentActivityThread() returned null")
        val systemContext = activityThreadGetSystemContextMethod.invoke(activityThread) as? Context
            ?: throw IllegalStateException("ActivityThread.getSystemContext() returned null")
        return systemContext.createPackageContext(
            packageName, Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
        )
    }
}
