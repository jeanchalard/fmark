package com.j.fmark.fdrive

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

public enum class Type(val value : Int) {
  CREATE_FOLDER(1),
  RENAME_FOLDER(2),
  PUT_FILE(3)
}

private class Converters {
  @TypeConverter fun typeToInt(t : Type) = t.value
  @TypeConverter fun intToType(i : Int) = Type.values().find { i == it.value }
}

// TODO : override equals/hashcode
@Entity
public data class SyncItem(@PrimaryKey(autoGenerate = true) val seq : Int = 0,
                           val type : Type, val oldName : String, val newName : String, val binData : ByteArray)

@Dao
public interface SyncQueue {
  @Query("SELECT * FROM SyncItem ORDER BY seq LIMIT 1")
  fun getNext() : SyncItem

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun insert(item : SyncItem)

  @Query("DELETE FROM SyncItem WHERE seq = :seq")
  fun delete(seq : Int)

  fun delete(item : SyncItem) = delete(item.seq)
}

@Database(entities = [SyncItem::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
public abstract class SyncItemDatabase : RoomDatabase() {
  protected abstract fun queue() : SyncQueue
  companion object {
    @Volatile private var INSTANCE : SyncItemDatabase? = null
    protected fun getDatabase(context : Context) : SyncItemDatabase {
      INSTANCE?.let { return it }
      synchronized(this) {
        INSTANCE?.let { return it }
        val instance = Room.databaseBuilder(context.applicationContext, SyncItemDatabase::class.java, "SyncItemDatabase").build()
        INSTANCE = instance
        return instance
      }
    }

    fun queue(c : Context) = getDatabase(c).queue()
  }
}
