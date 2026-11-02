import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

// ---------- Model ----------

enum class Protection(val label: String, val color: Color) {
    UNPROTECTED("No passphrase", Color(0xFFD32F2F)),
    PROTECTED("Passphrase protected", Color(0xFF388E3C)),
    BAD_PERMISSIONS("Permissions too open", Color(0xFFF57C00)),
    UNKNOWN("Unreadable", Color(0xFF757575)),
}

data class SshKey(val file: File, val format: String, val protection: Protection)

sealed interface CheckResult {
    data object Correct : CheckResult
    data object Wrong : CheckResult
    data class Error(val message: String) : CheckResult
}

// ---------- Logic ----------

private val skipNames = setOf("known_hosts", "known_hosts.old", "config", "authorized_keys")

private fun sshDir() = File(System.getProperty("user.home"), ".ssh")

/** Returns the PEM header ("OPENSSH PRIVATE KEY", "RSA PRIVATE KEY", ...) if the file is a private key. */
private fun privateKeyFormat(file: File): String? {
    if (!file.isFile || file.length() > 64 * 1024) return null
    val first = runCatching { file.useLines { it.firstOrNull() } }.getOrNull() ?: return null
    return Regex("-----BEGIN (.*PRIVATE KEY)-----").find(first)?.groupValues?.get(1)
}

/**
 * Runs `ssh-keygen -y -P <passphrase> -f <key>`, which derives the public key and only succeeds
 * when the passphrase is right. Note: the passphrase is briefly visible in the process arguments.
 */
private fun runKeygen(file: File, passphrase: String): Pair<Int, String> {
    val process = ProcessBuilder("ssh-keygen", "-y", "-P", passphrase, "-f", file.absolutePath)
        .redirectErrorStream(true)
        .start()
    process.outputStream.close()
    val output = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return -1 to "ssh-keygen timed out"
    }
    return process.exitValue() to output
}

private fun scanKeys(): List<SshKey> =
    (sshDir().listFiles() ?: emptyArray())
        .filter { it.name !in skipNames && !it.name.endsWith(".pub") }
        .sortedBy { it.name }
        .mapNotNull { file ->
            val format = privateKeyFormat(file) ?: return@mapNotNull null
            val (code, out) = runCatching { runKeygen(file, "") }.getOrElse { -1 to it.message.orEmpty() }
            val protection = when {
                code == 0 -> Protection.UNPROTECTED
                "UNPROTECTED PRIVATE KEY FILE" in out || "bad permissions" in out -> Protection.BAD_PERMISSIONS
                "incorrect passphrase" in out || "passphrase" in out -> Protection.PROTECTED
                else -> Protection.UNKNOWN
            }
            SshKey(file, format, protection)
        }

// Saved passphrases: one "keyname:base64" line per key. Base64 is encoding, NOT encryption.
private fun storeFile() = File(sshDir(), "passphrases.b64")

private fun loadSavedPassphrase(keyName: String): String? =
    runCatching {
        storeFile().readLines()
            .firstOrNull { it.substringBefore(':') == keyName }
            ?.substringAfter(':')
            ?.let { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }
    }.getOrNull()

private fun savePassphrase(keyName: String, passphrase: String) {
    val file = storeFile()
    val others = if (file.exists()) file.readLines().filter { it.isNotBlank() && it.substringBefore(':') != keyName } else emptyList()
    val encoded = Base64.getEncoder().encodeToString(passphrase.toByteArray(Charsets.UTF_8))
    file.writeText((others + "$keyName:$encoded").joinToString("\n") + "\n")
    file.setReadable(false, false); file.setReadable(true, true)
    file.setWritable(false, false); file.setWritable(true, true)
}

private fun checkPassphrase(key: SshKey, passphrase: String): CheckResult {
    val (code, out) = runCatching { runKeygen(key.file, passphrase) }
        .getOrElse { return CheckResult.Error(it.message ?: "Could not run ssh-keygen") }
    return when {
        code == 0 -> CheckResult.Correct
        "incorrect passphrase" in out -> CheckResult.Wrong
        else -> CheckResult.Error(out.trim().ifEmpty { "ssh-keygen failed (exit $code)" })
    }
}

// ---------- UI ----------

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    var keys by remember { mutableStateOf<List<SshKey>?>(null) }
    var selected by remember { mutableStateOf<SshKey?>(null) }

    fun refresh() {
        keys = null
        scope.launch {
            keys = withContext(Dispatchers.IO) { scanKeys() }
            selected = keys?.firstOrNull { it.file == selected?.file }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(320.dp).fillMaxHeight().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SSH keys", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = ::refresh) { Text("Rescan") }
                    }
                    Text(sshDir().path, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    when (val list = keys) {
                        null -> CircularProgressIndicator()
                        else -> if (list.isEmpty()) Text("No private keys found.") else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(list, key = { it.file.path }) { key ->
                                KeyRow(key, key.file == selected?.file) { selected = key }
                            }
                        }
                    }
                }
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
                    selected?.let { DetailPane(it) } ?: Text("Select a key on the left.")
                }
            }
        }
    }
}

@Composable
private fun KeyRow(key: SshKey, isSelected: Boolean, onClick: () -> Unit) {
    val bg = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Column(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(10.dp)
    ) {
        Text(key.file.name, style = MaterialTheme.typography.titleSmall)
        Text(key.protection.label, color = key.protection.color, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DetailPane(key: SshKey) {
    val scope = rememberCoroutineScope()
    var passphrase by remember(key.file) { mutableStateOf(loadSavedPassphrase(key.file.name).orEmpty()) }
    var visible by remember(key.file) { mutableStateOf(false) }
    var savedNote by remember(key.file) { mutableStateOf<String?>(null) }
    var result by remember(key.file) { mutableStateOf<CheckResult?>(null) }
    var busy by remember(key.file) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(key.file.name, style = MaterialTheme.typography.headlineSmall)
        Text("Path: ${key.file.path}")
        Text("Format: ${key.format}")
        Text("Status: ${key.protection.label}", color = key.protection.color)

        if (key.protection == Protection.UNPROTECTED) {
            Text("Anyone who can read this file can use this key. Consider adding a passphrase with: ssh-keygen -p -f \"${key.file.path}\"")
        } else {
            HorizontalDivider()
            Text("Verify a passphrase", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it; result = null; savedNote = null },
                label = { Text("Passphrase") },
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") }
                },
                modifier = Modifier.width(360.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = passphrase.isNotEmpty() && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            result = withContext(Dispatchers.IO) { checkPassphrase(key, passphrase) }
                            busy = false
                        }
                    },
                ) { Text(if (busy) "Checking..." else "Check") }
                OutlinedButton(
                    enabled = passphrase.isNotEmpty(),
                    onClick = {
                        savedNote = runCatching { savePassphrase(key.file.name, passphrase) }
                            .fold({ "Saved to ${storeFile().path}" }, { "Save failed: ${it.message}" })
                    },
                ) { Text("Save") }
            }
            savedNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            when (val r = result) {
                CheckResult.Correct -> Text("Passphrase is correct.", color = Color(0xFF388E3C))
                CheckResult.Wrong -> Text("Passphrase is incorrect.", color = Color(0xFFD32F2F))
                is CheckResult.Error -> Text(r.message, color = Color(0xFFF57C00))
                null -> {}
            }
        }
    }
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "SSH Key Checker") {
        App()
    }
}
