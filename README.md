# MV Decrypter (Android)

App para Android que descifra archivos cifrados de RPG Maker MV/MZ
(`.rpgmvp`/`.png_`, `.rpgmvo`/`.ogg_`, `.rpgmvm`/`.m4a_`), inspirada en la
herramienta web de Petschko (petschko.org/tools/mv_decrypter).

## Cómo funciona el formato

- Los primeros 16 bytes del archivo son una "cabecera falsa" (firma `RPGMV`) y se descartan.
- Los 16 bytes siguientes del contenido real están cifrados con XOR contra una
  clave de 16 bytes (32 caracteres hex). Esa clave suele estar en el
  `System.json` del proyecto, bajo `"encryptionKey"`.
- El resto del archivo no está cifrado.

La app tiene dos modos:

1. **Con clave** — funciona para imágenes y audio. Pide la clave hex de 32 caracteres.
2. **Restaurar PNG sin clave** — solo para imágenes. Como la cabecera real de
   cualquier PNG es siempre la misma, la clave se deriva automáticamente
   comparándola con los bytes cifrados del archivo.

## Uso

1. Abre la app.
2. Toca **"Seleccionar archivos cifrados"** y elige uno o varios archivos.
3. Toca **"Elegir carpeta de salida"** (carpeta donde se guardarán los archivos descifrados).
4. Elige el modo (con clave / restaurar PNG) y, si aplica, pega la clave hex.
5. Toca **"Descifrar"**. Verás el resultado en el log y una vista previa si es una imagen.

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
