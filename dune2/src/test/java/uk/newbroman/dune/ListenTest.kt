package uk.newbroman.dune

import org.junit.Assert.assertEquals
import org.junit.Test

class ListenTest {
    private fun c(s: String) = Assistant.interpret(s)

    @Test fun recent() {
        assertEquals(ListenCmd.Recent("spotify"), c("Open Spotify and show my recent plays"))
        assertEquals(ListenCmd.Recent("spotify"), c("show my recently played on Spotify"))
        assertEquals(ListenCmd.Recent(null), c("what have I been listening to"))
        assertEquals(ListenCmd.Recent(null), c("recent plays"))
        assertEquals(ListenCmd.PickNumber(2), c("number 2"))
        assertEquals(ListenCmd.PickNumber(3), c("the third one"))
        assertEquals(ListenCmd.PickNumber(1), c("play number one"))
        assertEquals(Cmd.Spotify(null), c("open Spotify"))
        assertEquals(Cmd.Spotify("Brian Eno"), c("play Brian Eno on Spotify"))
    }

    @Test fun bbc() {
        assertEquals(ListenCmd.Station("radio 3"), c("Play Radio 3"))
        assertEquals(ListenCmd.Station("radio 3"), c("put on BBC Radio 3"))
        assertEquals(ListenCmd.Station("radio 3"), c("radio three"))
        assertEquals(ListenCmd.Station("6 music"), c("play six music"))
        assertEquals(ListenCmd.SoundsFavourites, c("open BBC Sounds and show my favourites"))
        assertEquals(ListenCmd.SoundsFavourites, c("my favourite shows"))
        assertEquals(ListenCmd.Show("unclassified"), c("play Unclassified"))
        assertEquals(ListenCmd.Show("new music show"), c("play the New Music Show"))
        assertEquals(ListenCmd.Show("new music show"), c("play the latest New Music Show"))
        assertEquals(ListenCmd.Show("in our time"), c("play In Our Time on BBC Sounds"))
    }

    @Test fun spheres() {
        assertEquals(ListenCmd.Spheres(null, false), c("play Music of the Spheres"))
        assertEquals(ListenCmd.Spheres("eno", true), c("play the Eno mix"))
        assertEquals(ListenCmd.Spheres("eno", true), c("play Music of the Spheres and go to the Eno mix"))
        assertEquals(ListenCmd.Spheres("eno", false), c("play Eno on Music of the Spheres"))
        assertEquals(ListenCmd.Spheres("satie", false), c("Music of the Spheres Satie"))
        assertEquals("https://newbroman.github.io/Music_of_the_Spheres/dance.html#style=eno&mix=1&play", Listen.spheresUrl("eno", true))
    }
}
