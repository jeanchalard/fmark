package com.j.fmark.fdrive

import com.google.api.client.googleapis.services.AbstractGoogleClientRequest
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.Drive.Files

public class NullDrive(t : HttpTransport, j : JsonFactory, h : HttpRequestInitializer) : Drive(t, j, h) {
  override fun about() = null
  override fun channels() = null
  override fun replies() = null
  override fun teamdrives() = null
  override fun revisions() = null
  override fun comments() = null
  override fun permissions() = null
  override fun changes() = null
  override fun files() = object : Files() {
  }
}
