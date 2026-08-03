package com.example.vhpmatchpresentation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.vhpmatchpresentation.R
import com.example.vhpmatchpresentation.data.PlayerPresentation

class PhotoMappingAdapter(
    private val players: List<PlayerPresentation>,
    private val onSelectPhotoClicked: (PlayerPresentation) -> Unit
) : RecyclerView.Adapter<PhotoMappingAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imgPhoto: ImageView = itemView.findViewById(R.id.imgItemPhoto)
        val txtName: TextView = itemView.findViewById(R.id.txtItemPlayerName)
        val txtRole: TextView = itemView.findViewById(R.id.txtItemPlayerRole)
        val btnSelect: Button = itemView.findViewById(R.id.btnItemSelectPhoto)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_player_photo_mapping, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val player = players[position]
        holder.txtName.text = "#${player.number} ${player.name}"
        holder.txtRole.text = player.role

        if (!player.photoUri.isNullOrEmpty()) {
            holder.imgPhoto.load(player.photoUri) {
                placeholder(R.drawable.ic_player_silhouette)
                error(R.drawable.ic_player_silhouette)
            }
            holder.btnSelect.text = "Change Photo"
        } else {
            holder.imgPhoto.setImageResource(R.drawable.ic_player_silhouette)
            holder.btnSelect.text = "Assign Photo"
        }

        holder.btnSelect.setOnClickListener {
            onSelectPhotoClicked(player)
        }
    }

    override fun getItemCount(): Int = players.size
}
