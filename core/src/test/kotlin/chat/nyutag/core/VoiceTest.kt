// Нютаг. Голосовые: волна из уровней записи, формат MSC3245 туда и обратно.

package chat.nyutag.core

import chat.nyutag.core.media.Attachment
import chat.nyutag.core.media.MXC
import chat.nyutag.core.media.Voice
import chat.nyutag.json.JsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceTest {
    @Test fun waveform() {
        assertEquals(listOf(0, 1024, 512, 0), Voice.waveform(listOf(-160f, 0f, -25f, Float.NaN)))
        val long = Voice.waveform(List(1000) { -25f })
        assertTrue(long.size == 100 && long.all { it == 512 })
        assertEquals(emptyList<Int>(), Voice.waveform(emptyList()))
        assertEquals(listOf(0.15, 0.15, 0.15), Voice.display(emptyList(), 3))
        assertEquals(listOf(0.08, 0.08, 1.0, 1.0), Voice.display(listOf(0, 1024), 4))
        assertEquals("1:23", Voice.clock(83_400))
        assertEquals("0:07", Voice.clock(7_000))
    }

    @Test fun roundTrip() {
        val mxc = requireNotNull(MXC.parse("mxc://nyutag.chat/abc"))
        val content = Attachment.content(Attachment.Source.Plain(mxc), "Голосовое.m4a", "audio/mp4", 1200,
                                         duration = 4200, voice = listOf(10, 500, 1024))
        assertEquals("m.audio", content["msgtype"]?.string)
        assertEquals(JsonValue.Obj(emptyMap()), content["org.matrix.msc3245.voice"])
        assertEquals(4200, content.at("org.matrix.msc1767.audio", "duration")?.int)
        val read = requireNotNull(Attachment.read(content))
        assertTrue(read.isVoice)
        assertEquals(listOf(10, 500, 1024), read.waveform)
        assertEquals(4200, read.duration)
    }

    @Test fun foreign() {
        val content = JsonValue.parse(
            """{"msgtype":"m.audio","body":"Voice message","url":"mxc://x/1","org.matrix.msc3245.voice":{},
               "org.matrix.msc1767.audio":{"duration":3000,"waveform":[-5,2000,300]}}""",
        )
        val read = requireNotNull(Attachment.read(content))
        assertEquals(3000, read.duration)
        assertEquals(listOf(0, 1024, 300), read.waveform)
        val music = requireNotNull(Attachment.read(JsonValue.parse("""{"msgtype":"m.audio","body":"song.mp3","url":"mxc://x/2"}""")))
        assertFalse(music.isVoice)
    }
}
