package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class L14ConnectTest {
    private val env = L14Connect.env(" 100.64.0.5 ", "john", "", tsApp = true)

    @Test fun envQuotesAndDefaults() {
        assertEquals("L14_HOST='100.64.0.5' L14_USER='john' L14_PORT='22' TS_APP=1", env)
        assertEquals("L14_HOST='l14' L14_USER='john' L14_PORT='2222' TS_APP=0", L14Connect.env("l14", " ", "2222", false))
        assertEquals("L14_HOST='a'\\''b' L14_USER='john' L14_PORT='22' TS_APP=0", L14Connect.env("a'b", "john", "22", false))
    }

    @Test fun connectCommandRunsTheScriptWithTheSettings() {
        val c = L14Connect.connectCommand(env)
        assertTrue(c.contains("$env \"\$HOME/bin/l14\""))
        assertTrue(c.startsWith("if [ -x \"\$HOME/bin/l14\" ]"))
        assertTrue(c.contains("set up Termux"))
    }

    @Test fun delimiterAvoidsLinesInTheScripts() {
        assertEquals("DUNE_EOF", L14Connect.delimiter("echo hi\nDUNE_EOF_x\n"))
        assertEquals("DUNE_EOF_1", L14Connect.delimiter("a\nDUNE_EOF\nb"))
        assertEquals("DUNE_EOF_2", L14Connect.delimiter("DUNE_EOF", "x\nDUNE_EOF_1"))
    }

    @Test fun installCommandWritesBothFilesThenRunsSetup() {
        val script = "#!/bin/bash\necho 'it'\"s\" \$HOME\n"
        val setup = "#!/bin/bash\nmain() { :; }\nmain\n"
        val c = L14Connect.installCommand(script, setup, env)
        val lines = c.lines()
        assertEquals("mkdir -p \"\$HOME/bin\" && cat > \"\$HOME/bin/l14\" <<'DUNE_EOF'", lines[0])
        assertEquals(listOf("#!/bin/bash", "echo 'it'\"s\" \$HOME", "DUNE_EOF"), lines.subList(1, 4))
        assertEquals("cat > \"\$HOME/bin/l14-setup.sh\" <<'DUNE_EOF'", lines[4])
        assertEquals("DUNE_EOF", lines[8])
        assertEquals("chmod +x \"\$HOME/bin/l14\" \"\$HOME/bin/l14-setup.sh\" && $env L14_LOCAL=1 bash \"\$HOME/bin/l14-setup.sh\"", lines[9])
        assertEquals(10, lines.size)
    }

    @Test fun pasteLineFetchesTheSetupFromTheRepo() {
        assertEquals("curl -fsSL https://raw.githubusercontent.com/newbroman/Dune/main/l14/termux/l14-setup.sh | $env bash", L14Connect.pasteLine(env))
    }

    @Test fun adbLinesCoverThePhantomProcessKillerAndDoze() {
        val all = L14Connect.adbLines.joinToString("\n")
        assertTrue(all.contains("max_phantom_processes 2147483647"))
        assertTrue(all.contains("set_sync_disabled_for_tests persistent"))
        assertTrue(all.contains("settings_enable_monitor_phantom_procs false"))
        assertTrue(all.contains("deviceidle whitelist +com.termux"))
        assertTrue(L14Connect.adbLines.all { it.startsWith("adb shell ") })
    }

    // ---- the scripts themselves (the repo copies the APK bundles): no secrets, and the login-link parser

    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "l14/termux/l14").exists() }
    private fun script(name: String) = File(repo, "l14/termux/$name")

    @Test fun scriptsHoldNoSecretsOrAddresses() {
        assumeTrue(repo != null)
        for (n in listOf("l14", "l14-setup.sh")) {
            val t = script(n).readText()
            assertFalse(n, Regex("""\b100\.\d+\.\d+\.\d+\b""").containsMatchIn(t))
            assertFalse(n, Regex("""\b192\.168\.\d+\.\d+\b""").containsMatchIn(t))
            assertFalse(n, Regex("""tskey-|ssh-ed25519 AAAA|\.ts\.net""").containsMatchIn(t))
            assertTrue(n, t.startsWith("#!/data/data/com.termux/files/usr/bin/bash"))
        }
    }

    /** Runs the script's own l14_login_url with bash, where bash is available (Linux build hosts). */
    private fun loginUrl(text: String): String? {
        val bash = listOf("/bin/bash", "/usr/bin/bash").firstOrNull { File(it).canExecute() }
        assumeTrue(repo != null && bash != null)
        val p = ProcessBuilder(bash, "-c", "source <(sed -n '/^l14_login_url()/,/^}/p' \"$1\"); l14_login_url", "x", script("l14").path)
            .redirectErrorStream(true).start()
        p.outputStream.use { it.write(text.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText().trim()
        val rc = p.waitFor()   // 0 = found, 1 = no link in the text
        assertEquals(if (out.isEmpty()) 1 else 0, rc)
        return out.ifEmpty { null }
    }

    @Test fun loginUrlFromTailscaleUpOutput() {
        assertEquals("https://login.tailscale.com/a/abc123",
            loginUrl("Warning: see https://tailscale.com/kb/1234/ for details\nTo authenticate, visit:\n\n\thttps://login.tailscale.com/a/abc123\n\n"))
        assertEquals("https://login.tailscale.com/a/0123456789abcdef", loginUrl("To authenticate, visit:\n\n\thttps://login.tailscale.com/a/0123456789abcdef\n"))
        assertEquals("https://headscale.example.org/register/xyz", loginUrl("To authenticate, visit:\n\thttps://headscale.example.org/register/xyz\n"))
    }

    @Test fun loginUrlFallsBackToAnyUrlThatIsNotDocs() {
        assertEquals("https://login.tailscale.com/a/zzz", loginUrl("{\"AuthURL\": \"https://login.tailscale.com/a/zzz\", \"BackendState\": \"NeedsLogin\"}"))
        assertEquals(null, loginUrl("Warning: see https://tailscale.com/kb/1234/\nnothing else\n"))
        assertEquals(null, loginUrl("Success.\n"))
    }
}
