package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupRulesTest {
    private val rules = parseGroupRules(DEFAULT_GROUP_RULES)
    private fun g(label: String, pkg: String, web: String? = null, cat: Int = -1) =
        rules.firstOrNull { it.matches(AppFacts(label, pkg, cat, web)) }?.name ?: "Tools"

    @Test fun groups() {
        assertEquals("Languages", g("Polish Phrase Master", "org.chromium.webapk.a1", "https://newbroman.github.io/Polish_survival_phrases/"))
        assertEquals("Languages", g("Duolingo", "com.duolingo"))
        assertEquals("My apps", g("Source Check", "org.chromium.webapk.b2", "https://newbroman.github.io/Source_Check/"))
        assertEquals("My apps", g("Music of the Spheres", "uk.newbroman.spheres"))
        assertEquals("AI", g("Gemini", "com.google.android.apps.bard"))
        assertEquals("AI", g("Claude", "com.anthropic.claude"))
        assertEquals("AI", g("ChatGPT", "com.openai.chatgpt"))
        assertEquals("Web", g("Chrome", "com.android.chrome"))
        assertEquals("Web", g("Google", "com.google.android.googlequicksearchbox"))
        assertEquals("Shopping", g("Screwfix", "com.screwfix.app"))
        assertEquals("Shopping", g("Toolstation", "com.toolstation.app"))
        assertEquals("Shopping", g("Costa Coffee Club", "uk.co.costa.coffeeclub"))
        assertEquals("Shopping", g("Greggs", "com.greggs.app"))
        assertEquals("Shopping", g("ASDA Rewards", "com.asda.rewards"))
        assertEquals("Comms", g("Messages", "com.google.android.apps.messaging"))
        assertEquals("Work", g("Termux", "com.termux"))
        assertEquals("Media", g("Camera", "com.android.camera"))
        assertEquals("Music", g("Spotify", "com.spotify.music"))
        assertEquals("Music", g("YouTube Music", "com.google.android.apps.youtube.music"))
        assertEquals("Google", g("YouTube", "com.google.android.youtube"))
        assertEquals("Google", g("Drive", "com.google.android.apps.docs"))
        assertEquals("Google", g("Gmail", "com.google.android.gm"))
        assertEquals("AI", g("Gemini", "com.google.android.apps.bard"))
        assertEquals("Files", g("Files by Google", "com.google.android.apps.nbu.files"))
        assertEquals("Files", g("Files", "com.android.documentsui"))
        assertEquals("Stars & Geography", g("Stellarium Mobile", "com.noctuasoftware.stellarium_free"))
        assertEquals("Stars & Geography", g("Google Earth", "com.google.earth"))
        assertEquals("Stars & Geography", g("Star Walk 2", "com.vitotechnology.StarWalk2Free"))
        assertEquals("Google", g("Maps", "com.google.android.apps.maps"))
        assertEquals("Travel", g("Trainline", "com.thetrainline"))
        assertEquals("News", g("BBC News", "bbc.mobile.news.uk"))
        assertEquals("Media", g("BBC iPlayer", "bbc.iplayer.android"))
        assertEquals("Tools", g("Torch", "com.example.torch"))
        assertEquals("Tools", g("Clock", "com.android.deskclock"))
    }
}
