package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.local.MessageDao
import au.edu.unimelb.campuscompanion.data.local.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** In-memory [MessageDao] that orders messages like the Room queries do. */
class FakeMessageDao : MessageDao {
    private val rows = MutableStateFlow<Map<Pair<String, String>, MessageEntity>>(emptyMap())

    /** Every cached message in timeline order. */
    val all: List<MessageEntity>
        get() = rows.value.values.sortedWith(TIMELINE)

    override fun observeGroup(groupId: String): Flow<List<MessageEntity>> =
        rows.map { byKey -> byKey.values.filter { it.groupId == groupId }.sortedWith(TIMELINE) }

    override suspend fun find(senderId: String, clientId: String): MessageEntity? =
        rows.value[senderId to clientId]

    override suspend fun oldest(groupId: String, status: String): MessageEntity? =
        inGroup(groupId, status).firstOrNull()

    override suspend fun newest(groupId: String, status: String): MessageEntity? =
        inGroup(groupId, status).lastOrNull()

    override suspend fun upsert(messages: List<MessageEntity>) {
        rows.update { byKey -> byKey + messages.associateBy { it.senderId to it.clientId } }
    }

    override suspend fun replaceStatus(from: String, to: String): Int {
        val matching = rows.value.values.filter { it.status == from }
        upsert(matching.map { it.copy(status = to) })
        return matching.size
    }

    override suspend fun deleteGroupMessages(groupId: String, status: String) {
        rows.update { byKey -> byKey.filterValues { !(it.groupId == groupId && it.status == status) } }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }

    private fun inGroup(groupId: String, status: String): List<MessageEntity> =
        rows.value.values.filter { it.groupId == groupId && it.status == status }.sortedWith(TIMELINE)

    private companion object {
        val TIMELINE = compareBy<MessageEntity>({ it.createdAtMicros }, { it.serverId })
    }
}
