package com.docssuite.pdftools

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Le « gestionnaire de sécurité standard » des PDF (ISO 32000-2, § 7.6.4),
 * dans ses quatre générations :
 * - R2 : RC4 40 bits (PDF 1.1) ;
 * - R3 : RC4 jusqu'à 128 bits (PDF 1.4) ;
 * - R4 : filtres de chiffrement, RC4 ou AES-128 (PDF 1.5) ;
 * - R5 et R6 : AES-256 (extension Adobe, puis PDF 2.0).
 *
 * On sait tout lire ; on ne produit que du R6, le seul qui ne soit pas
 * cassé aujourd'hui.
 */
internal class PdfDecryptor private constructor(
    private val fileKey: ByteArray,
    private val strings: Method,
    private val streams: Method,
    val encryptMetadata: Boolean,
    /** Vrai si le mot de passe donné est celui du propriétaire. */
    val owner: Boolean,
    val permissions: Int,
    val description: String
) {
    enum class Method { IDENTITY, RC4, AES128, AES256 }

    fun string(data: ByteArray, num: Int, gen: Int): ByteArray = apply(strings, data, num, gen)

    fun stream(data: ByteArray, num: Int, gen: Int): ByteArray = apply(streams, data, num, gen)

    private fun apply(method: Method, data: ByteArray, num: Int, gen: Int): ByteArray = when (method) {
        Method.IDENTITY -> data
        Method.RC4 -> Crypto.rc4(objectKey(num, gen, aes = false), data)
        Method.AES128 -> Crypto.aesDecrypt(objectKey(num, gen, aes = true), data)
        Method.AES256 -> Crypto.aesDecrypt(fileKey, data)
    }

    /** Clé propre à l'objet (§ 7.6.3.2, algorithme 1) — sauf en AES-256, où la clé est commune. */
    private fun objectKey(num: Int, gen: Int, aes: Boolean): ByteArray {
        val md5 = MessageDigest.getInstance("MD5")
        md5.update(fileKey)
        md5.update(byteArrayOf(num.toByte(), (num shr 8).toByte(), (num shr 16).toByte(), gen.toByte(), (gen shr 8).toByte()))
        if (aes) md5.update(byteArrayOf(0x73, 0x41, 0x6C, 0x54)) // « sAlT »
        return md5.digest().copyOf(minOf(fileKey.size + 5, 16))
    }

    /** Déchiffre chaînes et flux d'un objet indirect, en profondeur. */
    fun decrypt(obj: PdfObject, num: Int, gen: Int): PdfObject = when (obj) {
        is PdfString -> PdfString.hex(string(obj.bytes(), num, gen))
        is PdfArray -> PdfArray(obj.items.mapTo(ArrayList()) { decrypt(it, num, gen) })
        is PdfDict -> decryptDict(obj, num, gen)
        is PdfStream -> {
            val dict = decryptDict(obj.dict, num, gen)
            // Les métadonnées XMP peuvent rester en clair, pour les moteurs de recherche.
            val clearMetadata = !encryptMetadata && obj.dict.nameOf("Type") == "Metadata"
            PdfStream(dict, if (clearMetadata) obj.data else stream(obj.data, num, gen))
        }
        else -> obj
    }

    private fun decryptDict(dict: PdfDict, num: Int, gen: Int): PdfDict {
        // Le contenu d'une signature numérique n'est jamais chiffré (§ 7.6.2).
        val signature = dict.nameOf("Type") == "Sig"
        return PdfDict(LinkedHashMap<String, PdfObject>().apply {
            dict.entries.forEach { (key, value) ->
                put(key, if (signature && key == "Contents") value else decrypt(value, num, gen))
            }
        })
    }

    companion object {

        /**
         * Ouvre avec [password] ; `null` essaie le mot de passe vide, qui ouvre
         * les PDF seulement « verrouillés » contre l'impression ou la copie.
         * Renvoie `null` si le mot de passe ne convient pas.
         */
        fun open(encrypt: PdfDict, id: ByteArray, password: String?, resolve: (PdfObject?) -> PdfObject?): PdfDecryptor? {
            fun int(key: String, default: Int) = (resolve(encrypt[key]) as? PdfNumber)?.intValue ?: default
            fun bytes(key: String) = (resolve(encrypt[key]) as? PdfString)?.bytes() ?: ByteArray(0)

            val filter = (resolve(encrypt["Filter"]) as? PdfName)?.raw
            if (filter != "Standard") throw PdfFormatException("Protection « $filter » non prise en charge : seule la protection par mot de passe l'est")
            val v = int("V", 0)
            val r = int("R", 2)
            val p = int("P", -1)
            val o = bytes("O")
            val u = bytes("U")
            val encryptMetadata = (resolve(encrypt["EncryptMetadata"]) as? PdfBool)?.value ?: true
            val pw = password ?: ""

            val (strings, streams) = methods(encrypt, v, resolve)
            val bits = when {
                r >= 5 -> 256
                v >= 4 -> 128
                v == 1 || r == 2 -> 40
                else -> int("Length", 40).coerceIn(40, 128)
            }
            val description = when {
                r >= 5 -> "AES 256 bits"
                strings == Method.AES128 || streams == Method.AES128 -> "AES 128 bits"
                else -> "RC4 $bits bits"
            }

            if (r >= 5) {
                val secret = pw.toByteArray(Charsets.UTF_8).let { it.copyOf(minOf(it.size, 127)) }
                fun hash(salt: ByteArray, udata: ByteArray) = if (r == 5) Crypto.sha256(secret + salt + udata) else Crypto.hash2B(secret, salt, udata)
                if (o.size < 48 || u.size < 48) throw PdfFormatException("Protection AES-256 incomplète")
                val u48 = u.copyOf(48)
                val key = when {
                    hash(o.copyOfRange(32, 40), u48).contentEquals(o.copyOf(32)) ->
                        Crypto.aesNoPadding(hash(o.copyOfRange(40, 48), u48), bytes("OE"), encrypt = false) to true
                    hash(u.copyOfRange(32, 40), ByteArray(0)).contentEquals(u.copyOf(32)) ->
                        Crypto.aesNoPadding(hash(u.copyOfRange(40, 48), ByteArray(0)), bytes("UE"), encrypt = false) to false
                    else -> return null
                }
                return PdfDecryptor(key.first, strings, streams, encryptMetadata, key.second, p, description)
            }

            // R2 à R4 : clé dérivée par MD5 du mot de passe complété (algorithme 2).
            val length = if (r == 2) 5 else bits / 8
            fun fileKey(padded: ByteArray): ByteArray {
                val md5 = MessageDigest.getInstance("MD5")
                md5.update(padded)
                md5.update(o.copyOf(32))
                md5.update(byteArrayOf(p.toByte(), (p shr 8).toByte(), (p shr 16).toByte(), (p shr 24).toByte()))
                md5.update(id)
                if (r >= 4 && !encryptMetadata) md5.update(byteArrayOf(-1, -1, -1, -1))
                var hash = md5.digest()
                if (r >= 3) repeat(50) { hash = MessageDigest.getInstance("MD5").digest(hash.copyOf(length)) }
                return hash.copyOf(length)
            }
            fun userMatches(key: ByteArray): Boolean = if (r == 2) {
                Crypto.rc4(key, PADDING).contentEquals(u.copyOf(32))
            } else {
                var x = Crypto.rc4(key, MessageDigest.getInstance("MD5").digest(PADDING + id))
                for (i in 1..19) x = Crypto.rc4(key.xor(i), x)
                x.contentEquals(u.copyOf(16))
            }

            val latin = pad(pw.toByteArray(Charsets.ISO_8859_1))
            // Le mot de passe du propriétaire déchiffre celui de l'utilisateur (algorithme 7).
            var ownerKey = MessageDigest.getInstance("MD5").digest(latin)
            if (r >= 3) repeat(50) { ownerKey = MessageDigest.getInstance("MD5").digest(ownerKey) }
            ownerKey = ownerKey.copyOf(length)
            var userFromOwner = o.copyOf(32)
            if (r == 2) {
                userFromOwner = Crypto.rc4(ownerKey, userFromOwner)
            } else {
                for (i in 19 downTo 0) userFromOwner = Crypto.rc4(ownerKey.xor(i), userFromOwner)
            }
            fileKey(userFromOwner).let { if (userMatches(it)) return PdfDecryptor(it, strings, streams, encryptMetadata, true, p, description) }
            fileKey(latin).let { if (userMatches(it)) return PdfDecryptor(it, strings, streams, encryptMetadata, false, p, description) }
            return null
        }

        private fun methods(encrypt: PdfDict, v: Int, resolve: (PdfObject?) -> PdfObject?): Pair<Method, Method> {
            if (v < 4) return Method.RC4 to Method.RC4
            val filters = resolve(encrypt["CF"]) as? PdfDict
            fun method(key: String): Method {
                val name = (resolve(encrypt[key]) as? PdfName)?.raw ?: "Identity"
                if (name == "Identity") return Method.IDENTITY
                val filter = resolve(filters?.get(name)) as? PdfDict ?: return Method.IDENTITY
                return when ((resolve(filter["CFM"]) as? PdfName)?.raw) {
                    "V2" -> Method.RC4
                    "AESV2" -> Method.AES128
                    "AESV3" -> Method.AES256
                    else -> Method.IDENTITY
                }
            }
            return method("StrF") to method("StmF")
        }

        private val PADDING = byteArrayOf(
            0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08,
            0x2E, 0x2E, 0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(), 0x2F, 0x0C, 0xA9.toByte(), 0xFE.toByte(), 0x64, 0x53, 0x69, 0x7A
        )

        private fun pad(password: ByteArray): ByteArray = (password.copyOf(minOf(password.size, 32)) + PADDING).copyOf(32)

        private fun ByteArray.xor(value: Int) = ByteArray(size) { (this[it].toInt() xor value).toByte() }
    }
}

