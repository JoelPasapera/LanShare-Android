# LanShare para Android

Tus aplicaciones web en el teléfono, servidas como en el PC. LanShare levanta
un servidor HTTP propio dentro de la app, da a cada proyecto su puerto en
`127.0.0.1` y lo abre en el navegador: módulos ES, `fetch`, WebAssembly,
workers, IndexedDB y service workers funcionan igual que en el escritorio,
cosa que no ocurre abriendo un `index.html` con `file://`.

Hermana de [LanShare para Windows](https://github.com/JoelPasapera/LanShare):
mismo servidor por sitios, mismo listado de carpetas, mismo QR verificado con
OpenCV y la misma consola remota, reescritos en Kotlin.

## En dos minutos

1. Copia tus proyectos al teléfono, por ejemplo en `Documents/Proyectos/`.
2. En LanShare: **Añadir proyecto → Carpeta del teléfono** y elige
   `Proyectos`. Si dentro hay varios proyectos con `index.html`, se añaden
   todos de una vez, cada uno con su puerto.
3. **Encender el servidor** y **Abrir** en la tarjeta del proyecto: se abre
   en el navegador en `http://127.0.0.1:8080/`.
4. Opcional: **⋮ → Crear acceso directo** para tenerlo en la pantalla de
   inicio como si fuera una app, o **En la red** + **QR** para abrirlo en otro
   equipo de tu Wi-Fi.

## Qué hace

- **Sirve tus proyectos desde el teléfono**, cada uno en su propio puerto y
  con su propio origen: `localStorage`, IndexedDB y los service workers de un
  proyecto no se mezclan con los de otro, y se conservan entre sesiones porque
  el puerto no cambia.
- **Tres formas de añadir un proyecto:**
  - una **carpeta del teléfono** (con permiso permanente del selector del
    sistema). Si eliges una carpeta que contiene varios proyectos, ofrece
    añadirlos todos de una vez;
  - un **archivo .zip**;
  - un **repositorio de GitHub** (también su página de GitHub Pages) o
    cualquier enlace `https` a un `.zip`. Se descarga una copia que luego se
    **actualiza** desde el menú del proyecto.
- **Abrir** lo lleva al navegador predeterminado en `http://127.0.0.1:puerto/`,
  que es un contexto seguro: WebGPU, `crypto.subtle`, service workers y
  PWA instalables funcionan.
- **Accesos directos en la pantalla de inicio**: cada proyecto como si fuera
  una app, con el icono de su propio manifiesto. Al tocarlo se enciende el
  servidor si estaba apagado y se abre el proyecto.
- **Compartir en la red local** proyecto a proyecto, con **código QR** para
  abrirlo en otro móvil, tablet o PC sin teclear la IP. Si el teléfono tiene
  varias redes (Wi-Fi y punto de acceso) propone primero la buena y deja
  elegir. Solo atiende a equipos de tu red (las privadas y aquellas a las que
  el teléfono está conectado directamente): una conexión que llegue por datos
  móviles, por una IPv6 pública o desde el otro lado de una VPN se rechaza y
  queda anotada en el registro.
- **Consola remota**: en el móvil no hay herramientas de desarrollo. Con esta
  opción, los errores de JavaScript, las promesas rechazadas, los recursos que
  no cargan y los `console.log` de tus páginas aparecen en el registro de la
  app con archivo y línea. *(Ajustes, apagada de serie)*
- **Aislamiento de origen** por proyecto (COOP + COEP) para
  `SharedArrayBuffer`: WebAssembly con hilos.
- **CORS** opcional por proyecto, para que otras páginas lo consuman.
- **Listado de carpetas** sin `index.html`: ruta navegable, orden por nombre,
  tamaño o fecha y filtro desde ocho entradas.
- **Aplicaciones de una sola página**: una ruta sin extensión que no existe
  sirve la página de inicio.
- **Rangos** (`Range`, `If-Range`): el vídeo admite búsqueda.
- **Revalidación barata**: `ETag` con nanosegundos de la fecha de modificación
  y `304 Not Modified`; nunca ves una versión vieja de tu propio trabajo.
- **gzip** para HTML, CSS, JS, JSON, SVG y WebAssembly que piden otros equipos,
  con caché en memoria. En el propio teléfono no compensa y no se usa.
  Comprimir tiene presupuesto de memoria propio, proporcional al de la app:
  si no hay sitio, ese archivo se sirve sin comprimir en vez de arriesgarse.
- **Registro** en vivo con filtros (todo, problemas, consola JS) y pestaña de
  **equipos** que han abierto algo.
- Editar un proyecto con el servidor encendido **se aplica en caliente**: solo
  un cambio de puerto o de alcance reabre su puerto; el resto ni corta una
  descarga.

## Instalar

### Opción 1: que lo compile GitHub (sin instalar nada)

1. Sube esta carpeta a un repositorio tuyo (por ejemplo con GitPush Studio).
2. En la pestaña **Actions** se ejecuta el flujo *APK* solo. Al terminar,
   entra en la ejecución y descarga **LanShare-Android-apk** de *Artifacts*.
3. Copia `app-release.apk` al teléfono, ábrelo y permite instalar apps de esa
   fuente cuando Android lo pida.

### Opción 2: Android Studio

Requiere Android Studio **Otter 3 (2025.2.3)** o posterior, que es lo que pide
el Android Gradle Plugin 9.0. Abre la carpeta, espera a que
sincronice y pulsa *Run* con el teléfono conectado (depuración USB activada).
Para un APK suelto: *Build → Generate App Bundles or APKs → Generate APKs*.

### Opción 3: línea de comandos

Con el SDK de Android instalado (`ANDROID_HOME` o `local.properties`) y JDK 17
o superior:

```
./gradlew :core:check :app:assembleRelease
```

El APK queda en `app/build/outputs/apk/release/app-release.apk`.

**Firma.** Todas las compilaciones (Android Studio, GitHub o terminal) usan la
misma clave de depuración, `app/keystore/lanshare-debug.p12`. Así una versión
nueva se instala encima de la anterior sin perder tus proyectos. No es secreta;
si algún día publicas en Google Play, usa una clave propia.

## Uso diario

**Dónde poner los proyectos.** Cópialos al teléfono en una carpeta normal, por
ejemplo `Documents/Proyectos/`, y añade esa carpeta: si tiene varias
subcarpetas con `index.html`, LanShare propone añadirlas todas. Android no deja
conceder la raíz del almacenamiento ni la de Descargas, pero sí cualquier
subcarpeta. Los cambios que hagas en los archivos se ven al recargar la página:
no hay copia intermedia.

**Desde GitHub.** Pega `https://github.com/usuario/repo`, una rama
(`…/tree/rama`) o la dirección de GitHub Pages (`https://usuario.github.io/repo/`).
Solo repositorios públicos. Para traer los cambios nuevos: menú del proyecto →
*Actualizar desde el enlace*.

**Puertos.** El primero se propone desde 8080 (configurable en Ajustes). Android
no permite puertos por debajo de 1024. Si otra app ocupa uno, la tarjeta del
proyecto lo dice y basta con cambiarlo en *Editar*.

**El servidor y la batería.** Mientras sirve, una notificación persistente
mantiene viva la app (servicio en primer plano); desde ella se detiene. Si
compartes en la red, LanShare evita que el procesador se suspenda para que los
demás equipos no esperen (desactivable en Ajustes). Si estaba encendido y
cierras la app, se vuelve a encender al abrirla.

**Otros equipos.** Deben estar en la misma Wi-Fi que el teléfono o conectados a
su punto de acceso. Por la red local, el navegador no considera la página
segura (es HTTP con una IP): no tendrá service workers, WebGPU ni
`SharedArrayBuffer`. En el propio teléfono, por `127.0.0.1`, sí.

## Cómo está hecho

| Carpeta | Responsabilidad |
|---|---|
| `core/` | Kotlin puro, sin Android ni dependencias: servidor HTTP/1.1, resolución de rutas, rangos, validadores, MIME, listado, consola remota, QR, JSON, importación de .zip, detección de proyectos y de iconos |
| `core/src/test/` | Batería de pruebas con su propio ejecutor, sin frameworks |
| `app/…/server` | Servicio en primer plano, notificación y controlador que traduce proyectos en sitios |
| `app/…/storage` | Árbol de archivos sobre SAF y proyectos importados |
| `app/…/importing` | Carpetas, .zip y descargas |
| `app/…/data` | Proyectos (JSON) y ajustes |
| `app/…/ui` | Interfaz con las vistas de la plataforma: sin XML de diseño, sin AppCompat ni Material Components |

**Sin dependencias.** La app solo usa el SDK de Android y la librería estándar
de Kotlin; en *release*, R8 deja solo lo que se usa.

**Servidor.** Escrito desde cero sobre sockets: un hilo de aceptación por
puerto y un único pool acotado para todas las conexiones, con keep-alive,
peticiones encadenadas y `Expect: 100-continue` (el «100» solo se envía si de
verdad se va a leer el cuerpo). El
analizador trabaja sobre bytes con un único búfer por conexión, admite LF sin
CR y rechaza lo que abre la puerta al contrabando de peticiones (espacios antes
de los dos puntos, cabeceras plegadas, `Content-Length` contradictorios,
`Transfer-Encoding`, `Host` repetido). Cada respuesta declara su longitud, así
que la conexión se reutiliza sin ambigüedad; si algo falla a mitad, se cierra.

**Nadie acapara el servidor.** El pool es compartido por todos los puertos, así
que ningún cliente debe poder retener sus hilos: plazo total para recibir la
cabecera (408 a quien la envía byte a byte), plazo para el cuerpo, cupo de
conexiones por equipo, keep-alive que se acorta cuando el pool se llena y un
vigilante que corta las respuestas que el cliente deja de leer (Java no tiene
plazo de escritura en sockets). Tras un error se cierra «con espera» para que
el cliente llegue a leer la respuesta. Un fallo inesperado de un manejador,
incluso sin memoria, cuesta esa conexión y no la app.

**DNS rebinding.** Una web puede hacer que su dominio apunte a tu teléfono;
para el navegador sigue siendo «su» origen y podría leer tus proyectos. Toda
petición cuyo `Host` sea un nombre de Internet recibe `421`: solo se atienden
IP, `localhost` y nombres que no se pueden registrar (`.local`, `.lan`,
`.home.arpa`…).

**Rutas.** La URL se separa por `/` *antes* de decodificar cada tramo: `%2F` no
fabrica separadores y `%2e%2e` se ve como `..` y se rechaza con 403. Lo que
empieza por punto (`.git`, `.env`) no existe hacia fuera, salvo `.well-known`.

**SAF sin perder rendimiento.** En el almacenamiento del teléfono y la tarjeta
SD, el identificador de un documento es `volumen:ruta`, así que se construye
sin consultar al proveedor. Cada archivo cuesta una sola llamada entre
procesos: se abre directamente y `fstat` sobre el descriptor da tamaño, fecha
con nanosegundos y si es carpeta. Los demás proveedores (Descargas, nubes) se
resuelven tramo a tramo con listados cacheados cuatro segundos.

**Inyección sin decodificar ni cargar.** La etiqueta de la consola remota se
inserta recorriendo el principio de la página como el tokenizador de HTML
(comentarios, DOCTYPE, atributos entre comillas) y solo tras `<head>` si es la
primera etiqueta: un `"<head>"` escrito dentro de un script no la atrae. Se
trabaja sobre bytes, así que una página en Latin-1 o Windows-1252 sale intacta
salvo por lo añadido, y se envía en trozos sin cargar el archivo entero. El
script se sirve como archivo propio (`/__consola.js`) y no en línea, para que
funcione con una CSP `script-src 'self'`.

**Dos hilos de trabajo.** Encender, aplicar cambios y apagar van por un hilo;
examinar carpetas, descargar e importar, por otro. Una importación de minutos
nunca retrasa «Detener». Ninguno de los dos muere por una excepción: un fallo
inesperado acaba en el registro en lugar de cerrar la app.

**Importación hostil.** Cada nombre del .zip se convierte en una ruta validada
(nada de `..`, rutas absolutas ni barras invertidas), los límites de bytes se
aplican mientras se escribe y se extrae en una carpeta temporal que solo se
coloca, con un renombrado, si todo salió bien.

**QR.** Implementado desde cero según ISO/IEC 18004 y portado línea a línea de
LanShare para Windows. Las pruebas exigen que genere exactamente las matrices
que leyó OpenCV, incluida la regresión de los bits de formato traspuestos.

**Persistencia a prueba de cortes.** La lista de proyectos se guarda con
`AtomicFile`: si el proceso muere a mitad de escribir, queda la versión
anterior entera.

## Pruebas

```
./gradlew :core:check
```

Compila y ejecuta la batería del núcleo (144 pruebas). Sin JUnit: el ejecutor,
las aserciones y el árbol temporal están en `core/src/test/.../testkit`, como en
LanShare para Windows. Las marcadas `SEGURIDAD:` cubren contención de rutas,
escapado, zip slip, bombas de descompresión, CSRF y DNS rebinding, y análisis
estricto de peticiones; las `REGRESION:`, fallos que ya ocurrieron una vez.
`ServerIntegrationTests` levanta el servidor y habla con él por sockets reales:
keep-alive, peticiones encadenadas, rangos con bytes exactos, 304, gzip para la
red, carga concurrente, cabeceras lentas, puerto ocupado y cambios en caliente.
`RobustnessTests` comprueba que un equipo no pueda acaparar el servidor: cupo
de conexiones por equipo, conexiones ociosas que ceden su hilo cuando el pool
se llena y cabeceras enviadas byte a byte. `HardeningTests` recoge lo que
encontró una revisión de seguridad independiente, cada hallazgo con su prueba:
User-Agent con retroceso catastrófico (ReDoS), memoria acotada al comprimir,
clientes que dejan de leer, `Host` ajeno (421), equipos de fuera de la red
local, archivos que dejan de poder leerse (500 en lugar de un corte mudo) y un
manejador sin memoria que no tumba el servidor.

## Límites conocidos

- **Sin recarga automática al guardar.** El selector de Android no permite
  vigilar una carpeta; recarga la página en el navegador.
- **Por la red local no hay contexto seguro** (ver arriba). Para probar service
  workers o WebGPU, ábrelo en el propio teléfono.
- **Solo repositorios públicos** de GitHub. Para uno privado, descarga el .zip
  e impórtalo como archivo.
- Algunos fabricantes (Xiaomi, Huawei, Oppo…) cierran apps en segundo plano
  aunque tengan servicio en primer plano. Si el servidor se apaga solo, quita
  la optimización de batería para LanShare en los ajustes del sistema.
- Chrome en Android envía un User-Agent congelado (`Android 10; K`): en
  *Equipos* el modelo de otros móviles aparece como oculto.

## Licencia

Apache 2.0. Ver [LICENSE](LICENSE).

Autor: [Joel Pasapera](https://github.com/JoelPasapera)
