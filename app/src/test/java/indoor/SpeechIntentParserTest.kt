package indoor

import com.example.pathfinderindoor.indoor.AppNavMode
import com.example.pathfinderindoor.indoor.SpeechIntentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechIntentParserTest {

    @Test
    fun testModeParsing() {
        assertEquals(AppNavMode.INDOOR, SpeechIntentParser.parseMode("Indoor"))
        assertEquals(AppNavMode.INDOOR, SpeechIntentParser.parseMode("Indoors"))
        assertEquals(AppNavMode.INDOOR, SpeechIntentParser.parseMode("I want indoor navigation"))
        assertEquals(AppNavMode.INDOOR, SpeechIntentParser.parseMode("Take me indoors"))
        assertEquals(AppNavMode.INDOOR, SpeechIntentParser.parseMode("take me inside"))
        assertEquals(AppNavMode.OUTDOOR, SpeechIntentParser.parseMode("Outdoor"))
        assertEquals(AppNavMode.OUTDOOR, SpeechIntentParser.parseMode("Outdoors"))
        assertEquals(AppNavMode.OUTDOOR, SpeechIntentParser.parseMode("I want outdoor navigation"))
        assertEquals(AppNavMode.OUTDOOR, SpeechIntentParser.parseMode("I want to go outside"))
        assertEquals(AppNavMode.OUTDOOR, SpeechIntentParser.parseMode("Take me outside"))
        assertEquals(AppNavMode.NONE, SpeechIntentParser.parseMode("hello"))
    }

    @Test
    fun testDestinationExtraction() {
        assertEquals("library", SpeechIntentParser.extractDestination("Library"))
        assertEquals("library", SpeechIntentParser.extractDestination("library"))
        assertEquals("library", SpeechIntentParser.extractDestination("I want to go to the library"))
        assertEquals("library", SpeechIntentParser.extractDestination("Take me to the library"))
        assertEquals("library", SpeechIntentParser.extractDestination("Guide me to the library"))
        assertEquals("library", SpeechIntentParser.extractDestination("I need to go to the library"))
        assertEquals("library", SpeechIntentParser.extractDestination("Go to library"))
        assertEquals("library", SpeechIntentParser.extractDestination("Can you take me to the library?"))
        assertEquals("library", SpeechIntentParser.extractDestination("I need directions to the library"))
        assertEquals("kochi", SpeechIntentParser.extractDestination("I want to go to Kochi"))
        assertEquals("kochi", SpeechIntentParser.extractDestination("Kochi"))
        assertEquals("kochi", SpeechIntentParser.extractDestination("take me to kochi please"))
        assertEquals("computer lab", SpeechIntentParser.extractDestination("could you please take me to the computer lab"))
    }

    @Test
    fun testStopCommands() {
        assertTrue(SpeechIntentParser.isStopOrCancel("stop"))
        assertTrue(SpeechIntentParser.isStopOrCancel("Stop navigation"))
        assertTrue(SpeechIntentParser.isStopOrCancel("cancel"))
        assertTrue(SpeechIntentParser.isStopOrCancel("exit navigation"))
        assertTrue(SpeechIntentParser.isStopOrCancel("go back"))
    }

    @Test
    fun testStartCommands() {
        assertTrue(SpeechIntentParser.isStartCommand("start"))
        assertTrue(SpeechIntentParser.isStartCommand("start navigation"))
        assertTrue(SpeechIntentParser.isStartCommand("yes start"))
        assertTrue(SpeechIntentParser.isStartCommand("let's go"))
        assertTrue(SpeechIntentParser.isStartCommand("use this route"))
    }
}
