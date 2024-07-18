package de.jrpie.android.launcher.list.apps

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import de.jrpie.android.launcher.PREF_SEARCH_AUTO_KEYBOARD
import de.jrpie.android.launcher.R
import de.jrpie.android.launcher.UIObject
import de.jrpie.android.launcher.getPreferences
import de.jrpie.android.launcher.list.ListActivity
import de.jrpie.android.launcher.list.forGesture
import de.jrpie.android.launcher.list.intention
import de.jrpie.android.launcher.openSoftKeyboard
import kotlinx.android.synthetic.main.list_apps.*


/**
 * The [ListFragmentApps] is used as a tab in ListActivity.
 *
 * It is a list of all installed applications that are can be launched.
 */
class ListFragmentApps : Fragment(), UIObject {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.list_apps, container, false)
    }

    override fun onStart() {
        super<Fragment>.onStart()
        super<UIObject>.onStart()
    }

    override fun applyTheme() {
    }

    override fun setOnClicks() { }

    override fun adjustLayout() {

        val appsRViewAdapter = AppsRecyclerAdapter(activity!!, intention, forGesture)

        // set up the list / recycler
        list_apps_rview.apply {
            // improve performance (since content changes don't change the layout size)
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(context)
            adapter = appsRViewAdapter
        }

        list_apps_searchview.setOnQueryTextListener(object :
            androidx.appcompat.widget.SearchView.OnQueryTextListener {

            override fun onQueryTextSubmit(query: String): Boolean {
                appsRViewAdapter.filter(query)
                return false
            }

            override fun onQueryTextChange(newText: String): Boolean {
                appsRViewAdapter.filter(newText)
                return false
            }
        })

        if (intention == ListActivity.ListActivityIntention.VIEW
            && getPreferences(context!!)
                .getBoolean(PREF_SEARCH_AUTO_KEYBOARD, true)) {
            openSoftKeyboard(context!!, list_apps_searchview)
        }
    }
}