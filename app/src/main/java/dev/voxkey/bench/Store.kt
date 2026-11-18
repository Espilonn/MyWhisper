package dev.voxkey.bench

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

// Persistance locale (SQLite) : dictées, dictionnaire de mots, mots-clés.
// Une seule connexion partagée par le helper ; SQLite sérialise les accès entre threads.

data class Dictation(val id: Long, val ts: Long, val text: String, val words: Int)

data class WordEntry(val word: String, val count: Int, val manual: Boolean)

data class Keyword(val id: Long, val trigger: String, val replacement: String)

object Store {

    @Volatile
    private var helper: SQLiteOpenHelper? = null

    /** Idempotent : le premier appel crée le helper sur le Context d'application. */
    fun init(ctx: Context) {
        if (helper != null) return
        synchronized(this) {
            if (helper == null) helper = Helper(ctx.applicationContext)
        }
    }

    private fun db(): SQLiteDatabase =
        (helper ?: error("Store.init() doit être appelé avant toute lecture")).writableDatabase

    /** Formes stockées indexées par minuscules, pour comparer sans tenir compte de la casse. */
    private fun storedForms(d: SQLiteDatabase): HashMap<String, String> {
        val map = HashMap<String, String>()
        d.rawQuery("SELECT word FROM word", null).use { c ->
            while (c.moveToNext()) {
                val w = c.getString(0)
                map[w.lowercase()] = w
            }
        }
        return map
    }

    // ------------------------------------------------------------- dictées

    fun addDictation(text: String, ts: Long = System.currentTimeMillis()): Long {
        val d = db()
        d.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("ts", ts)
                put("text", text)
                put("words", countWords(text))
            }
            val id = d.insert("dictation", null, values)
            d.setTransactionSuccessful()
            return id
        } finally {
            d.endTransaction()
        }
    }

    fun dictations(): List<Dictation> {
        val out = ArrayList<Dictation>()
        db().rawQuery("SELECT id, ts, text, words FROM dictation ORDER BY ts DESC, id DESC", null).use { c ->
            while (c.moveToNext()) out.add(Dictation(c.getLong(0), c.getLong(1), c.getString(2), c.getInt(3)))
        }
        return out
    }

    fun deleteDictation(id: Long) {
        db().execSQL("DELETE FROM dictation WHERE id = ?", arrayOf<Any>(id))
    }

    // --------------------------------------------------------- dictionnaire

    fun words(query: String = ""): List<WordEntry> {
        val q = query.trim().lowercase()
        val out = ArrayList<WordEntry>()
        // Vocabulaire manuel seulement : les mots appris automatiquement ne sont plus alimentés.
        db().rawQuery("SELECT word, count, manual FROM word WHERE manual = 1 ORDER BY word", null).use { c ->
            while (c.moveToNext()) {
                val word = c.getString(0)
                if (q.isEmpty() || word.lowercase().contains(q)) {
                    out.add(WordEntry(word, c.getInt(1), c.getInt(2) != 0))
                }
            }
        }
        return out
    }

    fun addWord(word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        val d = db()
        val stored = storedForms(d)[w.lowercase()]
        if (stored == null) {
            d.execSQL("INSERT INTO word(word, count, manual) VALUES(?, 1, 1)", arrayOf<Any>(w))
        } else {
            d.execSQL("UPDATE word SET manual = 1 WHERE word = ?", arrayOf<Any>(stored))
        }
    }

    fun deleteWord(word: String) {
        val d = db()
        val stored = storedForms(d)[word.trim().lowercase()] ?: return
        d.execSQL("DELETE FROM word WHERE word = ?", arrayOf<Any>(stored))
    }

    // ------------------------------------------------------------ mots-clés

    fun keywords(): List<Keyword> {
        val out = ArrayList<Keyword>()
        db().rawQuery("SELECT id, trigger, replacement FROM keyword ORDER BY id", null).use { c ->
            while (c.moveToNext()) out.add(Keyword(c.getLong(0), c.getString(1), c.getString(2)))
        }
        return out
    }

    /** Upsert sur trigger : même déclencheur = même ligne, remplacement mis à jour. */
    fun saveKeyword(trigger: String, replacement: String): Long {
        val d = db()
        d.execSQL(
            "INSERT INTO keyword(trigger, replacement) VALUES(?, ?) " +
                "ON CONFLICT(trigger) DO UPDATE SET replacement = excluded.replacement",
            arrayOf<Any>(trigger, replacement),
        )
        d.rawQuery("SELECT id FROM keyword WHERE trigger = ?", arrayOf(trigger)).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else -1L
        }
    }

    fun deleteKeyword(id: Long) {
        db().execSQL("DELETE FROM keyword WHERE id = ?", arrayOf<Any>(id))
    }

    // --------------------------------------------------------------- stats

    /** Une valeur par jour, du plus ancien au plus récent, jours sans dictée à 0. */
    fun wordsPerDay(days: Int): List<Int> {
        if (days <= 0) return emptyList()
        // Débuts de journée locaux : Calendar gère l'heure d'été (une journée ne fait pas 24 h).
        val starts = LongArray(days)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        starts[days - 1] = cal.timeInMillis
        for (i in days - 1 downTo 1) {
            cal.add(Calendar.DAY_OF_MONTH, -1)
            starts[i - 1] = cal.timeInMillis
        }
        val out = IntArray(days)
        db().rawQuery(
            "SELECT ts, words FROM dictation WHERE ts >= ?",
            arrayOf(starts[0].toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val ts = c.getLong(0)
                for (i in days - 1 downTo 0) {
                    if (ts >= starts[i]) {
                        out[i] += c.getInt(1)
                        break
                    }
                }
            }
        }
        return out.toList()
    }

    fun totalWordsSince(sinceTs: Long): Int {
        db().rawQuery("SELECT IFNULL(SUM(words), 0) FROM dictation WHERE ts >= ?", arrayOf(sinceTs.toString())).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun dictationCount(): Int {
        db().rawQuery("SELECT COUNT(*) FROM dictation", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // -------------------------------------------------------------- schéma

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "voxkey.db", null, 2) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE dictation(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                    "text TEXT NOT NULL, words INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE word(word TEXT PRIMARY KEY, count INTEGER NOT NULL, " +
                    "manual INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL(
                "CREATE TABLE keyword(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, trigger TEXT NOT NULL UNIQUE, " +
                    "replacement TEXT NOT NULL)",
            )
            // Les stats filtrent toutes sur ts : sans cet index elles balaient la table entière.
            db.execSQL("CREATE INDEX dictation_ts ON dictation(ts)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.execSQL("CREATE INDEX IF NOT EXISTS dictation_ts ON dictation(ts)")
        }
    }
}
