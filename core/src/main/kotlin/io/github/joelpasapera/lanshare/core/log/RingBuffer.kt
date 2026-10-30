package io.github.joelpasapera.lanshare.core.log

/**
 * Cola circular de capacidad fija: al llenarse, cada inserción pisa la entrada
 * más antigua. Inserción O(1) sin asignar memoria, que es lo que hace falta
 * para un registro que recibe una línea por petición.
 *
 * No es segura entre hilos por sí sola; quien la usa sincroniza.
 */
class RingBuffer<T>(val capacity: Int) {
    private val items = arrayOfNulls<Any>(capacity)
    private var head = 0
    var size = 0
        private set

    init {
        require(capacity > 0) { "Capacidad no válida" }
    }

    fun add(item: T) {
        val tail = (head + size) % capacity
        items[tail] = item
        if (size < capacity) size++ else head = (head + 1) % capacity
    }

    /** Elemento [index] contando desde el más antiguo. */
    @Suppress("UNCHECKED_CAST")
    operator fun get(index: Int): T {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("$index fuera de 0..${size - 1}")
        return items[(head + index) % capacity] as T
    }

    fun toList(): List<T> {
        val list = ArrayList<T>(size)
        for (i in 0 until size) list.add(get(i))
        return list
    }

    fun clear() {
        java.util.Arrays.fill(items, null)
        head = 0
        size = 0
    }
}
