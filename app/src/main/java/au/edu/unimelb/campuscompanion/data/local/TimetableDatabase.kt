package au.edu.unimelb.campuscompanion.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** User-authored rows are durable; future versions must migrate rather than rebuild this file. */
@Database(entities = [TimetableSourceEntity::class, ImportedClassEntity::class, LocalClassEntity::class],
    version = 1, exportSchema = false)
abstract class TimetableDatabase : RoomDatabase() {
    abstract fun timetableDao(): TimetableDao

    companion object {
        fun create(context: Context): TimetableDatabase = Room.databaseBuilder(
            context.applicationContext, TimetableDatabase::class.java, "timetable.db"
        ).build()
    }
}
