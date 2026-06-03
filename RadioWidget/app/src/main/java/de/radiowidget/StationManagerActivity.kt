package de.radiowidget

import android.content.Context
import android.graphics.Canvas
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StationManagerActivity : AppCompatActivity() {

    // FIX: lifecycleScope replaces manual CoroutineScope — automatically cancelled on destroy
    // (remove: private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob()))

    private lateinit var tabMy: TextView
    private lateinit var tabSearch: TextView
    private lateinit var panelMy: LinearLayout
    private lateinit var panelSearch: LinearLayout

    private lateinit var recycler: RecyclerView
    private lateinit var stationAdapter: StationAdapter
    private val myStations = mutableListOf<RadioStation>()

    private lateinit var searchInput: EditText
    private lateinit var btnSearch: Button
    private lateinit var searchProgress: ProgressBar
    private lateinit var searchList: ListView
    private lateinit var searchAdapter: ArrayAdapter<String>
    private val searchResults = mutableListOf<SearchResult>()

    private lateinit var itemTouchHelper: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_station_manager)
        supportActionBar?.title = "Sender verwalten"

        tabMy       = findViewById(R.id.tab_my)
        tabSearch   = findViewById(R.id.tab_search)
        panelMy     = findViewById(R.id.panel_my)
        panelSearch = findViewById(R.id.panel_search)

        recycler       = findViewById(R.id.recycler_my_stations)
        searchInput    = findViewById(R.id.search_input)
        btnSearch      = findViewById(R.id.btn_search)
        searchProgress = findViewById(R.id.search_progress)
        searchList     = findViewById(R.id.list_search_results)

        setupRecycler()
        loadMyStations()
        setupTabs()
        setupSearch()
    }

    private fun setupRecycler() {
        stationAdapter = StationAdapter(
            myStations,
            onDragStart = { vh -> itemTouchHelper.startDrag(vh) },
            onDelete    = { pos ->
                // FIX: capture the station object now; re-find by reference after dialog confirm
                // to handle any reordering between click and confirm
                val station = myStations.getOrNull(pos) ?: return@StationAdapter
                AlertDialog.Builder(this)
                    .setTitle(station.name)
                    .setMessage("Sender entfernen?")
                    .setPositiveButton("Entfernen") { _, _ ->
                        val actualPos = myStations.indexOf(station)
                        if (actualPos >= 0) {
                            myStations.removeAt(actualPos)
                            stationAdapter.notifyItemRemoved(actualPos)
                            persist()
                        }
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
        )

        recycler.apply {
            layoutManager = LinearLayoutManager(this@StationManagerActivity)
            adapter = stationAdapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder,
                                target: RecyclerView.ViewHolder): Boolean {
                // FIX: use bindingAdapterPosition (adapterPosition is deprecated)
                val from = vh.bindingAdapterPosition
                val to   = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                myStations.add(to, myStations.removeAt(from))
                stationAdapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                persist()
            }
            override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                                     dX: Float, dY: Float, actionState: Int, isActive: Boolean) {
                vh.itemView.alpha = if (isActive) 0.85f else 1f
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isActive)
            }
        }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(recycler)
    }

    private fun loadMyStations() {
        myStations.clear()
        myStations.addAll(StationRepository.getAllVisible(this))
        stationAdapter.notifyDataSetChanged()
    }

    private fun persist() {
        StationRepository.saveAll(this, myStations)
        RadioWidgetProvider.updateAllWidgets(this)
    }

    private fun setupTabs() {
        tabMy.setOnClickListener {
            panelMy.visibility = View.VISIBLE
            panelSearch.visibility = View.GONE
            tabMy.alpha = 1f; tabSearch.alpha = 0.5f
        }
        tabSearch.setOnClickListener {
            panelMy.visibility = View.GONE
            panelSearch.visibility = View.VISIBLE
            tabSearch.alpha = 1f; tabMy.alpha = 0.5f
        }
        tabMy.alpha = 1f; tabSearch.alpha = 0.5f
    }

    private fun setupSearch() {
        searchAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        searchList.adapter = searchAdapter

        btnSearch.setOnClickListener {
            val q = searchInput.text.toString().trim()
            if (q.length < 2) {
                Toast.makeText(this, "Mindestens 2 Zeichen eingeben", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            runSearch(q)
        }
        searchInput.setOnEditorActionListener { _, _, _ -> btnSearch.performClick(); true }

        searchList.setOnItemClickListener { _, _, pos, _ ->
            val r = searchResults[pos]
            AlertDialog.Builder(this)
                .setTitle("\"${r.name}\" hinzufügen?")
                .setMessage(buildString {
                    append("Genre: ${r.genre.ifBlank { "–" }}\n")
                    append("Land: ${r.country.ifBlank { "–" }}\n")
                    append("Codec: ${r.codec}  ${if (r.bitrate > 0) "${r.bitrate} kbps" else ""}")
                })
                .setPositiveButton("Hinzufügen") { _, _ ->
                    StationRepository.addCustom(this, RadioStation(
                        name      = r.name,
                        shortName = r.name.take(3).uppercase(),
                        frequency = if (r.bitrate > 0) "${r.bitrate} kbps" else "Stream",
                        genre     = r.genre,
                        streamUrl = r.streamUrl,
                        isCustom  = true
                    ))
                    loadMyStations()
                    RadioWidgetProvider.updateAllWidgets(this)
                    Toast.makeText(this, "${r.name} hinzugefügt", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
    }

    private fun runSearch(query: String) {
        searchProgress.visibility = View.VISIBLE
        btnSearch.isEnabled = false
        searchResults.clear()
        searchAdapter.clear()   // FIX: single clear before launch — was also cleared inside the coroutine

        // FIX: lifecycleScope instead of manual scope.launch
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                runCatching { RadioBrowserApi.searchByName(query) }.getOrElse { emptyList() }
            }
            searchProgress.visibility = View.GONE
            btnSearch.isEnabled = true
            if (results.isEmpty()) {
                Toast.makeText(this@StationManagerActivity, "Keine Ergebnisse", Toast.LENGTH_SHORT).show()
                return@launch
            }
            searchResults.addAll(results)
            // FIX: removed duplicate searchAdapter.clear() and redundant notifyDataSetChanged()
            // addAll() calls notifyDataSetChanged() internally
            searchAdapter.addAll(results.map {
                "${it.name}  ${if (it.bitrate > 0) "• ${it.bitrate} kbps" else ""}  ${it.country}"
            })
        }
    }

    // FIX: onDestroy no longer needs scope.cancel() — lifecycleScope handles this automatically
}

// ── RecyclerView Adapter with drag handle + delete button ──────────────────

class StationAdapter(
    private val items: MutableList<RadioStation>,
    private val onDragStart: (RecyclerView.ViewHolder) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<StationAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name:   TextView    = v.findViewById(R.id.item_name)
        val sub:    TextView    = v.findViewById(R.id.item_sub)
        val drag:   ImageView   = v.findViewById(R.id.item_drag)
        val delete: ImageButton = v.findViewById(R.id.item_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_station, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(vh: VH, position: Int) {
        val s = items[position]
        vh.name.text = s.name
        vh.sub.text  = buildString {
            append(s.genre)
            if (s.frequency.isNotBlank()) append("  •  ${s.frequency}")
            if (s.isCustom) append("  ★")
        }
        vh.drag.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onDragStart(vh)
            false
        }
        vh.delete.setOnClickListener {
            // FIX: guard against NO_POSITION — item may be animating when tapped
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onDelete(pos)
        }
    }

    override fun getItemCount() = items.size
}