/**
 * Protection AES-256 (R6) d'un document à écrire. [restrict] interdit la
 * modification et la copie du texte, en laissant lecture et impression.
 */
class PdfProtection(val userPassword: String, val restrict: Boolean = false) {

    private val random = SecureRandom()
    private val fileKey = ByteArray(32).also(random::nextBytes)

    /** Les chaînes et flux sont chiffrés avec la même clé, précédés d'un vecteur aléatoire. */
    internal fun encrypt(data: ByteArray): ByteArray {
        val iv = ByteArray(16).also(random::nextBytes)
        return iv + Crypto.aes(fileKey, iv, data, encrypt = true, padding = true)
    }

    internal fun encrypt(obj: PdfObject): PdfObject = when (obj) {
        is PdfString -> PdfString.hex(encrypt(obj.bytes()))
        is PdfArray -> PdfArray(obj.items.mapTo(ArrayList()) { encrypt(it) })
        is PdfDict -> PdfDict(LinkedHashMap<String, PdfObject>().apply {
            obj.entries.forEach { (key, value) -> put(key, encrypt(value)) }
        })
        is PdfStream -> PdfStream(encrypt(obj.dict) as PdfDict, encrypt(obj.data))
        else -> obj
    }

    /** Le dictionnaire `/Encrypt`, placé directement dans le dictionnaire de fin. */
    internal fun dictionary(): PdfDict {
        val user = userPassword.toByteArray(Charsets.UTF_8).let { it.copyOf(minOf(it.size, 127)) }
        // Le mot de passe du propriétaire n'est jamais montré : aléatoire, il
        // garantit que les restrictions ne se lèvent pas avec celui d'ouverture.
        val owner = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }.toByteArray()
        fun salt() = ByteArray(8).also(random::nextBytes)

