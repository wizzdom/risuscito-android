package it.cammino.risuscito

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.core.os.postDelayed
import androidx.core.view.isInvisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.observe
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.afollestad.materialcab.MaterialCab
import com.afollestad.materialcab.MaterialCab.Companion.destroy
import com.crashlytics.android.Crashlytics
import com.mikepenz.fastadapter.IAdapter
import com.mikepenz.fastadapter.adapters.FastItemAdapter
import com.mikepenz.fastadapter.select.SelectExtension
import com.mikepenz.iconics.utils.IconicsMenuInflaterUtil
import com.mikepenz.itemanimators.SlideRightAlphaAnimator
import it.cammino.risuscito.database.RisuscitoDatabase
import it.cammino.risuscito.dialogs.SimpleDialogFragment
import it.cammino.risuscito.items.SimpleItem
import it.cammino.risuscito.utils.ListeUtils
import it.cammino.risuscito.utils.ThemeUtils.Companion.isDarkMode
import it.cammino.risuscito.utils.ioThread
import it.cammino.risuscito.utils.themeColor
import it.cammino.risuscito.viewmodels.FavoritesViewModel
import kotlinx.android.synthetic.main.activity_favourites.*
import kotlinx.android.synthetic.main.activity_main.*

class FavoritesFragment : Fragment(R.layout.activity_favourites), SimpleDialogFragment.SimpleCallback {
    private val mFavoritesViewModel: FavoritesViewModel by viewModels()
    private val cantoAdapter: FastItemAdapter<SimpleItem> = FastItemAdapter()
    private var selectExtension: SelectExtension<SimpleItem>? = null
    private var actionModeOk: Boolean = false
    private var mMainActivity: MainActivity? = null
    private var mLUtils: LUtils? = null
    private var mLastClickTime: Long = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mMainActivity = requireActivity() as? MainActivity
        mMainActivity?.setupToolbarTitle(R.string.title_activity_favourites)
        mMainActivity?.setTabVisible(false)
        mMainActivity?.enableBottombar(false)
        mMainActivity?.enableFab(false)

        mLUtils = LUtils.getInstance(requireActivity())

        if (!PreferenceManager.getDefaultSharedPreferences(context)
                        .getBoolean(Utility.PREFERITI_OPEN, false)) {
            PreferenceManager.getDefaultSharedPreferences(context).edit { putBoolean(Utility.PREFERITI_OPEN, true) }
            Handler().postDelayed(250) {
                Toast.makeText(activity, getString(R.string.new_hint_remove), Toast.LENGTH_SHORT)
                        .show()
            }
        }

        val sFragment = SimpleDialogFragment.findVisible(mMainActivity, FAVORITES_RESET)
        sFragment?.setmCallback(this)

        cantoAdapter.onPreClickListener = { _: View?, _: IAdapter<SimpleItem>, _: SimpleItem, position: Int ->
            var consume = false
            if (MaterialCab.isActive) {
                if (SystemClock.elapsedRealtime() - mLastClickTime >= Utility.CLICK_DELAY_SELECTION) {
                    mLastClickTime = SystemClock.elapsedRealtime()
                    cantoAdapter
                            .getAdapterItem(position)
                            .isSelected = !cantoAdapter.getAdapterItem(position).isSelected
                    cantoAdapter.notifyAdapterItemChanged(position)
                    if (selectExtension?.selectedItems?.size == 0)
                        destroy()
                    else
                        startCab()
                }
                consume = true
            }
            consume
        }

        cantoAdapter.onClickListener = { _: View?, _: IAdapter<SimpleItem>, item: SimpleItem, _: Int ->
            var consume = false
            if (SystemClock.elapsedRealtime() - mLastClickTime >= Utility.CLICK_DELAY) {
                mLastClickTime = SystemClock.elapsedRealtime()
                // lancia l'activity che visualizza il canto passando il parametro creato
                val intent = Intent(activity, PaginaRenderActivity::class.java)
                intent.putExtras(bundleOf(Utility.PAGINA to item.source?.getText(context), Utility.ID_CANTO to item.id))
                mLUtils?.startActivityWithTransition(intent)
                consume = true
            }
            consume
        }

        cantoAdapter.onPreLongClickListener = { _: View?, _: IAdapter<SimpleItem>, _: SimpleItem, position: Int ->
            if (!MaterialCab.isActive) {
                if (mMainActivity?.isOnTablet != true)
                    mMainActivity?.toolbar_layout?.setExpanded(true, true)
                cantoAdapter.getAdapterItem(position).isSelected = true
                cantoAdapter.notifyAdapterItemChanged(position)
                startCab()
            }
            true
        }

