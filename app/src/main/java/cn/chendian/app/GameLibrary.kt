package cn.chendian.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** A portable catalog, deliberately separate from personal account backups. */
class GameLibrary(private val context: Context) {
    private val storage = AtomicFile(File(context.filesDir, "game-library.json"))
    fun read(): List<JSONObject> = if (!storage.baseFile.exists()) emptyList() else storage.openRead().use { decode(it) }
    private fun decode(input: InputStream): List<JSONObject> {
        val root = JSONObject(String(Backup.bytes(input, 32L * 1024 * 1024), Charsets.UTF_8))
        require(root.getString("format") == "chendian-game-library" && root.getInt("version") == 1) { "请选择受支持的游戏库文件" }
        val rows = root.getJSONArray("games")
        require(rows.length() <= 2000) { "游戏库最多支持 2000 个游戏" }
        val names = mutableSetOf<String>()
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val name = row.getString("name").trim()
            require(name.isNotEmpty() && name.length <= 200 && names.add(name)) { "游戏名称为空、过长或重复" }
            val icon = row.getString("icon")
            require(icon.length <= 2 * 1024 * 1024) { "游戏图标过大" }
            if (icon.isNotEmpty()) {
                val bytes = Base64.decode(icon, Base64.DEFAULT)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth in 1..1024 && bounds.outHeight in 1..1024) { "游戏图标无效或尺寸过大" }
            }
            JSONObject().put("name", name).put("icon", icon)
        }
    }
    private fun encoded(rows: List<JSONObject>) = JSONObject().put("format", "chendian-game-library")
        .put("version", 1).put("games", JSONArray(rows)).toString().toByteArray(Charsets.UTF_8)
    private fun save(rows: List<JSONObject>) {
        val bytes = encoded(rows)
        bytes.inputStream().use { decode(it) }
        val out = storage.startWrite()
        try { out.write(bytes); storage.finishWrite(out) } catch (e: Exception) { storage.failWrite(out); throw e }
    }
    fun merge(input: InputStream): Int = mergeRows(decode(input))
    private fun mergeRows(incoming: List<JSONObject>): Int {
        val rows = read().associateByTo(linkedMapOf()) { it.getString("name") }
        var changed = 0
        for (row in incoming) {
            val name = row.getString("name")
            val old = rows[name]
            if (old == null || old.getString("icon").isEmpty() && row.getString("icon").isNotEmpty()) {
                rows[name] = row; changed++
            }
        }
        save(rows.values.sortedBy { it.getString("name") })
        return changed
    }
    fun export(output: OutputStream) { output.write(encoded(read())) }
    fun remove(name: String) { save(read().filter { it.getString("name") != name }) }
    fun collect(store: Store): Int = mergeRows(store.query("SELECT g.name,a.file FROM games g LEFT JOIN avatars a ON a.gameId=g.id").map {
        val file = it.optString("file")
        JSONObject().put("name", it.getString("name")).put("icon", if (file.isEmpty()) "" else icon(File(Backup.directory(context), file)))
    })
    fun add(name: String, file: File?): Int = mergeRows(listOf(JSONObject().put("name", name.trim()).put("icon", file?.let { icon(it) } ?: "")))
    private fun icon(file: File): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取游戏图标" }
        var sample = 1
        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
        bounds.inJustDecodeBounds = false; bounds.inSampleSize = sample
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, bounds))
        try { return ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) } }
        finally { bitmap.recycle() }
    }
    fun select(row: JSONObject, store: Store) {
        val db = store.writableDatabase
        var copied: File? = null
        db.beginTransaction()
        try {
            val id = store.ensureGame(row.getString("name"))
            if (row.getString("icon").isNotEmpty() && store.one("SELECT id FROM avatars WHERE gameId=?", id) == null) {
                val dest = File(Backup.directory(context), Store.uuid() + ".png"); copied = dest
                dest.writeBytes(Base64.decode(row.getString("icon"), Base64.DEFAULT))
                db.execSQL("INSERT INTO avatars(id,gameId,file) VALUES(?,?,?)", arrayOf(Store.uuid(), id, dest.name))
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) { copied?.delete(); throw e } finally { db.endTransaction() }
    }
}