        val uValidation = salt()
        val uKey = salt()
        val u = Crypto.hash2B(user, uValidation, ByteArray(0)) + uValidation + uKey
        val ue = Crypto.aesNoPadding(Crypto.hash2B(user, uKey, ByteArray(0)), fileKey, encrypt = true)
        val oValidation = salt()
        val oKey = salt()
        val o = Crypto.hash2B(owner, oValidation, u) + oValidation + oKey
        val oe = Crypto.aesNoPadding(Crypto.hash2B(owner, oKey, u), fileKey, encrypt = true)

        val p = permissions()
        val perms = byteArrayOf(p.toByte(), (p shr 8).toByte(), (p shr 16).toByte(), (p shr 24).toByte(), -1, -1, -1, -1, 'T'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(), 'b'.code.toByte()) +
            ByteArray(4).also(random::nextBytes)
        val permsEncrypted = Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"))
            doFinal(perms)
        }

        return PdfDict().apply {
            set("Filter", PdfName("Standard"))
            set("V", PdfNumber.of(5))
            set("R", PdfNumber.of(6))
            set("Length", PdfNumber.of(256))
            set("CF", PdfDict().apply {
                set("StdCF", PdfDict().apply {
                    set("AuthEvent", PdfName("DocOpen"))
                    set("CFM", PdfName("AESV3"))
                    set("Length", PdfNumber.of(32))
                })
            })
            set("StmF", PdfName("StdCF"))
            set("StrF", PdfName("StdCF"))
            set("O", PdfString.hex(o))
            set("U", PdfString.hex(u))
            set("OE", PdfString.hex(oe))
            set("UE", PdfString.hex(ue))
            set("P", PdfNumber.of(p))
            set("Perms", PdfString.hex(permsEncrypted))
            set("EncryptMetadata", PdfBool(true))
        }
    }

    /**
     * Bits 1-2 à zéro, 7-8 et 13-32 à un (§ 7.6.4.2). Restreint : impression
     * (même haute qualité) et accessibilité seulement.
     */
    private fun permissions(): Int =
        if (!restrict) -4 else (0xFFFFF0C0L or 0x4L or 0x200L or 0x800L).toInt()
}

