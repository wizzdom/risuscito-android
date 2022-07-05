package it.cammino.risuscito.ui.activity

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.*
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Base64.DEFAULT
import android.util.Base64.encodeToString
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.postDelayed
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.getkeepsafe.taptargetview.TapTarget
import com.getkeepsafe.taptargetview.TapTargetSequence
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.crashlytics.ktx.setCustomKeys
import com.google.firebase.ktx.Firebase
import com.leinardi.android.speeddial.SpeedDialActionItem
import com.leinardi.android.speeddial.SpeedDialView
import it.cammino.risuscito.R
import it.cammino.risuscito.database.RisuscitoDatabase
import it.cammino.risuscito.database.entities.LocalLink
import it.cammino.risuscito.databinding.ActivityPaginaRenderBinding
import it.cammino.risuscito.playback.MusicService
import it.cammino.risuscito.ui.InitialScrollWebClient
import it.cammino.risuscito.ui.dialog.DialogState
import it.cammino.risuscito.ui.dialog.ProgressDialogFragment
import it.cammino.risuscito.ui.dialog.SimpleDialogFragment
import it.cammino.risuscito.utils.*
import it.cammino.risuscito.utils.LocaleManager.Companion.LANGUAGE_ENGLISH
import it.cammino.risuscito.utils.LocaleManager.Companion.LANGUAGE_POLISH
import it.cammino.risuscito.utils.LocaleManager.Companion.LANGUAGE_UKRAINIAN
import it.cammino.risuscito.utils.Utility.getExternalLink
import it.cammino.risuscito.utils.Utility.getExternalMediaIdByName
import it.cammino.risuscito.utils.Utility.mediaScan
import it.cammino.risuscito.utils.Utility.retrieveMediaFileLink
import it.cammino.risuscito.utils.extension.*
import it.cammino.risuscito.viewmodels.PaginaRenderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern


class PaginaRenderActivity : ThemeableActivity() {

    private lateinit var cambioAccordi: CambioAccordi
    private val mExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var mDownload: Boolean = false

    private val noChangesTabBarre: Boolean
        get() = mCantiViewModel.notaCambio == mCantiViewModel.mCurrentCanto?.savedTab && mCantiViewModel.barreCambio == mCantiViewModel.mCurrentCanto?.savedBarre

    private val mSharedPrefs: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(this)

    private val mRiuscitoDb: RisuscitoDatabase
        get() = RisuscitoDatabase.getInstance(this)

    private lateinit var mDownloader: Downloader