        selectExtension = SelectExtension(cantoAdapter)
        selectExtension?.isSelectable = true
        selectExtension?.multiSelect = true
        selectExtension?.selectOnLongClick = true
        selectExtension?.deleteAllSelectedItems()

        cantoAdapter.setHasStableIds(true)

        favouritesList?.adapter = cantoAdapter
        val llm = if (mMainActivity?.isGridLayout == true)
            GridLayoutManager(context, if (mMainActivity?.hasThreeColumns == true) 3 else 2)
        else
            LinearLayoutManager(context)
        favouritesList?.layoutManager = llm
        val insetDivider = DividerItemDecoration(requireContext(), llm.orientation)
        ContextCompat.getDrawable(requireContext(), R.drawable.material_inset_divider)?.let { insetDivider.setDrawable(it) }
        favouritesList?.addItemDecoration(insetDivider)
        favouritesList?.itemAnimator = SlideRightAlphaAnimator()

    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setHasOptionsMenu(true)
        subscribeUiFavorites()
    }

    override fun onDestroy() {
        destroy()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        IconicsMenuInflaterUtil.inflate(
                requireActivity().menuInflater, requireContext(), R.menu.clean_list_menu, menu)
        menu.findItem(R.id.list_reset).isVisible = cantoAdapter.adapterItemCount > 0
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.list_reset -> {
                mMainActivity?.let {
                    SimpleDialogFragment.Builder(it, this, FAVORITES_RESET)
                            .title(R.string.dialog_reset_favorites_title)
                            .content(R.string.dialog_reset_favorites_desc)
                            .positiveButton(R.string.clear_confirm)
                            .negativeButton(R.string.cancel)
                            .show()
                }
                return true
            }
            R.id.action_help -> {
                Toast.makeText(activity, getString(R.string.new_hint_remove), Toast.LENGTH_SHORT)
                        .show()
                return true
            }
        }
        return false
    }

    override fun onPositive(tag: String) {
        Log.d(javaClass.name, "onPositive: $tag")
        when (tag) {
            FAVORITES_RESET ->
                // run the sentence in a new thread
                ioThread {
                    val mDao = RisuscitoDatabase.getInstance(requireContext()).favoritesDao()
                    mDao.resetFavorites()
                }
        }
    }

    override fun onNegative(tag: String) {
        // no-op
    }

    private fun removeFavorites() {
        ListeUtils.removeFavoritesWithUndo(this, selectExtension?.selectedItems)
    }

    private fun startCab() {
        MaterialCab.attach(activity as AppCompatActivity, R.id.cab_stub) {
            val itemSelectedCount = selectExtension?.selectedItems?.size ?: 0
            title = resources.getQuantityString(R.plurals.item_selected, itemSelectedCount, itemSelectedCount)
            popupTheme = R.style.ThemeOverlay_MaterialComponents_Dark_ActionBar
            if (isDarkMode(requireContext()))
                backgroundColor = requireContext().themeColor(R.attr.colorSurface)
            contentInsetStartRes(R.dimen.mcab_default_content_inset)
            menuRes = R.menu.menu_delete

            onCreate { _, _ ->
                Log.d(TAG, "MaterialCab onCreate")
                actionModeOk = false
            }

            onSelection { item ->
                Log.d(TAG, "MaterialCab onSelection: ${item.itemId}")
                if (item.itemId == R.id.action_remove_item) {
                    removeFavorites()
                    actionModeOk = true
                    destroy()
                    true
                } else
                    false
            }

            onDestroy {
                Log.d(TAG, "MaterialCab onDestroy: $actionModeOk")
                if (!actionModeOk) {
                    try {
                        selectExtension?.deselect()
                    } catch (e: Exception) {
                        Crashlytics.logException(e)
                    }
                }
                true
            }
        }
    }

    private fun subscribeUiFavorites() {
        mFavoritesViewModel.mFavoritesResult?.observe(this) { canti ->
            cantoAdapter.set(canti.sortedBy { it.title?.getText(context) })
            no_favourites?.isInvisible = cantoAdapter.adapterItemCount > 0
            favouritesList.isInvisible = cantoAdapter.adapterItemCount == 0
            activity?.invalidateOptionsMenu()
        }
    }

    companion object {
        private val TAG = FavoritesFragment::class.java.canonicalName
        private const val FAVORITES_RESET = "FAVORITES_RESET"
    }

}