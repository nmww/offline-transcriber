package com.nightowl.transcriber.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** 设置项持久化：主题 / 排版 / 时段头 / 自动保存 */
private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    companion object {
        private val KEY_THEME = stringPreferencesKey("theme_mode")      // system | light | dark
        private val KEY_STYLE = stringPreferencesKey("transcript_style") // dialog | plain | line
        private val KEY_TS = booleanPreferencesKey("show_timestamps")
        private val KEY_AUTOSAVE = booleanPreferencesKey("auto_save")
    }

    val themeMode: Flow<String> = read(KEY_THEME, "system")
    val transcriptStyle: Flow<String> = read(KEY_STYLE, ExportFormat.DIALOG.code)
    val showTimestamps: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_TS] ?: true }
    val autoSave: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[KEY_AUTOSAVE] ?: true }

    suspend fun setTheme(mode: String) = context.dataStore.edit { it[KEY_THEME] = mode }
    suspend fun setStyle(code: String) = context.dataStore.edit { it[KEY_STYLE] = code }
    suspend fun setTimestamps(on: Boolean) = context.dataStore.edit { it[KEY_TS] = on }
    suspend fun setAutoSave(on: Boolean) = context.dataStore.edit { it[KEY_AUTOSAVE] = on }

    private fun read(key: Preferences.Key<String>, def: String): Flow<String> =
        context.dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { it[key] ?: def }
}
