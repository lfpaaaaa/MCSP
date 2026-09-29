package au.edu.unimelb.campuscompanion.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * On-device cache for data the signed-in user is allowed to see. It is cleared when the user signs
 * out and can always be rebuilt from the server.
 */
@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class CampusDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        private const val FILE_NAME = "campus-companion.db"

        fun create(context: Context): CampusDatabase =
            Room.databaseBuilder(context.applicationContext, CampusDatabase::class.java, FILE_NAME)
                .fallbackToDestructiveMigration()
                .build()
    }
}
