/*
 * Copyright (c) Kuba Szczodrzyński 2026-9-9.
 */

package pl.szczodrzynski.edziennik.ui.excuses

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.mikepenz.iconics.typeface.library.community.material.CommunityMaterial
import kotlinx.coroutines.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import pl.szczodrzynski.edziennik.App
import pl.szczodrzynski.edziennik.MainActivity
import pl.szczodrzynski.edziennik.R
import pl.szczodrzynski.edziennik.data.api.events.ExcuseSentEvent
import pl.szczodrzynski.edziennik.databinding.ExcusesFragmentBinding
import pl.szczodrzynski.edziennik.utils.SimpleDividerItemDecoration
import kotlin.coroutines.CoroutineContext

class ExcusesFragment : Fragment(), CoroutineScope {
    companion object {
        private const val TAG = "ExcusesFragment"
    }

    private lateinit var app: App
    private lateinit var activity: MainActivity
    private lateinit var b: ExcusesFragmentBinding

    private val job: Job = Job()
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Main

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        activity = getActivity() as? MainActivity ?: return null
        context ?: return null
        app = activity.application as App
        b = ExcusesFragmentBinding.inflate(inflater)
        return b.root
    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
    }

    override fun onStop() {
        super.onStop()
        EventBus.getDefault().unregister(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    @Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
    fun onExcuseSentEvent(event: ExcuseSentEvent) {
        EventBus.getDefault().removeStickyEvent(event)
        if (!isAdded) return
        if (event.excuse == null) {
            Toast.makeText(activity, R.string.excuses_send_failed, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(activity, R.string.excuses_sent, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onExcuseAddClick(view: View?) {
        ExcuseEditorDialog(activity = activity).show()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!isAdded) return

        activity.navView.apply {
            bottomBar.apply {
                fabEnable = true
                fabExtendedText = getString(R.string.excuses_action_add)
                fabIcon = CommunityMaterial.Icon3.cmd_text_box_plus_outline
            }

            setFabOnClickListener(this@ExcusesFragment::onExcuseAddClick)
        }
        activity.gainAttentionFAB()

        val adapter = ExcuseListAdapter(activity) { excuse ->
            ExcuseDetailsDialog(activity, excuse).show()
        }

        app.db.librusExcuseDao().getAll(App.profileId).observe(activity) { excuses ->
            if (!isAdded) return@observe

            b.progressBar.isVisible = false
            b.list.isVisible = excuses.isNotEmpty()
            b.noData.isVisible = excuses.isEmpty()
            if (excuses.isEmpty()) return@observe

            adapter.items = excuses
            if (b.list.adapter == null) {
                b.list.adapter = adapter
                b.list.apply {
                    isNestedScrollingEnabled = false
                    layoutManager = LinearLayoutManager(context)
                    addItemDecoration(SimpleDividerItemDecoration(context))
                }
            }
            adapter.notifyDataSetChanged()
        }
    }
}
