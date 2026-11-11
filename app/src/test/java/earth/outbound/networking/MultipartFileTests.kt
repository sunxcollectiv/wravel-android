//
//  MultipartFileTests.kt
//  outboundTests
//

package earth.outbound.networking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultipartFileTests {
    @Test
    fun bodyWrapsTheFileInOneBoundedPart() {
        val file = MultipartFile(field = "file", filename = "screenshot.png", contentType = "image/png", data = "PNGDATA".encodeToByteArray())
        val body = file.body

        val boundary = body.contentType.substringAfterLast("boundary=")
        assertTrue(body.contentType.startsWith("multipart/form-data; boundary="))
        val text = body.data.decodeToString()
        assertEquals(
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"screenshot.png\"\r\n" +
                "Content-Type: image/png\r\n" +
                "\r\n" +
                "PNGDATA\r\n" +
                "--$boundary--\r\n",
            text,
        )
    }
}
