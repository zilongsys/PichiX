# Firma estable de PichiX (PC + GitHub)

El APK de GitHub Actions y el de Android Studio deben ir firmados con **la misma clave**. Si no, Android no deja actualizar una instalación sobre la otra.

## Archivos (no van a Git)

En la raíz del proyecto, en este PC:

- `keystore/pichix.jks` — almacén PKCS12, alias `pichix`, validez ~27 años
- `keystore.properties` — rutas y contraseñas que lee Gradle

Huella SHA-256 de esta clave (para comprobar que CI y PC coinciden):

`BF:A3:6F:8E:33:F6:15:C7:CB:F4:57:CC:ED:4D:75:92:42:72:94:FE:B7:B6:D2:5B:57:10:F8:C2:4B:6A:14:FD`

Haz una copia de esos dos archivos fuera del disco (USB o gestor de contraseñas). Si se pierden, hay que generar otra clave y **desinstalar** PichiX en el móvil para volver a instalar.

## Android Studio / Gradle local

Con `keystore.properties` presente, `assembleDebug` y `assembleRelease` firman con esa clave. No hace falta configurar nada más en el IDE.

## GitHub Actions

Secretos del repo (`Settings → Secrets and variables → Actions`):

| Secreto | Valor |
|---|---|
| `PICHIX_KEYSTORE_BASE64` | el `.jks` en Base64 |
| `PICHIX_KEYSTORE_PASSWORD` | `storePassword` de `keystore.properties` |
| `PICHIX_KEY_ALIAS` | `pichix` |
| `PICHIX_KEY_PASSWORD` | `keyPassword` (igual que store) |

Desde la raíz del repo, con `gh` autenticado:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\upload-signing-secrets.ps1
```

El workflow escribe el `.jks` en un temp del runner y Gradle lo usa igual que en el PC.

## Comprobar

Tras un build en el PC y otro en GitHub:

```bash
keytool -printcert -jarfile PichiX-vX.Y.Z.apk
```

Las huellas SHA-256 deben coincidir.
