package uk.newbroman.dune

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiKeyTest {
    @Test fun whoseKey() {
        assertEquals("gemini", Brain.providerForKey("AIzaSyABC"))
        assertEquals("openai", Brain.providerForKey(" sk-proj-abc"))
        assertEquals("openrouter", Brain.providerForKey("sk-or-v1-abc"))
        assertEquals("claude", Brain.providerForKey("sk-ant-api03-abc"))
        assertEquals(null, Brain.providerForKey("abc"))
    }

    @Test fun geminiArrayError() {
        // Gemini's OpenAI-style endpoint wraps its error in a list – it used to show as a bare "HTTP 400"
        val raw = """[{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}]"""
        val t = Brain.errorText(400, null, raw)
        assertTrue(t, t.startsWith("the key was refused") && t.contains("API key not valid"))
    }

    @Test fun ordinaryError() {
        assertEquals("HTTP 400: Unsupported parameter: 'reasoning_effort'",
            Brain.errorText(400, JSONObject("""{"error":{"message":"Unsupported parameter: 'reasoning_effort'"}}""")))
        assertEquals("HTTP 502: Bad gateway", Brain.errorText(502, null, "Bad gateway"))
        assertTrue(Brain.errorText(401, JSONObject("""{"error":{"message":"Incorrect API key provided"}}""")).startsWith("the key was refused"))
    }
}
