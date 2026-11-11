//
//  ReceivedImagesAPI.kt
//  outbound
//

package earth.outbound

import earth.outbound.networking.Endpoint
import earth.outbound.networking.HTTPMethod
import earth.outbound.networking.MultipartFile
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `ImageReceiptRead`: an uploaded image as the received-item pipeline records it.
 * `tripId` is set once the image's bookings are imported into a trip.
 */
@Serializable
data class ImageReceipt(
    val id: String,
    val tripId: String? = null,
    val status: String,
)

/**
 * `ImageImportResult`: what became of an uploaded image once processed: placed in a
 * trip (`tripId`, with `itemCount` new items), or `no_match` / `ambiguous` (with the
 * trips it could fit) / `skipped` (not a confident booking).
 */
@Serializable
data class ImageImportResult(
    val receipt: ImageReceipt,
    val outcome: String,
    val tripId: String? = null,
    val itemCount: Int,
)

/**
 * Request shapes for uploaded images (booking screenshots).
 *
 *     POST /received/images/process  multipart `file` -> ImageImportResult (201)
 */
object ReceivedImagesAPI {
    /**
     * Uploads one image and runs the whole pipeline on it in the same call: store,
     * read the booking out of it, and import it into the matching trip.
     */
    fun process(file: MultipartFile) = Endpoint(
        path = "/received/images/process",
        method = HTTPMethod.POST,
        rawBody = file.body,
        timeout = processTimeout,
    )

    /** How long a processed upload gets, start to finish, before it counts as failed. */
    val processTimeout: Duration = 30.seconds
}
