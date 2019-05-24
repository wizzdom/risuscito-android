package it.cammino.risuscito.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.Transformations
import it.cammino.risuscito.LUtils
import it.cammino.risuscito.R
import it.cammino.risuscito.database.RisuscitoDatabase
import it.cammino.risuscito.items.CheckableItem
import it.cammino.risuscito.items.SimpleItem
import java.util.*

class ConsegnatiViewModel(application: Application) : AndroidViewModel(application) {

    var editMode: Boolean = false

    var titoliChoose: List<CheckableItem> = ArrayList()
    var titoliChooseFiltered: List<CheckableItem> = ArrayList()

    var titoli: List<SimpleItem> = ArrayList()

    var mIndexResult: LiveData<List<SimpleItem>>? = null

    init {
        val mDb = RisuscitoDatabase.getInstance(getApplication())
        mIndexResult = Transformations.map(mDb.consegnatiDao().liveConsegnati) { canti ->
            val newList = ArrayList<SimpleItem>()
            canti.forEach {
                newList.add(
                        SimpleItem()
                                .withTitle(LUtils.getResId(it.titolo, R.string::class.java))
                                .withPage(LUtils.getResId(it.pagina, R.string::class.java))
                                .withSource(LUtils.getResId(it.source, R.string::class.java))
                                .withColor(it.color!!)
                                .withId(it.id)
                )
            }
            newList
        }
    }

}
