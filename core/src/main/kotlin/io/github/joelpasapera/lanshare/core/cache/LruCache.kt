package io.github.joelpasapera.lanshare.core.cache

/**
 * Caché LRU con presupuesto en bytes, escrita desde cero: tabla hash para el
 * acceso y lista doblemente enlazada intrusiva para el orden de uso. Todas las
 * operaciones son O(1) y no se crea memoria al reordenar.
 *
 * Al insertar, se expulsa desde el extremo menos usado hasta caber en
 * [maxWeight]. Un valor que por sí solo supera el presupuesto no se guarda.
 */
class LruCache<K : Any, V : Any>(
    private val maxWeight: Long,
    private val weigher: (V) -> Long,
) {
    private class Node<K, V>(val key: K, var value: V, var weight: Long) {
        var prev: Node<K, V>? = null
        var next: Node<K, V>? = null
    }

    private val map = HashMap<K, Node<K, V>>()
    private var newest: Node<K, V>? = null
    private var oldest: Node<K, V>? = null

    var weight = 0L
        private set
    var hits = 0L
        private set
    var misses = 0L
        private set

    @get:Synchronized
    val size: Int get() = map.size

    @Synchronized
    fun get(key: K): V? {
        val node = map[key]
        if (node == null) {
            misses++
            return null
        }
        hits++
        moveToFront(node)
        return node.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        val w = weigher(value)
        val existing = map.remove(key)
        if (existing != null) {
            unlink(existing)
            weight -= existing.weight
        }
        if (w > maxWeight) return

        val node = Node(key, value, w)
        map[key] = node
        linkFront(node)
        weight += w
        while (weight > maxWeight) {
            val victim = oldest ?: break
            unlink(victim)
            map.remove(victim.key)
            weight -= victim.weight
        }
    }

    @Synchronized
    fun remove(key: K) {
        val node = map.remove(key) ?: return
        unlink(node)
        weight -= node.weight
    }

    @Synchronized
    fun removeIf(predicate: (K) -> Boolean) {
        var node = oldest
        while (node != null) {
            val prev = node.prev
            if (predicate(node.key)) {
                unlink(node)
                map.remove(node.key)
                weight -= node.weight
            }
            node = prev
        }
    }

    @Synchronized
    fun clear() {
        map.clear()
        newest = null
        oldest = null
        weight = 0
    }

    /** Claves del más reciente al más antiguo (para pruebas y depuración). */
    @Synchronized
    fun keysByRecency(): List<K> {
        val keys = ArrayList<K>(map.size)
        var node = newest
        while (node != null) {
            keys.add(node.key)
            node = node.next
        }
        return keys
    }

    private fun moveToFront(node: Node<K, V>) {
        if (node === newest) return
        unlink(node)
        linkFront(node)
    }

    private fun linkFront(node: Node<K, V>) {
        node.prev = null
        node.next = newest
        newest?.prev = node
        newest = node
        if (oldest == null) oldest = node
    }

    private fun unlink(node: Node<K, V>) {
        val prev = node.prev
        val next = node.next
        if (prev != null) prev.next = next else newest = next
        if (next != null) next.prev = prev else oldest = prev
        node.prev = null
        node.next = null
    }
}
