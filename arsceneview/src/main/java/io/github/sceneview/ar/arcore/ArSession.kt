package io.github.sceneview.ar.arcore

import android.content.Context
import android.view.Display
import android.view.WindowManager
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.*
import io.github.sceneview.ar.ArSceneLifecycle
import io.github.sceneview.ar.ArSceneLifecycleObserver
import io.github.sceneview.ar.defaultApproximateDistanceMeters
import io.github.sceneview.utils.FrameTime

class ArSession(
    val cameraTextureId: Int,
    val lifecycle: ArSceneLifecycle,
    features: Set<Feature> = defaultFeatures,
    config: (Config) -> Unit = defaultConfig
) : Session(lifecycle.context, features), ArSceneLifecycleObserver {

    companion object {
        val defaultFocusMode = Config.FocusMode.AUTO
        val defaultPlaneFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        val defaultDepthEnabled = true
        val defaultInstantPlacementEnabled = true
        val defaultLightEstimationMode = LightEstimationMode.REALISTIC

        val defaultFeatures = setOf<Feature>()

        val defaultConfig = { config: Config ->
            config.focusMode = defaultFocusMode
            config.planeFindingMode = defaultPlaneFindingMode
            config.depthEnabled = defaultDepthEnabled
            config.instantPlacementEnabled = defaultInstantPlacementEnabled
            config.lightEstimationMode = defaultLightEstimationMode.sessionConfigMode
        }
    }

    private var hasSetTextureNames = false

    val display: Display by lazy {
        (lifecycle.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).getDefaultDisplay()
    }

    // We use device display sizes by default cause the onSurfaceChanged may be called after the
    // onResume and ARCore doesn't appreciate that we do things on session before calling
    // setDisplayGeometry()
    // = flickering screen if the phone is locked when starting the app
    var displayRotation = display.rotation
    var displayWidth = display.width
    var displayHeight = display.height

    var isResumed = false

    // TODO: See if it really has a performance impact
//    private var _config: Config? = null

    var hasAugmentedImageDatabase = false

    /**
     * ### The distance at which to create an InstantPlacementPoint
     *
     * This is only used while the tracking method for the returned point is
     * InstantPlacementPoint.TrackingMethod#SCREENSPACE_WITH_APPROXIMATE_DISTANCE.
     */
    val approximateDistanceMeters = defaultApproximateDistanceMeters

    /**
     * ### The most recent ARCore Frame if it is available
     *
     * The frame is updated at the beginning of each drawing frame.
     * Callers of this method should not retain a reference to the return value, since it will be
     * invalid to use the ARCore frame starting with the next frame.
     */
    var currentFrame: ArFrame? = null
        private set

    var allTrackables: List<Trackable> = listOf()

    init {
        lifecycle.addObserver(this)

        configure(config)
    }

    override fun onResume(owner: LifecycleOwner) {
        resume()
    }

    override fun resume() {
        isResumed = true
        super.resume()

        // Don't remove this code-block. It is important to correctly set the DisplayGeometry for
        // the ArCore-Session if for example the permission Dialog is shown on the screen.
        // If we remove this part, the camera is flickering if returned from the permission Dialog.
        setDisplayGeometry(displayRotation, displayWidth, displayHeight)
        lifecycle.dispatchEvent<ArSceneLifecycleObserver> { onArSessionResumed(this@ArSession) }
    }

    override fun onPause(owner: LifecycleOwner) {
        pause()
    }

    override fun pause() {
        isResumed = false
        super.pause()
    }

    /**
     * ### Explicitly close the ARCore session to release native resources.
     *
     * Review the API reference for important considerations before calling close() in apps with
     * more complicated lifecycle requirements: [Session.close]
     */
    override fun onDestroy(owner: LifecycleOwner) {
        close()
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        setDisplayGeometry(display.rotation, width, height)
    }

    override fun onArFrame(arFrame: ArFrame) {
        allTrackables = getAllTrackables(Trackable::class.java).toList()
    }

    fun update(frameTime: FrameTime): ArFrame? {
        // Texture names should only be set once on a GL thread unless they change.
        // This is done during updateFrame rather than init since the session is
        // not guaranteed to have been initialized during the execution of init.
        if (!hasSetTextureNames) {
            setCameraTextureName(cameraTextureId)
            hasSetTextureNames = true
        }
        // Check if no frame or same timestamp, no drawing.
        return super.update()?.takeIf {
            it.timestamp != (currentFrame?.frame?.timestamp ?: 0) && it.camera != null
        }?.let { frame ->
            ArFrame(this, frameTime, frame).also {
                currentFrame = it
            }
        }
    }

    override fun setDisplayGeometry(rotation: Int, widthPx: Int, heightPx: Int) {
        displayRotation = rotation
        displayWidth = widthPx
        displayHeight = heightPx
        if (isResumed) {
            super.setDisplayGeometry(displayRotation, widthPx, heightPx)
        }
    }

// TODO: See if it really has a performance impact
//    /**
//     * ### Gets the current config
//     *
//     * More specifically, returns a copy of the config most recently set by [configure].
//     *
//     * Note: if the session was not explicitly configured, a default configuration is returned
//     * (same as [com.gorisse.thomas.sceneview.ARCore.defaultSessionConfig].
//     */
//    override fun getConfig(): Config {
//        return _config ?: super.getConfig().also {
//            _config = it
//        }
//    }

    /**
     * ### Define the session config used by ARCore
     *
     * Prefer calling this method before the global (Activity or Fragment) onResume() cause the session
     * base configuration in made there.
     * Any later calls (after onSessionResumed()) to this function are not completely sure be taken in
     * account by ARCore (even if most of them will work)
     *
     * Please check that all your Session Config parameters are taken in account by ARCore at
     * runtime.
     *
     * @param config the apply block for the new config
     */
    fun configure(config: (Config) -> Unit) {
        val sessionConfig = this.config.apply {
            config(this)

            if (depthEnabled && !isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                depthMode = Config.DepthMode.DISABLED
            }

            // Light estimation is not usable with front camera
            if (cameraConfig.facingDirection == CameraConfig.FacingDirection.FRONT
                && lightEstimationMode != Config.LightEstimationMode.DISABLED
            ) {
                lightEstimationMode = Config.LightEstimationMode.DISABLED
            }
            hasAugmentedImageDatabase = (augmentedImageDatabase?.numImages ?: 0) > 0
        }
        super.configure(sessionConfig)
        lifecycle.dispatchEvent<ArSceneLifecycleObserver> {
            onArSessionConfigChanged(this@ArSession, sessionConfig)
        }
    }

    /**
     * ### ARCore light estimation configuration
     *
     * ARCore estimate lighting to provide directional light, ambient spherical harmonics,
     * and reflection cubemap estimation
     *
     * Light bounces off of surfaces differently depending on whether the surface has specular
     * (highly reflective) or diffuse (not reflective) properties.
     * For example, a metallic ball will be highly specular and reflect its environment, while
     * another ball painted a dull matte gray will be diffuse. Most real-world objects have a
     * combination of these properties — think of a scuffed-up bowling ball or a well-used credit
     * card.
     *
     * Reflective surfaces also pick up colors from the ambient environment. The coloring of an
     * object can be directly affected by the coloring of its environment. For example, a white ball
     * in a blue room will take on a bluish hue.
     *
     * The main directional light API calculates the direction and intensity of the scene's
     * main light source. This information allows virtual objects in your scene to show reasonably
     * positioned specular highlights, and to cast shadows in a direction consistent with other
     * visible real objects.
     *
     * LightEstimationConfig.SPECTACULAR vs LightEstimationConfig.REALISTIC mostly differs on the
     * reflections parts and you will mainly only see differences if your model has more metallic
     * than roughness material values.
     *
     * Adjust the based reference/factored lighting intensities and other values with:
     * - [io.github.sceneview.ar.ArSceneView.mainLight]
     * - [io.github.sceneview.environment.Environment.indirectLight]
     *
     * @see LightEstimationMode.REALISTIC
     * @see LightEstimationMode.SPECTACULAR
     * @see LightEstimationMode.AMBIENT_INTENSITY
     * @see LightEstimationMode.DISABLED
     */
    var lightEstimationMode = defaultLightEstimationMode
        set(value) {
            configure { config ->
                config.lightEstimationMode = value.sessionConfigMode
            }
            field = value
        }

    var focusMode: Config.FocusMode
        get() = config.focusMode
        set(value) {
            configure {
                it.focusMode = value
            }
        }

    var planeFindingEnabled: Boolean
        get() = config.planeFindingEnabled
        set(value) {
            configure {
                it.planeFindingEnabled = value
            }
        }

    var planeFindingMode: Config.PlaneFindingMode
        get() = config.planeFindingMode
        set(value) {
            configure {
                it.planeFindingMode = value
            }
        }

    var depthEnabled: Boolean
        get() = config.depthEnabled
        set(value) {
            configure {
                it.depthEnabled = value
            }
        }

    var instantPlacementEnabled: Boolean
        get() = config.instantPlacementEnabled
        set(value) {
            configure {
                it.instantPlacementEnabled = value
            }
        }

    /**
     * ### Retrieve the session tracked Planes
     */
    val allPlanes: List<Plane> get() = allTrackables.mapNotNull { it as? Plane }

    /**
     * ### Retrieve if the session has already tracked a Plane
     *
     * @return true if the session has tracked at least one Plane
     */
    val hasTrackedPlane: Boolean
        get() = allPlanes.filter {
            it.trackingState in listOf(TrackingState.TRACKING, TrackingState.PAUSED)
        }.isNotEmpty()

    /**
     * ### Retrieve if the session frame is currently tracking a Plane
     *
     * @return true if the session frame is fully tracking at least one Plane
     */
    val isTrackingPlane: Boolean get() = currentFrame?.isTrackingPlane == true

    /**
     * ### Retrieve the session tracked Augmented Images
     */
    val allAugmentedImages: List<AugmentedImage> get() = allTrackables.mapNotNull { it as? AugmentedImage }

    /**
     * ### Retrieve if the session frame is currently tracking an Augmented Image
     *
     * @return true if the session frame is fully tracking at least one Augmented Image
     */
    val isTrackingAugmentedImage: Boolean get() = currentFrame?.isTrackingAugmentedImage == true

    /**
     * ### Retrieve if the session has already tracked an Augmented Image
     *
     * @return true if the session has tracked at least one Augmented Image
     */
    val hasTrackedAugmentedImage: Boolean
        get() = allAugmentedImages.filter {
            it.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING &&
                    it.trackingState in listOf(TrackingState.TRACKING, TrackingState.PAUSED)
        }.isNotEmpty()

    /**
     * ### Retrieve the session tracked Augmented Faces
     */
    val allAugmentedFaces: List<AugmentedFace> get() = allTrackables.mapNotNull { it as? AugmentedFace }

    /**
     * ### Retrieve if the session frame is currently tracking an Augmented Face
     *
     * @return true if the session frame is fully tracking at least one Augmented Face
     */
    val isTrackingAugmentedFace: Boolean get() = currentFrame?.isTrackingAugmentedFace == true

    /**
     * ### Retrieve if the session has already tracked an Augmented Face
     *
     * @return true if the session has tracked at least one Augmented Face
     */
    val hasTrackedAugmentedFace: Boolean
        get() = allAugmentedFaces.filter {
            it.trackingState in listOf(TrackingState.TRACKING, TrackingState.PAUSED)
        }.isNotEmpty()
}

