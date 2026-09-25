package au.edu.unimelb.campuscompanion.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageDaoTest {
    private lateinit var database: CampusDatabase
    private lateinit var dao: MessageDao

    @Before
    fun createDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, CampusDatabase::class.java).build()
        dao = database.messageDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun messagesAreOrderedByTimeThenServerId() = runBlocking<Unit> {
        dao.upsert(
            listOf(
                message("c3", micros = 300, serverId = "s3"),
                message("c1", micros = 100, serverId = "s1"),
                message("c2", micros = 300, serverId = "s2")
            )
        )

        assertEquals(listOf("c1", "c2", "c3"), dao.observeGroup(GROUP).first().map { it.clientId })
    }

    @Test
    fun upsertReplacesThePendingCopy() = runBlocking<Unit> {
        dao.upsert(listOf(message("c1", micros = 100, serverId = null, status = "Sending")))
        dao.upsert(listOf(message("c1", micros = 200, serverId = "s1", status = "Sent")))

        val stored = dao.observeGroup(GROUP).first().single()
        assertEquals("s1", stored.serverId)
        assertEquals("Sent", stored.status)
    }

    @Test
    fun oldestAndNewestOnlyConsiderTheRequestedStatus() = runBlocking<Unit> {
        dao.upsert(
            listOf(
                message("c1", micros = 100, serverId = "s1"),
                message("c2", micros = 200, serverId = "s2"),
                message("c3", micros = 300, serverId = null, status = "Failed")
            )
        )

        assertEquals("c1", dao.oldest(GROUP, "Sent")?.clientId)
        assertEquals("c2", dao.newest(GROUP, "Sent")?.clientId)
    }

    @Test
    fun interruptedSendsFailAndSigningOutClearsTheCache() = runBlocking<Unit> {
        dao.upsert(listOf(message("c1", micros = 100, serverId = null, status = "Sending")))

        assertEquals(1, dao.replaceStatus(from = "Sending", to = "Failed"))
        assertEquals("Failed", dao.find(SENDER, "c1")?.status)

        dao.deleteAll()
        assertNull(dao.find(SENDER, "c1"))
    }

    private fun message(clientId: String, micros: Long, serverId: String?, status: String = "Sent") =
        MessageEntity(
            senderId = SENDER,
            clientId = clientId,
            groupId = GROUP,
            serverId = serverId,
            senderName = "Alice",
            body = "Message $clientId",
            createdAtMicros = micros,
            status = status
        )

    private companion object {
        const val GROUP = "group-1"
        const val SENDER = "user-1"
    }
}
