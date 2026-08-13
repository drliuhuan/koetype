package com.drliuhuan.sayboardpro.data

import android.content.Context
import android.util.Log
import com.drliuhuan.sayboardpro.AppPrefs
import com.drliuhuan.sayboardpro.R

/**
 * 词典后处理：把识别文本中"与自定义词汇相近"的片段纠正为词汇本身。
 *
 * 语义：词库是用户的自定义术语表（词汇 + 词性 + 权重），词条本身就是目标词，
 * 不再有"误听文本 → 目标文本"的纠错对。例如词条 `示例医院` 会被用于把 STT
 * 误识别的 `示例医园` 纠正回 `示例医院`。
 *
 * 两段式处理：
 * 1. 同音修正（[homophoneFix]）：基于内置拼音表，把"读音相同/相近但字面不同"的
 *    片段替换为词条原文（如 `甲钴胺`→`假骨安` 这种三字同音全错，也能命中）。
 *    做法是把词条与文本窗口都转成无声调拼音，**逐字读音集合取交集**匹配：
 *    词条第 j 字的任一读音 ∈ 窗口第 j 字的读音集合 → 该位置读音相同，
 *    全部位置命中 → 整窗口替换为词条原文。任何同音字天然命中，
 *    不需要维护同音字表、不需要生成组合变体。
 *    可选**模糊拼音**（[AppPrefs.dictionaryFuzzyPinyin]，默认关闭，需用户在设置中
 *    主动开启）：精确读音交集无命中时，再按平翘舌/前后鼻音/n-l 等常见混淆归并的
 *    "模糊键"做全位比较（如"是/四、新/星"），命中同样整词替换为词条原文。
 * 2. 编辑距离匹配：滑动窗口模糊替换，容忍少量增删字符。
 *
 * 编辑距离匹配策略（对齐 OpenTypeless correction_rules 的应用思路）：
 * - 编辑距离滑动窗口替换：窗口长度取词汇长度 ±1，容忍少量增删字符；
 * - 优先级：精确命中（文本中已是该词）优先 → 权重降序 → 长词优先 → 位置靠前优先；
 * - 重叠跳过：已选中的片段不再被其他词条覆盖。
 * 精确命中是 no-op（替换为自身），但它占据的区间会被保护：其他词条的近似匹配
 * 若与其重叠则被跳过，避免把用户已有的正确词条破坏掉。
 */
