package com.hubra.app.data

import androidx.room.*

@Entity(tableName = "state")
data class StateRow(@PrimaryKey val id: Int = 1, val revision: Long, val payload: String)

@Dao interface StateDao {
    @Query("SELECT * FROM state WHERE id = 1") suspend fun get(): StateRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: StateRow)
}

@Database(entities = [StateRow::class], version = 1, exportSchema = true)
abstract class HubraDatabase : RoomDatabase() { abstract fun stateDao(): StateDao }