internal object Crypto {

    fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        val out = ByteArray(data.size)
        var a = 0
        var b = 0
        for (k in data.indices) {
            a = (a + 1) and 0xFF
            b = (b + s[a]) and 0xFF
            val t = s[a]; s[a] = s[b]; s[b] = t
            out[k] = (data[k].toInt() xor s[(s[a] + s[b]) and 0xFF]).toByte()
        }
        return out
    }

    fun aes(key: ByteArray, iv: ByteArray, data: ByteArray, encrypt: Boolean, padding: Boolean): ByteArray =
        Cipher.getInstance(if (padding) "AES/CBC/PKCS5Padding" else "AES/CBC/NoPadding").run {
            init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }

    /** Pour `/OE` et `/UE` : vecteur nul, pas de bourrage. */
    fun aesNoPadding(key: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray =
        aes(key, ByteArray(16), data.copyOf(32), encrypt, padding = false)

    /**
     * Vecteur en tête, puis les blocs. Un bourrage abîmé (fréquent chez
     * certains logiciels) n'empêche pas la lecture : on garde les blocs bruts.
     */
    fun aesDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        if (data.size < 16) return ByteArray(0)
        val iv = data.copyOf(16)
        val body = data.copyOfRange(16, 16 + (data.size - 16) / 16 * 16)
        if (body.isEmpty()) return ByteArray(0)
        return runCatching { aes(key, iv, body, encrypt = false, padding = true) }
            .getOrElse { aes(key, iv, body, encrypt = false, padding = false) }
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** Algorithme 2.B de l'ISO 32000-2 : le hachage itératif de l'AES-256 R6. */
    fun hash2B(password: ByteArray, salt: ByteArray, udata: ByteArray): ByteArray {
        var k = sha256(password + salt + udata)
        var e = ByteArray(0)
        var round = 0
        while (round < 64 || (e.last().toInt() and 0xFF) > round - 32) {
            val block = password + k.copyOf(minOf(k.size, 64)) + udata
            val k1 = ByteArray(block.size * 64)
            for (i in 0 until 64) System.arraycopy(block, 0, k1, i * block.size, block.size)
            e = aes(k.copyOf(16), k.copyOfRange(16, 32), k1, encrypt = true, padding = false)
            val remainder = e.copyOf(16).sumOf { it.toInt() and 0xFF } % 3
            k = MessageDigest.getInstance(
                when (remainder) {
                    0 -> "SHA-256"
                    1 -> "SHA-384"
                    else -> "SHA-512"
                }
            ).digest(e)
            round++
        }
        return k.copyOf(32)
    }
}
