package com.drliuhuan.sayboardpro.downloader

import android.content.Context
import android.util.Log
import com.drliuhuan.sayboardpro.Constants
import com.drliuhuan.sayboardpro.CrashLogger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 通用 zip 解压工具（移植自 SayboardNeo 的 ZipTools，去掉 Vosk 特定校验）。
 *
 * 用于用户填写的"自定义模型 URL"（zip 包）下载后解压到 sherpa 模型目录。
 * sherpa 官方模型预设走 [SherpaModelDownloader] 的"单文件下载"路径，不使用本类。
 */
object ZipTools {
    private const val TAG = "ZipTools"

    /**
     * 把 [archive] 解压到 [targetDir]（先清空旧内容）。
     * @return true 表示解压成功；false 表示无法打开/损坏。
     */
    fun extractZipTo(archive: File, targetDir: File): Boolean {
        val zipfile = try {
            ZipFile(archive)
        } catch (e: Exception) {
            Log.e(TAG, "Zip 文件无法打开: ${archive.absolutePath}", e)
            CrashLogger.w(TAG, "DL: Zip 文件无法打开: ${archive.absolutePath}")
            return false
        }
        try {
            if (targetDir.exists()) Constants.deleteRecursive(targetDir)
            targetDir.parentFile?.mkdirs()
            if (!targetDir.mkdirs()) {
                Log.e(TAG, "无法创建目标目录: ${targetDir.absolutePath}")
                CrashLogger.w(TAG, "DL: 无法创建目标目录: ${targetDir.absolutePath}")
                return false
            }

            zipfile.use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement() as ZipEntry
                    extractEntry(zip, entry, targetDir.absolutePath)
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "解压失败: ${archive.absolutePath}", e)
            CrashLogger.w(TAG, "DL: 解压失败: ${archive.absolutePath}")
            if (targetDir.exists()) Constants.deleteRecursive(targetDir)
            return false
        }
    }

    private fun extractEntry(zipfile: ZipFile, entry: ZipEntry, outputDir: String) {
        // Zip Slip 防护：拒绝绝对路径、含 ../ 或 ..\ 的路径穿越、空名，
        // 防止恶意 zip 通过 entry.name 写出目标目录覆盖任意文件。
        if (!isSafeEntryName(entry.name)) {
            CrashLogger.w(TAG, "DL: 拒绝不安全的 zip 条目: ${entry.name}")
            return
        }
        if (entry.isDirectory) {
            val dir = File(outputDir, entry.name)
            if (!dir.exists()) dir.mkdirs()
            return
        }
        val outputFile = File(outputDir, entry.name)
        val parent = outputFile.parentFile
        if (parent == null) {
            CrashLogger.w(TAG, "DL: 条目无父目录，跳过: ${entry.name}")
            return
        }
        if (!parent.exists()) parent.mkdirs()
        zipfile.getInputStream(entry).use { zin ->
            BufferedInputStream(zin).use { input ->
                BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
                    val b = ByteArray(8192)
                    var n: Int
                    while (input.read(b).also { n = it } >= 0) {
                        output.write(b, 0, n)
                    }
                }
            }
        }
    }

    /** Zip Slip 防护：拒绝绝对路径、含 ../ 或 ..\ 的路径穿越、空名。 */
    private fun isSafeEntryName(name: String): Boolean {
        if (name.isEmpty()) return false
        // 绝对路径：Unix "/"、Windows "\" 或盘符（如 C:）
        if (name.startsWith("/") || name.startsWith("\\")) return false
        if (name.length >= 2 && name[1] == ':') return false
        // 路径穿越：".." 段（含 ../ 或 ..\）
        if (name == ".." || name.contains("../") || name.contains("..\\")) return false
        return true
    }

    /**
     * 选择性解压：只处理 [mapping] 中列出的条目，并按映射改名写入 [targetDir]。
     *
     * 键 = zip 内条目名（含相对路径），值 = 写入 [targetDir] 后的相对路径（可含子目录）。
     * 未列出的条目跳过；条目缺失即返回 false（调用方负责清理已写入内容）。
     * 用于 GitHub 整包 zip：一次下载同时装 ASR 与标点两个目录，各自只取自己的文件并改名为
     * 现有 [SherpaModelDownloader] config/校验期望的名字（如 zh_encoder.int8.onnx →
     * encoder.int8.onnx）。解压前清空 [targetDir] 旧内容。
     */
    fun extractMapped(archive: File, targetDir: File, mapping: Map<String, String>): Boolean {
        val zipfile = try {
            ZipFile(archive)
        } catch (e: Exception) {
            Log.e(TAG, "Zip 文件无法打开: ${archive.absolutePath}", e)
            CrashLogger.w(TAG, "DL: Zip 文件无法打开: ${archive.absolutePath}")
            return false
        }
        try {
            if (targetDir.exists()) Constants.deleteRecursive(targetDir)
            val targetParent = targetDir.parentFile
            if (targetParent != null && !targetParent.exists()) targetParent.mkdirs()
            if (!targetDir.mkdirs()) {
                Log.e(TAG, "无法创建目标目录: ${targetDir.absolutePath}")
                CrashLogger.w(TAG, "DL: 无法创建目标目录: ${targetDir.absolutePath}")
                return false
            }

            zipfile.use { zip ->
                for ((entryName, outputRel) in mapping) {
                    val entry = zip.getEntry(entryName)
                    if (entry == null) {
                        Log.e(TAG, "Zip 中缺少条目: $entryName")
                        CrashLogger.w(TAG, "DL: Zip 中缺少条目: $entryName")
                        Constants.deleteRecursive(targetDir)
                        return false
                    }
                    val outputFile = File(targetDir, outputRel)
                    val outputParent = outputFile.parentFile
                    if (outputParent != null && !outputParent.exists()) outputParent.mkdirs()
                    zip.getInputStream(entry).use { zin ->
                        BufferedInputStream(zin).use { input ->
                            BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
                                val b = ByteArray(8192)
                                var n: Int
                                while (input.read(b).also { n = it } >= 0) {
                                    output.write(b, 0, n)
                                }
                            }
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "解压失败: ${archive.absolutePath}", e)
            CrashLogger.w(TAG, "DL: 解压失败: ${archive.absolutePath}")
            if (targetDir.exists()) Constants.deleteRecursive(targetDir)
            return false
        }
    }
}
