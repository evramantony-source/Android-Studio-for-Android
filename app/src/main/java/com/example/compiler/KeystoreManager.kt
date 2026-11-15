package com.example.compiler

import com.android.apksig.ApkSigner
import com.example.model.ReleaseSigningConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class LoadedSigner(
    val alias: String,
    val privateKey: PrivateKey,
    val chain: List<X509Certificate>,
    val subject: String,
    val sha1Fingerprint: String,
    val sha256Fingerprint: String,
) {
    fun toApkSignerConfig(): ApkSigner.SignerConfig =
        ApkSigner.SignerConfig.Builder(
            alias.uppercase(Locale.US).replace(Regex("[^A-Z0-9_]"), "_").ifBlank { "DROIDFORGE" },
            privateKey,
            chain,
        ).build()
}

object KeystoreManager {

    private val OID_SHA256_WITH_RSA = intArrayOf(1, 2, 840, 113549, 1, 1, 11)
    private val OID_SHA256 = intArrayOf(2, 16, 840, 1, 101, 3, 4, 2, 1)
    private val OID_PKCS7_DATA = intArrayOf(1, 2, 840, 113549, 1, 7, 1)
    private val OID_PKCS7_SIGNED_DATA = intArrayOf(1, 2, 840, 113549, 1, 7, 2)
    private val OID_CN = intArrayOf(2, 5, 4, 3)
    private val OID_ORG = intArrayOf(2, 5, 4, 10)
    private val OID_COUNTRY = intArrayOf(2, 5, 4, 6)

    fun generatePkcs12Keystore(
        outputFile: File,
        alias: String,
        storePassword: String,
        keyPassword: String,
        commonName: String,
        organization: String,
        country: String = "US",
        validityYears: Int = 20,
    ): ReleaseSigningConfig {
        val cleanAlias = alias.trim().ifBlank { "release" }
        val cleanCn = commonName.trim().ifBlank { "DroidForge Developer" }
        val cleanOrg = organization.trim().ifBlank { "DroidForge Studio" }
        val cleanCountry = country.trim().take(2).uppercase(Locale.US).ifBlank { "US" }
        val effectiveKeyPass = keyPassword.ifBlank { storePassword }

        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048, SecureRandom())
        val keyPair = keyGen.generateKeyPair()

        val cert = createSelfSignedCertificate(
            keyPair = keyPair,
            commonName = cleanCn,
            organization = cleanOrg,
            country = cleanCountry,
            validityYears = validityYears.coerceIn(1, 24),
        )

        outputFile.parentFile?.mkdirs()
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(cleanAlias, keyPair.private, effectiveKeyPass.toCharArray(), arrayOf(cert))
        outputFile.outputStream().buffered().use { out ->
            ks.store(out, storePassword.toCharArray())
        }

        val sha1 = fingerprint(cert.encoded, "SHA-1")
        val sha256 = fingerprint(cert.encoded, "SHA-256")

