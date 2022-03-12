import net.adoptium.marketplace.client.signature.Rsa256SignatureVerify
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.Signature
import java.util.*


/**
 * Signs files in our test assets directory using our test key.
 */
object SignAssets {
    private val KEY: PrivateKey?

    init {
        KEY = try {
            Security.addProvider(BouncyCastleProvider())

            val keystore = getKeyStore()
            keystore.getKey(getKeyAlias(), getKeyPassword()) as PrivateKey
        } catch (e: Exception) {
            null
        }
    }

    private fun getKeystorePassword(): CharArray {
        return System.getProperty("KEYSTORE_PASSWORD").toCharArray()
    }

    private fun getKeyPassword(): CharArray {
        return System.getProperty("SIGNING_KEY_PASSWORD").toCharArray()
    }

    private fun getKeyAlias(): String {
        return System.getProperty("SIGNING_KEY_ALIAS", "adopt-signing-key")
    }

    private fun getKeystoreLocation() = System.getProperty("KEY_STORE", "keystore.jks")

    fun getKeyStore(): KeyStore {
        val keystoreFile = Path.of(getKeystoreLocation()).toFile()

        return FileInputStream(keystoreFile)
            .use {
                val keystore = KeyStore.getInstance("JKS");
                keystore.load(it, getKeystorePassword())
                keystore
            }
    }


    @Throws(Exception::class)
    fun sign(path: String?) {
        val signatureSHA256Java = Signature.getInstance("SHA256withRSA")
        Files.walk(Paths.get(path))
            .filter { path: Path? -> Files.isRegularFile(path) }
            .filter { fileName: Path -> fileName.toFile().name.endsWith(".json") }
            .forEach { file: Path ->
                val outFile = file.toAbsolutePath().toString() + "." + Rsa256SignatureVerify.FILE_SUFFIX
                try {
                    FileInputStream(file.toFile()).use { fis ->
                        FileOutputStream(outFile).use { fos ->
                            signatureSHA256Java.initSign(KEY)
                            signatureSHA256Java.update(fis.readAllBytes())
                            val sig = signatureSHA256Java.sign()
                            fos.write(Base64.getEncoder().encode(sig))
                        }
                    }
                } catch (e: Exception) {
                    throw RuntimeException(e)
                }
            }
    }
}
