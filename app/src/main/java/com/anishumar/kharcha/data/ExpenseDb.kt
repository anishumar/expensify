package com.anishumar.kharcha.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ExpenseDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "kharcha.db", null, 1) {

    companion object {
        @Volatile private var instance: ExpenseDb? = null
        fun get(context: Context): ExpenseDb =
            instance ?: synchronized(this) {
                instance ?: ExpenseDb(context).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE expenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                amount REAL NOT NULL,
                payee TEXT NOT NULL,
                category TEXT NOT NULL,
                source TEXT NOT NULL,
                ref TEXT,
                ts INTEGER NOT NULL,
                note TEXT,
                raw TEXT
            )"""
        )
        db.execSQL("CREATE INDEX idx_exp_ts ON expenses(ts)")
        db.execSQL("CREATE TABLE payee_rules (payee_key TEXT PRIMARY KEY, category TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------- expenses ----------

    fun insert(e: Expense): Long = writableDatabase.insert("expenses", null, values(e))

    fun update(e: Expense) {
        writableDatabase.update("expenses", values(e), "id=?", arrayOf(e.id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("expenses", "id=?", arrayOf(id.toString()))
    }

    fun byId(id: Long): Expense? =
        readableDatabase.rawQuery("SELECT * FROM expenses WHERE id=?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) read(it) else null }

    fun between(from: Long, to: Long): List<Expense> =
        readableDatabase.rawQuery(
            "SELECT * FROM expenses WHERE ts>=? AND ts<? ORDER BY ts DESC",
            arrayOf(from.toString(), to.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }

    fun all(): List<Expense> = between(0, Long.MAX_VALUE)

    fun byRef(ref: String): Expense? =
        readableDatabase.rawQuery("SELECT * FROM expenses WHERE ref=? LIMIT 1", arrayOf(ref))
            .use { if (it.moveToFirst()) read(it) else null }

    // ---------- learned categories ----------

    fun ruleFor(payeeKey: String): String? =
        readableDatabase.rawQuery("SELECT category FROM payee_rules WHERE payee_key=?", arrayOf(payeeKey))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    fun saveRule(payeeKey: String, category: String) {
        if (payeeKey.isBlank()) return
        val v = ContentValues().apply {
            put("payee_key", payeeKey)
            put("category", category)
        }
        writableDatabase.insertWithOnConflict("payee_rules", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun values(e: Expense) = ContentValues().apply {
        put("amount", e.amount)
        put("payee", e.payee)
        put("category", e.category)
        put("source", e.source)
        put("ref", e.ref)
        put("ts", e.timestamp)
        put("note", e.note)
        put("raw", e.raw?.take(2000))
    }

    private fun read(c: Cursor) = Expense(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        amount = c.getDouble(c.getColumnIndexOrThrow("amount")),
        payee = c.getString(c.getColumnIndexOrThrow("payee")),
        category = c.getString(c.getColumnIndexOrThrow("category")),
        source = c.getString(c.getColumnIndexOrThrow("source")),
        ref = c.getString(c.getColumnIndexOrThrow("ref")),
        timestamp = c.getLong(c.getColumnIndexOrThrow("ts")),
        note = c.getString(c.getColumnIndexOrThrow("note")),
        raw = c.getString(c.getColumnIndexOrThrow("raw"))
    )
}
