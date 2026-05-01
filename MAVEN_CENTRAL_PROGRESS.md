# Maven Central Yükləmə — Progress Qeydləri

## Nə etdik?

`jooq-sql-generate` kitabxanasını **Maven Central**-a uğurla yüklədi.

---

## Addımlar

### 1. `build.gradle.kts` yeniləndi
- `signing` və `io.github.gradle-nexus.publish-plugin` v2.0.0 əlavə edildi
- `nexusPublishing` bloku əlavə edildi (yeni Sonatype endpoint-i ilə)
- GPG imzalama üçün `useInMemoryPgpKeys` istifadə edildi — açar `~/.gradle/secret.pgp` faylından oxunur

### 2. Sonatype hesabı
- **URL:** central.sonatype.com
- Namespace `az.mbm` təsdiqləndi — Cloudflare DNS-ə TXT record əlavə edildi
- **Token:** central.sonatype.com → Profil → "Generate User Token" ilə alındı

### 3. GPG açarı yaradıldı
- `gpg --gen-key` ilə `ed25519` açarı yaradıldı
- **Fingerprint:** `3174A18056536731813E20AB14758D72E48BDA28`
- **Key ID (son 8):** `E48BDA28`
- Açar keyserver-ə göndərildi: `gpg --keyserver keyserver.ubuntu.com --send-keys 3174A18056536731813E20AB14758D72E48BDA28`
- ASCII armor formatda export edildi: `gpg --export-secret-keys --armor E48BDA28 > ~/.gradle/secret.pgp`

### 4. `~/.gradle/gradle.properties` faylı
```properties
sonatypeUsername=SONATYPE_USERNAME_BURAYA
sonatypePassword=SONATYPE_PASSWORD_BURAYA
signingPassword=GPG_PASSPHRASE_BURAYA
```
> ⚠️ Bu faylı heç vaxt Git-ə push etmə!

### 5. Yükləmə komandası
```bash
./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository
```

---

## Nəticə

✅ `BUILD SUCCESSFUL` — paket Maven Central-a yükləndi.

---

## Başqa proyektdən istifadə

```kts
dependencies {
    implementation("az.mbm:jooq-sql-generate:1.0.0")
}
```

Heç bir əlavə repository və ya token lazım deyil.

---

## Qeydlər

- `~/.gradle/secret.pgp` — GPG açarının ASCII armor faylı (lokal saxla, paylaşma)
- `~/.gradle/gradle.properties` — bütün şifrə və tokenləri burada saxla
- GPG açarı 2029-04-19 tarixinə qədər etibarlıdır
- Yeni versiya yükləmək üçün `build.gradle.kts`-də `version = "1.0.1"` kimi artır
