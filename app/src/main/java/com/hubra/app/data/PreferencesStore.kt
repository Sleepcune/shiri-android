package com.hubra.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.hubraPreferences by preferencesDataStore("hubra-preferences")
class PreferencesStore(private val context: Context) {
    suspend fun set(key: String, value: String) { context.hubraPreferences.edit { it[stringPreferencesKey(key)] = value } }
    suspend fun get(key: String): String? = context.hubraPreferences.data.first()[stringPreferencesKey(key)]
}