class DictionaryPostProcessor(
    context: Context,
    private val dictionary: CustomDictionary
) {

    /**
     * 拼音表（无声调，字 → 读音集合），懒加载：首次 [homophoneFix] 时加载一次。
     * 底层是进程级缓存（见 [loadPinyinTable]），IME 服务重建不会重复解析。
     */
    private val pinyin: Map<Char, List<String>> by lazy { loadPinyinTable(context) }

    /** 设置项读取：模糊拼音开关在 [process]/[homophoneFix] 时动态读取
     * （SharedPreferences 读操作线程安全，后处理所在的解码线程可直接读），
     *  用户在设置页切换后无需重启输入法即生效。 */
    private val prefs = AppPrefs(context)

    private val fuzzyPinyinEnabled: Boolean get() = prefs.dictionaryFuzzyPinyin

    /** 对一段识别文本做词汇规范化，返回处理后的文本。 */
    fun process(text: String): String {
        if (text.isEmpty()) return text
        val entries = dictionary.enabledEntries()
        if (entries.isEmpty()) return text

        // 0. 同音修正（精确交集；模糊拼音开关开启时追加模糊键兜底）：先按拼音把
        //    "读音相同/相近但字面不同"的片段替换为词条原文。放在模糊编辑距离匹配之前——
        //    先做克制的读音替换，后续模糊匹配命中的就都是词条原文（精确命中区间受保护），
        //    不会二次破坏。
        val base = applyHomophoneFix(text, entries)

        // 1. 收集所有候选匹配（编辑距离内的近似片段）
        val matches = mutableListOf<Candidate>()
        for (entry in entries) collectMatches(base, entry, matches)
        if (matches.isEmpty()) return base

        // 2. 优先级排序：精确命中优先（保护已正确的词条）→ 权重降序 → 长词优先 → 靠前优先
        matches.sortWith(
            compareByDescending<Candidate> { it.distance == 0 }
                .thenByDescending { it.entry.weight }
                .thenByDescending { it.entry.word.length }
                .thenBy { it.start }
        )

        // 3. 重叠跳过：贪心选取互不重叠的匹配（精确命中先被选中，其区间成为保护区）
        val picked = mutableListOf<Candidate>()
        for (c in matches) {
            if (picked.none { p -> c.start < p.end && c.end > p.start }) picked += c
        }
        if (picked.isEmpty()) return base

        // 4. 从右往左原位替换，避免替换引起的区间偏移
        val sb = StringBuilder(base)
        picked.sortedByDescending { it.start }.forEach { c ->
            sb.replace(c.start, c.end, c.entry.word)
        }
        return sb.toString()
    }

    /**
     * 同音/近音修正：把识别结果中"与词条读音相同/相近但字面不同"的片段替换为词条原文。
     *
     * 背景：热词 boost 只是提高词条路径的概率，压不过模型默认输出的同音字
     * （实测"甲钴胺"被识别成"甲骨胺"）。词库词条即目标词（词汇 + 词性模型），
     * 这里对词条逐字做读音集合交集匹配，命中的窗口替换回原文。
     *
     * 匹配条件（见 [applyHomophoneFix]）：
     * - 词条每字的读音集合 与 窗口对应字的读音集合 交集非空 → 该位置读音相同；
     * - 全部位置命中 → 整窗口替换为词条原文（窗口长度 = 词条长度，逐字比较）。
     * 任何同音字天然命中：`椎`{chui,zhui} vs `追`{zhui}、`甲`{jia} vs `假`{jia}
     * 都能命中，不需要维护同音字表、不需要生成组合变体。
     *
     * 模糊拼音（[AppPrefs.dictionaryFuzzyPinyin] 开启时）追加第二级匹配：
     * - 精确读音交集全部位置命中 → 命中（第一优先级，与关闭时行为一致）；
     * - 否则按平翘舌/前后鼻音/n-l 归并的模糊键做全位比较，全部位置一致 → 命中；
     * - 否则不命中。模糊命中与精确命中同权替换为词条原文。
     *
     * 保守策略（宁可不替换也不误替换），详见 [applyHomophoneFix]：
     * - 仅处理启用词条；单字词不参与精确同音（"可以"这类常用词误伤率太高）；
     *   模糊拼音开启时单字词放宽到平翘舌/前后鼻音（设置文案承诺"是/四、新/星"可修正），
     *   但单字词的 n/l 混淆仍不处理（"牛/刘"风险过高，见 [fuzzyKey]）；
     * - 词条原文已出现在结果中时不替换；
     * - 词条含拼音表外生僻字 → 读音集合不完整 → 整体跳过；
     * - 文本窗口某字在表外 → 该窗口不命中（精确与模糊一致）；
     * - 命中片段本身是其他启用词条原文时不替换（保护用户显式添加的合法词）；
     * - 长词优先（enabledEntries 已按词长降序）。
     *
     * 拼音匹配是"读音相同就换"的取舍：读音相同但字不同的词也会被替换（如词条
     * `形式` 会命中文本 `形势`），这是拼音方案的固有取舍，可接受；模糊拼音把
     * 这一取舍扩展到近似读音（平翘舌/前后鼻音/n-l），故默认关闭、由用户主动开启。
     *
     * [process] 已内置调用；也单独暴露，便于后续在 FINAL 结果上独立复用。
     */
    fun homophoneFix(text: String): String {
        if (text.isEmpty()) return text
        val entries = dictionary.enabledEntries()
        if (entries.isEmpty()) return text
        return applyHomophoneFix(text, entries)
    }

    /**
     * 同音修正的实现体。[entries] 由调用方传入（[process] 复用已取到的启用词条，
     * [homophoneFix] 单独取）。按词长降序遍历（enabledEntries 已排序），长词先修。
     */
    private fun applyHomophoneFix(text: String, entries: List<DictionaryEntry>): String {
        if (text.isEmpty()) return text
        val fuzzy = fuzzyPinyinEnabled
        // 其他启用词条的原文集合：命中片段若是某个合法词条原文则跳过替换，
        // 避免把用户显式添加的词改坏（例如同存"寰枢"与"环枢"两个词条）。
        val enabledWords = entries.mapTo(HashSet<String>()) { it.word }
        var result = text
        for (entry in entries) {
            val word = entry.word
            // 单字词：精确同音替换误伤率过高（如"可以"被当成"可乙"的变体），不处理。
            // 模糊拼音开启时放宽到平翘舌/前后鼻音（设置文案承诺"是/四、新/星"可修正）；
            // 单字词 n/l 混淆（如"牛/刘"）风险过高，仍不归并（见 fuzzyKey 的 includeNl 参数）。
            if (fuzzy) {
                if (word.isEmpty()) continue
            } else {
                if (word.length < MIN_HOMOPHONE_WORD_LENGTH) continue
            }
            // 词条原文已出现在结果中：说明模型已输出正确词，无需（也不该）替换
            if (result.contains(word)) continue
            // 词条读音集合；任一字在拼音表外（生僻字）→ 读音不完整 → 整体跳过
            val wordSounds = wordSoundsOf(word) ?: continue
            // 模糊键集合（仅开关开启时计算）：每字的全部读音 → 模糊键去重集合。
            // 单字词不归并 n/l（保守，见 [fuzzyKey]），多字词用完整模糊键。
            val includeNl = word.length > 1
            val wordFuzzy = if (fuzzy) wordSounds.map { fuzzyKeys(it, includeNl) } else null
            // 滑动窗口：窗口长度 = 词条长度，逐字读音集合交集。
            // 命中后窗口与词条同长，替换不改变字符串长度，可原地从前往后扫。
            var start = 0
            while (start + word.length <= result.length) {
                val window = result.substring(start, start + word.length)
                // 命中片段是其他启用词条原文 → 跳过（enabledWords 集合保护）
                if (window !in enabledWords) {
                    // 优先级：精确读音交集命中 → 模糊键全位一致命中（开关开启时）→ 不命中
                    val exact = soundsMatch(wordSounds, result, start)
                    val hit = exact || (fuzzy && fuzzyMatch(wordFuzzy!!, result, start, includeNl))
                    if (hit) {
                        result = result.replaceRange(start, start + word.length, word)
                        start += word.length // 已替换为词条原文，跳过该区间
                    } else {
                        start++
                    }
                } else {
                    start++
                }
            }
        }
        return result
    }

    /**
     * 词条读音集合：word 每字在拼音表中的读音列表。
     * 任一字不在拼音表（生僻字）→ 返回 null，表示该词条整体不可用（保守跳过）。
     */
    private fun wordSoundsOf(word: String): List<List<String>>? {
        val sounds = ArrayList<List<String>>(word.length)
        for (ch in word) {
            val readings = pinyin[ch].orEmpty()
            if (readings.isEmpty()) return null
            sounds += readings
        }
        return sounds
    }

    /**
     * 逐字读音集合交集匹配：文本 [text] 以 [start] 起始、与词条等长的窗口，
     * 每个位置 j 都满足 词条第 j 字读音集合 ∩ 窗口第 j 字读音集合 非空。
     * 窗口某字在表外（读音集合为空）→ 该窗口不命中（保守）。
     */
    private fun soundsMatch(wordSounds: List<List<String>>, text: String, start: Int): Boolean {
        for (i in wordSounds.indices) {
            if (start + i >= text.length) return false // 防御：索引越界即不命中，绝不抛 OOB
            val windowReadings = pinyin[text[start + i]].orEmpty()
            if (windowReadings.isEmpty()) return false
            if (wordSounds[i].none { it in windowReadings }) return false
        }
        return true
    }

    /**
     * 模糊键全位比较：词条第 i 字的任一模糊键 ∈ 窗口第 i 字的模糊键集合 → 该位置模糊相同。
     * 仅在 [soundsMatch]（精确读音交集）未命中时兜底比较；窗口某字在表外 → 不命中（与精确一致）。
     * [includeNl] 需与计算 [wordFuzzy] 时一致（单字词不含 n/l，见 [fuzzyKey]）。
     */
    private fun fuzzyMatch(wordFuzzy: List<Set<String>>, text: String, start: Int, includeNl: Boolean): Boolean {
        for (i in wordFuzzy.indices) {
            if (start + i >= text.length) return false // 防御：索引越界即不命中，绝不抛 OOB
            val windowReadings = pinyin[text[start + i]].orEmpty()
            if (windowReadings.isEmpty()) return false
            val windowFuzzy = fuzzyKeys(windowReadings, includeNl)
            if (wordFuzzy[i].none { it in windowFuzzy }) return false
        }
        return true
    }

    /** 一组读音的模糊键集合（去重；多音字的每个读音各自归并）。 */
    private fun fuzzyKeys(readings: List<String>, includeNl: Boolean): Set<String> =
        readings.mapTo(HashSet()) { fuzzyKey(it, includeNl) }

    /**
     * 模糊键：把音节按常见混淆归并到同一"模糊等价类"，供模糊拼音开启时的兜底比较。
     * 规则（只做归并，不做反向映射）：
     * - 平翘舌：zh→z、ch→c、sh→s（仅声母）；
     * - 前后鼻音：ang→an、eng→en、ing→in（iang/uang 因包含 ang 自动归并为 ian/uan）；
     * - n/l：声母 n→l（不处理韵尾 n，避免"an→al"这类误归并）。
     *
     * [includeNl]：单字词时关闭 n/l——"牛/刘"这类单字 n/l 混淆风险过高（两字都极常见，
     * 可能是用户本就要输入的字），设置文案也只承诺"是/四、新/星"等平翘舌/前后鼻音的
     * 单字修正；多字词保留 n/l（短语语境下 n/l 混淆更可能是识别错误）。
     */
    private fun fuzzyKey(syllable: String, includeNl: Boolean): String {
        if (syllable.isEmpty()) return "" // 防御：空音节直接返回空键，不做归并
        var s = syllable
        when {
            s.startsWith("zh") -> s = "z" + s.substring(2)
            s.startsWith("ch") -> s = "c" + s.substring(2)
            s.startsWith("sh") -> s = "s" + s.substring(2)
            s.startsWith("n") && includeNl -> s = "l" + s.substring(1)
        }
        return s.replace("iang", "ian")
            .replace("uang", "uan")
            .replace("ang", "an")
            .replace("eng", "en")
            .replace("ing", "in")
    }

    private data class Candidate(
        val start: Int,
        val end: Int,
        val entry: DictionaryEntry,
        val distance: Int
    )

    private fun collectMatches(text: String, entry: DictionaryEntry, out: MutableList<Candidate>) {
        val word = entry.word
        if (word.isEmpty()) return
        val threshold = editDistanceThreshold(word)
        for (start in text.indices) {
            // 滑动窗口：词汇长度 ±1，允许少量增删字符；每个起点只记最佳窗口
            var best: Candidate? = null
            for (len in (word.length - 1)..(word.length + 1)) {
                if (len <= 0) continue
                val end = start + len
                if (end > text.length) continue
                val distance = levenshteinDistance(word, text.substring(start, end), threshold)
                if (distance <= threshold) {
                    // 优先选距离更近、长度更长的窗口：精确匹配 > 少删截 > 多删截，
                    // 避免把"医院"截成"医"再替换成"医院"的破坏性改动
                    if (best == null ||
                        distance < best.distance ||
                        (distance == best.distance && len > best.end - best.start)
                    ) {
                        best = Candidate(start, end, entry, distance)
                    }
                }
            }
            if (best != null) out += best
        }
    }

    /**
     * 编辑距离阈值（允许的字符编辑次数上限）：
     * - 单字词要求完全一致（太短的词模糊匹配误伤率过高）；
     * - 2~5 字允许 1 个编辑（最常见的单字误识别）；
     * - 6 字以上允许 len/3（最多 3），长词通常更独特、不易误伤。
     */
    private fun editDistanceThreshold(word: String): Int =
        if (word.length <= 1) 0 else (word.length / 3).coerceIn(1, 3)

    /** 带剪枝的 Levenshtein 距离：单行 DP，某一行最小值超过 limit 即提前返回 limit+1。 */
    private fun levenshteinDistance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            var rowMin = Int.MAX_VALUE
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1, // 删除
                    curr[j - 1] + 1, // 插入
                    prev[j - 1] + cost // 替换
                )
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > limit) return limit + 1
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    companion object {
        /** 同音修正的最小词长：单字词误伤率过高，不处理。模糊拼音开启时单字词放宽（见 [applyHomophoneFix]） */
        private const val MIN_HOMOPHONE_WORD_LENGTH = 2

        private const val TAG = "DictionaryPostProcessor"

        /** 进程级拼音表缓存：首次加载后常驻，IME 服务重建时避免重复解析 ~200KB。 */
        @Volatile
        private var pinyinTableCache: Map<Char, List<String>>? = null

        private val pinyinTableLock = Any()

        /**
         * 懒加载拼音表（字 → 无声调读音列表，多音字逗号分隔），进程级缓存。
         * 数据来自 res/raw/hanzi_pinyin.txt（`字=拼音1,拼音2,...`，UTF-8）。
         * 表外字不收录 → 读音集合为空 → 匹配时该位置永不命中（保守）。
         * 加载失败时返回空表：同音修正退化为 no-op（宁可不替换也不误替换）。
         */
        private fun loadPinyinTable(context: Context): Map<Char, List<String>> {
            pinyinTableCache?.let { return it }
            synchronized(pinyinTableLock) {
                pinyinTableCache?.let { return it }
                val map = HashMap<Char, List<String>>()
                try {
                    context.resources.openRawResource(R.raw.hanzi_pinyin)
                        .bufferedReader(Charsets.UTF_8)
                        .useLines { lines ->
                            for (line in lines) {
                                if (line.isBlank()) continue
                                val eq = line.indexOf('=')
                                if (eq != 1) continue // 格式：单字=拼音1,拼音2（行首必须是单字）
                                val ch = line[0]
                                // 防御：异常行（孤立代理位/非字母键）直接跳过，不把损坏字符挂进表
                                if (Character.isSurrogate(ch) || !ch.isLetter()) continue
                                val readings = line.substring(eq + 1)
                                    .split(',')
                                    .map { it.trim() }
                                    // 只收合法拼音音节：非空且全为字母（含 ê 等带音符字母，保留
                                    // "欸/誒"这类读音；空拼音/数字/标点一律丢弃，fuzzyKey 只处理字母）
                                    .filter { it.isNotEmpty() && it.all { c -> c.isLetter() } }
                                if (readings.isNotEmpty()) map[ch] = readings
                            }
                        }
                } catch (e: Exception) {
                    Log.w(TAG, "拼音表加载失败，同音修正退化为 no-op", e)
                }
                val loaded = if (map.isEmpty()) emptyMap() else map
                pinyinTableCache = loaded
                return loaded
            }
        }
    }
}
