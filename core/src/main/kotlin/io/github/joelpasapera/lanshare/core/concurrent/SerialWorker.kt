package io.github.joelpasapera.lanshare.core.concurrent

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Ejecutor de un solo hilo, en orden, que no muere por una excepción.
 *
 * En Android, una excepción sin capturar en cualquier hilo cierra la app
 * entera (y con ella el servidor). Un fallo inesperado en una tarea de fondo
 * (un archivo raro al importar, un proveedor de SAF que lanza lo que no
 * documenta) se entrega a [onError] y el hilo sigue con la siguiente.
 */
class SerialWorker(name: String, private val onError: (Throwable) -> Unit) : ThreadPoolExecutor(
    1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
    ThreadFactory { runnable -> Thread(runnable, name).apply { isDaemon = true } },
) {
    override fun execute(command: Runnable) {
        super.execute {
            try {
                command.run()
            } catch (t: Throwable) {
                try {
                    onError(t)
                } catch (ignored: Throwable) {
                    // Ni el aviso puede tumbar el hilo.
                }
            }
        }
    }
}
