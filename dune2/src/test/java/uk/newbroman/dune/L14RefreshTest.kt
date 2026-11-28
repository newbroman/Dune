package uk.newbroman.dune

import org.junit.Assert.assertTrue
import org.junit.Test

class L14RefreshTest {
    @Test fun refreshWritesTheScriptThenConnects() {
        val c = L14Connect.refreshCommand("#!/bin/bash\necho hi\n") + L14Connect.connectCommand("L14_HOST='x'")
        assertTrue(c.startsWith("if [ -x \"\$HOME/bin/l14\" ]; then cat > \"\$HOME/bin/l14.new\" <<'DUNE_EOF'\n#!/bin/bash\necho hi\nDUNE_EOF\n"))
        assertTrue(c.contains("mv -f \"\$HOME/bin/l14.new\" \"\$HOME/bin/l14\"; fi; if [ -x"))
    }
}
