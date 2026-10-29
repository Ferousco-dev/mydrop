package com.mydrop.android.share

/** Recent phone notifications, waiting for the Mac to collect them. */
object MirrorBuffer {
    class Item(val id: Long, val app: String, val title: String, val text: String, val time: Long)

    private val items = ArrayDeque<Item>()
    private var nextId = 1L

    @Synchronized fun add(app: String, title: String, text: String) {
        items.addLast(Item(nextId++, app, title, text, System.currentTimeMillis()))
        while (items.size > 100) items.removeFirst()
    }

    @Synchronized fun after(id: Long): List<Item> = items.filter { it.id > id }
    @Synchronized fun lastId(): Long = nextId - 1
}
