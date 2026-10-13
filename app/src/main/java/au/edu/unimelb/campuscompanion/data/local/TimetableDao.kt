package au.edu.unimelb.campuscompanion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TimetableDao {
    @Query("SELECT * FROM timetable_source WHERE ownerId = :ownerId")
    suspend fun source(ownerId: String): TimetableSourceEntity?

    @Query("SELECT * FROM imported_classes WHERE ownerId = :ownerId")
    suspend fun imported(ownerId: String): List<ImportedClassEntity>

    @Query("SELECT * FROM local_classes WHERE ownerId = :ownerId")
    suspend fun local(ownerId: String): List<LocalClassEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSource(source: TimetableSourceEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun putImported(rows: List<ImportedClassEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLocal(row: LocalClassEntity)

    @Query("DELETE FROM timetable_source WHERE ownerId = :ownerId")
    suspend fun deleteSource(ownerId: String)

    @Query("DELETE FROM imported_classes WHERE ownerId = :ownerId")
    suspend fun deleteImported(ownerId: String)

    @Query("DELETE FROM local_classes WHERE ownerId = :ownerId AND kind != 'MANUAL'")
    suspend fun deleteAdjustments(ownerId: String)

    @Query("DELETE FROM local_classes WHERE ownerId = :ownerId AND id = :id")
    suspend fun deleteLocal(ownerId: String, id: String)
}
