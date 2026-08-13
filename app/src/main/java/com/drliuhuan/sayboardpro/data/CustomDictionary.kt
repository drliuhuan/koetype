package com.drliuhuan.sayboardpro.data

import android.content.Context
import android.util.Log
import com.drliuhuan.sayboardpro.AppPrefs
import org.json.JSONArray

/**
 * 自定义词典：用户的"自定义术语表"。
 *
 * 数据模型是 词汇 + 词性 + 权重，词条本身就是应该被正确识别/保留的目标词
 * （例如 `示例医院`(机构名)、`木乱`(动词)），不再是旧的"误听文本 → 目标文本"
 * 纠错映射。概念对齐 OpenTypeless 的 dictionary 存储（storage/mod.rs 的
 * `dictionary` 表：word + pronunciation），这里把 pronunciation 换成中文词性。
 *
 * 存储：沿用 AppPrefs.dictionaryJson 槽位（SharedPreferences 字符串），但改为
 * 每行一个词条的文本格式：
 *   示例医院,机构名,80
 *   木乱,动词,50,0          ← enabled 为 0（禁用）时显式写出第 4 列，启用时省略
 * 词汇里带逗号不兼容本格式（中文词条一般不含逗号）。
 *
 * 权重（第 3 列）是历史遗留字段：UI 不再让用户输入，新词条统一写 [DictionaryEntry.DEFAULT_WEIGHT]；
 * 排序不再使用权重（见 [enabledEntries]），读取时缺失/非法值回退到默认权重。文件格式保持不变，
 * 旧数据仍可正常解析。
 *
 * 旧版本用 JSON 数组保存 {match, replacement}，读取到 `[` 开头的内容时自动迁移
 * （见 [migrateLegacyJson]）：match == replacement 的恒等词条保留为 word（词性
 * 默认"其他"）；不等价纠错映射在新模型下无法表达，丢弃并打日志。迁移结果立即
 * 落盘为新的行格式，之后按新格式读取。
 */
enum class PartOfSpeech(val label: String) {
    NOUN("名词"),
    VERB("动词"),
    ADJECTIVE("形容词"),
    ADVERB("副词"),
    PLACE_NAME("地名"),
    PERSON_NAME("人名"),
    ORG_NAME("机构名"),
    PHRASE("短语"),
    OTHER("其他");

    companion object {
        /** 解析存储/输入值：接受中文标签或英文枚举名，未知值回退到"其他"。 */
        fun fromLabel(label: String?): PartOfSpeech {
            val v = label?.trim().orEmpty()
            if (v.isEmpty()) return OTHER
            return values().firstOrNull { it.label == v }
                ?: values().firstOrNull { it.name.equals(v, ignoreCase = true) }
                ?: OTHER
        }
    }
}

/** 词条：词汇 + 词性 + 权重。权重为历史遗留字段，排序已不参与（见 [CustomDictionary.enabledEntries]）。 */
data class DictionaryEntry(
    val word: String,
    val partOfSpeech: PartOfSpeech,
    val weight: Float,
    val enabled: Boolean = true
) {
    /** 序列化为 `词汇,词性,权重[,0]`；启用的词条省略第 4 列。 */
    fun toLine(): String = buildString {
        append(word)
        append(',')
        append(partOfSpeech.label)
        append(',')
        append(if (weight % 1f == 0f) weight.toInt().toString() else weight.toString())
        if (!enabled) append(",0")
    }

    companion object {
        /** 新词条以及缺失/非法权重统一使用的默认值（仅为文件格式兼容保留）。 */
        const val DEFAULT_WEIGHT = 80f

        /** 解析一行 `词汇,词性,权重[,enabled]`；词汇为空返回 null，词性/权重缺失或非法时回退到"其他"/[DEFAULT_WEIGHT]。 */
        fun fromLine(line: String): DictionaryEntry? {
            val parts = line.split(',')
            val word = parts.getOrNull(0)?.trim().orEmpty()
            if (word.isEmpty()) return null
            val weight = parts.getOrNull(2)?.trim()?.toFloatOrNull() ?: DEFAULT_WEIGHT
            val enabled = parts.getOrNull(3)?.trim().let { flag ->
                when {
                    flag.isNullOrEmpty() -> true
                    flag == "0" || flag.equals("false", ignoreCase = true) -> false
                    else -> true
                }
            }
            return DictionaryEntry(
                word = word,
                partOfSpeech = PartOfSpeech.fromLabel(parts.getOrNull(1)),
                weight = weight,
                enabled = enabled
            )
        }
    }
}

class CustomDictionary(private val prefs: AppPrefs) {

