# MV Decrypter (Android)

App para Android que descifra archivos cifrados de RPG Maker MV/MZ
(`.rpgmvp`/`.png_`, `.rpgmvo`/`.ogg_`, `.rpgmvm`/`.m4a_`) y extrae archivos
Ren'Py (`.rpa`).

## Modos

1. **Con clave** (RPG Maker) — funciona para imágenes y audio. Pide la clave hex de 32 caracteres,
   o detéctala automáticamente desde una imagen con el botón correspondiente.
2. **Restaurar PNG sin clave** (RPG Maker) — deriva la clave comparando contra la cabecera PNG conocida.
3. **Extraer .rpa** (Ren'Py) — selecciona un único archivo `.rpa` en el paso 1; la app lee su
   índice (comprimido con zlib y serializado con pickle de Python — hay un mini-intérprete de
   pickle incluido, `PickleReader.kt`) y extrae todo su contenido a la carpeta de salida,
   recreando las subcarpetas originales del juego.

## Sobre assets de Unity

No están soportados y no está planeado reimplementarlos aquí: el formato de serialización de
Unity (type trees variables por versión del motor, compresión LZ4/LZMA, decenas de tipos de
objeto) es de una complejidad muy distinta a RPG Maker o Ren'Py. Para eso usa herramientas ya
establecidas y mantenidas activamente: **AssetStudio**, **AssetRipper** o **UnityPy**.

## Uso

1. Abre la app.
2. Elige el modo (paso 3).
3. Toca **"Seleccionar archivos"** — uno o varios para RPG Maker, uno solo (el `.rpa`) para Ren'Py.
4. Toca **"Elegir carpeta de salida"**.
5. Si el modo lo requiere, pega o detecta la clave.
6. Toca el botón principal. La barra de progreso muestra el avance archivo por archivo.


## Compilar el APK sin Android Studio (GitHub Actions)

Este proyecto no incluye el `gradle-wrapper.jar` (para no depender de descargas
fuera de dominios permitidos), así que el workflow usa la acción oficial de
Gradle para instalarlo en el runner de GitHub — mismo enfoque que ya usas para
compilar los templates SSE4.2 de Godot.

1. Sube esta carpeta a un repositorio de GitHub.
2. Ve a la pestaña **Actions** → workflow **"Build APK"** → **Run workflow**
   (o simplemente haz push a `main`, se dispara solo).
3. Cuando termine, descarga el artefacto `mv-decrypter-debug-apk` — contiene el `.apk`.
4. Pásalo a tu teléfono e instálalo (activa "Instalar apps de fuentes desconocidas" si te lo pide).

## Compilar localmente (opcional)

Si en algún momento tienes Android Studio a mano:

```
File → Open → selecciona esta carpeta → Run
```

Android Studio genera el `gradle-wrapper.jar` automáticamente al abrir el proyecto.

## Notas

- `minSdk 24` (Android 7.0+), sin dependencias raras — debería andar bien en
  casi cualquier teléfono, incluso modestos.
- Usa Storage Access Framework (SAF), así que funciona igual en Android 10+
  con almacenamiento con scope.
- Sin conexión a internet, sin publicidad, todo el procesamiento es local.