var Config.planeFindingEnabled
    get() = planeFindingMode != Config.PlaneFindingMode.DISABLED
    set(value) {
        planeFindingMode = if (value) {
            Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        } else {
            Config.PlaneFindingMode.DISABLED
        }
    }

/**
 * ### Enable or disable the [Config.DepthMode.AUTOMATIC]
 *
 * Not all devices support all modes. Use [Session.isDepthModeSupported] to determine whether the
 * current device and the selected camera support a particular depth mode.
 */
var Config.depthEnabled
    get() = depthMode == Config.DepthMode.AUTOMATIC
    set(value) {
        depthMode = if (value) {
            Config.DepthMode.AUTOMATIC
        } else {
            Config.DepthMode.DISABLED
        }
    }


/**
 * //TODO: Doc
 */
var Config.rawDepthEnabled
    get() = depthMode == Config.DepthMode.RAW_DEPTH_ONLY
    set(value) {
        depthMode = if (value) {
            Config.DepthMode.RAW_DEPTH_ONLY
        } else {
            Config.DepthMode.DISABLED
        }
    }

/**
 * TODO : Doc
 */
var Config.instantPlacementEnabled
    get() = instantPlacementMode != Config.InstantPlacementMode.DISABLED
    set(value) {
        instantPlacementMode = if (value) {
            Config.InstantPlacementMode.LOCAL_Y_UP
        } else {
            Config.InstantPlacementMode.DISABLED
        }
    }