package wtf.lennard.thorbro.ui

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import wtf.lennard.thorbro.Prefs

/** favorite games (by path) – they are what the home screen shows */
object Favorites {
    val paths = mutableStateListOf<String>()
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val a = runCatching { JSONArray(Prefs.of(ctx).getString("favorites", "[]")) }.getOrDefault(JSONArray())
        paths.clear(); for (i in 0 until a.length()) paths += a.getString(i)
    }

    fun toggle(ctx: Context, path: String) {
        load(ctx)
        if (path in paths) paths.remove(path) else paths.add(0, path)
        Prefs.of(ctx).edit().putString("favorites", JSONArray(paths.toList()).toString()).apply()
    }

    fun isFav(ctx: Context, path: String): Boolean { load(ctx); return path in paths }
}

/** which console is open inside "Alle Spiele" (null = the console grid); B goes back to the grid */
object GamesNav { val system = mutableStateOf<String?>(null) }
