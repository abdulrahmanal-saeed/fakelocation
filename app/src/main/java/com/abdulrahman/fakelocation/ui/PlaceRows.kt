package com.abdulrahman.fakelocation.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.databinding.ItemPlaceBinding

/** One row in a list of places (search results, favorites, history). */
data class PlaceRow(
    val key: String,
    val title: String,
    val subtitle: String?,
    val initial: String? = null,
    val icon: Int = R.drawable.ic_pin,
    val tinted: Boolean = true,
    val trailing: String? = null,
    val onGo: (() -> Unit)? = null,
    val starred: Boolean? = null,
    val onStar: (() -> Unit)? = null,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

fun ItemPlaceBinding.bind(row: PlaceRow) {
    val context = root.context
    title.text = row.title
    subtitle.text = row.subtitle
    subtitle.isVisible = !row.subtitle.isNullOrBlank()
    initial.isVisible = row.initial != null
    initial.text = row.initial
    icon.isVisible = row.initial == null
    icon.setImageResource(row.icon)
    val fg = ContextCompat.getColor(context, if (row.tinted) R.color.action else R.color.ink_soft)
    val bg = ContextCompat.getColor(context, if (row.tinted) R.color.action_tint else R.color.nav_active)
    icon.imageTintList = ColorStateList.valueOf(fg)
    iconBox.backgroundTintList = ColorStateList.valueOf(bg)
    trailingText.isVisible = row.trailing != null
    trailingText.text = row.trailing
    goButton.isVisible = row.onGo != null
    goButton.setOnClickListener { row.onGo?.invoke() }
    starButton.isVisible = row.onStar != null
    starButton.setIconResource(if (row.starred == true) R.drawable.ic_star_filled else R.drawable.ic_star)
    starButton.contentDescription = context.getString(if (row.starred == true) R.string.remove_favorite else R.string.save_favorite)
    starButton.setOnClickListener { row.onStar?.invoke() }
    root.setOnClickListener { row.onClick() }
    root.setOnLongClickListener(row.onLongClick?.let { action -> { _ -> action(); true } })
}

/** Adds rows to a plain vertical container (short lists inside a scroll view). */
fun ViewGroup.showRows(rows: List<PlaceRow>) {
    removeAllViews()
    for (row in rows) {
        val binding = ItemPlaceBinding.inflate(LayoutInflater.from(context), this, false)
        binding.bind(row)
        addView(binding.root)
        binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = (6 * resources.displayMetrics.density).toInt() }
    }
}

class PlaceRowAdapter : ListAdapter<PlaceRow, PlaceRowAdapter.Holder>(Diff) {

    class Holder(val binding: ItemPlaceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemPlaceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = (8 * parent.resources.displayMetrics.density).toInt()
        }
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.binding.bind(getItem(position))

    private object Diff : DiffUtil.ItemCallback<PlaceRow>() {
        override fun areItemsTheSame(a: PlaceRow, b: PlaceRow) = a.key == b.key
        override fun areContentsTheSame(a: PlaceRow, b: PlaceRow) =
            a.title == b.title && a.subtitle == b.subtitle && a.starred == b.starred && a.trailing == b.trailing
    }
}