    private val mCantiViewModel: PaginaRenderViewModel by viewModels()
    private val progressDialogViewModel: ProgressDialogFragment.DialogViewModel by viewModels()
    private val downloaderViewModel: Downloader.DownloaderViewModel by viewModels()
    private val simpleDialogViewModel: SimpleDialogFragment.DialogViewModel by viewModels()
    private var url: String? = null
    private var personalUrl: String? = null
    private var localUrl: String? = null
    private var htmlContent: String? = null
    private var mLastPlaybackState: PlaybackStateCompat? = null
    private val mUpdateProgressTask = Runnable { updateProgress() }
    private var mScheduleFuture: ScheduledFuture<*>? = null
    private var mRegularFont: Typeface? = null
    private var mMediumFont: Typeface? = null
    private val mHandler = Handler(Looper.getMainLooper())
    private var resolveDeleteAudioConsent: ActivityResultLauncher<IntentSenderRequest>? = null
    private val mScrollDown: Runnable = object : Runnable {
        override fun run() {
            mCantiViewModel.speedValue?.let {
                try {
                    binding.cantoView.scrollBy(0, Integer.valueOf(it))
                } catch (e: NumberFormatException) {
                    binding.cantoView.scrollBy(0, 0)
                }

                mHandler.postDelayed(this, 700)
            } ?: Log.d(TAG, "attività chiusa o annullato lo scroll")
        }
    }

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                Log.d(
                    TAG,
                    "${getString(R.string.file_selected)}: $it",
                )
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.file_selected) + ": " + it,
                    Snackbar.LENGTH_SHORT
                )
                    .show()
                stopMedia()
                lifecycleScope.launch { insertLink(it.toString()) }
            }
        }

    // Callback that ensures that we are showing the controls
    private val mMediaControllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat) {
            Log.d(TAG, "onPlaybackStateChanged: a " + state.state)
            mLastPlaybackState = state
            when (state.state) {
                PlaybackStateCompat.STATE_PAUSED -> {
                    stopSeekbarUpdate()
                    showPlaying(false)
                    binding.musicSeekbar.isEnabled = true
                }
                PlaybackStateCompat.STATE_STOPPED -> {
                    stopSeekbarUpdate()
                    updateSeekBarValue(0F)
                    binding.musicSeekbar.isEnabled = false
                    showPlaying(false)
                }
                PlaybackStateCompat.STATE_ERROR -> {
                    binding.musicSeekbar.isVisible = true
                    binding.musicLoadingbar.isVisible = false
                    stopSeekbarUpdate()
                    updateSeekBarValue(0F)
                    binding.musicSeekbar.isEnabled = false
                    showPlaying(false)
                    Log.e(TAG, "onPlaybackStateChanged: " + state.errorMessage)
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        state.errorMessage,
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
                PlaybackStateCompat.STATE_PLAYING -> {
                    binding.musicSeekbar.isVisible = true
                    binding.musicLoadingbar.isVisible = false
                    scheduleSeekbarUpdate()
                    showPlaying(true)
                    binding.musicSeekbar.isEnabled = true
                }
                else -> {
                    Log.i(TAG, "Non gestito")
                }
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            Log.d(TAG, "onMetadataChanged")
            if (metadata != null) {
                updateSeekBarValueTo(
                    metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION).toFloat()
                )
                binding.musicSeekbar.isEnabled = true
            }
        }
    }

    private var mMediaBrowser: MediaBrowserCompat? = null
    private val mConnectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            Log.d(TAG, "onConnected")
            try {
                mMediaBrowser?.let {
                    val mediaController = MediaControllerCompat(
                        this@PaginaRenderActivity, it.sessionToken
                    )
                    MediaControllerCompat.setMediaController(
                        this@PaginaRenderActivity,
                        mediaController
                    )
                    mediaController.registerCallback(mMediaControllerCallback)
                    mLastPlaybackState = mediaController.playbackState
                    if (mLastPlaybackState?.state == PlaybackStateCompat.STATE_PLAYING) {
                        scheduleSeekbarUpdate()
                    }
                    showPlaying(mLastPlaybackState?.state == PlaybackStateCompat.STATE_PLAYING)
                    binding.musicSeekbar.isEnabled =
                        mLastPlaybackState?.state == PlaybackStateCompat.STATE_PLAYING || mLastPlaybackState?.state == PlaybackStateCompat.STATE_PAUSED

                    if (mediaController.metadata != null) {
                        Log.d(
                            TAG,
                            "onConnected: duration " + mediaController
                                .metadata
                                .getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
                        )
                        updateSeekBarValueTo(
                            mediaController
                                .metadata
                                .getLong(MediaMetadataCompat.METADATA_KEY_DURATION).toFloat()
                        )
                    }
                    Log.d(
                        TAG,
                        "onConnected: mLastPlaybackState.getPosition() ${mLastPlaybackState?.position}"
                    )
                    updateSeekBarValue(mLastPlaybackState?.position?.toFloat() ?: 0F)
                } ?: Log.e(TAG, "onConnected: mMediaBrowser is NULL")
            } catch (e: RemoteException) {
                Log.e(TAG, "onConnected: could not connect media controller", e)
            }

        }

        override fun onConnectionFailed() {
            Log.e(TAG, "onConnectionFailed")
        }

        override fun onConnectionSuspended() {
            Log.d(TAG, "onConnectionSuspended")
            val mediaController =
                MediaControllerCompat.getMediaController(this@PaginaRenderActivity)
            mediaController?.unregisterCallback(mMediaControllerCallback)
            MediaControllerCompat.setMediaController(this@PaginaRenderActivity, null)
        }
    }

    private val catalogReadyBR = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Implement UI change code here once notification is received
            try {
                Log.d(TAG, MusicService.MSG_RETRIEVE_DONE)
                val done = intent.getBooleanExtra(MusicService.MSG_RETRIEVE_DONE, false)
                Log.d(TAG, "MSG_RETRIEVE_DONE: $done")
                mCantiViewModel.retrieveDone = done
                showPlaying(false)
                binding.playSong.isEnabled = done
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, e.localizedMessage, e)
            }

        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: ")
        try {
            mMediaBrowser?.connect()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "onStart: mMediaBrowser connecting")
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: ")
        mMediaBrowser?.disconnect()
        val controller = MediaControllerCompat.getMediaController(this)
        controller?.unregisterCallback(mMediaControllerCallback)

    }

    private lateinit var binding: ActivityPaginaRenderBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!OSUtils.isObySamsung()) {
            // Set the transition name, which matches Activity A’s start view transition name, on
            // the root view.
            findViewById<View>(android.R.id.content).transitionName = "shared_element_container"

            // Attach a callback used to receive the shared elements from Activity A to be
            // used by the container transform transition.
            setEnterSharedElementCallback(MaterialContainerTransformSharedElementCallback())
            setExitSharedElementCallback(MaterialContainerTransformSharedElementCallback())

            // Set this Activity’s enter and return transition to a MaterialContainerTransform
            window.sharedElementEnterTransition = MaterialContainerTransform().apply {
                addTarget(android.R.id.content)
                duration = 700L
            }

            // Keep system bars (status bar, navigation bar) persistent throughout the transition.
            window.sharedElementsUseOverlay = false
        }

        super.onCreate(savedInstanceState)
        binding = ActivityPaginaRenderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mDownloader = Downloader(this)

        mRegularFont =
            ResourcesCompat.getFont(this, getTypedValueResId(R.attr.risuscito_regular_font))
        mMediumFont =
            ResourcesCompat.getFont(this, getTypedValueResId(R.attr.risuscito_medium_font))

        setSupportActionBar(binding.risuscitoToolbar)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // recupera il numero della pagina da visualizzare dal parametro passato dalla chiamata
        val bundle = this.intent.extras
        mCantiViewModel.pagina = mCantiViewModel.pagina
            ?: bundle?.getCharSequence(Utility.PAGINA, StringUtils.EMPTY)?.toString()
        mCantiViewModel.idCanto = bundle?.getInt(Utility.ID_CANTO) ?: return

        Log.d(TAG, "LINGUA CTX: ${resources.systemLocale.language}")
        Log.d(TAG, "LINGUA BASE: ${baseContext.resources.systemLocale.language}")
        cambioAccordi = CambioAccordi(this)

        try {
            Firebase.crashlytics.setCustomKeys {
                key("pagina_canto", mCantiViewModel.pagina ?: StringUtils.EMPTY)
                key("lingua", resources.systemLocale.language)
            }

            mCantiViewModel.primaNota =
                mCantiViewModel.primaNota.ifEmpty {
                    CambioAccordi.recuperaPrimoAccordo(
                        resources.openRawResource(
                            Utility.getResId(
                                mCantiViewModel.pagina,
                                R.raw::class.java
                            )
                        ),
                        resources.systemLocale.language
                    )
                }
            mCantiViewModel.primoBarre =
                mCantiViewModel.primoBarre.ifEmpty {
                    cambioAccordi.recuperaBarre(
                        resources.openRawResource(
                            Utility.getResId(
                                mCantiViewModel.pagina,
                                R.raw::class.java
                            )
                        ),
                        resources.systemLocale.language
                    )
                }
        } catch (e: IOException) {
            Log.e(TAG, e.localizedMessage, e)
        }

        binding.musicSeekbar.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                stopSeekbarUpdate()
            }

            override fun onStopTrackingTouch(slider: Slider) {
                val controller = MediaControllerCompat.getMediaController(this@PaginaRenderActivity)
                controller?.transportControls?.seekTo(slider.value.toLong())
                    ?: return
                scheduleSeekbarUpdate()
            }
        })

        binding.musicSeekbar.addOnChangeListener { _, value, _ ->
            val time = String.format(
                resources.systemLocale,
                "%02d:%02d",
                TimeUnit.MILLISECONDS.toMinutes(value.toLong()),
                TimeUnit.MILLISECONDS.toSeconds(value.toLong()) - TimeUnit.MINUTES.toSeconds(
                    TimeUnit.MILLISECONDS.toMinutes(value.toLong())
                )
            )
            binding.timeText.text = time
        }

        binding.speedSeekbar.addOnChangeListener { _, value, _ ->
            mCantiViewModel.speedValue = value.toInt().toString()
            binding.sliderText.text = getString(R.string.percent_progress, value.toInt())
            Log.d(javaClass.toString(), "speedValue cambiato! " + mCantiViewModel.speedValue)
        }

        showScrolling(false)

        lifecycleScope.launch { retrieveData() }

        binding.playSong.setOnClickListener {
            val controller = MediaControllerCompat.getMediaController(this)
            val stateObj = controller.playbackState
            val state = stateObj?.state ?: PlaybackStateCompat.STATE_NONE
            Log.d(TAG, "playPause: Button pressed, in state $state")

            when (state) {
                PlaybackStateCompat.STATE_STOPPED, PlaybackStateCompat.STATE_NONE -> {
                    playFromId(mCantiViewModel.idCanto.toString())
                }
                PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.STATE_BUFFERING, PlaybackStateCompat.STATE_CONNECTING -> {
                    pauseMedia()
                }
                PlaybackStateCompat.STATE_PAUSED -> {
                    playMedia()
                }
            }
        }

        binding.playScroll.setOnClickListener { v ->
            if (v.isSelected) {
                showScrolling(false)
                mCantiViewModel.scrollPlaying = false
                mHandler.removeCallbacks(mScrollDown)
            } else {
                showScrolling(true)
                mCantiViewModel.scrollPlaying = true
                mScrollDown.run()
            }
        }

        if (savedInstanceState == null)
            mCantiViewModel.mostraAudio = mSharedPrefs.getBoolean(Utility.SHOW_AUDIO, true)

        // Connect a media browser just to get the media session token. There are other ways
        // this can be done, for example by sharing the session token directly.
        mMediaBrowser = MediaBrowserCompat(
            this, ComponentName(
                this, MusicService::
                class.java
            ), mConnectionCallback, null
        )

        onBackPressedDispatcher.addCallback(this) {
            onBackPressedAction()
        }

        progressDialogViewModel.state.observe(this) {
            Log.d(TAG, "progressDialogViewModel state $it")
            if (!progressDialogViewModel.handled) {
                when (it) {
                    is DialogState.Positive -> {
                        when (progressDialogViewModel.mTag) {
                            DOWNLOAD_MP3 -> {
                                progressDialogViewModel.handled = true
                                mDownloader.cancel()
                            }
                        }
                    }
                    is DialogState.Negative -> {
                        progressDialogViewModel.handled = true
                    }
                }
            }
        }

        simpleDialogViewModel.state.observe(this) {
            Log.d(TAG, "simpleDialogViewModel state $it")
            if (!simpleDialogViewModel.handled) {
                when (it) {
                    is DialogState.Positive -> {
                        when (simpleDialogViewModel.mTag) {
                            DELETE_LINK -> {
                                simpleDialogViewModel.handled = true
                                Snackbar.make(
                                    findViewById(android.R.id.content),
                                    R.string.delink_delete,
                                    Snackbar.LENGTH_SHORT
                                )
                                    .show()
                                stopMedia()
                                lifecycleScope.launch { deleteLink() }
                            }
                            DELETE_MP3 -> {
                                simpleDialogViewModel.handled = true
                                localUrl?.let { url ->
                                    stopMedia()
                                    if (isDefaultLocationPublic && OSUtils.hasQ()) {
                                        mCantiViewModel.toDelete = ContentUris.withAppendedId(
                                            MediaStore.Audio.Media
                                                .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                                            getExternalMediaIdByName(this, url)
                                        )
                                        Log.d(TAG, "DELETE_MP3 toDelete: $mCantiViewModel.toDelete")
                                        try {
                                            deleteAudio(mCantiViewModel.toDelete!!)
                                            mCantiViewModel.toDelete = null
                                        } catch (securityException: SecurityException) {
                                            if (OSUtils.hasQ()) {
                                                val recoverableSecurityException =
                                                    securityException as?
                                                            RecoverableSecurityException
                                                        ?: throw RuntimeException(
                                                            securityException.message,
                                                            securityException
                                                        )
                                                val intentSender =
                                                    recoverableSecurityException.userAction.actionIntent.intentSender
                                                resolveDeleteAudioConsent?.launch(
                                                    IntentSenderRequest.Builder(intentSender)
                                                        .build()
                                                )
                                            } else {
                                                throw RuntimeException(
                                                    securityException.message,
                                                    securityException
                                                )
                                            }
                                        }
                                    } else {
                                        val fileToDelete = File(url)
                                        if (fileToDelete.delete()) {
                                            if (fileToDelete.absolutePath.contains("/Risuscit")) {
                                                // initiate media scan and put the new things into the path array to
                                                // make the scanner aware of the location and the files you want to see
                                                MediaScannerConnection.scanFile(
                                                    applicationContext,
                                                    arrayOf(fileToDelete.absolutePath),
                                                    null,
                                                    null
                                                )
                                            }
                                            Snackbar.make(
                                                findViewById(android.R.id.content),
                                                R.string.file_delete,
                                                Snackbar.LENGTH_SHORT
                                            )
                                                .show()
                                        } else
                                            Snackbar.make(
                                                findViewById(android.R.id.content),
                                                R.string.error,
                                                Snackbar.LENGTH_SHORT
                                            )
                                                .show()
                                    }
                                }
                                refreshCatalog()
                                lifecycleScope.launch { checkRecordState() }
                            }
                            DOWNLINK_CHOOSE -> {
                                simpleDialogViewModel.handled = true
                                if (isDefaultLocationPublic) {
                                    if (hasStorageAccess)
                                    // Have permission, do the thing!
                                        startDownload(true)
                                    else {
                                        mSharedPrefs.edit { putString(Utility.SAVE_LOCATION, "0") }
                                        Snackbar.make(
                                            findViewById(android.R.id.content),
                                            R.string.forced_private,
                                            Snackbar.LENGTH_SHORT
                                        )
                                            .show()
                                        startDownload(false)
                                    }
                                } else
                                    startDownload(false)
                            }
                            ONLY_LINK -> {
                                simpleDialogViewModel.handled = true
                                pickAudio.launch(arrayOf(MP3_MIME_TYPE))
                            }
                            SAVE_TAB -> {
                                simpleDialogViewModel.handled = true
                                if (mCantiViewModel.scrollPlaying) {
                                    showScrolling(false)
                                    mHandler.removeCallbacks(mScrollDown)
                                }
                                saveZoom(andSpeedAlso = true, andSaveTabAlso = true)
                                finishAfterTransitionWrapper()
                            }
                        }
                    }
                    is DialogState.Negative -> {
                        when (simpleDialogViewModel.mTag) {
                            SAVE_TAB -> {
                                simpleDialogViewModel.handled = true
                                if (mCantiViewModel.scrollPlaying) {
                                    showScrolling(false)
                                    mHandler.removeCallbacks(mScrollDown)
                                }
                                saveZoom(andSpeedAlso = true, andSaveTabAlso = false)
                                finishAfterTransitionWrapper()
                            }
                        }
                    }
                }
            }
        }

        downloaderViewModel.state.observe(this) {
            Log.d(TAG, "downloaderViewModel state $it")
            if (!downloaderViewModel.handled) {
                when (it) {
                    is DownloadState.Progress -> {
                        Log.d(TAG, "DownloadListener update: ${it.progress}")
                        downloaderViewModel.handled = true
                        val sFragment = ProgressDialogFragment.findVisible(
                            this@PaginaRenderActivity,
                            DOWNLOAD_MP3
                        )
                        sFragment?.setProgress(it.progress)
                    }
                    is DownloadState.Completed -> {
                        Log.d(TAG, "DownloadListener onComplete")
                        downloaderViewModel.handled = true
                        dismissProgressDialog(DOWNLOAD_MP3)
                        // initiate media scan and put the new things into the path array to
                        // make the scanner aware of the location and the files you want to see
                        if (isDefaultLocationPublic && !OSUtils.hasQ())
                            mediaScan(this@PaginaRenderActivity, url.orEmpty())
                        Snackbar.make(
                            findViewById(android.R.id.content),
                            R.string.download_completed,
                            Snackbar.LENGTH_SHORT
                        )
                            .show()
                        stopMedia()
                        refreshCatalog()
                        lifecycleScope.launch { checkRecordState() }
                    }
                    is DownloadState.Error -> {
                        Log.d(TAG, "DownloadListener onError: ${it.message}")
                        downloaderViewModel.handled = true
                        dismissProgressDialog(DOWNLOAD_MP3)
                        Snackbar.make(
                            findViewById(android.R.id.content),
                            " ${getString(R.string.download_error)}: $it.message",
                            Snackbar.LENGTH_SHORT
                        )
                            .show()
                    }
                }
            }
        }

        resolveDeleteAudioConsent = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                mCantiViewModel.toDelete?.let {
                    deleteAudio(it)
                    mCantiViewModel.toDelete = null
                }
            } else {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.external_storage_denied),
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.canto, menu)
        super.onCreateOptionsMenu(menu)
        Log.d(
            TAG,
            "onCreateOptionsMenu - INTRO_PAGINARENDER: ${
                mSharedPrefs.getBoolean(
                    Utility.INTRO_PAGINARENDER,
                    false
                )
            }"
        )
        if (!mSharedPrefs.getBoolean(Utility.INTRO_PAGINARENDER, false)) {
            Handler(Looper.getMainLooper()).postDelayed(1500) {
                playIntro(binding.musicButtons.isVisible)
            }
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home ->
                if (mCantiViewModel.notaCambio.isEmpty()
                    || mCantiViewModel.mCurrentCanto?.savedTab == null
                    || mCantiViewModel.barreCambio.isEmpty()
                    || mCantiViewModel.mCurrentCanto?.savedBarre == null
                    || noChangesTabBarre
                ) {
                    if (mCantiViewModel.scrollPlaying) {
                        showScrolling(false)
                        mHandler.removeCallbacks(mScrollDown)
                    }
                    saveZoom(andSpeedAlso = true, andSaveTabAlso = false)
                    finishAfterTransitionWrapper()
                    return true
                } else {
                    SimpleDialogFragment.show(
                        SimpleDialogFragment.Builder(
                            this, SAVE_TAB
                        )
                            .title(R.string.dialog_save_tab_title)
                            .icon(R.drawable.save_24px)
                            .content(R.string.dialog_save_tab)
                            .positiveButton(R.string.save_exit_confirm)
                            .negativeButton(R.string.discard_exit_confirm),
                        supportFragmentManager
                    )
                    return true
                }
            R.id.action_exp_pdf -> {
                lifecycleScope.launch { exportPdf() }
                return true
            }
            R.id.action_help_canto -> {
                playIntro(binding.musicButtons.isVisible)
                return true
            }
            R.id.action_save_tab -> {
                if (!mCantiViewModel.mCurrentCanto?.savedTab.equals(
                        mCantiViewModel.notaCambio,
                        ignoreCase = true
                    )
                ) {
                    mCantiViewModel.mCurrentCanto?.savedTab = mCantiViewModel.notaCambio
                    lifecycleScope.launch { updateCanto(1) }
                } else {
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        R.string.tab_not_saved,
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
                return true
            }
            R.id.action_reset_tab -> {
                mCantiViewModel.notaCambio = mCantiViewModel.primaNota
                val convMap = cambioAccordi.diffSemiToni(
                    mCantiViewModel.primaNota,
                    mCantiViewModel.notaCambio
                )
                var convMin: HashMap<String, String>? = null
                if (resources.systemLocale.language.equals(
                        LANGUAGE_UKRAINIAN,
                        ignoreCase = true
                    ) || resources.systemLocale.language.equals(
                        LANGUAGE_POLISH,
                        ignoreCase = true
                    )
                )
                    convMin = cambioAccordi.diffSemiToniMin(
                        mCantiViewModel.primaNota,
                        mCantiViewModel.notaCambio
                    )
                saveZoom(andSpeedAlso = false, andSaveTabAlso = false)
                if (convMap != null) {
                    loadContentIntoWebView(
                        cambiaAccordi(
                            convMap,
                            mCantiViewModel.barreCambio,
                            convMin
                        )
                    )
                } else
                    loadContentIntoWebView(
                        readTextFromResource(
                            mCantiViewModel.pagina
                                ?: NO_CANTO
                        )
                    )
                mCantiViewModel.mCurrentCanto?.let {
                    if (it.zoom > 0)
                        binding.cantoView.setInitialScale(it.zoom)
                }
                return true
            }
            R.id.action_save_barre -> {
                if (!mCantiViewModel.mCurrentCanto?.savedBarre.equals(
                        mCantiViewModel.barreCambio,
                        ignoreCase = true
                    )
                ) {
                    mCantiViewModel.mCurrentCanto?.savedBarre = mCantiViewModel.barreCambio
                    lifecycleScope.launch { updateCanto(2) }
                } else {
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        R.string.barre_not_saved,
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                }
                return true
            }
            R.id.action_reset_barre -> {
                mCantiViewModel.barreCambio = mCantiViewModel.primoBarre
                val convMap1 = cambioAccordi.diffSemiToni(
                    mCantiViewModel.primaNota,
                    mCantiViewModel.notaCambio
                )
                var convMin1: HashMap<String, String>? = null
                if (resources.systemLocale.language.equals(
                        LANGUAGE_UKRAINIAN,
                        ignoreCase = true
                    ) || resources.systemLocale.language.equals(
                        LANGUAGE_POLISH,
                        ignoreCase = true
                    )
                )
                    convMin1 = cambioAccordi.diffSemiToniMin(
                        mCantiViewModel.primaNota,
                        mCantiViewModel.notaCambio
                    )
                saveZoom(andSpeedAlso = false, andSaveTabAlso = false)
                if (convMap1 != null) {
                    loadContentIntoWebView(
                        cambiaAccordi(
                            convMap1,
                            mCantiViewModel.barreCambio,
                            convMin1
                        )
                    )
                } else
                    loadContentIntoWebView(
                        readTextFromResource(
                            mCantiViewModel.pagina
                                ?: NO_CANTO
                        )
                    )
                mCantiViewModel.mCurrentCanto?.let {
                    if (it.zoom > 0)
                        binding.cantoView.setInitialScale(it.zoom)
                }
                return true
            }
            else -> {
                if (item.groupId == R.id.menu_gruppo_note) {
                    mCantiViewModel.notaCambio = item.titleCondensed.toString()
                    val convMap2 = cambioAccordi.diffSemiToni(
                        mCantiViewModel.primaNota,
                        mCantiViewModel.notaCambio
                    )
                    var convMin2: HashMap<String, String>? = null
                    if (resources.systemLocale.language.equals(
                            LANGUAGE_UKRAINIAN,
                            ignoreCase = true
                        ) || resources.systemLocale.language.equals(
                            LANGUAGE_POLISH,
                            ignoreCase = true
                        )
                    )
                        convMin2 = cambioAccordi.diffSemiToniMin(
                            mCantiViewModel.primaNota,
                            mCantiViewModel.notaCambio
                        )
                    saveZoom(andSpeedAlso = false, andSaveTabAlso = false)
                    if (convMap2 != null) {
                        loadContentIntoWebView(
                            cambiaAccordi(
                                convMap2,
                                mCantiViewModel.barreCambio,
                                convMin2
                            )
                        )
                    } else
                        loadContentIntoWebView(
                            readTextFromResource(
                                mCantiViewModel.pagina
                                    ?: NO_CANTO
                            )
                        )
                    mCantiViewModel.mCurrentCanto?.let {
                        if (it.zoom > 0)
                            binding.cantoView.setInitialScale(it.zoom)
                    }
                    return true
                }
                if (item.groupId == R.id.menu_gruppo_barre) {
                    mCantiViewModel.barreCambio = item.titleCondensed.toString()
                    val convMap3 = cambioAccordi.diffSemiToni(
                        mCantiViewModel.primaNota,
                        mCantiViewModel.notaCambio
                    )
                    var convMin3: HashMap<String, String>? = null
                    if (resources.systemLocale.language.equals(
                            LANGUAGE_UKRAINIAN,
                            ignoreCase = true
                        ) || resources.systemLocale.language.equals(
                            LANGUAGE_POLISH,
                            ignoreCase = true
                        )
                    )
                        convMin3 = cambioAccordi.diffSemiToniMin(
                            mCantiViewModel.primaNota,
                            mCantiViewModel.notaCambio
                        )
                    saveZoom(andSpeedAlso = false, andSaveTabAlso = false)
                    if (convMap3 != null) {
                        loadContentIntoWebView(
                            cambiaAccordi(
                                convMap3,
                                mCantiViewModel.barreCambio,
                                convMin3
                            )
                        )
                    } else
                        loadContentIntoWebView(
                            readTextFromResource(
                                mCantiViewModel.pagina
                                    ?: NO_CANTO
                            )
                        )
                    mCantiViewModel.mCurrentCanto?.let {
                        if (it.zoom > 0)
                            binding.cantoView.setInitialScale(it.zoom)
                    }
                    return true
                }
            }
        }
        return false
    }

    private fun onBackPressedAction() {
        Log.d(TAG, "onBackPressed: ")

        if (binding.fabCanti.isOpen) {
            binding.fabCanti.close()
            return
        }

        if (mCantiViewModel.notaCambio.isEmpty()
            || mCantiViewModel.mCurrentCanto?.savedTab == null
            || mCantiViewModel.barreCambio.isEmpty()
            || mCantiViewModel.mCurrentCanto?.savedBarre == null
            || noChangesTabBarre
        ) {
            if (mCantiViewModel.scrollPlaying) {
                showScrolling(false)
                mHandler.removeCallbacks(mScrollDown)
            }
            saveZoom(andSpeedAlso = true, andSaveTabAlso = false)
            finishAfterTransitionWrapper()
        } else {
            SimpleDialogFragment.show(
                SimpleDialogFragment.Builder(
                    this, SAVE_TAB
                )
                    .title(R.string.dialog_save_tab_title)
                    .icon(R.drawable.save_24px)
                    .content(R.string.dialog_save_tab)
                    .positiveButton(R.string.save_exit_confirm)
                    .negativeButton(R.string.discard_exit_confirm),
                supportFragmentManager
            )
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        binding.fabCanti.expansionMode =
            if (isFabExpansionLeft) SpeedDialView.ExpansionMode.LEFT else SpeedDialView.ExpansionMode.TOP
    }

    public override fun onResume() {
        super.onResume()

        Log.d(TAG, "onResume: ")

        binding.musicControls.isVisible = mCantiViewModel.mostraAudio

        val mLocalBroadcastManager = LocalBroadcastManager.getInstance(applicationContext)
        // registra un receiver per ricevere la notifica di preparazione della registrazione
//        mLocalBroadcastManager.registerReceiver(
//                downloadPosBRec, IntentFilter(DownloadService.BROADCAST_DOWNLOAD_PROGRESS))
//        mLocalBroadcastManager.registerReceiver(
//                downloadCompletedBRec, IntentFilter(DownloadService.BROADCAST_DOWNLOAD_COMPLETED))
//        mLocalBroadcastManager.registerReceiver(downloadErrorBRec, IntentFilter(DownloadService.BROADCAST_DOWNLOAD_ERROR))
        mLocalBroadcastManager.registerReceiver(
            catalogReadyBR,
            IntentFilter(MusicService.BROADCAST_RETRIEVE_ASYNC)
        )
    }

    override fun onPause() {
        super.onPause()
        val mLocalBroadcastManager = LocalBroadcastManager.getInstance(applicationContext)
//        mLocalBroadcastManager.unregisterReceiver(downloadPosBRec)
//        mLocalBroadcastManager.unregisterReceiver(downloadCompletedBRec)
//        mLocalBroadcastManager.unregisterReceiver(downloadErrorBRec)
        mLocalBroadcastManager.unregisterReceiver(catalogReadyBR)
    }

    public override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy(): $isFinishing")
        if (isFinishing) stopMedia()
        stopSeekbarUpdate()
        mExecutorService.shutdown()
    }

    // recupera e setta il record per la registrazione
    private fun getRecordLink() {
        url = if (!mCantiViewModel.mCurrentCanto?.link.isNullOrEmpty())
            getString(Utility.getResId(mCantiViewModel.mCurrentCanto?.link, R.string::class.java))
        else
            StringUtils.EMPTY

        val mDao = mRiuscitoDb.localLinksDao()
        val localLink = mDao.getLocalLinkByCantoId(mCantiViewModel.idCanto)

        personalUrl = localLink?.localPath.orEmpty()
    }

    private fun saveZoom(andSpeedAlso: Boolean, andSaveTabAlso: Boolean) {
        mCantiViewModel.mCurrentCanto?.let {
            @Suppress("DEPRECATION")
            it.zoom = (binding.cantoView.scale * 100).toInt()
            it.scrollX = binding.cantoView.scrollX
            it.scrollY = binding.cantoView.scrollY

            if (andSpeedAlso) it.savedSpeed = mCantiViewModel.speedValue
                ?: "2"

            if (andSaveTabAlso) {
                it.savedBarre = mCantiViewModel.barreCambio
                it.savedTab = mCantiViewModel.notaCambio
            }

            lifecycleScope.launch { updateCanto(0) }
        }
    }

    private fun cambiaAccordi(
        conversione: HashMap<String, String>?,
        barre: String?,
        conversioneMin: HashMap<String, String>?
    ): String? {
        val cantoTrasportato = StringBuffer()

        var barreScritto = false

        try {
            val br = BufferedReader(
                InputStreamReader(
                    resources.openRawResource(
                        Utility.getResId(
                            mCantiViewModel.pagina,
                            R.raw::class.java
                        )
                    ), ECONDING_UTF8
                )
            )

            var line: String? = br.readLine()

            val language = resources.systemLocale.language

            val pattern: Pattern
            var patternMinore: Pattern? = null

            when (language) {
//                LANGUAGE_ITALIAN, LANGUAGE_TURKISH -> pattern = Pattern.compile("Do#|Do|Re|Mib|Mi|Fa#|Fa|Sol#|Sol|La|Sib|Si")
                LANGUAGE_UKRAINIAN -> {
                    pattern = Pattern.compile("Cis|C|D|Eb|E|Fis|F|Gis|G|A|B|H")
                    // inserito spazio prima di "b" per evitare che venga confuso con "Eb" o "eb"
                    patternMinore = Pattern.compile("cis|c|d|eb|e|fis|f|gis|g|a| b|h")
                }
                LANGUAGE_POLISH -> {
                    pattern = Pattern.compile("Cis|C|D|Dis|E|Fis|F|Gis|G|A|B|H")
                    // inserito spazio prima di "b" per evitare che venga confuso con "Eb" o "eb"
                    patternMinore = Pattern.compile("cis|c|d|dis|e|fis|f|gis|g|a| b|h")
                }
                LANGUAGE_ENGLISH -> pattern = Pattern.compile("C#|C|D|Eb|E|F#|F|G#|G|A|Bb|B")
                else -> pattern = Pattern.compile("Do#|Do|Re|Mib|Mi|Fa#|Fa|Sol#|Sol|La|Sib|Si")
            }

            // serve per segnarsi se si è già evidenziato il primo accordo del testo
            var notaHighlighed = false

            while (line != null) {
                Log.v(TAG, "RIGA DA ELAB: $line")
                if (line.contains("A13F3C") && !line.contains("<H2>") && !line.contains("<H4>")) {
                    if (language.equals(LANGUAGE_UKRAINIAN, ignoreCase = true) || language.equals(
                            LANGUAGE_ENGLISH,
                            ignoreCase = true
                        ) || language.equals(LANGUAGE_POLISH, ignoreCase = true)
                    ) {
                        line = line.replace("</FONT><FONT COLOR=\"#A13F3C\">".toRegex(), "<K>")
                        line = line.replace("</FONT><FONT COLOR=\"#000000\">".toRegex(), "<K2>")
                    }
                    val matcher = pattern.matcher(line)
                    val sb = StringBuffer()
                    val sb2 = StringBuffer()
                    while (matcher.find()) matcher.appendReplacement(
                        sb, conversione?.get(
                            matcher.group(0).orEmpty()
                        ).orEmpty()
                    )
                    matcher.appendTail(sb)
                    if (language.equals(LANGUAGE_UKRAINIAN, ignoreCase = true) || language.equals(
                            LANGUAGE_POLISH,
                            ignoreCase = true
                        )
                    ) {
                        val matcherMin = patternMinore?.matcher(sb.toString())
                        while (matcherMin?.find() == true)
                            matcherMin.appendReplacement(
                                sb2, conversioneMin?.get(
                                    matcherMin.group(0).orEmpty()
                                ).orEmpty()
                            )
                        matcherMin?.appendTail(sb2)
                        line = sb2.toString()
                        //                        Log.d(TAG, "RIGA ELAB 1: " + line);
                        //                        Log.d(TAG, "notaHighlighed: " + notaHighlighed);
                        //                        Log.d(TAG, "notaCambio: " + notaCambio);
                        //                        Log.d(TAG, "primaNota: " + primaNota);
                        if (!notaHighlighed) {
                            if (!mCantiViewModel.primaNota.equals(
                                    mCantiViewModel.notaCambio,
                                    ignoreCase = true
                                )
                            ) {
                                if (Utility.isLowerCase(mCantiViewModel.primaNota[0])) {
                                    var notaCambioMin = mCantiViewModel.notaCambio
                                    notaCambioMin = if (notaCambioMin.length == 1)
                                        notaCambioMin.lowercase(resources.systemLocale)
                                    else
                                        notaCambioMin.substring(0, 1)
                                            .lowercase(resources.systemLocale) + notaCambioMin.substring(
                                            1
                                        )
                                    line = line.replaceFirst(
                                        notaCambioMin.toRegex(),
                                        "<SPAN STYLE=\"BACKGROUND-COLOR:#FFFF00\">$notaCambioMin</SPAN>"
                                    )
                                } else
                                    line = line.replaceFirst(
                                        mCantiViewModel.notaCambio.toRegex(),
                                        "<SPAN STYLE=\"BACKGROUND-COLOR:#FFFF00\">"
                                                + mCantiViewModel.notaCambio
                                                + "</SPAN>"
                                    )
                                notaHighlighed = true
                            }
                        }
                        //                        Log.d(TAG, "RIGA ELAB 2: " + line);
                        line = line.replace("<K>".toRegex(), "</FONT><FONT COLOR='#A13F3C'>")
                        line = line.replace("<K2>".toRegex(), "</FONT><FONT COLOR='#000000'>")
                        //                        Log.d(TAG, "RIGA ELAB 3: " + line);
                    } else {
                        line = sb.toString()
                        if (!notaHighlighed) {
                            if (!mCantiViewModel.primaNota.equals(
                                    mCantiViewModel.notaCambio,
                                    ignoreCase = true
                                )
                            ) {
                                line = line.replaceFirst(
                                    mCantiViewModel.notaCambio.toRegex(),
                                    "<SPAN STYLE=\"BACKGROUND-COLOR:#FFFF00\">"
                                            + mCantiViewModel.notaCambio
                                            + "</SPAN>"
                                )
                                notaHighlighed = true
                            }
                        }

                        if (language.equals(LANGUAGE_ENGLISH, ignoreCase = true)) {
                            line = line.replace("<K>".toRegex(), "</FONT><FONT COLOR='#A13F3C'>")
                            line = line.replace("<K2>".toRegex(), "</FONT><FONT COLOR='#000000'>")
                        }
                    }
                    cantoTrasportato.append(line)
                    cantoTrasportato.append("\n")
                } else {
                    if (line.contains("<H3>")) {
                        if (barre != null && barre != "0") {
                            if (!barreScritto) {
                                val oldLine: String = if (!barre.equals(
                                        mCantiViewModel.primoBarre,
                                        ignoreCase = true
                                    )
                                ) {
                                    ("<H4><SPAN STYLE=\"BACKGROUND-COLOR:#FFFF00\"><FONT COLOR=\"#A13F3C\"><I>"
                                            + getString(R.string.barre_al_tasto, barre)
                                            + "</I></FONT></SPAN></H4>")
                                } else {
                                    ("<H4><FONT COLOR=\"#A13F3C\"><I>"
                                            + getString(R.string.barre_al_tasto, barre)
                                            + "</I></FONT></H4>")
                                }
                                cantoTrasportato.append(oldLine)
                                cantoTrasportato.append("\n")
                                barreScritto = true
                            }
                        }
                        cantoTrasportato.append(line)
                        cantoTrasportato.append("\n")
                    } else {
                        if (!line.contains(getString(R.string.barre_search_string))) {
                            cantoTrasportato.append(line)
                            cantoTrasportato.append("\n")
                        }
                    }
                }
                line = br.readLine()
            }
            br.close()
            Log.i(TAG, "cambiaAccordi cantoTrasportato -> $cantoTrasportato")
            return cantoTrasportato.toString()
        } catch (e: Exception) {
            Log.e(TAG, e.localizedMessage, e)
            return null
        }

    }

    private fun startDownload(isExternal: Boolean) {
        Log.d(TAG, "startDownload - isExternal: $isExternal")
        Log.d(
            TAG,
            "startDownload - isExternalStorageWritable: ${Utility.isExternalStorageWritable}"
        )
        if ((isExternal && Utility.isExternalStorageWritable) || !isExternal) {
            val localFilePath = this.filesDir.toString() + "/" + Utility.filterMediaLink(url)
            ProgressDialogFragment.show(
                ProgressDialogFragment.Builder(DOWNLOAD_MP3).apply {
                    title = R.string.save_file
                    content = R.string.download_running
                    icon = R.drawable.file_download_24px
                    progressIndeterminate = false
                    progressMax = 100
                    positiveButton = R.string.cancel
                },
                supportFragmentManager
            )
            lifecycleScope.launch(Dispatchers.IO) {
                mDownloader.startSaving(
                    url, if (isExternal) getExternalLink(
                        url.orEmpty()
                    ) else localFilePath, isExternal
                )
            }
        } else
            Snackbar.make(
                findViewById(android.R.id.content),
                R.string.no_memory_writable,
                Snackbar.LENGTH_SHORT
            )
                .show()
    }

    private fun showPlaying(started: Boolean) {
        Log.d(TAG, "showPlaying: ")
//        (binding.playSong as? MaterialButton)?.setIconResource(if (started) R.drawable.baseline_pause_24 else R.drawable.baseline_play_arrow_24)
        val anim = if (started) AnimatedVectorDrawableCompat.create(
            this,
            R.drawable.play_to_pause_anim
        ) else AnimatedVectorDrawableCompat.create(this, R.drawable.pause_to_play_anim)
        (binding.playSong as? MaterialButton)?.icon = anim
        anim?.start()
        binding.playSong.isVisible = mCantiViewModel.retrieveDone
        binding.loadingBar.isGone = mCantiViewModel.retrieveDone
    }

    private fun showScrolling(scrolling: Boolean) {
        val anim = if (scrolling) AnimatedVectorDrawableCompat.create(
            this,
            R.drawable.play_to_pause_circle_anim
        ) else AnimatedVectorDrawableCompat.create(this, R.drawable.pause_to_play_circle_anim)
        (binding.playScroll as? MaterialButton)?.icon = anim
        anim?.start()
//        (binding.playScroll as? MaterialButton)?.setIconResource(if (scrolling) R.drawable.pause_circle_24px else R.drawable.play_circle_24px)
        binding.playScroll.isSelected = scrolling
    }

    private fun playIntro(isFull: Boolean) {
        binding.musicControls.isVisible = true
        val colorOnPrimary = MaterialColors.getColor(this, R.attr.colorOnPrimary, TAG)
        var id = 1
        TapTargetSequence(this).apply {
            continueOnCancel(true)
            target(
                TapTarget.forToolbarMenuItem(
                    binding.risuscitoToolbar,
                    R.id.tonalita,
                    getString(R.string.action_tonalita),
                    getString(R.string.sc_tonalita_desc)
                )
                    // All options below are optional
                    .targetCircleColorInt(colorOnPrimary) // Specify a color for the target circle
                    .descriptionTypeface(mRegularFont) // Specify a typeface for the text
                    .titleTypeface(mMediumFont) // Specify a typeface for the text
                    .titleTypeface(mMediumFont) // Specify a typeface for the text
                    .titleTextColorInt(colorOnPrimary)
                    .textColorInt(colorOnPrimary)
                    .id(id++)
            )
            target(
                TapTarget.forToolbarMenuItem(
                    binding.risuscitoToolbar,
                    R.id.barre,
                    getString(R.string.action_barre),
                    getString(R.string.sc_barre_desc)
                )
                    // All options below are optional
                    .targetCircleColorInt(colorOnPrimary) // Specify a color for the target circle
                    .descriptionTypeface(mRegularFont) // Specify a typeface for the text
                    .titleTypeface(mMediumFont) // Specify a typeface for the text
                    .titleTextColorInt(colorOnPrimary)
                    .textColorInt(colorOnPrimary)
                    .id(id++)
            )
            if (isFull) {
                target(
                    TapTarget.forView(
                        binding.playSong,
                        getString(R.string.sc_audio_title),
                        getString(R.string.sc_audio_desc)
                    )
                        // All options below are optional
                        .targetCircleColorInt(colorOnPrimary) // Specify a color for the target circle
                        .descriptionTypeface(mRegularFont) // Specify a typeface for the text
                        .titleTypeface(mMediumFont) // Specify a typeface for the text
                        .titleTextColorInt(colorOnPrimary)
                        .textColorInt(colorOnPrimary)
                        .id(id++)
                )
            }
            target(
                TapTarget.forView(
                    binding.playScroll,
                    getString(R.string.sc_scroll_title),
                    getString(R.string.sc_scroll_desc)
                )
                    // All options below are optional
                    .targetCircleColorInt(colorOnPrimary) // Specify a color for the target circle
                    .descriptionTypeface(mRegularFont) // Specify a typeface for the text
                    .titleTypeface(mMediumFont) // Specify a typeface for the text
                    .titleTextColorInt(colorOnPrimary)
                    .textColorInt(colorOnPrimary)
                    .id(id++)
            )
            target(
                TapTarget.forToolbarOverflow(
                    binding.risuscitoToolbar,
                    getString(R.string.showcase_end_title),
                    getString(R.string.showcase_help_general)
                )
                    // All options below are optional
                    .targetCircleColorInt(colorOnPrimary) // Specify a color for the target circle
                    .descriptionTypeface(mRegularFont) // Specify a typeface for the text
                    .titleTypeface(mMediumFont) // Specify a typeface for the text
                    .titleTextColorInt(colorOnPrimary)
                    .textColorInt(colorOnPrimary)
                    .id(id)
            )
            listener(
                object :
                    TapTargetSequence.Listener { // The listener can listen for regular clicks, long clicks or cancels
                    override fun onSequenceFinish() {
                        mSharedPrefs.edit { putBoolean(Utility.INTRO_PAGINARENDER, true) }
                        binding.musicControls.isVisible = mCantiViewModel.mostraAudio
                    }

                    override fun onSequenceStep(tapTarget: TapTarget, b: Boolean) {
                        // no-op
                    }

                    override fun onSequenceCanceled(tapTarget: TapTarget) {
                        mSharedPrefs.edit { putBoolean(Utility.INTRO_PAGINARENDER, true) }
                        binding.musicControls.isVisible = mCantiViewModel.mostraAudio
                    }
                })
        }.start()
    }

    private fun playMedia() {
        Log.d(TAG, "playMedia: ")
        val controller = MediaControllerCompat.getMediaController(this)
        controller?.transportControls?.play()
    }

    private fun pauseMedia() {
        Log.d(TAG, "pauseMedia: ")
        val controller = MediaControllerCompat.getMediaController(this)
        controller?.transportControls?.pause()
    }

    private fun stopMedia() {
        Log.d(TAG, "stopMedia: ")
        if (mLastPlaybackState?.state != PlaybackStateCompat.STATE_STOPPED) {
            val controller = MediaControllerCompat.getMediaController(this)
            controller?.transportControls?.stop()
        }
    }

    private fun playFromId(id: String) {
        binding.musicSeekbar.isVisible = false
        binding.musicLoadingbar.isVisible = true
//        showPlaying(true)
        val controller = MediaControllerCompat.getMediaController(this)
        controller?.transportControls?.playFromMediaId(id, null)
    }

    private fun refreshCatalog() {
        Log.d(TAG, "refreshCatalog")
        binding.playSong.isEnabled = false
        val controller = MediaControllerCompat.getMediaController(this)
        controller?.transportControls?.sendCustomAction(MusicService.ACTION_REFRESH, null)
    }

    private fun scheduleSeekbarUpdate() {
        stopSeekbarUpdate()
        if (!mExecutorService.isShutdown) {
            mScheduleFuture = mExecutorService.scheduleAtFixedRate(
                { mHandler.post(mUpdateProgressTask) },
                PROGRESS_UPDATE_INITIAL_INTERVAL,
                PROGRESS_UPDATE_INTERNAL,
                TimeUnit.MILLISECONDS
            )
        }
    }

    private fun stopSeekbarUpdate() {
        mScheduleFuture?.cancel(false)
    }

    private fun updateProgress() {
        if (mLastPlaybackState == null) {
            return
        }
        var currentPosition = mLastPlaybackState?.position ?: 0
        if (mLastPlaybackState?.state == PlaybackStateCompat.STATE_PLAYING) {
            // Calculate the elapsed time between the last position update and now and unless
            // paused, we can assume (delta * speed) + current position is approximately the
            // latest position. This ensure that we do not repeatedly call the getPlaybackState()
            // on MediaControllerCompat.
            val timeDelta =
                SystemClock.elapsedRealtime() - (mLastPlaybackState?.lastPositionUpdateTime
                    ?: 0L)
            currentPosition += (timeDelta.toInt() * (mLastPlaybackState?.playbackSpeed
                ?: 0F)).toLong()
        }
        binding.musicSeekbar.isEnabled = true
        updateSeekBarValue(currentPosition.toFloat())
    }

    private fun updateSeekBarValue(value: Float) {
        if (value < binding.musicSeekbar.valueTo)
            binding.musicSeekbar.value = value
    }

    private fun updateSeekBarValueTo(valueTo: Float) {
        if (valueTo > 0F) {
            binding.musicSeekbar.valueTo = valueTo
            binding.musicSeekbar.valueFrom = 0F
        }
    }

    private fun checkRecordsState() {
        // c'è la registrazione online
        if (!url.isNullOrEmpty()) {
            // controllo se ho scaricato un file in locale
            if (isDefaultLocationPublic) {
                localUrl = if (hasStorageAccess) {
                    // Have permission, do the thing!
                    retrieveMediaFileLink(this, url, true)
                } else {
                    mSharedPrefs.edit { putString(Utility.SAVE_LOCATION, "0") }
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        getString(R.string.external_storage_denied),
                        Snackbar.LENGTH_SHORT
                    )
                        .show()
                    retrieveMediaFileLink(this, url, false)
                }
            } else
                localUrl = retrieveMediaFileLink(this, url, false)

            mDownload = !(localUrl.isNullOrEmpty() && personalUrl.isNullOrEmpty())

            // almeno una registrazione c'è, quindi nascondo il messaggio di binding.noRecords
            binding.noRecord.isVisible = false
            // mostra i pulsanti per il lettore musicale se ho una registrazione locale oppure se sono
            // online, altrimenti mostra il messaggio di mancata connessione
            Log.d(TAG, "isOnline $isOnline")
            binding.musicButtons.isVisible = isOnline || mDownload
            binding.noConnection.isInvisible = isOnline || mDownload
        } else {
            mDownload = !personalUrl.isNullOrEmpty()
            // Se c'è una registrazione locale mostro i pulsanti
            binding.musicButtons.isVisible = mDownload
            binding.noRecord.isInvisible = mDownload
        }// NON c'è la registrazione online
        initFabOptions()
    }

    private fun loadContentIntoWebView(content: String?) {
        if (!content.isNullOrEmpty()) binding.cantoView.loadData(
            encodeToString(
                content.toByteArray(Charset.forName(ECONDING_UTF8)),
                DEFAULT
            ), DEFAULT_MIME_TYPE, ECONDING_BASE64
        )
        htmlContent = content
    }

    private suspend fun retrieveData() {
        val mDao = mRiuscitoDb.cantoDao()
        withContext(lifecycleScope.coroutineContext + Dispatchers.IO) {
            mCantiViewModel.mCurrentCanto = mDao.getCantoById(mCantiViewModel.idCanto)
            getRecordLink()
        }
        if (mCantiViewModel.mCurrentCanto?.savedTab == null) {
            if (mCantiViewModel.notaCambio.isEmpty()) {
                mCantiViewModel.notaCambio = mCantiViewModel.primaNota
                mCantiViewModel.mCurrentCanto?.savedTab = mCantiViewModel.notaCambio
            } else
                mCantiViewModel.mCurrentCanto?.savedTab = mCantiViewModel.primaNota
        } else if (mCantiViewModel.notaCambio.isEmpty())
            mCantiViewModel.notaCambio = mCantiViewModel.mCurrentCanto?.savedTab.orEmpty()

        if (mCantiViewModel.mCurrentCanto?.savedBarre == null) {
            if (mCantiViewModel.barreCambio.isEmpty()) {
                mCantiViewModel.barreCambio = mCantiViewModel.primoBarre
                mCantiViewModel.mCurrentCanto?.savedBarre = mCantiViewModel.barreCambio
            } else
                mCantiViewModel.mCurrentCanto?.savedBarre = mCantiViewModel.primoBarre
        } else {
            //	    	Log.i("BARRESALVATO", barreSalvato);
            if (mCantiViewModel.barreCambio.isEmpty())
                mCantiViewModel.barreCambio = mCantiViewModel.mCurrentCanto?.savedBarre.orEmpty()
        }

        val convMap =
            cambioAccordi.diffSemiToni(mCantiViewModel.primaNota, mCantiViewModel.notaCambio)
        var convMin: HashMap<String, String>? = null
        if (resources.systemLocale.language.equals(
                LANGUAGE_UKRAINIAN,
                ignoreCase = true
            ) || resources.systemLocale.language.equals(LANGUAGE_POLISH, ignoreCase = true)
        )
            convMin =
                cambioAccordi.diffSemiToniMin(mCantiViewModel.primaNota, mCantiViewModel.notaCambio)
        if (convMap != null) {
            loadContentIntoWebView(cambiaAccordi(convMap, mCantiViewModel.barreCambio, convMin))
        } else
            loadContentIntoWebView(
                readTextFromResource(
                    mCantiViewModel.pagina
                        ?: NO_CANTO
                )
            )

        val webSettings = binding.cantoView.settings
        webSettings.useWideViewPort = true
        webSettings.setSupportZoom(true)
        webSettings.loadWithOverviewMode = true

        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false

        mCantiViewModel.mCurrentCanto?.let {
            if (it.zoom > 0)
                binding.cantoView.setInitialScale(it.zoom)
        }
        binding.cantoView.webViewClient = InitialScrollWebClient(mCantiViewModel.mCurrentCanto)

        if (mCantiViewModel.speedValue == null)
            try {
                binding.speedSeekbar.value = (mCantiViewModel.mCurrentCanto?.savedSpeed
                    ?: "2").toFloat()
            } catch (e: NumberFormatException) {
                Log.e(TAG, "savedSpeed ${mCantiViewModel.mCurrentCanto?.savedSpeed}", e)
                binding.speedSeekbar.value = 2F
            }
        else
            binding.speedSeekbar.value = (mCantiViewModel.speedValue ?: "0").toFloat()

        //	    Log.i(this.getClass().toString(), "scrollPlaying? " + scrollPlaying);
        if (mCantiViewModel.scrollPlaying) {
            showScrolling(true)
            mScrollDown.run()
        }
        checkRecordsState()
    }

    private suspend fun insertLink(path: String) {
        val mDao = mRiuscitoDb.localLinksDao()
        val linkToInsert = LocalLink()
        linkToInsert.idCanto = mCantiViewModel.idCanto
        linkToInsert.localPath = path
        withContext(lifecycleScope.coroutineContext + Dispatchers.IO) {
            mDao.insertLocalLink(linkToInsert)
            getRecordLink()
        }
        refreshCatalog()
        checkRecordsState()
    }

    private suspend fun checkRecordState() {
        withContext(lifecycleScope.coroutineContext + Dispatchers.IO) {
            getRecordLink()
        }
        checkRecordsState()
    }

    private suspend fun deleteLink() {
        val mDao = mRiuscitoDb.localLinksDao()
        val linkToDelete = LocalLink()
        linkToDelete.idCanto = mCantiViewModel.idCanto
        withContext(lifecycleScope.coroutineContext + Dispatchers.IO) {
            mDao.deleteLocalLink(linkToDelete)
            getRecordLink()
        }
        refreshCatalog()
        checkRecordsState()
    }

    private suspend fun updateFavorite() {
        val mDao = mRiuscitoDb.cantoDao()
        mCantiViewModel.mCurrentCanto?.let {
            it.favorite = if (mCantiViewModel.mCurrentCanto?.favorite == 1) 0 else 1
            withContext(lifecycleScope.coroutineContext + Dispatchers.IO) {
                mDao.updateCanto(it)
            }
            Snackbar.make(
                findViewById(android.R.id.content),
                if (it.favorite == 1) R.string.favorite_added else R.string.favorite_removed,
                Snackbar.LENGTH_SHORT
            )
                .show()
            initFabOptions()
        }
    }

    private suspend fun updateCanto(option: Int) {
        val mDao = mRiuscitoDb.cantoDao()
        mCantiViewModel.mCurrentCanto?.let {
            withContext(lifecycleScope.coroutineContext + Dispatchers.IO) {
                mDao.updateCanto(it)
            }
            if (option != 0)
                Snackbar.make(
                    findViewById(android.R.id.content),
                    if (option == 1) R.string.tab_saved else R.string.barre_saved,
                    Snackbar.LENGTH_SHORT
                )
                    .show()
        }
    }


    private fun initFabOptions() {
        val iconColor = MaterialColors.getColor(this, R.attr.colorOnSecondaryContainer, TAG)
        val backgroundColor = MaterialColors.getColor(this, R.attr.colorSecondaryContainer, TAG)

        val colorPrimaryContainer = MaterialColors.getColor(this, R.attr.colorPrimaryContainer, TAG)
        val colorOnPrimaryContainer =
            MaterialColors.getColor(this, R.attr.colorOnPrimaryContainer, TAG)
        binding.fabCanti.mainFabOpenedBackgroundColor = colorPrimaryContainer
        binding.fabCanti.mainFabClosedBackgroundColor = colorPrimaryContainer
        binding.fabCanti.mainFabClosedIconColor = colorOnPrimaryContainer
        binding.fabCanti.mainFabOpenedIconColor = colorOnPrimaryContainer
        binding.fabCanti.clearActionItems()
        binding.fabCanti.expansionMode =
            if (isFabExpansionLeft) SpeedDialView.ExpansionMode.LEFT else SpeedDialView.ExpansionMode.TOP

        binding.fabCanti.addActionItem(
            SpeedDialActionItem.Builder(
                R.id.fab_fullscreen_on,
                AppCompatResources.getDrawable(this, R.drawable.fullscreen_24px)
            )
                .setTheme(R.style.Risuscito_SpeedDialActionItem)
                .setLabel(getString(R.string.fullscreen))
                .setFabBackgroundColor(backgroundColor)
                .setLabelBackgroundColor(backgroundColor)
                .setLabelColor(iconColor)
                .create()
        )

        binding.fabCanti.addActionItem(
            SpeedDialActionItem.Builder(
                R.id.fab_sound_off,
                AppCompatResources.getDrawable(
                    this,
                    if (mCantiViewModel.mostraAudio) R.drawable.headphones_24px else R.drawable.headset_off_24px
                )
            )
                .setTheme(R.style.Risuscito_SpeedDialActionItem)
                .setLabel(getString(if (mCantiViewModel.mostraAudio) R.string.audio_off else R.string.audio_on))
                .setFabBackgroundColor(backgroundColor)
                .setLabelBackgroundColor(backgroundColor)
                .setLabelColor(iconColor)
                .create()
        )

        if (mDownload) {
            val icon = AppCompatResources.getDrawable(
                this,
                if ((!personalUrl.isNullOrEmpty())) R.drawable.link_off_24px else R.drawable.delete_24px
            )
            val text = if (!personalUrl.isNullOrEmpty())
                getString(R.string.dialog_delete_link_title) else
                getString(R.string.fab_delete_unlink)
            binding.fabCanti.addActionItem(
                SpeedDialActionItem.Builder(R.id.fab_delete_file, icon)
                    .setTheme(R.style.Risuscito_SpeedDialActionItem)
                    .setLabel(text)
                    .setFabBackgroundColor(backgroundColor)
                    .setLabelBackgroundColor(backgroundColor)
                    .setLabelColor(iconColor)
                    .create()
            )
        } else {
            if (!url.isNullOrEmpty())
                binding.fabCanti.addActionItem(
                    SpeedDialActionItem.Builder(
                        R.id.fab_save_file,
                        AppCompatResources.getDrawable(this, R.drawable.file_download_24px)
                    )
                        .setTheme(R.style.Risuscito_SpeedDialActionItem)
                        .setLabel(getString(R.string.save_file))
                        .setFabBackgroundColor(backgroundColor)
                        .setLabelBackgroundColor(backgroundColor)
                        .setLabelColor(iconColor)
                        .create()
                )
            binding.fabCanti.addActionItem(
                SpeedDialActionItem.Builder(
                    R.id.fab_link_file,
                    AppCompatResources.getDrawable(this, R.drawable.add_link_24px)
                )
                    .setTheme(R.style.Risuscito_SpeedDialActionItem)
                    .setLabel(getString(R.string.only_link_title))
                    .setFabBackgroundColor(backgroundColor)
                    .setLabelBackgroundColor(backgroundColor)
                    .setLabelColor(iconColor)
                    .create()
            )

        }

        binding.fabCanti.addActionItem(
            SpeedDialActionItem.Builder(
                R.id.fab_favorite,
                AppCompatResources.getDrawable(
                    this,
                    if (mCantiViewModel.mCurrentCanto?.favorite == 1) R.drawable.bookmark_remove_24px else R.drawable.bookmark_add_24px
                )
            )
                .setTheme(R.style.Risuscito_SpeedDialActionItem)
                .setLabel(getString(if (mCantiViewModel.mCurrentCanto?.favorite == 1) R.string.favorite_off else R.string.favorite_on))
                .setFabBackgroundColor(backgroundColor)
                .setLabelBackgroundColor(backgroundColor)
                .setLabelColor(iconColor)
                .create()
        )

        binding.fabCanti.setOnActionSelectedListener {
            when (it.id) {
                R.id.fab_fullscreen_on -> {
                    binding.fabCanti.close()
                    mHandler.removeCallbacks(mScrollDown)
                    saveZoom(andSpeedAlso = false, andSaveTabAlso = false)
                    val bundle = Bundle()
                    bundle.putString(Utility.HTML_CONTENT, htmlContent)
                    bundle.putInt(Utility.SPEED_VALUE, binding.speedSeekbar.value.toInt())
                    bundle.putBoolean(Utility.SCROLL_PLAYING, mCantiViewModel.scrollPlaying)
                    bundle.putInt(Utility.ID_CANTO, mCantiViewModel.idCanto)

                    val intent = Intent(this, PaginaRenderFullScreen::class.java)
                    intent.putExtras(bundle)
                    startActivityWithFadeIn(intent)
                    true
                }
                R.id.fab_sound_off -> {
                    binding.fabCanti.close()
                    mCantiViewModel.mostraAudio = !mCantiViewModel.mostraAudio
                    binding.musicControls.isVisible = mCantiViewModel.mostraAudio
                    initFabOptions()
                    true
                }
                R.id.fab_delete_file -> {
                    binding.fabCanti.close()
                    if (!url.isNullOrEmpty() && personalUrl.isNullOrEmpty()) {
                        SimpleDialogFragment.show(
                            SimpleDialogFragment.Builder(
                                this, DELETE_MP3
                            )
                                .title(R.string.dialog_delete_mp3_title)
                                .icon(R.drawable.delete_24px)
                                .content(R.string.dialog_delete_mp3)
                                .positiveButton(R.string.delete_confirm)
                                .negativeButton(R.string.cancel),
                            supportFragmentManager
                        )
                    } else {
                        SimpleDialogFragment.show(
                            SimpleDialogFragment.Builder(
                                this, DELETE_LINK
                            )
                                .title(R.string.dialog_delete_link_title)
                                .icon(R.drawable.link_off_24px)
                                .content(R.string.dialog_delete_link)
                                .positiveButton(R.string.unlink_confirm)
                                .negativeButton(R.string.cancel),
                            supportFragmentManager
                        )
                    }
                    true
                }
                R.id.fab_save_file -> {
                    binding.fabCanti.close()
                    SimpleDialogFragment.show(
                        SimpleDialogFragment.Builder(
                            this, DOWNLINK_CHOOSE
                        )
                            .title(R.string.save_file)
                            .icon(R.drawable.file_download_24px)
                            .content(R.string.download_message)
                            .positiveButton(R.string.download_confirm)
                            .negativeButton(R.string.cancel),
                        supportFragmentManager
                    )
                    true
                }
                R.id.fab_link_file -> {
                    binding.fabCanti.close()
                    SimpleDialogFragment.show(
                        SimpleDialogFragment.Builder(
                            this, ONLY_LINK
                        )
                            .title(R.string.only_link_title)
                            .icon(R.drawable.add_link_24px)
                            .content(R.string.only_link)
                            .positiveButton(R.string.associate_confirm)
                            .negativeButton(R.string.cancel),
                        supportFragmentManager
                    )
                    true
                }
                R.id.fab_favorite -> {
                    binding.fabCanti.close()
                    lifecycleScope.launch { updateFavorite() }
                    true
                }
                else -> {
                    false
                }
            }
        }
    }

    private fun dismissProgressDialog(tag: String) {
        val sFragment = ProgressDialogFragment.findVisible(this, tag)
        sFragment?.dismiss()
    }

    private suspend fun exportPdf() {
        ProgressDialogFragment.show(
            ProgressDialogFragment.Builder(EXPORT_PDF).apply {
                title = R.string.action_exp_pdf
                icon = R.drawable.picture_as_pdf_24px
                content = R.string.export_running
                progressIndeterminate = true
                canceable = true
            },
            supportFragmentManager
        )
        val pdfOutput = PdfExporter(this).exportPdf(htmlContent)
        delay(1000)
        dismissProgressDialog(EXPORT_PDF)
        if (pdfOutput.isError) {
            Snackbar.make(
                findViewById(android.R.id.content),
                "${getString(R.string.error)}: ${pdfOutput.errorMessage}",
                Snackbar.LENGTH_SHORT
            )
                .show()
        } else {
            val file = File(pdfOutput.pdfPath)
            val target = Intent(Intent.ACTION_VIEW)
            val pdfUri = FileProvider.getUriForFile(
                this@PaginaRenderActivity, "it.cammino.risuscito.fileprovider", file
            )
            Log.d(TAG, "pdfUri: $pdfUri")
            target.setDataAndType(pdfUri, "application/pdf")
            target.flags = Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_GRANT_READ_URI_PERMISSION
            val intent2 = Intent.createChooser(target, getString(R.string.open_pdf))
            try {
                startActivity(intent2)
            } catch (e: ActivityNotFoundException) {
                Snackbar.make(
                    findViewById(android.R.id.content),
                    R.string.no_pdf_reader,
                    Snackbar.LENGTH_SHORT
                )
                    .show()
            }
        }
    }

    private fun deleteAudio(toDelete: Uri) {
        if (contentResolver.delete(toDelete, null, null) > 0) {
            Snackbar.make(
                findViewById(android.R.id.content),
                R.string.file_delete,
                Snackbar.LENGTH_SHORT
            )
                .show()
            refreshCatalog()
            lifecycleScope.launch { checkRecordState() }
        } else
            Snackbar.make(
                findViewById(android.R.id.content),
                R.string.error,
                Snackbar.LENGTH_SHORT
            ).show()
    }

    companion object {
        internal val TAG = PaginaRenderActivity::class.java.canonicalName
        private const val PROGRESS_UPDATE_INTERNAL: Long = 1000
        private const val PROGRESS_UPDATE_INITIAL_INTERVAL: Long = 100
        private const val EXPORT_PDF = "EXPORT_PDF"
        private const val ONLY_LINK = "ONLY_LINK"
        private const val DOWNLINK_CHOOSE = "DOWNLINK_CHOOSE"
        private const val DELETE_LINK = "DELETE_LINK"
        private const val DOWNLOAD_MP3 = "DOWNLOAD_MP3"
        private const val DELETE_MP3 = "DELETE_MP3"
        private const val SAVE_TAB = "SAVE_TAB"

        private const val ECONDING_UTF8 = "utf-8"
        private const val ECONDING_BASE64 = "base64"
        private const val NO_CANTO = "no_canto"
        private const val DEFAULT_MIME_TYPE = "text/html; charset=utf-8"
        private const val MP3_MIME_TYPE = "audio/mpeg"

    }
}
