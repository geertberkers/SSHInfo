import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Uses throwaway keys from src/test/resources/ssh (passphrase for the protected ones: "test-passphrase").
 * They are copied to a temp dir first because OpenSSH refuses keys with loose permissions.
 * Requires `ssh-keygen` on the PATH.
 */
class SshKeyCheckerTest {
    private val passphrase = "test-passphrase"
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ssh-test").toFile()
        val source = File(javaClass.getResource("/ssh/config")!!.toURI()).parentFile
        source.listFiles()!!.forEach { f ->
            val copy = f.copyTo(File(dir, f.name))
            restrictToOwner(copy)
        }
    }

    private fun restrictToOwner(file: File) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            // Drop inherited ACLs so only the current user can access the key.
            ProcessBuilder("icacls", file.absolutePath, "/inheritance:r", "/grant:r", "${System.getenv("USERNAME")}:F")
                .redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()
        } else {
            file.setReadable(false, false); file.setReadable(true, true)
            file.setWritable(false, false); file.setWritable(true, true)
        }
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun key(name: String) = scanKeys(dir).first { it.file.name == name }

    @Test
    fun `finds only private keys`() {
        val names = scanKeys(dir).map { it.file.name }
        assertEquals(listOf("id_ed25519_plain", "id_ed25519_protected", "id_rsa_pem_protected"), names)
    }

    @Test
    fun `detects key formats`() {
        assertEquals("OPENSSH PRIVATE KEY", key("id_ed25519_plain").format)
        assertEquals("RSA PRIVATE KEY", key("id_rsa_pem_protected").format)
    }

    @Test
    fun `detects unprotected and protected keys`() {
        assertEquals(Protection.UNPROTECTED, key("id_ed25519_plain").protection)
        assertEquals(Protection.PROTECTED, key("id_ed25519_protected").protection)
        assertEquals(Protection.PROTECTED, key("id_rsa_pem_protected").protection)
    }

    @Test
    fun `accepts the right passphrase`() {
        assertEquals(CheckResult.Correct, checkPassphrase(key("id_ed25519_protected"), passphrase))
        assertEquals(CheckResult.Correct, checkPassphrase(key("id_rsa_pem_protected"), passphrase))
    }

    @Test
    fun `rejects a wrong passphrase`() {
        assertEquals(CheckResult.Wrong, checkPassphrase(key("id_ed25519_protected"), "nope"))
    }

    @Test
    fun `passphrase store round trips as base64`() {
        assertNull(loadSavedPassphrase("id_a", dir))
        savePassphrase("id_a", "pässword:1", dir)
        savePassphrase("id_b", "other", dir)
        savePassphrase("id_a", passphrase, dir) // overwrite
        assertEquals(passphrase, loadSavedPassphrase("id_a", dir))
        assertEquals("other", loadSavedPassphrase("id_b", dir))
        assertEquals(2, storeFile(dir).readLines().size)
        assertTrue(storeFile(dir).readText().contains("id_b:b3RoZXI="))
    }

    @Test
    fun `parses ssh config`() {
        val hosts = parseConfig(File(dir, "config"))
        assertEquals(listOf("*", "github github.com", "prod"), hosts.map { it.patterns })

        val github = hosts[1]
        assertEquals("github.com", github.option("hostname")) // case-insensitive
        assertEquals("git", github.option("User"))
        assertEquals("~/.ssh/id_ed25519_plain", github.option("IdentityFile"))

        val prod = hosts[2]
        assertEquals("10.0.0.5", prod.option("HostName")) // "key=value" syntax
        assertEquals("2222", prod.option("Port"))
        assertEquals("bastion", prod.option("ProxyJump"))
        assertNotNull(prod.line)
    }

    @Test
    fun `missing config gives empty list`() {
        assertTrue(parseConfig(File(dir, "does-not-exist")).isEmpty())
    }
}
