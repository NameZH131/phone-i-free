package com.phoneinteraction.free.service

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.phoneinteraction.free.data.AppLanguage
import java.util.Locale

// Available command words live in asset keyword files (sherpa-onnx format: phoneme/pinyin syllables + space + @display-word).
// The rules page options and the runtime wake check must read this same file, or configured words will not match the engine.
//
// kws-zh-en-3M is bilingual (tokens.txt holds both English ARPAbet and Chinese pinyin syllables), so English commands
// need no new model, only per-language keyword files; inline keywords only append to file keywords and cannot override
// them, so VoicePipeline picks keywords-zh.txt / keywords-en.txt by the current UI language.
object VoiceKeywords {

    private const val MODEL_DIR = "kws-zh-en-3M"

    // First line of a keywords file is the wake word: hitting it only revives vision and never runs as a command.
    // Display words must not contain spaces; English multi-word phrases use underscores (the engine echoes the underscored text).
    const val WAKE_WORD_ZH = "唤醒"
    const val WAKE_WORD_EN = "WAKE_UP"

    // Read the AppCompat app locale (falling back to the system locale) instead of Service/Application resources:
    // AppCompat's app-level override only reliably applies to Activities, so a foreground service holding the
    // application context may still report the system language.
    fun isChinese(): Boolean {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        val lang = if (!appLocales.isEmpty) {
            appLocales[0]?.language
        } else {
            Locale.getDefault().language
        }
        return lang?.startsWith("zh") == true
    }

    // Resolves the language selected in settings directly to zh/en so the service can pick the keyword file without
    // depending on the AppCompat locale override having taken effect (avoids a DataStore-updated/locale-pending race).
    fun isChinese(language: AppLanguage): Boolean = when (language) {
        AppLanguage.ZH_CN -> true
        AppLanguage.EN -> false
        AppLanguage.SYSTEM -> Locale.getDefault().language.startsWith("zh")
    }

    fun wakeWord(): String = if (isChinese()) WAKE_WORD_ZH else WAKE_WORD_EN

    // Wake word follows the language of the engine's loaded keyword list, so it never mismatches the engine mid-switch.
    fun wakeWord(chinese: Boolean): String = if (chinese) WAKE_WORD_ZH else WAKE_WORD_EN

    // KeywordSpotter opens this by assets-relative path; the file must exist or the native side aborts the process.
    fun assetPath(chinese: Boolean): String = "$MODEL_DIR/keywords-${if (chinese) "zh" else "en"}.txt"

    // '@' marks the raw phrase in a sherpa-onnx keywords file; the engine returns exactly the word after '@'.
    fun parse(text: String): List<String> = text.lineSequence()
        .mapNotNull { line ->
            val word = line.substringAfter('@', "").trim()
            word.ifEmpty { null }
        }
        .distinct()
        .toList()

    fun all(context: Context): List<String> = try {
        context.assets.open(assetPath(isChinese())).bufferedReader().use { parse(it.readText()) }
    } catch (e: Exception) {
        // Missing asset indicates a packaging bug; degrade to wake-word-only so the rules page does not crash.
        listOf(wakeWord())
    }

    fun commands(context: Context): List<String> = all(context).filter { it != wakeWord() }
}
