package com.sigpher.nopdf.common.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * 把选中的词交给外部应用查词。
 *
 * 本功能只做「英文 → 中文」，不在 App 内做翻译，因此不引入任何网络请求与密钥。
 * 两条路径：
 * 1. 海词词典 App（若已安装）——直接跳到它的查词界面；
 * 2. 兜底用系统浏览器打开 `https://dict.cn/search?q=<词>`。
 *
 * 海词在不同渠道/年代发布过多个包名（应用商店当前主推 `cn.dict.android.pro`，
 * 早期为 `com.hibid.hacdict`），且各家未公开统一的查词 Intent 协议，
 * 因此这里按候选列表逐个尝试，全部失败再走浏览器，不做硬编码假设。
 */
object DictionaryLauncher {

    private const val TAG = "DictionaryLauncher"

    /** 海词在线查询页。参数名与 dict.cn 站内搜索表单一致（GET /search?q=）。 */
    private const val WEB_QUERY_URL = "https://dict.cn/search?q=%s"

    /** 已知的海词词典包名，按新到旧排列。 */
    private val DICT_PACKAGES = arrayOf(
            "cn.dict.android.pro",
            "com.hibid.hacdict"
    )

    /**
     * 词典 App 可能注册的查词 scheme，词以 URI 编码后追加在末尾。
     * 这些属于「尽力而为」的猜测，逐个试探，失败即回落到浏览器。
     */
    private val DICT_SCHEMES = arrayOf(
            "haici://word/",
            "haici://search?word=",
            "dict://word/"
    )

    /**
     * 查词总入口：先用词典 App，不可用则回落到浏览器。
     *
     * @return 是否成功唤起了任一查词应用
     */
    fun lookup(context: Context, word: String): Boolean {
        if (launchDictionaryApp(context, word)) {
            return true
        }
        return launchWeb(context, word)
    }

    /**
     * 优先唤起已安装的词典 App。
     *
     * @return true 表示确实交给了某个词典 App
     */
    fun launchDictionaryApp(context: Context, word: String): Boolean {
        val encoded = Uri.encode(word)
        for (pkg in DICT_PACKAGES) {
            for (intent in candidateIntents(context, pkg, encoded)) {
                if (tryStart(context, intent)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * 用系统浏览器打开海词在线查询页。
     *
     * @return true 表示成功唤起了浏览器
     */
    fun launchWeb(context: Context, word: String): Boolean {
        val url = String.format(WEB_QUERY_URL, Uri.encode(word))
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStart(context, intent)
    }

    /**
     * 针对某个词典包构造候选 Intent。
     *
     * 词典 App 未必声明了接受词的 Activity，因此这里覆盖「显式带词」与「仅打开 App」两种，
     * 由 [tryStart] 逐个试探。
     */
    private fun candidateIntents(context: Context, pkg: String, encodedWord: String): List<Intent> {
        val result = ArrayList<Intent>(DICT_SCHEMES.size + 1)
        for (scheme in DICT_SCHEMES) {
            result.add(Intent(Intent.ACTION_VIEW, Uri.parse(scheme + encodedWord))
                    .setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        // 退回到打开词典主界面，至少让用户可以自己粘贴。
        // getLaunchIntentForPackage 返回的 Intent 已带正确的 component 与 NEW_TASK。
        context.packageManager.getLaunchIntentForPackage(pkg)?.let { result.add(it) }
        return result
    }

    private fun tryStart(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.d(TAG, "no activity for $intent")
            false
        } catch (e: SecurityException) {
            Log.d(TAG, "not allowed to start $intent")
            false
        } catch (e: Throwable) {
            Log.w(TAG, "startActivity failed: $intent", e)
            false
        }
    }
}
