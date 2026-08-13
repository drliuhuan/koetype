package com.drliuhuan.sayboardpro

import android.app.Application

/**
 * 应用 Application 类：进程最早入口，安装崩溃日志捕获。
 * manifest 中 application android:name 指向本类。
 */
class SayboardProApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}
