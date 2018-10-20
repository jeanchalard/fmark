package com.j.fmark.fragments

import android.content.Context
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.widget.AppCompatImageButton
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.google.android.gms.drive.DriveResourceClient
import com.google.android.gms.drive.Metadata
import com.j.fmark.FMark
import com.j.fmark.R
import com.j.fmark.drive.decodeName

class FEditor(private val fmarkHost : FMark, private val driveResourceClient : DriveResourceClient, private val clientFolder : Metadata) : Fragment()
{
  val name = decodeName(clientFolder)
  override fun onCreateView(inflater : LayoutInflater, container : ViewGroup?, savedInstanceState : Bundle?) : View?
  {
    val view = inflater.inflate(R.layout.fragment_feditor, container, false)
    view.setOnKeyListener { v, keycode, event -> if (KeyEvent.KEYCODE_BACK == keycode) { fmarkHost.supportFragmentManager.popBackStack(); true } else false }
    view.findViewById<AppCompatImageButton>(R.id.feditor_face).setOnClickListener { _ -> switchDrawing(R.id.feditor_face) }
    view.findViewById<AppCompatImageButton>(R.id.feditor_front).setOnClickListener { _ -> switchDrawing(R.id.feditor_front) }
    view.findViewById<AppCompatImageButton>(R.id.feditor_back).setOnClickListener { _ -> switchDrawing(R.id.feditor_back) }
    view.findViewById<ImageView>(R.id.feditor_guide).setImageResource(R.drawable.face)
    return view
  }

  // Return the regular LayoutInflater so that this fragment can be put fullscreen on top of the existing interface.
  override fun onGetLayoutInflater(savedFragmentState : Bundle?) = fmarkHost.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

  private fun switchDrawing(drawingCode : Int)
  {
    val guideId = when (drawingCode)
    {
      R.id.feditor_face -> R.drawable.face
      R.id.feditor_front -> R.drawable.front
      R.id.feditor_back -> R.drawable.back
      else -> 0
    }
    val view = view
    if (null == view || 0 == drawingCode) return
    view.findViewById<ImageView>(R.id.feditor_guide).setImageResource(guideId)
  }
}
