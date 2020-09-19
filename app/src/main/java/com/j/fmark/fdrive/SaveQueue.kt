package com.j.fmark.fdrive

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.j.fmark.fdrive.CommandStatus.CommandResult
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import com.google.api.services.drive.model.File as DriveFile

public enum class Type(val value : Int) {
  CREATE_FOLDER(1),
  RENAME_FILE(2),
  PUT_FILE(3)
}

private class Converters {
  @TypeConverter fun typeToInt(t : Type) = t.value
  @TypeConverter fun intToType(i : Int) = Type.values().find { i == it.value }
}

// TODO : override equals/hashcode
@Entity
public data class SaveItem(val type : Type, val fileId : String?, val name : String?, val binData : ByteArray? = null, val metadata : String? = null,
                           @PrimaryKey(autoGenerate = true) val seq : Long = 0) {
  override fun toString() = "seq = ${seq}, type = ${type}, fileId = ${fileId}, name = ${name}, binData size = ${binData?.size}, metadata = ${metadata}"
}

@Dao
private interface SaveQueueDao {
  @Query("SELECT * FROM SaveItem ORDER BY seq LIMIT 1")
  suspend fun getNext() : SaveItem?

  @Query("DELETE FROM SaveItem WHERE seq = :seq")
  suspend fun markDone(seq : Long)
  suspend fun markDone(item : SaveItem) = delete(item.seq)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(item : SaveItem) : Long

  @Query("DELETE FROM SaveItem WHERE seq = :seq")
  suspend fun delete(seq : Long)

  suspend fun delete(item : SaveItem) = delete(item.seq)
}

@Database(entities = [SaveItem::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
private abstract class SaveItemDatabase : RoomDatabase() {
  abstract fun dao() : SaveQueueDao
}

object CommandStatus {
  public data class CommandResult(val seq : Long, val driveFile : DriveFile?)
  private val lock = ReentrantLock()
  var lastExecutedCommand = CommandResult(0L, null)
    set(l) = lock.withLock {
      field = l
      listeners.forEach { it.invoke(l) }
    }
  private val listeners = ArrayList<(CommandResult) -> Unit>()
  public fun addListener(listener : (CommandResult) -> Unit) = lock.withLock { listeners.add(listener) }
  public fun removeListener(listener : (CommandResult) -> Unit) = lock.withLock { listeners.remove(listener) }
}

public inline class CommandId(private val id : Long) {
  private class Listener(private val id : Long, private val continuation : Continuation<CommandResult>) : (CommandResult) -> Unit {
    override fun invoke(lastCommand : CommandResult) {
      if (lastCommand.seq < id) return
      CommandStatus.removeListener(this)
      continuation.resume(lastCommand)
    }
  }
  suspend fun await() = suspendCoroutine<CommandResult> { continuation -> CommandStatus.addListener(Listener(id, continuation)) }
}

public class SaveQueue private constructor(private val restManager : RESTManager, private val dao : SaveQueueDao) {
  companion object {
    @Volatile private lateinit var INSTANCE : SaveQueue
    public fun get(context : Context) : SaveQueue {
      if (this::INSTANCE.isInitialized) return INSTANCE
      synchronized(this) {
        if (this::INSTANCE.isInitialized) return INSTANCE
        INSTANCE = SaveQueue(RESTManager(context), Room.databaseBuilder(context.applicationContext, SaveItemDatabase::class.java, "SaveItemDatabase").build().dao())
        return INSTANCE
      }
    }
  }

  public suspend fun getNext() = dao.getNext()
  public suspend fun markDone(seq : Long) = dao.markDone(seq)
  public suspend fun markDone(s : SaveItem) = dao.markDone(s)

  public suspend fun createFolder(parentFolder : DriveFile, name : String) = createFolder(parentFolder.id, name)
  public suspend fun createFolder(parentFolderId : String, name : String) =
   CommandId(dao.insert(SaveItem(Type.CREATE_FOLDER, fileId = parentFolderId, name = name))).also { restManager.tickle() }

  public suspend fun renameFile(file : DriveFile, name : String) = renameFile(file.id, name)
  public suspend fun renameFile(fileId : String, newName : String) =
   CommandId(dao.insert(SaveItem(Type.RENAME_FILE, fileId = fileId, name = newName))).also { restManager.tickle() }

  public suspend fun putFile(fileId : String?, name : String?, data : ByteArray, mimeType : String) =
   CommandId(dao.insert(SaveItem(Type.PUT_FILE, fileId = fileId, name = name, binData = data, metadata = mimeType))).also { restManager.tickle() }
}
