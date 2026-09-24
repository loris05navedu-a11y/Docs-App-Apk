package com.docssuite.tasks

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID

/**
 * Listes et tâches, dans un fichier JSON de l'app, écrit à chaque
 * changement (fichier temporaire puis renommage : jamais à moitié écrit).
 */
class TaskStore(context: Context) {

    private val file = File(context.filesDir, "tasks.json")

    private var lists = ArrayList<TaskList>()
    private var tasks = ArrayList<Task>()

    init {
        load()
        if (lists.isEmpty()) {
            lists.add(TaskList(DEFAULT_LIST, "Mes tâches"))
            persist()
        }
    }

    private fun load() {
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        root.optJSONArray("lists")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { lists.add(TaskList(it.getString("id"), it.getString("name"))) }
        }
        root.optJSONArray("tasks")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                tasks.add(
                    Task(
                        id = o.getString("id"),
                        listId = o.getString("list"),
                        title = o.getString("title"),
                        notes = o.optString("notes"),
                        done = o.optBoolean("done"),
                        remindAt = if (o.has("remindAt")) o.getLong("remindAt") else null,
                        repeat = runCatching { Repeat.valueOf(o.optString("repeat")) }.getOrDefault(Repeat.NONE),
                        createdAt = o.optLong("createdAt"),
                        repeatDay = if (o.has("repeatDay")) o.getInt("repeatDay") else null
                    )
                )
            }
        }
    }

    private fun persist() {
        val root = JSONObject()
            .put("lists", JSONArray().apply { lists.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
            .put("tasks", JSONArray().apply {
                tasks.forEach { t ->
                    put(JSONObject().apply {
                        put("id", t.id); put("list", t.listId); put("title", t.title); put("notes", t.notes)
                        put("done", t.done); put("repeat", t.repeat.name); put("createdAt", t.createdAt)
                        t.remindAt?.let { put("remindAt", it) }
                        t.repeatDay?.let { put("repeatDay", it) }
                    })
                }
            })
        val temp = File(file.parentFile, "tasks.json.tmp")
        temp.writeText(root.toString())
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    // ------------------------------------------------------------ listes

    fun lists(): List<TaskList> = lists.toList()

    fun createList(name: String): TaskList =
        TaskList(UUID.randomUUID().toString(), name.trim().ifBlank { "Nouvelle liste" }).also { lists.add(it); persist() }

    fun renameList(id: String, name: String) {
        val i = lists.indexOfFirst { it.id == id }
        if (i >= 0 && name.isNotBlank()) {
            lists[i] = lists[i].copy(name = name.trim())
            persist()
        }
    }

    /** Supprime la liste et ses tâches ; renvoie les tâches supprimées (pour annuler leurs rappels). */
    fun deleteList(id: String): List<Task> {
        if (lists.size <= 1) return emptyList() // il reste toujours une liste
        val removed = tasks.filter { it.listId == id }
        tasks.removeAll { it.listId == id }
        lists.removeAll { it.id == id }
        persist()
        return removed
    }

    // ------------------------------------------------------------ tâches

    fun all(): List<Task> = tasks.toList()

    fun get(id: String): Task? = tasks.firstOrNull { it.id == id }

    fun inList(listId: String): List<Task> = tasks.filter { it.listId == listId }

    fun add(listId: String, title: String, remindAt: Long? = null, repeat: Repeat = Repeat.NONE, now: Long = System.currentTimeMillis()): Task =
        Task(
            UUID.randomUUID().toString(), listId, title.trim(), remindAt = remindAt, repeat = repeat, createdAt = now,
            repeatDay = remindAt?.takeIf { repeat == Repeat.MONTHLY }?.let { Calendar.getInstance().apply { timeInMillis = it }.get(Calendar.DAY_OF_MONTH) }
        ).also { tasks.add(it); persist() }

    fun update(task: Task) {
        val i = tasks.indexOfFirst { it.id == task.id }
        if (i >= 0) {
            tasks[i] = task
            persist()
        }
    }

    fun delete(id: String) {
        tasks.removeAll { it.id == id }
        persist()
    }

    /**
     * Cocher une tâche qui se répète ne la termine pas : elle passe à sa
     * prochaine occurrence, décochée (comme un rappel de loyer qui revient
     * chaque mois). Renvoie la tâche mise à jour.
     */
    fun toggle(id: String, now: Long = System.currentTimeMillis()): Task? {
        val task = get(id) ?: return null
        val updated = if (!task.done && task.repeat != Repeat.NONE && task.remindAt != null) {
            val originalDay = if (task.repeat == Repeat.MONTHLY) {
                task.repeatDay ?: Calendar.getInstance().apply { timeInMillis = task.remindAt }.get(Calendar.DAY_OF_MONTH)
            } else null
            var next = nextOccurrence(task.remindAt, task.repeat, originalDay = originalDay)
            // Rattrape les occurrences manquées : la suivante est dans le futur.
            while (next <= now) next = nextOccurrence(next, task.repeat, originalDay = originalDay)
            task.copy(remindAt = next)
        } else {
            task.copy(done = !task.done)
        }
        update(updated)
        return updated
    }

    fun clearCompleted(listId: String): List<Task> {
        val removed = tasks.filter { it.listId == listId && it.done }
        tasks.removeAll { it.listId == listId && it.done }
        persist()
        return removed
    }

    companion object {
        const val DEFAULT_LIST = "default"
    }
}
