package it.cammino.risuscito

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.os.*
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import androidx.core.os.postDelayed
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.postDelayed
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.files.fileChooser
import com.getkeepsafe.taptargetview.TapTarget
import com.getkeepsafe.taptargetview.TapTargetSequence
import com.google.android.material.snackbar.Snackbar
import com.leinardi.android.speeddial.SpeedDialActionItem
import com.leinardi.android.speeddial.SpeedDialView
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.IconicsSize.Companion.dp
import com.mikepenz.iconics.dsl.iconicsDrawable
import com.mikepenz.iconics.typeface.library.community.material.CommunityMaterial
import com.mikepenz.iconics.utils.IconicsMenuInflaterUtil
import it.cammino.risuscito.LUtils.Companion.hasQ
import it.cammino.risuscito.Utility.getExternalLink
import it.cammino.risuscito.Utility.getExternalMediaIdByName
import it.cammino.risuscito.Utility.isDefaultLocationPublic
import it.cammino.risuscito.Utility.mediaScan
import it.cammino.risuscito.Utility.readTextFromResource
import it.cammino.risuscito.Utility.retrieveMediaFileLink
import it.cammino.risuscito.database.RisuscitoDatabase
import it.cammino.risuscito.database.entities.LocalLink
import it.cammino.risuscito.dialogs.ProgressDialogFragment
import it.cammino.risuscito.dialogs.SimpleDialogFragment
import it.cammino.risuscito.playback.MusicService
import it.cammino.risuscito.services.DownloadService
import it.cammino.risuscito.services.PdfExportService
import it.cammino.risuscito.ui.LocaleManager.Companion.LANGUAGE_ENGLISH
import it.cammino.risuscito.ui.LocaleManager.Companion.LANGUAGE_ITALIAN
import it.cammino.risuscito.ui.LocaleManager.Companion.LANGUAGE_UKRAINIAN
import it.cammino.risuscito.ui.LocaleManager.Companion.getSystemLocale
import it.cammino.risuscito.ui.ThemeableActivity
import it.cammino.risuscito.viewmodels.PaginaRenderViewModel
import kotlinx.android.synthetic.main.activity_pagina_render.*
import pub.devrel.easypermissions.AppSettingsDialog
import pub.devrel.easypermissions.EasyPermissions
import java.io.*
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class PaginaRenderActivity : ThemeableActivity(), SimpleDialogFragment.SimpleCallback, ProgressDialogFragment.ProgressCallback {

    val cambioAccordi = CambioAccordi(this, null)
    private val mExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var mDownload: Boolean = false

    private val noChangesTabBarre: Boolean
        get() = mViewModel.notaCambio == mViewModel.mCurrentCanto?.savedTab && mViewModel.barreCambio == mViewModel.mCurrentCanto?.savedBarre

    private val mSharedPrefs: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(this)

    private val mRiuscitoDb: RisuscitoDatabase
        get() = RisuscitoDatabase.getInstance(this)

    private val mViewModel: PaginaRenderViewModel by viewModels()
    private var url: String? = null
    private var personalUrl: String? = null
    private var localUrl: String? = null
    private var mLastPlaybackState: PlaybackStateCompat? = null
    private val mUpdateProgressTask = Runnable { updateProgress() }
    private var mScheduleFuture: ScheduledFuture<*>? = null
    private var mRegularFont: Typeface? = null
    private val mHandler = Handler()
    internal val mScrollDown: Runnable = object : Runnable {
        override fun run() {
            mViewModel.speedValue?.let {
                try {
                    cantoView?.scrollBy(0, Integer.valueOf(it))
                } catch (e: NumberFormatException) {
                    cantoView?.scrollBy(0, 0)
                }

                mHandler.postDelayed(this, 700)
            } ?: Log.d(TAG, "attività chiusa o annullato lo scroll")
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
                    music_seekbar.isEnabled = true
                }
                PlaybackStateCompat.STATE_STOPPED -> {
                    stopSeekbarUpdate()
                    music_seekbar.progress = 0
                    music_seekbar.isEnabled = false
                    showPlaying(false)
                }
                PlaybackStateCompat.STATE_ERROR -> {
//                    dismissProgressDialog(BUFFERING)
                    music_seekbar.isVisible = true
                    music_loadingbar.isVisible = false
                    stopSeekbarUpdate()
                    music_seekbar.progress = 0
                    music_seekbar.isEnabled = false
                    showPlaying(false)
                    Log.e(TAG, "onPlaybackStateChanged: " + state.errorMessage)
                    Snackbar.make(
                            findViewById(android.R.id.content),
                            state.errorMessage,
                            Snackbar.LENGTH_SHORT)
                            .show()
                }
                PlaybackStateCompat.STATE_PLAYING -> {
//                    dismissProgressDialog(BUFFERING)
                    music_seekbar.isVisible = true
                    music_loadingbar.isVisible = false
                    scheduleSeekbarUpdate()
                    showPlaying(true)
                    music_seekbar.isEnabled = true
                }
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            Log.d(TAG, "onMetadataChanged")
            if (metadata != null) {
                val duration = metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION).toInt()
                music_seekbar.max = duration
                music_seekbar.isEnabled = true
            }
        }
    }
    //  private int savedSpeed;
    private var mLUtils: LUtils? = null
    private var mMediaBrowser: MediaBrowserCompat? = null
    private val mConnectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            Log.d(TAG, "onConnected")
            try {
                mMediaBrowser?.let {
                    val mediaController = MediaControllerCompat(
                            this@PaginaRenderActivity, it.sessionToken)
                    MediaControllerCompat.setMediaController(this@PaginaRenderActivity, mediaController)
                    mediaController.registerCallback(mMediaControllerCallback)
                    mLastPlaybackState = mediaController.playbackState
                    if (mLastPlaybackState?.state == PlaybackStateCompat.STATE_PLAYING) {
                        scheduleSeekbarUpdate()
                    }
                    showPlaying(mLastPlaybackState?.state == PlaybackStateCompat.STATE_PLAYING)
                    music_seekbar.isEnabled = mLastPlaybackState?.state == PlaybackStateCompat.STATE_PLAYING || mLastPlaybackState?.state == PlaybackStateCompat.STATE_PAUSED

                    if (mediaController.metadata != null) {
                        Log.d(
                                TAG,
                                "onConnected: duration " + mediaController
                                        .metadata
                                        .getLong(MediaMetadataCompat.METADATA_KEY_DURATION))
                        music_seekbar.max = mediaController
                                .metadata
                                .getLong(MediaMetadataCompat.METADATA_KEY_DURATION).toInt()
                    }
                    Log.d(TAG, "onConnected: mLastPlaybackState.getPosition() ${mLastPlaybackState?.position}")
                    music_seekbar.progress = mLastPlaybackState?.position?.toInt() ?: 0
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
            val mediaController = MediaControllerCompat.getMediaController(this@PaginaRenderActivity)
            mediaController?.unregisterCallback(mMediaControllerCallback)
            MediaControllerCompat.setMediaController(this@PaginaRenderActivity, null)
        }
    }
    private val downloadPosBRec = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Implement UI change code here once notification is received
            try {
                Log.v(TAG, "BROADCAST_DOWNLOAD_PROGRESS")
                Log.v(TAG, "DATA_PROGRESS: " + intent.getIntExtra(DownloadService.DATA_PROGRESS, 0))
                val sFragment = ProgressDialogFragment.findVisible(this@PaginaRenderActivity, DOWNLOAD_MP3)
                sFragment?.setProgress(intent.getIntExtra(DownloadService.DATA_PROGRESS, 0))
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, e.localizedMessage, e)
            }

        }
    }
    private val downloadCompletedBRec = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Implement UI change code here once notification is received
            try {
                Log.d(TAG, "BROADCAST_DOWNLOAD_COMPLETED")
                dismissProgressDialog(DOWNLOAD_MP3)
//                val saveLocation = Integer.parseInt(mSharedPrefs.getString(Utility.SAVE_LOCATION, "0")
//                        ?: "0")
                // initiate media scan and put the new things into the path array to
                // make the scanner aware of the location and the files you want to see
                if (isDefaultLocationPublic(context) && !hasQ())
                    mediaScan(context, url ?: "")
                Snackbar.make(
                        findViewById(android.R.id.content),
                        R.string.download_completed,
                        Snackbar.LENGTH_SHORT)
                        .show()
                stopMedia()
                refreshCatalog()
                //            checkRecordsState();
                RecordStateCheckerTask().execute()
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, e.localizedMessage, e)
            }

        }
    }
    private val downloadErrorBRec = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Implement UI change code here once notification is received
            try {
                Log.d(TAG, "BROADCAST_DOWNLOAD_ERROR")
                Log.d(TAG, "DATA_ERROR: " + intent.getStringExtra(DownloadService.DATA_ERROR))
                dismissProgressDialog(DOWNLOAD_MP3)
                Snackbar.make(
                        findViewById(android.R.id.content),
                        getString(R.string.download_error)
                                + " "
                                + intent.getStringExtra(DownloadService.DATA_ERROR),
                        Snackbar.LENGTH_SHORT)
                        .show()
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, e.localizedMessage, e)
            }

        }
    }
    private val exportCompleted = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Implement UI change code here once notification is received
            Log.d(TAG, "BROADCAST_EXPORT_COMPLETED")
            Log.d(TAG, "DATA_PDF_PATH: " + intent.getStringExtra(PdfExportService.DATA_PDF_PATH))
            dismissProgressDialog(EXPORT_PDF)
            val localPDFPath = intent.getStringExtra(PdfExportService.DATA_PDF_PATH)
            localPDFPath?.let {
                val file = File(it)
                val target = Intent(Intent.ACTION_VIEW)
                val pdfUri = FileProvider.getUriForFile(
                        this@PaginaRenderActivity, "it.cammino.risuscito.fileprovider", file)
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
                            Snackbar.LENGTH_SHORT)
                            .show()
                }
            }
                    ?: Snackbar.make(findViewById(android.R.id.content), R.string.error, Snackbar.LENGTH_SHORT).show()
        }
    }
    private val exportError = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Implement UI change code here once notification is received
            try {
                Log.d(TAG, PdfExportService.BROADCAST_EXPORT_ERROR)
                Log.d(
                        TAG,
                        "$PdfExportService.DATA_EXPORT_ERROR: ${intent.getStringExtra(PdfExportService.DATA_EXPORT_ERROR)}")
                dismissProgressDialog(EXPORT_PDF)
                Snackbar.make(
                        findViewById(android.R.id.content),
                        intent.getStringExtra(PdfExportService.DATA_EXPORT_ERROR)
                                ?: getString(R.string.error),
                        Snackbar.LENGTH_SHORT)
                        .show()
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, e.localizedMessage, e)
            }

        }
    }

    private val catalogReadyBR = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Implement UI change code here once notification is received
            try {
                Log.d(TAG, MusicService.MSG_RETRIEVE_DONE)
                val done = intent.getBooleanExtra(MusicService.MSG_RETRIEVE_DONE, false)
                Log.d(TAG, "MSG_RETRIEVE_DONE: $done")
                mViewModel.retrieveDone = done
                showPlaying(false)
                play_song.isEnabled = done
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pagina_render)

        mRegularFont = ResourcesCompat.getFont(this, R.font.googlesans_regular)

        setSupportActionBar(risuscito_toolbar)
        supportActionBar?.setTitle(R.string.canto_title_activity)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        mLUtils = LUtils.getInstance(this)

