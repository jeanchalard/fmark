package com.j.fmark

import android.support.v7.widget.RecyclerView
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.gms.drive.Metadata
import com.google.android.gms.drive.MetadataBuffer
import com.j.fmark.drive.decodeName
import com.j.fmark.drive.decodeReading
import com.j.fmark.fragments.ClientList

class ClientAdapter(private var source : MetadataBuffer, private val clientList : ClientList) : RecyclerView.Adapter<ClientAdapter.Holder>()
{
  val updated : SparseArray<Metadata> = SparseArray() // To cache out-of-band updates, so that there is no need to re-fetch everything for known updates.

  class Holder(private val adapter : ClientAdapter, private val view : View) : RecyclerView.ViewHolder(view), View.OnClickListener, View.OnLongClickListener
  {
    var source : Metadata? = null
    val name : TextView = view.findViewById(R.id.client_name)
    val reading : TextView = view.findViewById(R.id.client_name_reading)
    init { view.setOnClickListener(this); view.setOnLongClickListener(this) }

    override fun onClick(v : View?)
    {
      val source = source ?: return
      adapter.clientList.startEditor(source)
    }

    override fun onLongClick(v : View?) : Boolean
    {
      val source = source ?: return false
      adapter.clientList.showClientDetails(source)
      return true
    }
  }

  fun setSource(s : MetadataBuffer)
  {
    source = s
    updated.clear()
    notifyDataSetChanged()
  }

  override fun getItemCount() : Int = source.count
  override fun onCreateViewHolder(parent : ViewGroup, viewType : Int) : ClientAdapter.Holder = Holder(this, LayoutInflater.from(parent.context).inflate(R.layout.client_view, parent, false))
  override fun onBindViewHolder(holder : ClientAdapter.Holder, position : Int)
  {
    val source = updated[position] ?: source[position]
    holder.source = source
    holder.name.text = decodeName(source)
    holder.reading.text = decodeReading(source)
  }

  fun notifyRenamed(clientFolder : Metadata)
  {
    val searchedId = clientFolder.driveId
    val index = source.indexOfFirst { it.driveId == searchedId }
    updated.put(index, clientFolder)
    notifyItemChanged(index)
  }
}
