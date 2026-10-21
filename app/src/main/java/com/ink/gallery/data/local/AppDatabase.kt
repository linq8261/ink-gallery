package com.ink.gallery.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TrashEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trashDao(): TrashDao
}
