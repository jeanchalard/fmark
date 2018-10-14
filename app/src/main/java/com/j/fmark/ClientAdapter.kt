package com.j.fmark

import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.gms.drive.Metadata
import com.google.android.gms.drive.MetadataBuffer
import com.j.fmark.drive.getName
import com.j.fmark.drive.getReading
import com.j.fmark.fragments.ClientList

class ClientAdapter(private var source : MetadataBuffer, private val clientList : ClientList) : RecyclerView.Adapter<ClientAdapter.Holder>()
{
  class Holder(private val adapter : ClientAdapter, private val view : View) : RecyclerView.ViewHolder(view), View.OnClickListener
  {
    var source : Metadata? = null
    val name : TextView = view.findViewById(R.id.client_name)
    val reading : TextView = view.findViewById(R.id.client_name_reading)
    init { view.setOnClickListener(this) }

    override fun onClick(v : View?)
    {
      val source = source ?: return
      adapter.clientList.startEditor(source)
    }
  }

  fun setSource(s : MetadataBuffer)
  {
    source = s
    notifyDataSetChanged()
  }

  override fun getItemCount() : Int = source.count
  override fun onCreateViewHolder(parent : ViewGroup, viewType : Int) : ClientAdapter.Holder = Holder(this, LayoutInflater.from(parent.context).inflate(R.layout.client_view, parent, false))
  override fun onBindViewHolder(holder : ClientAdapter.Holder, position : Int)
  {
    val source = source[position]
    holder.source = source
    holder.name.text = getName(source)
    holder.reading.text = getReading(source)
  }
}