        return ReleaseSigningConfig(
            keystorePath = outputFile.absolutePath,
            alias = cleanAlias,
            storePassword = storePassword,
            keyPassword = effectiveKeyPass,
            commonName = cleanCn,
            organization = cleanOrg,
            sha1Fingerprint = sha1,
            sha256Fingerprint = sha256,
        )
    }

    fun inspectKeystore(
        keystoreFile: File,
        storePassword: String,
        preferredAlias: String = "",
        keyPassword: String = storePassword,
    ): LoadedSigner {
        val typesToTry = listOf("PKCS12", "JKS", "BKS")
        var lastError: Throwable? = null
        for (type in typesToTry) {
            try {
                val ks = KeyStore.getInstance(type)
                keystoreFile.inputStream().buffered().use { ks.load(it, storePassword.toCharArray()) }
                val alias = if (preferredAlias.isNotBlank() && ks.containsAlias(preferredAlias)) {
                    preferredAlias
                } else {
                    ks.aliases().asSequence().firstOrNull { ks.isKeyEntry(it) }
                        ?: error("No key entry found in keystore")
                }
                val passChars = keyPassword.ifBlank { storePassword }.toCharArray()
                val key = ks.getKey(alias, passChars) as PrivateKey
                val chain = ks.getCertificateChain(alias).map { it as X509Certificate }
                val leaf = chain.first()
                return LoadedSigner(
                    alias = alias,
                    privateKey = key,
                    chain = chain,
                    subject = leaf.subjectX500Principal.name,
                    sha1Fingerprint = fingerprint(leaf.encoded, "SHA-1"),
                    sha256Fingerprint = fingerprint(leaf.encoded, "SHA-256"),
                )
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IllegalStateException("Unable to unlock keystore: ${lastError?.localizedMessage ?: "invalid password or format"}", lastError)
    }

    fun signJarArchive(
        unsignedZip: File,
        signedOutput: File,
        signer: LoadedSigner,
    ) {
        val entriesData = linkedMapOf<String, ByteArray>()
        ZipFile(unsignedZip).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory && !entry.name.startsWith("META-INF/")) {
                    entriesData[entry.name] = zip.getInputStream(entry).use { it.readBytes() }
                }
            }
        }

        val b64 = Base64.getEncoder()
        val sha256 = MessageDigest.getInstance("SHA-256")

        // Build MANIFEST.MF
        val mainHeader = buildString {
            appendManifestLine(this, "Manifest-Version", "1.0")
            appendManifestLine(this, "Created-By", "DroidForge Studio")
            append("\r\n")
        }
        val manifestSections = linkedMapOf<String, String>()
        val manifestAll = StringBuilder(mainHeader)
        for ((name, bytes) in entriesData) {
            val digestB64 = b64.encodeToString(sha256.digest(bytes))
            val section = buildString {
                appendManifestLine(this, "Name", name)
                appendManifestLine(this, "SHA-256-Digest", digestB64)
                append("\r\n")
            }
            manifestSections[name] = section
            manifestAll.append(section)
        }
        val manifestBytes = manifestAll.toString().toByteArray(Charsets.UTF_8)

        // Build .SF
        val manifestDigestB64 = b64.encodeToString(sha256.digest(manifestBytes))
        val mainAttrDigestB64 = b64.encodeToString(sha256.digest(mainHeader.toByteArray(Charsets.UTF_8)))
        val sfBuilder = StringBuilder().apply {
            appendManifestLine(this, "Signature-Version", "1.0")
            appendManifestLine(this, "Created-By", "DroidForge Studio")
            appendManifestLine(this, "SHA-256-Digest-Manifest", manifestDigestB64)
            appendManifestLine(this, "SHA-256-Digest-Manifest-Main-Attributes", mainAttrDigestB64)
            append("\r\n")
            for ((name, section) in manifestSections) {
                val secDigest = b64.encodeToString(sha256.digest(section.toByteArray(Charsets.UTF_8)))
                appendManifestLine(this, "Name", name)
                appendManifestLine(this, "SHA-256-Digest", secDigest)
                append("\r\n")
            }
        }
        val sfBytes = sfBuilder.toString().toByteArray(Charsets.UTF_8)

        // Build PKCS#7 .RSA
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(signer.privateKey)
        sig.update(sfBytes)
        val rawSignature = sig.sign()
        val pkcs7Bytes = buildPkcs7SignedData(signer.chain.first(), rawSignature)

        val baseAlias = signer.alias.uppercase(Locale.US)
            .replace(Regex("[^A-Z0-9_]"), "_")
            .take(8)
            .ifBlank { "DROIDFRG" }

        signedOutput.parentFile?.mkdirs()
        ZipOutputStream(signedOutput.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zos.write(manifestBytes)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("META-INF/$baseAlias.SF"))
            zos.write(sfBytes)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("META-INF/$baseAlias.RSA"))
            zos.write(pkcs7Bytes)
            zos.closeEntry()

            for ((name, bytes) in entriesData) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun fingerprint(certDer: ByteArray, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm).digest(certDer)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    private fun appendManifestLine(sb: StringBuilder, key: String, value: String) {
        val line = "$key: $value"
        val utf8 = line.toByteArray(Charsets.UTF_8)
        if (utf8.size <= 70) {
            sb.append(line).append("\r\n")
            return
        }
        var idx = 0
        var first = true
        while (idx < line.length) {
            val limit = if (first) 68 else 67
            val end = (idx + limit).coerceAtMost(line.length)
            if (!first) sb.append(" ")
            sb.append(line.substring(idx, end)).append("\r\n")
            idx = end
            first = false
        }
    }

    private fun createSelfSignedCertificate(
        keyPair: java.security.KeyPair,
        commonName: String,
        organization: String,
        country: String,
        validityYears: Int,
    ): X509Certificate {
        val serial = BigInteger(63, SecureRandom()).abs().max(BigInteger.ONE)
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val notBefore = cal.time
        cal.add(Calendar.YEAR, validityYears)
        val notAfter = cal.time

        val algId = derSequence(derOid(OID_SHA256_WITH_RSA) + derNull())
        val nameDer = encodeX500Name(commonName, organization, country)

        val tbsBytes = derSequence(
            // [0] EXPLICIT Version v3 (2)
            derExplicitTag(0xA0, derInteger(BigInteger.valueOf(2))) +
                derInteger(serial) +
                algId +
                nameDer +
                derSequence(derUtcTime(notBefore) + derUtcTime(notAfter)) +
                nameDer +
                keyPair.public.encoded,
        )

        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(keyPair.private)
        sig.update(tbsBytes)
        val signatureBytes = sig.sign()

        val certDer = derSequence(
            tbsBytes + algId + derBitString(signatureBytes),
        )
        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
    }

    private fun buildPkcs7SignedData(
        cert: X509Certificate,
        signatureBytes: ByteArray,
    ): ByteArray {
        val sha256Alg = derSequence(derOid(OID_SHA256) + derNull())
        val rsaSha256Alg = derSequence(derOid(OID_SHA256_WITH_RSA) + derNull())

        val issuerAndSerial = derSequence(
            cert.issuerX500Principal.encoded + derInteger(cert.serialNumber),
        )

        val signerInfo = derSequence(
            derInteger(BigInteger.ONE) +
                issuerAndSerial +
                sha256Alg +
                rsaSha256Alg +
                derOctetString(signatureBytes),
        )

        val signedData = derSequence(
            derInteger(BigInteger.ONE) +
                derSet(sha256Alg) +
                derSequence(derOid(OID_PKCS7_DATA)) +
                derExplicitTag(0xA0, cert.encoded) +
                derSet(signerInfo),
        )

        return derSequence(
            derOid(OID_PKCS7_SIGNED_DATA) + derExplicitTag(0xA0, signedData),
        )
    }

    private fun encodeX500Name(cn: String, org: String, country: String): ByteArray {
        fun rdn(oid: IntArray, value: String): ByteArray {
            val strTag = derPrintableOrUtf8(value)
            return derSet(derSequence(derOid(oid) + strTag))
        }
        return derSequence(
            rdn(OID_COUNTRY, country) +
                rdn(OID_ORG, org) +
                rdn(OID_CN, cn),
        )
    }

    private fun derPrintableOrUtf8(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return derTag(0x0C, bytes) // UTF8String
    }

    private fun derUtcTime(date: Date): ByteArray {
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return derTag(0x17, fmt.format(date).toByteArray(Charsets.US_ASCII))
    }

    private fun derSequence(content: ByteArray): ByteArray = derTag(0x30, content)
    private fun derSet(content: ByteArray): ByteArray = derTag(0x31, content)
    private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)
    private fun derOctetString(content: ByteArray): ByteArray = derTag(0x04, content)
    private fun derInteger(value: BigInteger): ByteArray = derTag(0x02, value.toByteArray())
    private fun derExplicitTag(tag: Int, content: ByteArray): ByteArray = derTag(tag, content)

    private fun derBitString(content: ByteArray): ByteArray {
        val withUnused = ByteArray(content.size + 1)
        withUnused[0] = 0x00
        System.arraycopy(content, 0, withUnused, 1, content.size)
        return derTag(0x03, withUnused)
    }

    private fun derOid(components: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(components[0] * 40 + components[1])
        for (i in 2 until components.size) {
            var v = components[i]
            val stack = IntArray(5)
            var len = 0
            stack[len++] = v and 0x7F
            v = v ushr 7
            while (v > 0) {
                stack[len++] = (v and 0x7F) or 0x80
                v = v ushr 7
            }
            for (j in len - 1 downTo 0) {
                out.write(stack[j])
            }
        }
        return derTag(0x06, out.toByteArray())
    }

    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        when {
            len < 0x80 -> out.write(len)
            len <= 0xFF -> {
                out.write(0x81)
                out.write(len)
            }
            len <= 0xFFFF -> {
                out.write(0x82)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                out.write(0x83)
                out.write((len shr 16) and 0xFF)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
        }
        out.write(content)
        return out.toByteArray()
    }
}