//        val icon = IconicsDrawable(this, CommunityMaterial.Icon2.cmd_plus)
//                .colorInt(Color.WHITE)
//                .sizeDp(24)
//                .paddingDp(4)
        val icon = iconicsDrawable(CommunityMaterial.Icon2.cmd_plus) {
            color = colorInt(Color.WHITE)
            size = sizeDp(24)
            padding = sizeDp(4)
        }
        fab_canti.setMainFabClosedDrawable(icon)

        // recupera il numero della pagina da visualizzare dal parametro passato dalla chiamata
        val bundle = this.intent.extras
        mViewModel.pagina = mViewModel.pagina
                ?: bundle?.getCharSequence(Utility.PAGINA, "")?.toString()
        mViewModel.idCanto = bundle?.getInt(Utility.ID_CANTO) ?: return

        try {
            mViewModel.primaNota = if (mViewModel.primaNota == PaginaRenderViewModel.NOT_VAL) CambioAccordi.recuperaPrimoAccordo(
                    resources.openRawResource(LUtils.getResId(mViewModel.pagina, R.raw::class.java)),
                    getSystemLocale(resources).language)
            else mViewModel.primaNota
            mViewModel.primoBarre = if (mViewModel.primoBarre == PaginaRenderViewModel.NOT_VAL) cambioAccordi.recuperaBarre(
                    resources.openRawResource(LUtils.getResId(mViewModel.pagina, R.raw::class.java)),
                    getSystemLocale(resources).language)
            else mViewModel.primoBarre
        } catch (e: IOException) {
            Log.e(TAG, e.localizedMessage, e)
        }

        music_seekbar.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        val time = String.format(
                                getSystemLocale(resources),
                                "%02d:%02d",
                                TimeUnit.MILLISECONDS.toMinutes(progress.toLong()),
                                TimeUnit.MILLISECONDS.toSeconds(progress.toLong()) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(progress.toLong())))
                        time_text.text = time
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) {
                        stopSeekbarUpdate()
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        val controller = MediaControllerCompat.getMediaController(this@PaginaRenderActivity)
                        controller?.transportControls?.seekTo(seekBar.progress.toLong())
                                ?: return
                        scheduleSeekbarUpdate()
                    }
                })

        speed_seekbar.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        mViewModel.speedValue = progress.toString()
                        slider_text.text = getString(R.string.percent_progress, progress)
                        Log.d(javaClass.toString(), "speedValue cambiato! " + mViewModel.speedValue)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })

        showScrolling(false)

        DataRetrieverTask().execute(mViewModel.idCanto)

        play_song.setOnClickListener {
            val controller = MediaControllerCompat.getMediaController(this)
            val stateObj = controller.playbackState
            val state = stateObj?.state ?: PlaybackStateCompat.STATE_NONE
            Log.d(TAG, "playPause: Button pressed, in state $state")

            if (state == PlaybackStateCompat.STATE_STOPPED || state == PlaybackStateCompat.STATE_NONE) {
                playFromId(mViewModel.idCanto.toString())
            } else if (state == PlaybackStateCompat.STATE_PLAYING
                    || state == PlaybackStateCompat.STATE_BUFFERING
                    || state == PlaybackStateCompat.STATE_CONNECTING) {
                pauseMedia()
            } else if (state == PlaybackStateCompat.STATE_PAUSED) {
                playMedia()
            }
        }

        play_scroll.setOnClickListener { v ->
            if (v.isSelected) {
                showScrolling(false)
                mViewModel.scrollPlaying = false
                mHandler.removeCallbacks(mScrollDown)
            } else {
                showScrolling(true)
                mViewModel.scrollPlaying = true
                mScrollDown.run()
            }
        }

        if (savedInstanceState == null)
            mViewModel.mostraAudio = mSharedPrefs.getBoolean(Utility.SHOW_AUDIO, true)

        val sFragment = ProgressDialogFragment.findVisible(this, DOWNLOAD_MP3)
        sFragment?.setmCallback(this)
        setDialogCallback(DELETE_LINK)
        setDialogCallback(DOWNLINK_CHOOSE)
        setDialogCallback(DELETE_MP3)
        setDialogCallback(ONLY_LINK)
        setDialogCallback(SAVE_TAB)

        // Connect a media browser just to get the media session token. There are other ways
        // this can be done, for example by sharing the session token directly.
        mMediaBrowser = MediaBrowserCompat(
                this, ComponentName(this, MusicService::
        class.java), mConnectionCallback, null)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        IconicsMenuInflaterUtil.inflate(
                menuInflater, this, R.menu.canto, menu, true)
        super.onCreateOptionsMenu(menu)
        Log.d(TAG, "onCreateOptionsMenu - INTRO_PAGINARENDER: ${mSharedPrefs.getBoolean(Utility.INTRO_PAGINARENDER, false)}")
        if (!mSharedPrefs.getBoolean(Utility.INTRO_PAGINARENDER, false)) {
            Handler().postDelayed(1500) {
                if (music_buttons.isVisible)
                    playIntroFull()
                else
                    playIntroSmall()
            }
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home ->
                if (mViewModel.notaCambio == PaginaRenderViewModel.NOT_VAL
                        || mViewModel.mCurrentCanto?.savedTab == null
                        || mViewModel.barreCambio == PaginaRenderViewModel.NOT_VAL
                        || mViewModel.mCurrentCanto?.savedBarre == null
                        || noChangesTabBarre) {
                    if (mViewModel.scrollPlaying) {
                        showScrolling(false)
                        mHandler.removeCallbacks(mScrollDown)
                    }
                    saveZoom(andSpeedAlso = true, andSaveTabAlso = false)
                    mLUtils?.closeActivityWithTransition()
                    return true
                } else {
                    SimpleDialogFragment.Builder(
                            this, this, SAVE_TAB)
                            .title(R.string.dialog_save_tab_title)
                            .content(R.string.dialog_save_tab)
                            .positiveButton(R.string.save_exit_confirm)
                            .negativeButton(R.string.discard_exit_confirm)
                            .show()
                    return true
                }
            R.id.action_exp_pdf -> {
                ProgressDialogFragment.Builder(this, null, EXPORT_PDF)
                        .content(R.string.export_running)
                        .progressIndeterminate(true)
                        .setCanceable()
                        .show()
                val i = Intent(applicationContext, PdfExportService::class.java)
                i.putExtra(PdfExportService.DATA_PRIMA_NOTA, mViewModel.primaNota)
                i.putExtra(PdfExportService.DATA_NOTA_CAMBIO, mViewModel.notaCambio)
                i.putExtra(PdfExportService.DATA_PRIMO_BARRE, mViewModel.primoBarre)
                i.putExtra(PdfExportService.DATA_BARRE_CAMBIO, mViewModel.barreCambio)
                i.putExtra(PdfExportService.DATA_PAGINA, mViewModel.pagina)
                i.putExtra(
                        PdfExportService.DATA_LINGUA,
                        getSystemLocale(resources).language)
                startService(i)
                return true
            }
            R.id.action_help_canto -> {
                if (music_buttons.isVisible)
                    playIntroFull()
                else
                    playIntroSmall()
                return true
            }
            R.id.action_save_tab -> {
                if (!mViewModel.mCurrentCanto?.savedTab.equals(mViewModel.notaCambio, ignoreCase = true)) {
                    mViewModel.mCurrentCanto?.savedTab = mViewModel.notaCambio
                    UpdateCantoTask().execute(1)
                } else {
                    Snackbar.make(
                            findViewById(android.R.id.content), R.string.tab_not_saved, Snackbar.LENGTH_SHORT)
                            .show()
                }
                return true
            }
            R.id.action_reset_tab -> {
                mViewModel.notaCambio = mViewModel.primaNota
                val convMap = cambioAccordi.diffSemiToni(mViewModel.primaNota, mViewModel.notaCambio)
                var convMin: HashMap<String, String>? = null
                if (getSystemLocale(resources).language.equals(LANGUAGE_UKRAINIAN, ignoreCase = true))
                    convMin = cambioAccordi.diffSemiToniMin(mViewModel.primaNota, mViewModel.notaCambio)
                saveZoom(andSpeedAlso = false, andSaveTabAlso = false)
                if (convMap != null) {
                    val nuovoFile = cambiaAccordi(convMap, mViewModel.barreCambio, convMin)
                    if (nuovoFile != null) cantoView.loadUrl(DEF_FILE_PATH + nuovoFile)
                } else
                    cantoView.loadData(readTextFromResource(resources, LUtils.getResId(mViewModel.pagina, R.raw::class.java)), "text/html", "utf-8")
                mViewModel.mCurrentCanto?.let {
                    if (it.zoom > 0)
                        cantoView.setInitialScale(it.zoom)
                }
                cantoView.webViewClient = MyWebViewClient()
                return true
            }
            R.id.action_save_barre -> {
                if (!mViewModel.mCurrentCanto?.savedBarre.equals(mViewModel.barreCambio, ignoreCase = true)) {
                    mViewModel.mCurrentCanto?.savedBarre = mViewModel.barreCambio
                    UpdateCantoTask().execute(2)
                } else {
                    Snackbar.make(
                            findViewById(android.R.id.content),
                            R.string.barre_not_saved,
                            Snackbar.LENGTH_SHORT)
                            .show()
                }
                return true
            }
            R.id.action_reset_barre -> {
                mViewModel.barreCambio = mViewModel.primoBarre
                val convMap1 = cambioAccordi.diffSemiToni(mViewModel.primaNota, mViewModel.notaCambio)
                var convMin1: HashMap<String, String>? = null
                if (getSystemLocale(resources).language.equals(LANGUAGE_UKRAINIAN, ignoreCase = true))
                    convMin1 = cambioAccordi.diffSemiToniMin(mViewModel.primaNota, mViewModel.notaCambio)
                saveZoom(andSpeedAlso = false, andSaveTabAlso = false)
                if (convMap1 != null) {
                    val nuovoFile = cambiaAccordi(convMap1, mViewModel.barreCambio, convMin1)
                    if (nuovoFile != null) cantoView.loadUrl(DEF_FILE_PATH + nuovoFile)
                } else
                    cantoView.loadData(readTextFromResource(resources, LUtils.getResId(mViewModel.pagina, R.raw::class.java)), "text/html", "utf-8")
                mViewModel.mCurrentCanto?.let {
                    if (it.zoom > 0)
                        cantoView.setInitialScale(it.zoom)
                }
                cantoView.webViewClient = MyWebViewClient()
                return true
            }
            else -> {
                if (item.groupId == R.id.menu_gruppo_note) {
                    mViewModel.notaCambio = item.titleCondensed.toString()
                    val convMap2 = cambioAccordi.diffSemiToni(mViewModel.primaNota, mViewModel.notaCambio)
                    var convMin2: HashMap<String, String>? = null
                    if (getSystemLocale(resources).language.equals(LANGUAGE_UKRAINIAN, ignoreCase = true))
                        convMin2 = cambioAccordi.diffSemiToniMin(mViewModel.primaNota, mViewModel.notaCambio)
                    saveZoom(andSpeedAlso = false, andSaveTabAlso = false)
                    if (convMap2 != null) {
                        val nuovoFile = cambiaAccordi(convMap2, mViewModel.barreCambio, convMin2)
                        if (nuovoFile != null) cantoView.loadUrl(DEF_FILE_PATH + nuovoFile)
                    } else
                        cantoView.loadData(readTextFromResource(resources, LUtils.getResId(mViewModel.pagina, R.raw::class.java)), "text/html", "utf-8")
                    mViewModel.mCurrentCanto?.let {
                        if (it.zoom > 0)
                            cantoView.setInitialScale(it.zoom)
                    }
                    cantoView.webViewClient = MyWebViewClient()
                    return true
                }
                if (item.groupId == R.id.menu_gruppo_barre) {
                    mViewModel.barreCambio = item.titleCondensed.toString()
                    val convMap3 = cambioAccordi.diffSemiToni(mViewModel.primaNota, mViewModel.notaCambio)
                    var convMin3: HashMap<String, String>? = null
                    if (getSystemLocale(resources).language.equals(LANGUAGE_UKRAINIAN, ignoreCase = true))
                        convMin3 = cambioAccordi.diffSemiToniMin(mViewModel.primaNota, mViewModel.notaCambio)
                    saveZoom(andSpeedAlso = false, andSaveTabAlso = false)
                    if (convMap3 != null) {
                        val nuovoFile = cambiaAccordi(convMap3, mViewModel.barreCambio, convMin3)
                        if (nuovoFile != null) cantoView.loadUrl(DEF_FILE_PATH + nuovoFile)
                    } else
                        cantoView.loadData(readTextFromResource(resources, LUtils.getResId(mViewModel.pagina, R.raw::class.java)), "text/html", "utf-8")
                    mViewModel.mCurrentCanto?.let {
                        if (it.zoom > 0)
                            cantoView.setInitialScale(it.zoom)
                    }
                    cantoView.webViewClient = MyWebViewClient()
                    return true
                }
            }
        }
        return false
    }

    override fun onBackPressed() {
        Log.d(TAG, "onBackPressed: ")

        if (fab_canti.isOpen) {
            fab_canti.close()
            return
        }

        if (mViewModel.notaCambio == PaginaRenderViewModel.NOT_VAL
                || mViewModel.mCurrentCanto?.savedTab == null
                || mViewModel.barreCambio == PaginaRenderViewModel.NOT_VAL
                || mViewModel.mCurrentCanto?.savedBarre == null
                || noChangesTabBarre) {
            if (mViewModel.scrollPlaying) {
                showScrolling(false)
                mHandler.removeCallbacks(mScrollDown)
            }
            saveZoom(andSpeedAlso = true, andSaveTabAlso = false)
            mLUtils?.closeActivityWithTransition()
        } else {
            SimpleDialogFragment.Builder(
                    this, this, SAVE_TAB)
                    .title(R.string.dialog_save_tab_title)
                    .content(R.string.dialog_save_tab)
                    .positiveButton(R.string.save_exit_confirm)
                    .negativeButton(R.string.discard_exit_confirm)
                    .show()
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        fab_canti.expansionMode = if (mLUtils?.isFabExpansionLeft == true) SpeedDialView.ExpansionMode.LEFT else SpeedDialView.ExpansionMode.TOP
    }

    public override fun onResume() {
        super.onResume()

        Log.d(TAG, "onResume: ")

        music_controls.isVisible = mViewModel.mostraAudio

        val mLocalBroadcastManager = LocalBroadcastManager.getInstance(applicationContext)
        // registra un receiver per ricevere la notifica di preparazione della registrazione
        mLocalBroadcastManager.registerReceiver(
                downloadPosBRec, IntentFilter(DownloadService.BROADCAST_DOWNLOAD_PROGRESS))
        mLocalBroadcastManager.registerReceiver(
                downloadCompletedBRec, IntentFilter(DownloadService.BROADCAST_DOWNLOAD_COMPLETED))
        mLocalBroadcastManager.registerReceiver(downloadErrorBRec, IntentFilter(DownloadService.BROADCAST_DOWNLOAD_ERROR))
        mLocalBroadcastManager.registerReceiver(
                exportCompleted, IntentFilter(PdfExportService.BROADCAST_EXPORT_COMPLETED))
        mLocalBroadcastManager.registerReceiver(exportError, IntentFilter(PdfExportService.BROADCAST_EXPORT_ERROR))
        mLocalBroadcastManager.registerReceiver(catalogReadyBR, IntentFilter(MusicService.BROADCAST_RETRIEVE_ASYNC))
    }

    override fun onPause() {
        super.onPause()
        val mLocalBroadcastManager = LocalBroadcastManager.getInstance(applicationContext)
        mLocalBroadcastManager.unregisterReceiver(downloadPosBRec)
        mLocalBroadcastManager.unregisterReceiver(downloadCompletedBRec)
        mLocalBroadcastManager.unregisterReceiver(downloadErrorBRec)
        mLocalBroadcastManager.unregisterReceiver(exportCompleted)
        mLocalBroadcastManager.unregisterReceiver(exportError)
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
        url = if (!mViewModel.mCurrentCanto?.link.isNullOrEmpty())
            getString(LUtils.getResId(mViewModel.mCurrentCanto?.link, R.string::class.java))
        else
            ""

        val mDao = mRiuscitoDb.localLinksDao()
        val localLink = mDao.getLocalLinkByCantoId(mViewModel.idCanto)

        personalUrl = localLink?.localPath ?: ""
    }

    private fun saveZoom(andSpeedAlso: Boolean, andSaveTabAlso: Boolean) {
        mViewModel.mCurrentCanto?.let {
            @Suppress("DEPRECATION")
            it.zoom = (cantoView.scale * 100).toInt()
            it.scrollX = cantoView.scrollX
            it.scrollY = cantoView.scrollY

            if (andSpeedAlso) it.savedSpeed = mViewModel.speedValue
                    ?: "2"

            if (andSaveTabAlso) {
                it.savedBarre = mViewModel.barreCambio
                it.savedTab = mViewModel.notaCambio
            }

            UpdateCantoTask().execute(0)
        }
    }

    private fun cambiaAccordi(
            conversione: HashMap<String, String>?,
            barre: String?,
            conversioneMin: HashMap<String, String>?): String? {
        val cantoTrasportato = this.filesDir.toString() + "/temporaneo.htm"

        var barreScritto = false

        try {
            val br = BufferedReader(InputStreamReader(resources.openRawResource(LUtils.getResId(mViewModel.pagina, R.raw::class.java)), "UTF-8"))

            var line: String? = br.readLine()

            val out = BufferedWriter(
                    OutputStreamWriter(FileOutputStream(cantoTrasportato), "UTF-8"))

            val language = getSystemLocale(resources).language

            val pattern: Pattern
            var patternMinore: Pattern? = null

            when (language) {
                LANGUAGE_ITALIAN -> pattern = Pattern.compile("Do#|Do|Re|Mib|Mi|Fa#|Fa|Sol#|Sol|La|Sib|Si")
                LANGUAGE_UKRAINIAN -> {
                    pattern = Pattern.compile("Cis|C|D|Eb|E|Fis|F|Gis|G|A|B|H")
                    // inserito spazio prima di "b" per evitare che venga confuso con "Eb" o "eb"
                    patternMinore = Pattern.compile("cis|c|d|eb|e|fis|f|gis|g|a| b|h")
                }
                LANGUAGE_ENGLISH -> pattern = Pattern.compile("C#|C|D|Eb|E|F#|F|G#|G|A|Bb|B")
                else -> pattern = Pattern.compile("Do#|Do|Re|Mib|Mi|Fa#|Fa|Sol#|Sol|La|Sib|Si")
            }

            // serve per segnarsi se si è già evidenziato il primo accordo del testo
            var notaHighlighed = false

            while (line != null) {
                Log.v(TAG, "RIGA DA ELAB: $line")
                if (line.contains("A13F3C") && !line.contains("<H2>") && !line.contains("<H4>")) {
                    if (language.equals(LANGUAGE_UKRAINIAN, ignoreCase = true) || language.equals(LANGUAGE_ENGLISH, ignoreCase = true)) {
                        line = line.replace("</FONT><FONT COLOR=\"#A13F3C\">".toRegex(), "<K>")
                        line = line.replace("</FONT><FONT COLOR=\"#000000\">".toRegex(), "<K2>")
                    }
                    val matcher = pattern.matcher(line)
                    val sb = StringBuffer()
                    val sb2 = StringBuffer()
                    while (matcher.find()) matcher.appendReplacement(sb, conversione?.get(matcher.group(0)
                            ?: "") ?: "")
                    matcher.appendTail(sb)
                    if (language.equals(LANGUAGE_UKRAINIAN, ignoreCase = true)) {
                        val matcherMin = patternMinore?.matcher(sb.toString())
                        while (matcherMin?.find() == true)
                            matcherMin.appendReplacement(sb2, conversioneMin?.get(matcherMin.group(0)
                                    ?: "") ?: "")
                        matcherMin?.appendTail(sb2)
                        line = sb2.toString()
                        //                        Log.d(TAG, "RIGA ELAB 1: " + line);
                        //                        Log.d(TAG, "notaHighlighed: " + notaHighlighed);
                        //                        Log.d(TAG, "notaCambio: " + notaCambio);
                        //                        Log.d(TAG, "primaNota: " + primaNota);
                        if (!notaHighlighed) {
                            if (!mViewModel.primaNota.equals(mViewModel.notaCambio, ignoreCase = true)) {
                                if (Utility.isLowerCase(mViewModel.primaNota[0])) {
                                    var notaCambioMin = mViewModel.notaCambio
                                    notaCambioMin = if (notaCambioMin.length == 1)
                                        notaCambioMin.toLowerCase(getSystemLocale(resources))
                                    else
                                        notaCambioMin.substring(0, 1).toLowerCase(getSystemLocale(resources)) + notaCambioMin.substring(1)
                                    line = line.replaceFirst(notaCambioMin.toRegex(), "<SPAN STYLE=\"BACKGROUND-COLOR:#FFFF00\">$notaCambioMin</SPAN>")
                                } else
                                    line = line.replaceFirst(mViewModel.notaCambio.toRegex(), "<SPAN STYLE=\"BACKGROUND-COLOR:#FFFF00\">"
                                            + mViewModel.notaCambio
                                            + "</SPAN>")
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
                            if (!mViewModel.primaNota.equals(mViewModel.notaCambio, ignoreCase = true)) {
                                line = line.replaceFirst(mViewModel.notaCambio.toRegex(), "<SPAN STYLE=\"BACKGROUND-COLOR:#FFFF00\">"
                                        + mViewModel.notaCambio
                                        + "</SPAN>")
                                notaHighlighed = true
                            }
                        }

                        if (language.equals(LANGUAGE_ENGLISH, ignoreCase = true)) {
                            line = line.replace("<K>".toRegex(), "</FONT><FONT COLOR='#A13F3C'>")
                            line = line.replace("<K2>".toRegex(), "</FONT><FONT COLOR='#000000'>")
                        }
                    }
                    out.write(line)
                    out.newLine()
                } else {
                    if (line.contains("<H3>")) {
                        if (barre != null && barre != "0") {
                            if (!barreScritto) {
                                val oldLine: String = if (!barre.equals(mViewModel.primoBarre, ignoreCase = true)) {
                                    ("<H4><SPAN STYLE=\"BACKGROUND-COLOR:#FFFF00\"><FONT COLOR=\"#A13F3C\"><I>"
                                            + getString(R.string.barre_al_tasto, barre)
                                            + "</I></FONT></SPAN></H4>")
                                } else {
                                    ("<H4><FONT COLOR=\"#A13F3C\"><I>"
                                            + getString(R.string.barre_al_tasto, barre)
                                            + "</I></FONT></H4>")
                                }
                                out.write(oldLine)
                                out.newLine()
                                barreScritto = true
                            }
                        }
                        out.write(line)
                        out.newLine()
                    } else {
                        if (!line.contains(getString(R.string.barre_search_string))) {
                            out.write(line)
                            out.newLine()
                        }
                    }
                }
                line = br.readLine()
            }
            br.close()
            out.flush()
            out.close()
            return cantoTrasportato
        } catch (e: Exception) {
            Log.e(TAG, e.localizedMessage, e)
            return null
        }

    }

    private fun startExternalDownload() {
        Log.d(TAG, " WRITE_EXTERNAL_STORAGE OK")
        if (Utility.isExternalStorageWritable) {
            ProgressDialogFragment.Builder(this, this, DOWNLOAD_MP3)
                    .content(R.string.download_running)
                    .progressIndeterminate(false)
                    .progressMax(100)
                    .positiveButton(R.string.cancel)
                    .show()
            val i = Intent(applicationContext, DownloadService::class.java)
            i.action = DownloadService.ACTION_DOWNLOAD
            val uri = url?.toUri()
            i.data = uri
            i.putExtra(DownloadService.DATA_DESTINATION_FILE, getExternalLink(url ?: ""))
            i.putExtra(DownloadService.DATA_EXTERNAL_DOWNLOAD, true)
            startService(i)
        } else
            Snackbar.make(
                    findViewById(android.R.id.content),
                    R.string.no_memory_writable,
                    Snackbar.LENGTH_SHORT)
                    .show()
    }

    private fun startInternalDownload() {
        val localFilePath = this.filesDir.toString() + "/" + Utility.filterMediaLink(url)
        ProgressDialogFragment.Builder(this, this, DOWNLOAD_MP3)
                .content(R.string.download_running)
                .progressIndeterminate(false)
                .progressMax(100)
                .positiveButton(R.string.cancel)
                .show()
        val i = Intent(applicationContext, DownloadService::class.java)
        i.action = DownloadService.ACTION_DOWNLOAD
        val uri = url?.toUri()
        i.data = uri
        i.putExtra(DownloadService.DATA_DESTINATION_FILE, localFilePath)
        startService(i)
    }

    private fun showPlaying(started: Boolean) {
        Log.d(TAG, "showPlaying: ")
//        val icon = IconicsDrawable(this, if (started) CommunityMaterial.Icon2.cmd_pause else CommunityMaterial.Icon2.cmd_play)
//                .colorInt(
//                        ContextCompat.getColor(
//                                this,
//                                R.color.text_color_secondary
//                        ))
//                .sizeDp(24)
//                .paddingDp(2)
        val icon = iconicsDrawable(if (started) CommunityMaterial.Icon2.cmd_pause else CommunityMaterial.Icon2.cmd_play) {
            color = colorInt(ContextCompat.getColor(
                    this@PaginaRenderActivity,
                    R.color.text_color_secondary
            ))
            size = sizeDp(24)
            padding = sizeDp(2)
        }
        play_song.setImageDrawable(icon)
        play_song.isVisible = mViewModel.retrieveDone
        loadingBar.isGone = mViewModel.retrieveDone
    }

    private fun showScrolling(scrolling: Boolean) {
//        val icon = IconicsDrawable(this, if (scrolling)
//            CommunityMaterial.Icon2.cmd_pause_circle_outline
//        else
//            CommunityMaterial.Icon2.cmd_play_circle_outline)
//                .colorInt(Color.WHITE)
//                .sizeDp(24)
//                .paddingDp(2)
        val icon = iconicsDrawable(if (scrolling)
            CommunityMaterial.Icon2.cmd_pause_circle_outline
        else
            CommunityMaterial.Icon2.cmd_play_circle_outline) {
            color = colorInt(Color.WHITE)
            size = sizeDp(24)
            padding = sizeDp(2)
        }
        play_scroll.setImageDrawable(icon)
        play_scroll.isSelected = scrolling
    }

    override fun onPositive(tag: String) {
        Log.d(TAG, "onPositive: $tag")
        when (tag) {
            DOWNLOAD_MP3 -> LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(Intent(DownloadService.ACTION_CANCEL))
            DELETE_LINK -> {
                Snackbar.make(
                        findViewById(android.R.id.content), R.string.delink_delete, Snackbar.LENGTH_SHORT)
                        .show()
                stopMedia()
                DeleteLinkTask().execute(mViewModel.idCanto)
            }
            DELETE_MP3 -> {
                localUrl?.let {
                    stopMedia()
//                    val saveLocation = Integer.parseInt(mSharedPrefs.getString(Utility.SAVE_LOCATION, "0")
//                            ?: "0")
                    if (isDefaultLocationPublic(this) && hasQ()) {
                        val toDelete = ContentUris.withAppendedId(MediaStore.Audio.Media
                                .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), getExternalMediaIdByName(this, it))
                        Log.d(TAG, "DELETE_MP3 toDelete: $toDelete")
                        if (contentResolver.delete(toDelete, null, null) > 0) {
                            Snackbar.make(
                                    findViewById(android.R.id.content), R.string.file_delete, Snackbar.LENGTH_SHORT)
                                    .show()
                        } else
                            Snackbar.make(findViewById(android.R.id.content), R.string.error, Snackbar.LENGTH_SHORT)
                                    .show()

                    } else {
                        val fileToDelete = File(it)
                        if (fileToDelete.delete()) {
                            if (fileToDelete.absolutePath.contains("/Risuscit")) {
                                // initiate media scan and put the new things into the path array to
                                // make the scanner aware of the location and the files you want to see
                                MediaScannerConnection.scanFile(
                                        applicationContext, arrayOf(fileToDelete.absolutePath), null, null)
                            }
                            Snackbar.make(
                                    findViewById(android.R.id.content), R.string.file_delete, Snackbar.LENGTH_SHORT)
                                    .show()
                        } else
                            Snackbar.make(findViewById(android.R.id.content), R.string.error, Snackbar.LENGTH_SHORT)
                                    .show()
//                        stopMedia()
//                        refreshCatalog()
                    }
                }
                refreshCatalog()
                RecordStateCheckerTask().execute()
            }
            DOWNLINK_CHOOSE -> {
//                val saveLocation = Integer.parseInt(mSharedPrefs.getString(Utility.SAVE_LOCATION, "0")
//                        ?: "0")
                if (isDefaultLocationPublic(this)) {
                    if (EasyPermissions.hasPermissions(
                                    this, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                    // Have permission, do the thing!
                        startExternalDownload()
                    else {
                        mSharedPrefs.edit { putString(Utility.SAVE_LOCATION, "0") }
                        Snackbar.make(
                                findViewById(android.R.id.content),
                                R.string.forced_private,
                                Snackbar.LENGTH_SHORT)
                                .show()
                        startInternalDownload()
                    }
                } else
                    startInternalDownload()
            }
            ONLY_LINK -> createFileChooser()
            SAVE_TAB -> {
                if (mViewModel.scrollPlaying) {
                    showScrolling(false)
                    mHandler.removeCallbacks(mScrollDown)
                }
                saveZoom(andSpeedAlso = true, andSaveTabAlso = true)
                mLUtils?.closeActivityWithTransition()
            }
        }
    }

    override fun onNegative(tag: String) {
        Log.d(TAG, "onNegative: $tag")
        when (tag) {
            SAVE_TAB -> {
                if (mViewModel.scrollPlaying) {
                    showScrolling(false)
                    mHandler.removeCallbacks(mScrollDown)
                }
                saveZoom(andSpeedAlso = true, andSaveTabAlso = false)
                mLUtils?.closeActivityWithTransition()
            }
        }
    }

    private fun createFileChooser() {
        if (EasyPermissions.hasPermissions(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            MaterialDialog(this)
                    .fileChooser(filter = { it.isDirectory || it.extension.toLowerCase(getSystemLocale(resources)) == "mp3" }) { _, file ->
                        val path = file.absolutePath
                        Snackbar.make(
                                findViewById(android.R.id.content),
                                getString(R.string.file_selected) + ": " + path,
                                Snackbar.LENGTH_SHORT)
                                .show()
                        stopMedia()
                        InsertLinkTask().execute(mViewModel.idCanto.toString(), path)
                    }
                    .show()
        } else AppSettingsDialog.Builder(this).build().show()
    }

    private fun playIntroSmall() {
        music_controls.isVisible = true
        TapTargetSequence(this)
                .continueOnCancel(true)
                .targets(
                        TapTarget.forToolbarMenuItem(
                                risuscito_toolbar,
                                R.id.tonalita,
                                getString(R.string.action_tonalita),
                                getString(R.string.sc_tonalita_desc))
                                // All options below are optional
                                .targetCircleColorInt(Color.WHITE) // Specify a color for the target circle
                                .textTypeface(mRegularFont) // Specify a typeface for the text
                                .titleTextColor(R.color.primary_text_default_material_dark)
                                .textColor(R.color.secondary_text_default_material_dark)
                                .id(1),
                        TapTarget.forToolbarMenuItem(
                                risuscito_toolbar,
                                R.id.barre,
                                getString(R.string.action_barre),
                                getString(R.string.sc_barre_desc))
                                // All options below are optional
                                .targetCircleColorInt(Color.WHITE) // Specify a color for the target circle
                                .textTypeface(mRegularFont) // Specify a typeface for the text
                                .titleTextColor(R.color.primary_text_default_material_dark)
                                .textColor(R.color.secondary_text_default_material_dark)
                                .id(2),
                        TapTarget.forView(
                                play_scroll,
                                getString(R.string.sc_scroll_title),
                                getString(R.string.sc_scroll_desc))
                                // All options below are optional
                                .targetCircleColorInt(Color.WHITE) // Specify a color for the target circle
                                .textTypeface(mRegularFont) // Specify a typeface for the text
                                .titleTextColor(R.color.primary_text_default_material_dark)
                                .textColor(R.color.secondary_text_default_material_dark)
                                .id(3),
                        TapTarget.forToolbarOverflow(
                                risuscito_toolbar,
                                getString(R.string.showcase_end_title),
                                getString(R.string.showcase_help_general))
                                // All options below are optional
                                .targetCircleColorInt(Color.WHITE) // Specify a color for the target circle
                                .textTypeface(mRegularFont) // Specify a typeface for the text
                                .titleTextColor(R.color.primary_text_default_material_dark)
                                .textColor(R.color.secondary_text_default_material_dark)
                                .id(4))
                .listener(
                        object : TapTargetSequence.Listener { // The listener can listen for regular clicks, long clicks or cancels
                            override fun onSequenceFinish() {
                                mSharedPrefs.edit { putBoolean(Utility.INTRO_PAGINARENDER, true) }
                                music_controls.isVisible = mViewModel.mostraAudio
                            }

                            override fun onSequenceStep(tapTarget: TapTarget, b: Boolean) {}

                            override fun onSequenceCanceled(tapTarget: TapTarget) {
                                mSharedPrefs.edit { putBoolean(Utility.INTRO_PAGINARENDER, true) }
                                music_controls.isVisible = mViewModel.mostraAudio
                            }
                        })
                .start()
    }

    private fun playIntroFull() {
        music_controls.isVisible = true
        TapTargetSequence(this)
                .continueOnCancel(true)
                .targets(
                        TapTarget.forToolbarMenuItem(
                                risuscito_toolbar,
                                R.id.tonalita,
                                getString(R.string.action_tonalita),
                                getString(R.string.sc_tonalita_desc))
                                // All options below are optional
                                .targetCircleColorInt(Color.WHITE) // Specify a color for the target circle
                                .textTypeface(mRegularFont) // Specify a typeface for the text
                                .titleTextColor(R.color.primary_text_default_material_dark)
                                .textColor(R.color.secondary_text_default_material_dark)
                                .id(1),
                        TapTarget.forToolbarMenuItem(
                                risuscito_toolbar,
                                R.id.barre,
                                getString(R.string.action_barre),
                                getString(R.string.sc_barre_desc))
                                // All options below are optional
                                .targetCircleColorInt(Color.WHITE) // Specify a color for the target circle
                                .textTypeface(mRegularFont) // Specify a typeface for the text
                                .titleTextColor(R.color.primary_text_default_material_dark)
                                .textColor(R.color.secondary_text_default_material_dark)
                                .id(2),
                        TapTarget.forView(
                                play_song,
                                getString(R.string.sc_audio_title),
                                getString(R.string.sc_audio_desc))
                                // All options below are optional
                                .targetCircleColorInt(Color.WHITE) // Specify a color for the target circle
                                .textTypeface(mRegularFont) // Specify a typeface for the text
                                .titleTextColor(R.color.primary_text_default_material_dark)
                                .textColor(R.color.secondary_text_default_material_dark)
                                .id(3),
                        TapTarget.forView(
                                play_scroll,
                                getString(R.string.sc_scroll_title),
                                getString(R.string.sc_scroll_desc))
                                // All options below are optional
                                .targetCircleColorInt(Color.WHITE) // Specify a color for the target circle
                                .textTypeface(mRegularFont) // Specify a typeface for the text
                                .titleTextColor(R.color.primary_text_default_material_dark)
                                .textColor(R.color.secondary_text_default_material_dark)
                                .id(4),
                        TapTarget.forToolbarOverflow(
                                risuscito_toolbar,
                                getString(R.string.showcase_end_title),
                                getString(R.string.showcase_help_general))
                                // All options below are optional
                                .targetCircleColorInt(Color.WHITE) // Specify a color for the target circle
                                .textTypeface(mRegularFont) // Specify a typeface for the text
                                .titleTextColor(R.color.primary_text_default_material_dark)
                                .textColor(R.color.secondary_text_default_material_dark)
                                .id(5))
                .listener(
                        object : TapTargetSequence.Listener { // The listener can listen for regular clicks, long clicks or cancels
                            override fun onSequenceFinish() {
                                mSharedPrefs.edit { putBoolean(Utility.INTRO_PAGINARENDER, true) }
                                music_controls.isVisible = mViewModel.mostraAudio
                            }

                            override fun onSequenceStep(tapTarget: TapTarget, b: Boolean) {}

                            override fun onSequenceCanceled(tapTarget: TapTarget) {
                                mSharedPrefs.edit { putBoolean(Utility.INTRO_PAGINARENDER, true) }
                                music_controls.isVisible = mViewModel.mostraAudio
                            }
                        })
                .start()
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
//        ProgressDialogFragment.Builder(this, null, BUFFERING)
//                .content(R.string.wait)
//                .progressIndeterminate(true)
//                .setCanceable()
//                .show()
        music_seekbar.isVisible = false
        music_loadingbar.isVisible = true
        showPlaying(true)
        val controller = MediaControllerCompat.getMediaController(this)
        controller?.transportControls?.playFromMediaId(id, null)
    }

    private fun refreshCatalog() {
        Log.d(TAG, "refreshCatalog")
        play_song.isEnabled = false
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
                    TimeUnit.MILLISECONDS)
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
        //        Log.d(TAG, "updateProgress: " + currentPosition);
        if (mLastPlaybackState?.state == PlaybackStateCompat.STATE_PLAYING) {
            // Calculate the elapsed time between the last position update and now and unless
            // paused, we can assume (delta * speed) + current position is approximately the
            // latest position. This ensure that we do not repeatedly call the getPlaybackState()
            // on MediaControllerCompat.
            val timeDelta = SystemClock.elapsedRealtime() - (mLastPlaybackState?.lastPositionUpdateTime
                    ?: 0L)
            currentPosition += (timeDelta.toInt() * (mLastPlaybackState?.playbackSpeed
                    ?: 0F)).toLong()
        }
        music_seekbar.isEnabled = true
        music_seekbar.progress = currentPosition.toInt()
    }

    private fun checkRecordsState() {
        // c'è la registrazione online
        if (!url.isNullOrEmpty()) {
            // controllo se ho scaricato un file in locale
//            val saveLocation = Integer.parseInt(mSharedPrefs.getString(Utility.SAVE_LOCATION, "0")
//                    ?: "0")
            if (isDefaultLocationPublic(this)) {
                localUrl = if (EasyPermissions.hasPermissions(
                                this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    // Have permission, do the thing!
                    retrieveMediaFileLink(this, url, true)
                } else {
                    mSharedPrefs.edit { putString(Utility.SAVE_LOCATION, "0") }
                    Snackbar.make(
                            findViewById(android.R.id.content),
                            getString(R.string.external_storage_denied),
                            Snackbar.LENGTH_SHORT)
                            .show()
                    retrieveMediaFileLink(this, url, false)
                }
            } else
                localUrl = retrieveMediaFileLink(this, url, false)

            mDownload = !(localUrl.isNullOrEmpty() && personalUrl.isNullOrEmpty())

            // almeno una registrazione c'è, quindi nascondo il messaggio di no_records
            no_record.isVisible = false
            // mostra i pulsanti per il lettore musicale se ho una registrazione locale oppure se sono
            // online, altrimenti mostra il messaggio di mancata connessione
            Log.d(TAG, "isOnline ${Utility.isOnline(this)}")
            music_buttons.isVisible = Utility.isOnline(this) || mDownload
            no_connection.isInvisible = Utility.isOnline(this) || mDownload
        } else {
            mDownload = !personalUrl.isNullOrEmpty()
            // Se c'è una registrazione locale mostro i pulsanti
            music_buttons.isVisible = mDownload
            no_record.isInvisible = mDownload
        }// NON c'è la registrazione online
        initFabOptions()
    }

    private inner class MyWebViewClient : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            view.postDelayed(600) {
                if ((mViewModel.mCurrentCanto?.scrollX
                                ?: 0) > 0 || (mViewModel.mCurrentCanto?.scrollY ?: 0) > 0)
                    cantoView.scrollTo(
                            mViewModel.mCurrentCanto?.scrollX
                                    ?: 0, mViewModel.mCurrentCanto?.scrollY ?: 0)
            }
            super.onPageFinished(view, url)
        }
    }

    @SuppressLint("StaticFieldLeak")
    private inner class DataRetrieverTask : AsyncTask<Int, Void, Int>() {
        override fun doInBackground(vararg params: Int?): Int? {
            Log.d(TAG, "doInBackground: ")
            val mDao = mRiuscitoDb.cantoDao()
            mViewModel.mCurrentCanto = mDao.getCantoById(params[0] ?: 0)
            getRecordLink()
            return 0
        }

        override fun onPostExecute(integer: Int?) {
            super.onPostExecute(integer)
            if (mViewModel.mCurrentCanto?.savedTab == null) {
                if (mViewModel.notaCambio == PaginaRenderViewModel.NOT_VAL) {
                    mViewModel.notaCambio = mViewModel.primaNota
                    mViewModel.mCurrentCanto?.savedTab = mViewModel.notaCambio
                } else
                    mViewModel.mCurrentCanto?.savedTab = mViewModel.primaNota
            } else if (mViewModel.notaCambio == PaginaRenderViewModel.NOT_VAL)
                mViewModel.notaCambio = mViewModel.mCurrentCanto?.savedTab
                        ?: PaginaRenderViewModel.NOT_VAL

            if (mViewModel.mCurrentCanto?.savedBarre == null) {
                if (mViewModel.barreCambio == PaginaRenderViewModel.NOT_VAL) {
                    mViewModel.barreCambio = mViewModel.primoBarre
                    mViewModel.mCurrentCanto?.savedBarre = mViewModel.barreCambio
                } else
                    mViewModel.mCurrentCanto?.savedBarre = mViewModel.primoBarre
            } else {
                //	    	Log.i("BARRESALVATO", barreSalvato);
                if (mViewModel.barreCambio == PaginaRenderViewModel.NOT_VAL)
                    mViewModel.barreCambio = mViewModel.mCurrentCanto?.savedBarre
                            ?: PaginaRenderViewModel.NOT_VAL
            }

            // fix per crash su android 4.1
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.JELLY_BEAN)
                cantoView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

            val convMap = cambioAccordi.diffSemiToni(mViewModel.primaNota, mViewModel.notaCambio)
            var convMin: HashMap<String, String>? = null
            if (getSystemLocale(resources).language.equals(LANGUAGE_UKRAINIAN, ignoreCase = true))
                convMin = cambioAccordi.diffSemiToniMin(mViewModel.primaNota, mViewModel.notaCambio)
            if (convMap != null) {
                val nuovoFile = cambiaAccordi(convMap, mViewModel.barreCambio, convMin)
                if (nuovoFile != null) cantoView.loadUrl(DEF_FILE_PATH + nuovoFile)
            } else
                cantoView.loadData(readTextFromResource(resources, LUtils.getResId(mViewModel.pagina, R.raw::class.java)), "text/html", "utf-8")

            val webSettings = cantoView.settings
            webSettings.useWideViewPort = true
            webSettings.setSupportZoom(true)
            webSettings.loadWithOverviewMode = true

            webSettings.builtInZoomControls = true
            webSettings.displayZoomControls = false

            mViewModel.mCurrentCanto?.let {
                if (it.zoom > 0)
                    cantoView.setInitialScale(it.zoom)
            }
            cantoView.webViewClient = MyWebViewClient()

            if (mViewModel.speedValue == null)
                try {
                    speed_seekbar.progress = Integer.valueOf(mViewModel.mCurrentCanto?.savedSpeed
                            ?: "2")
                } catch (e: NumberFormatException) {
                    Log.e(TAG, "savedSpeed ${mViewModel.mCurrentCanto?.savedSpeed}", e)
                    speed_seekbar.progress = 2
                }
            else
                speed_seekbar.progress = Integer.valueOf(mViewModel.speedValue ?: "0")

            //	    Log.i(this.getClass().toString(), "scrollPlaying? " + scrollPlaying);
            if (mViewModel.scrollPlaying) {
                showScrolling(true)
                mScrollDown.run()
            }
            checkRecordsState()
        }
    }

    @SuppressLint("StaticFieldLeak")
    private inner class RecordStateCheckerTask : AsyncTask<Void, Void, Int>() {
        override fun doInBackground(vararg params: Void): Int? {
            getRecordLink()
            return 0
        }

        override fun onPostExecute(integer: Int?) {
            super.onPostExecute(integer)
            checkRecordsState()
        }
    }

    @SuppressLint("StaticFieldLeak")
    private inner class DeleteLinkTask : AsyncTask<Int, Void, Int>() {
        override fun doInBackground(vararg params: Int?): Int? {
            val mDao = mRiuscitoDb.localLinksDao()
            val linkToDelete = LocalLink()
            linkToDelete.idCanto = params[0] ?: 0
            mDao.deleteLocalLink(linkToDelete)
            getRecordLink()
            return 0
        }

        override fun onPostExecute(integer: Int?) {
            super.onPostExecute(integer)
            refreshCatalog()
            checkRecordsState()
        }
    }

    @SuppressLint("StaticFieldLeak")
    private inner class InsertLinkTask : AsyncTask<String, Void, Int>() {
        override fun doInBackground(vararg params: String): Int? {
            val mDao = mRiuscitoDb.localLinksDao()
            val linkToInsert = LocalLink()
            linkToInsert.idCanto = Integer.valueOf(params[0])
            linkToInsert.localPath = params[1]
            mDao.insertLocalLink(linkToInsert)
            getRecordLink()
            return 0
        }

        override fun onPostExecute(integer: Int?) {
            super.onPostExecute(integer)
            refreshCatalog()
            checkRecordsState()
        }
    }

    @SuppressLint("StaticFieldLeak")
    private inner class UpdateFavoriteTask : AsyncTask<Int, Void, Int>() {
        override fun doInBackground(vararg params: Int?): Int? {
            mViewModel.mCurrentCanto?.let {
                val mDao = mRiuscitoDb.cantoDao()
                it.favorite = params[0] ?: 0
                mDao.updateCanto(it)
            }
            return params[0]
        }

        override fun onPostExecute(integer: Int?) {
            super.onPostExecute(integer)
            Snackbar.make(
                    findViewById(android.R.id.content),
                    if (integer == 1) R.string.favorite_added else R.string.favorite_removed,
                    Snackbar.LENGTH_SHORT)
                    .show()
            initFabOptions()
        }
    }

    @SuppressLint("StaticFieldLeak")
    private inner class UpdateCantoTask : AsyncTask<Int, Void, Int>() {
        override fun doInBackground(vararg params: Int?): Int? {
            mViewModel.mCurrentCanto?.let {
                val mDao = mRiuscitoDb.cantoDao()
                mDao.updateCanto(it)
            }
            return params[0]
        }

        override fun onPostExecute(integer: Int?) {
            super.onPostExecute(integer)
            if (integer != 0)
                Snackbar.make(
                        findViewById(android.R.id.content),
                        if (integer == 1) R.string.tab_saved else R.string.barre_saved,
                        Snackbar.LENGTH_SHORT)
                        .show()
        }
    }

    private fun initFabOptions() {
        val iconColor = ContextCompat.getColor(this, R.color.text_color_secondary)
        val backgroundColor = ContextCompat.getColor(this, R.color.floating_background)

        fab_canti.clearActionItems()
        fab_canti.expansionMode = if (mLUtils?.isFabExpansionLeft == true) SpeedDialView.ExpansionMode.LEFT else SpeedDialView.ExpansionMode.TOP

        fab_canti.addActionItem(
                SpeedDialActionItem.Builder(R.id.fab_fullscreen_on,
//                        IconicsDrawable(this, CommunityMaterial.Icon.cmd_fullscreen)
//                        .colorInt(iconColor)
//                        .sizeDp(24)
//                        .paddingDp(4)
                        iconicsDrawable(CommunityMaterial.Icon.cmd_fullscreen) {
                            size = sizeDp(24)
                            padding = sizeDp(4)
                        }
                )
                        .setTheme(R.style.Risuscito_SpeedDialActionItem)
                        .setLabel(getString(R.string.fullscreen))
                        .setFabBackgroundColor(backgroundColor)
                        .setLabelBackgroundColor(backgroundColor)
                        .setLabelColor(iconColor)
                        .create()
        )

        fab_canti.addActionItem(
                SpeedDialActionItem.Builder(R.id.fab_sound_off,
//                        IconicsDrawable(this, if (mostraAudioBool) CommunityMaterial.Icon2.cmd_headset_off else CommunityMaterial.Icon2.cmd_headset)
//                        .colorInt(iconColor)
//                        .sizeDp(24)
//                        .paddingDp(4)
                        iconicsDrawable(if (mViewModel.mostraAudio) CommunityMaterial.Icon2.cmd_headset_off else CommunityMaterial.Icon2.cmd_headset) {
                            size = sizeDp(24)
                            padding = sizeDp(4)
                        }
                )
                        .setTheme(R.style.Risuscito_SpeedDialActionItem)
                        .setLabel(getString(if (mViewModel.mostraAudio) R.string.audio_off else R.string.audio_on))
                        .setFabBackgroundColor(backgroundColor)
                        .setLabelBackgroundColor(backgroundColor)
                        .setLabelColor(iconColor)
                        .create()
        )

        if (mDownload) {
            val icon = IconicsDrawable(this)
                    .size(dp(24))
                    .padding(dp(4))
            val text = if (!personalUrl.isNullOrEmpty()) {
                icon.icon(CommunityMaterial.Icon2.cmd_link_variant_off)
                getString(R.string.dialog_delete_link_title)
            } else {
                icon.icon(CommunityMaterial.Icon.cmd_delete)
                getString(R.string.fab_delete_unlink)
            }
            fab_canti.addActionItem(
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
                fab_canti.addActionItem(
                        SpeedDialActionItem.Builder(R.id.fab_save_file,
//                                IconicsDrawable(this, CommunityMaterial.Icon.cmd_download)
//                                .colorInt(iconColor)
//                                .sizeDp(24)
//                                .paddingDp(4)
                                iconicsDrawable(CommunityMaterial.Icon.cmd_download) {
                                    size = sizeDp(24)
                                    padding = sizeDp(4)
                                }
                        )
                                .setTheme(R.style.Risuscito_SpeedDialActionItem)
                                .setLabel(getString(R.string.save_file))
                                .setFabBackgroundColor(backgroundColor)
                                .setLabelBackgroundColor(backgroundColor)
                                .setLabelColor(iconColor)
                                .create()
                )
            fab_canti.addActionItem(
                    SpeedDialActionItem.Builder(R.id.fab_link_file,
//                            IconicsDrawable(this, CommunityMaterial.Icon2.cmd_link_variant)
//                            .colorInt(iconColor)
//                            .sizeDp(24)
//                            .paddingDp(4)
                            iconicsDrawable(CommunityMaterial.Icon2.cmd_link_variant) {
                                size = sizeDp(24)
                                padding = sizeDp(4)
                            }
                    )
                            .setTheme(R.style.Risuscito_SpeedDialActionItem)
                            .setLabel(getString(R.string.only_link_title))
                            .setFabBackgroundColor(backgroundColor)
                            .setLabelBackgroundColor(backgroundColor)
                            .setLabelColor(iconColor)
                            .create()
            )

        }

        fab_canti.addActionItem(
                SpeedDialActionItem.Builder(R.id.fab_favorite,
//                        IconicsDrawable(this, if (mViewModel.mCurrentCanto?.favorite == 1) CommunityMaterial.Icon2.cmd_heart_outline else CommunityMaterial.Icon2.cmd_heart)
//                        .colorInt(iconColor)
//                        .sizeDp(24)
//                        .paddingDp(4)
                        iconicsDrawable(if (mViewModel.mCurrentCanto?.favorite == 1) CommunityMaterial.Icon2.cmd_star else CommunityMaterial.Icon2.cmd_star_outline) {
                            size = sizeDp(24)
                            padding = sizeDp(4)
                        }
                )
                        .setTheme(R.style.Risuscito_SpeedDialActionItem)
                        .setLabel(getString(if (mViewModel.mCurrentCanto?.favorite == 1) R.string.favorite_off else R.string.favorite_on))
                        .setFabBackgroundColor(backgroundColor)
                        .setLabelBackgroundColor(backgroundColor)
                        .setLabelColor(iconColor)
                        .create()
        )

        fab_canti.setOnActionSelectedListener {
            when (it.id) {
                R.id.fab_fullscreen_on -> {
                    fab_canti.close()
                    mHandler.removeCallbacks(mScrollDown)
                    saveZoom(andSpeedAlso = false, andSaveTabAlso = false)
                    val bundle = Bundle()
                    bundle.putString(Utility.URL_CANTO, cantoView.url)
                    bundle.putInt(Utility.SPEED_VALUE, speed_seekbar.progress)
                    bundle.putBoolean(Utility.SCROLL_PLAYING, mViewModel.scrollPlaying)
                    bundle.putInt(Utility.ID_CANTO, mViewModel.idCanto)

                    val intent2 = Intent(this, PaginaRenderFullScreen::class.java)
                    intent2.putExtras(bundle)
                    mLUtils?.startActivityWithFadeIn(intent2)
                    true
                }
                R.id.fab_sound_off -> {
                    fab_canti.close()
                    mViewModel.mostraAudio = !mViewModel.mostraAudio
                    music_controls.isVisible = mViewModel.mostraAudio
                    initFabOptions()
                    true
                }
                R.id.fab_delete_file -> {
                    fab_canti.close()
                    if (!url.isNullOrEmpty() && personalUrl.isNullOrEmpty()) {
                        SimpleDialogFragment.Builder(
                                this, this, DELETE_MP3)
                                .title(R.string.dialog_delete_mp3_title)
                                .content(R.string.dialog_delete_mp3)
                                .positiveButton(R.string.delete_confirm)
                                .negativeButton(R.string.cancel)
                                .show()
                    } else {
                        SimpleDialogFragment.Builder(
                                this, this, DELETE_LINK)
                                .title(R.string.dialog_delete_link_title)
                                .content(R.string.dialog_delete_link)
                                .positiveButton(R.string.unlink_confirm)
                                .negativeButton(R.string.cancel)
                                .show()
                    }
                    true
                }
                R.id.fab_save_file -> {
                    fab_canti.close()
                    SimpleDialogFragment.Builder(
                            this, this, DOWNLINK_CHOOSE)
                            .title(R.string.save_file)
                            .content(R.string.download_message)
                            .positiveButton(R.string.download_confirm)
                            .negativeButton(R.string.cancel)
                            .show()
                    true
                }
                R.id.fab_link_file -> {
                    fab_canti.close()
                    SimpleDialogFragment.Builder(
                            this, this, ONLY_LINK)
                            .title(R.string.only_link_title)
                            .content(R.string.only_link)
                            .positiveButton(R.string.associate_confirm)
                            .negativeButton(R.string.cancel)
                            .show()
                    true
                }
                R.id.fab_favorite -> {
                    fab_canti.close()
                    val favoriteYet = mViewModel.mCurrentCanto?.favorite == 1
                    UpdateFavoriteTask().execute(if (favoriteYet) 0 else 1)
                    true
                }
                else -> {
                    false
                }
            }
        }
    }

    private fun setDialogCallback(tag: String) {
        val sFragment = SimpleDialogFragment.findVisible(this, tag)
        sFragment?.setmCallback(this)
    }

    private fun dismissProgressDialog(tag: String) {
        val sFragment = ProgressDialogFragment.findVisible(this, tag)
        sFragment?.dismiss()
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
        private const val DEF_FILE_PATH = "file://"
    }
}
