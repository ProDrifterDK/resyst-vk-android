# ✦ Resyst VK Android — Checklist de release (OBLIGATORIA para toda lane)

Esta checklist existe porque una lane publicó una versión y las lanes siguientes
terminaron su trabajo DESPUÉS sin republicar — el usuario quedó con una versión
vieja creyendo tener la última. NUNCA más.

## Regla de oro

**El APK publicado en kv.resyst.cl SIEMPRE debe corresponder al HEAD de master.**
Si commiteas algo a master y no republicas, el release está VIEJO — eso es un bug
de release, no una decisión.

## Al TERMINAR tu lane (obligatorio, en orden)

1. `git status` limpio → commit → push origin + forgejo
2. Bump de versión SIEMPRE que haya cambios visibles:
   `-Pvk.versionCode=N+1 -Pvk.versionName=X.Y.Z` (o editar defaults en build.gradle.kts)
3. `./gradlew :app:assembleRelease -Pvk.versionCode=N ...` → APK firmado
4. `bash ~/Documentos/projects/resyst-vk-site/scripts/publish-apk.sh <apk>`
   (verifica firma → sube a R2 → actualiza manifest → redeploys)
5. Commit `site/release.json` en el repo del sitio + push
6. Instalar en Pixel 6 si está conectado (`adb install -r`)
7. Verificar `curl -s https://kv.resyst.cl/release.json` == tu versión

## Si NO puedes publicar (sin credenciales Cloudflare)

- NO commitees a master sin antes avisar en tu reporte que el release quedó pendiente
- Marca en el reporte: "RELEASE PENDIENTE: mi trabajo está en master pero
  kv.resyst.cl sirve la versión anterior"
- La lane siguiente o Hermes publicará — pero el DEFAULT es publicar tú mismo

## Verificación final (obligatoria antes de cerrar)

```bash
curl -s https://kv.resyst.cl/release.json | grep version
# debe mostrar TU versión. Si muestra otra, tu release NO se publicó.
```
