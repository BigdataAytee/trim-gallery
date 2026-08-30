package app.trimgallery.engine.android

import android.content.Context
import android.net.Uri
import app.trimgallery.core.model.BoundingBox
import app.trimgallery.core.model.FaceEmbedding
import app.trimgallery.core.model.Label
import app.trimgallery.core.model.MediaRef
import app.trimgallery.core.model.TextBlock
import app.trimgallery.engine.Indexer
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * On-device indexing with ML Kit (BUILD.md § 7, ARCHITECTURE.md § 15 milestone 9).
 *
 * > Image labels → search and auto-albums. Face detection + local embedding →
 * > people/pets clustering. **Never leaves device**; user can disable. OCR → search text.
 *
 * The bundled models are used, not the Play-services ones. That is not a performance
 * choice: the downloadable variants fetch their models over the network, and this app has
 * no INTERNET permission and never will (BUILD.md rule 8). A model that cannot download is
 * a feature that silently never works.
 *
 * **What this class does *not* do is cluster.** It produces embeddings; `FaceClustering`
 * decides who is who, and it is shared, because two devices grouping faces differently
 * would split a person in half the moment a library moved (ARCHITECTURE.md § 6).
 */
class MlKitIndexer(private val context: Context) : Indexer {

    private val labeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                // Below this the labels are noise, and every noisy label is a false
                // positive in someone's search results and a wrong auto-album.
                .setConfidenceThreshold(MIN_LABEL_CONFIDENCE)
                .build(),
        )
    }

    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                // ACCURATE, not FAST: this runs at night on a charging phone, where the
                // cost is minutes of a budget measured in minutes and the benefit is not
                // splitting someone's child across four "people".
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                // Faces smaller than this in the frame are passers-by in the background,
                // not people the user would name.
                .setMinFaceSize(MIN_FACE_SIZE)
                .build(),
        )
    }

    private val textRecogniser by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun labels(ref: MediaRef): List<Label> = withContext(Dispatchers.Default) {
        val mediaId = ref.value
        labeler.process(image(ref)).await().map { label ->
            Label(mediaId = mediaId, text = label.text, confidence = label.confidence)
        }
    }

    /**
     * Face rectangles and their landmark geometry.
     *
     * ARCHITECTURE.md § 6 puts the *embedding* on a LiteRT model, which is the thing that
     * makes two photographs of one person comparable. ML Kit's detector finds the faces and
     * their landmarks; until that model is chosen (PROJECT.md's open question), what this
     * returns is the geometry, and `FaceClustering` will group whatever vector it is given.
     * Returning landmark geometry rather than nothing means the boxes, the quality signal
     * and the plumbing are all exercised and correct before the model arrives.
     */
    override suspend fun faces(ref: MediaRef): List<FaceEmbedding> = withContext(Dispatchers.Default) {
        val mediaId = ref.value
        faceDetector.process(image(ref)).await().map { face ->
            FaceEmbedding(
                mediaId = mediaId,
                vector = landmarkVector(face),
                box = BoundingBox(
                    left = face.boundingBox.left.toFloat(),
                    top = face.boundingBox.top.toFloat(),
                    right = face.boundingBox.right.toFloat(),
                    bottom = face.boundingBox.bottom.toFloat(),
                ),
            )
        }
    }

    override suspend fun text(ref: MediaRef): List<TextBlock> = withContext(Dispatchers.Default) {
        val mediaId = ref.value
        textRecogniser.process(image(ref)).await().textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            TextBlock(
                mediaId = mediaId,
                text = block.text,
                box = BoundingBox(
                    box.left.toFloat(),
                    box.top.toFloat(),
                    box.right.toFloat(),
                    box.bottom.toFloat(),
                ),
            )
        }
    }

    /**
     * Landmark positions, normalised into the face's own box.
     *
     * Normalised so the vector describes the *shape* of a face rather than where it happened
     * to be in the frame or how large it was — the same person at two distances must not
     * become two people. A placeholder for the real embedding, and marked as one.
     */
    private fun landmarkVector(face: com.google.mlkit.vision.face.Face): FloatArray {
        val box = face.boundingBox
        val width = box.width().toFloat().coerceAtLeast(1f)
        val height = box.height().toFloat().coerceAtLeast(1f)

        val values = mutableListOf<Float>()
        LANDMARKS.forEach { type ->
            val point = face.getLandmark(type)?.position
            values += ((point?.x ?: box.exactCenterX()) - box.left) / width
            values += ((point?.y ?: box.exactCenterY()) - box.top) / height
        }
        values += face.headEulerAngleX / QUARTER_TURN
        values += face.headEulerAngleY / QUARTER_TURN
        values += face.headEulerAngleZ / QUARTER_TURN
        return values.toFloatArray()
    }

    private fun image(ref: MediaRef): InputImage =
        InputImage.fromFilePath(context, Uri.parse(ref.value))

    /**
     * Bridges a Play-services `Task` to a coroutine.
     *
     * Cancellable, because the night pass cancels its work within seconds of the phone
     * being unplugged (USER_JOURNEY.md § 3) and a detector that ran to completion anyway
     * would hold the CPU past the point the guards gave up.
     */
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> continuation.resume(result) }
        addOnFailureListener { error -> continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
    }

    private companion object {
        /** Below this, labels are noise — and noise is a wrong auto-album. */
        const val MIN_LABEL_CONFIDENCE = 0.7f

        /** As a fraction of the image's shorter side. */
        const val MIN_FACE_SIZE = 0.1f

        const val QUARTER_TURN = 90f

        val LANDMARKS = listOf(
            com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE,
            com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE,
            com.google.mlkit.vision.face.FaceLandmark.NOSE_BASE,
            com.google.mlkit.vision.face.FaceLandmark.MOUTH_LEFT,
            com.google.mlkit.vision.face.FaceLandmark.MOUTH_RIGHT,
            com.google.mlkit.vision.face.FaceLandmark.MOUTH_BOTTOM,
            com.google.mlkit.vision.face.FaceLandmark.LEFT_EAR,
            com.google.mlkit.vision.face.FaceLandmark.RIGHT_EAR,
            com.google.mlkit.vision.face.FaceLandmark.LEFT_CHEEK,
            com.google.mlkit.vision.face.FaceLandmark.RIGHT_CHEEK,
        )
    }
}