    /** 全部词条（含词性）。读取时若检测到旧 JSON 格式会自动迁移并写回新格式。 */
    fun getTerms(): List<DictionaryEntry> = parse(prefs.dictionaryJson)

    /** 全部词条（兼容旧调用名，等价于 [getTerms]）。 */
    fun all(): List<DictionaryEntry> = getTerms()

    /**
     * 仅启用的词条，按词长降序排序；等长词保持插入序（词性、权重不参与排序）。
     * 长词优先让"示例医院"这类专名优先于其子串被匹配到；权重已弃用，不再作为排序依据。
     */
    fun enabledEntries(): List<DictionaryEntry> =
        getTerms()
            .filter { it.enabled && it.word.isNotBlank() }
            .sortedByDescending { it.word.length }

    /**
     * 添加词条；词汇已存在时更新其词性/权重（保留启用状态），即按词去重。
     * @return false 表示词汇为空，未做任何改动。
     */
    fun add(word: String, partOfSpeech: PartOfSpeech, weight: Float = DictionaryEntry.DEFAULT_WEIGHT): Boolean {
        val w = word.trim()
        if (w.isEmpty()) return false
        val entries = getTerms().toMutableList()
        val idx = entries.indexOfFirst { it.word == w }
        if (idx >= 0) {
            entries[idx] = DictionaryEntry(w, partOfSpeech, weight, entries[idx].enabled)
        } else {
            entries += DictionaryEntry(w, partOfSpeech, weight)
        }
        save(entries)
        return true
    }

    fun update(entry: DictionaryEntry) {
        val entries = getTerms().toMutableList()
        val idx = entries.indexOfFirst { it.word == entry.word }
        if (idx >= 0) {
            entries[idx] = entry
            save(entries)
        }
    }

    /** 按词启用/停用。 */
    fun setEnabled(word: String, enabled: Boolean) {
        val entries = getTerms().toMutableList()
        val idx = entries.indexOfFirst { it.word == word }
        if (idx >= 0) {
            entries[idx] = entries[idx].copy(enabled = enabled)
            save(entries)
        }
    }

    /** 按词删除。 */
    fun remove(word: String) {
        save(getTerms().filterNot { it.word == word })
    }

    fun clear() {
        prefs.dictionaryJson = ""
    }

    private fun parse(raw: String): List<DictionaryEntry> {
        if (raw.isBlank()) return emptyList()
        // 旧版本 JSON 格式（[ 开头）：自动迁移并写回，之后按新行格式读取
        if (raw.trimStart().startsWith("[")) {
            val migrated = migrateLegacyJson(raw)
            if (migrated != null) {
                save(migrated)
                return migrated
            }
            return emptyList() // JSON 解析失败：按空词典处理
        }
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { DictionaryEntry.fromLine(it) }
            .toList()
    }

    /**
     * 旧 JSON 词条（{id, match, replacement, weight, enabled}）→ 新模型词条。
     * 仅保留 match == replacement 的恒等词条（语义与"该词汇原样保留"一致）；
     * 不等价纠错规则新模型不支持，丢弃并打日志。返回 null 表示 JSON 解析失败。
     */
    private fun migrateLegacyJson(json: String): List<DictionaryEntry>? {
        return try {
            val arr = JSONArray(json)
            val migrated = mutableListOf<DictionaryEntry>()
            var dropped = 0
            (0 until arr.length()).forEach { i ->
                val obj = arr.getJSONObject(i)
                val match = obj.optString("match", "")
                val replacement = obj.optString("replacement", "")
                if (match == replacement && match.isNotBlank()) {
                    migrated += DictionaryEntry(
                        word = match.trim(),
                        partOfSpeech = PartOfSpeech.OTHER,
                        weight = obj.optDouble("weight", 1.0).toFloat(),
                        enabled = obj.optBoolean("enabled", true)
                    )
                } else if (match.isNotBlank() || replacement.isNotBlank()) {
                    dropped++
                }
            }
            if (dropped > 0) {
                Log.w(TAG, "词典旧格式迁移：丢弃 $dropped 条\"误听→目标\"纠错规则（新模型仅支持词汇词条）")
            }
            migrated
        } catch (e: Exception) {
            Log.w(TAG, "词典旧格式解析失败，按空词典处理", e)
            null
        }
    }

    private fun save(entries: List<DictionaryEntry>) {
        prefs.dictionaryJson = entries.joinToString("\n") { it.toLine() }
    }

    companion object {
        private const val TAG = "CustomDictionary"
        fun from(context: Context) = CustomDictionary(AppPrefs(context))
    }
}
