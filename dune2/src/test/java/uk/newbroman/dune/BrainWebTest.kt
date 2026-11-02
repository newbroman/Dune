package uk.newbroman.dune
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
class BrainWebTest {
    @Test fun openRouterChat() {
        val a = Brain.parseChat(JSONObject("""{"choices":[{"message":{"content":"High tide 14:02.","annotations":[{"type":"url_citation","url_citation":{"url":"https://tides.example/llanddwyn","title":"Tides"}}]}}]}"""))
        assertEquals("High tide 14:02.", a.text); assertEquals("https://tides.example/llanddwyn", a.sources.single().second)
    }
    @Test fun openAiResponses() {
        val a = Brain.parseResponses(JSONObject("""{"output":[{"type":"web_search_call"},{"type":"message","content":[{"type":"output_text","text":"Open till 8pm.","annotations":[{"type":"url_citation","url":"https://shop.example","title":"Shop"}]}]}]}"""))
        assertEquals("Open till 8pm.", a.text); assertEquals("Shop" to "https://shop.example", a.sources.single())
    }
    @Test fun claudeMessages() {
        val a = Brain.parseClaude(JSONObject("""{"content":[{"type":"server_tool_use"},{"type":"web_search_tool_result"},{"type":"text","text":"It's 12°C","citations":[{"type":"web_search_result_location","url":"https://w.example","title":"Weather"}]},{"type":"text","text":" today."}]}"""))
        assertEquals("It's 12°C today.", a.text); assertEquals(1, a.sources.size)
    }
    @Test fun gemini() {
        val a = Brain.parseGemini(JSONObject("""{"candidates":[{"content":{"parts":[{"text":"Yes."}]},"groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://g.example","title":"g"}},{"web":{"uri":"https://g.example","title":"g"}}]}}]}"""))
        assertEquals("Yes.", a.text); assertEquals(1, a.sources.size)
    }
}
