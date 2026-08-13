package com.drliuhuan.sayboardpro

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.Locale

/** 常量与目录管理，参考 Sayboard / SayboardNeo 的 Constants。 */
object Constants {

    /** 麦克风与 sherpa-onnx 统一采样率 */
    const val SAMPLE_RATE = 16000

    /** 退格长按判定阈值（按住超过该时长开始连续退格），也即连续退格的初始延迟 */
    const val BACKSPACE_REPEAT_START_DELAY_MS = 400L

    /** 连续退格每删一个字符的间隔 */
    const val BACKSPACE_REPEAT_DELAY_MS = 60L

    private fun getFilesDir(context: Context): File =
        if (Environment.isExternalStorageEmulated() || !Environment.isExternalStorageRemovable()) {
            context.getExternalFilesDir(null)!!
        } else {
            context.filesDir
        }

    private fun getTempDir(context: Context): File =
        File(getFilesDir(context).absolutePath, "Temp")

    /** 下载的模型 zip 缓存位置 */
    fun getTemporaryDownloadLocation(context: Context, filename: String): File =
        File(File(getTempDir(context), "ModelZips"), filename)

    /** 解压临时目录 */
    fun getTemporaryUnzipLocation(context: Context): File =
        File(File(getTempDir(context), "TempUnzip"), "Folder")

    /** 已安装 sherpa 模型根目录：files/SherpaModels/ */
    fun getSherpaModelsDirectory(context: Context): File =
        File(getFilesDir(context).absolutePath, "SherpaModels")

    /** 已安装模型通用根目录：files/Models/（本地 LLM GGUF 等模型下载到这里） */
    fun getModelsDirectory(context: Context): File =
        File(getFilesDir(context).absolutePath, "Models")

    /** 某个 sherpa 模型的目录：files/SherpaModels/<modelFolder>/ */
    fun getSherpaModelDir(context: Context, modelName: String): File =
        File(getSherpaModelsDirectory(context), modelName)

    /** 清理整个目录 */
    fun deleteRecursive(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursive(it) }
        }
        file.delete()
    }

    /** 递归统计目录（或文件）占用的总字节数，用于模型列表显示存储占用 */
    fun directorySize(file: File): Long {
        if (file.isFile) return file.length()
        if (!file.isDirectory) return 0
        return file.listFiles()?.sumOf { directorySize(it) } ?: 0L
    }
}
